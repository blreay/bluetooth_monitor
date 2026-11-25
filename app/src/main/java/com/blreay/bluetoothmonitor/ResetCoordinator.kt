package com.blreay.bluetoothmonitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/** Sole main-looper run/command owner across launcher, service, receivers, permission, and late RPC callbacks. */
class ResetCoordinator(private val app: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val ownership = RunOwnership()
    private val admission = ForegroundAdmission({ SystemClock.elapsedRealtime() }, 4_000L) { cancelScanFor(it) }
    private val retirement = ScanRegistrationRetirement({ SystemClock.elapsedRealtime() },
        { BoundedBluetoothWork.retireExact(it) },
        { _, reason -> handler.post { RunNotification.message(app, "扫描清理未确认", reason) } })
    private val engine = DurableResetEngine(PendingResetStore.persistence(app),
        { ReEnableScheduler.armNextTick(app) }, { System.currentTimeMillis() }, { UUID.randomUUID().toString() })
    val client = ShizukuBluetoothClient(app) { RunNotification.authorization(app, it) }
    private val backend = object : SwitchBackend {
        override fun readState(lease: CommandLease, callback: (AdapterState) -> Unit) = readStateBounded(lease, callback)
        override fun preflight(lease: CommandLease, budgetMs: Long, callback: (SwitchCapability) -> Unit) = client.preflight(lease, budgetMs, callback)
        override fun privileged(enabled: Boolean, lease: CommandLease, budgetMs: Long, callback: (SwitchOutcome) -> Unit) =
            client.executeToggle(enabled, lease, budgetMs, callback)
        override fun ordinary(enabled: Boolean, lease: CommandLease, callback: (SwitchOutcome) -> Unit) {
            BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 1_000L,
                { SwitchOutcome.notInvoked("普通开关未进入执行；队列占用/执行窗口失效") },
                { SwitchOutcome.uncertain(SwitchMode.ORDINARY, "普通开关超时；无法确认系统请求是否仍执行") },
                { if (lease.valid()) BluetoothSwitch.ordinary(app, enabled) else SwitchOutcome.notInvoked("普通开关排队后窗口已过期") },
            ) { result, _ -> callback(result) }
        }
    }
    private val driver = BluetoothSwitchDriver(backend)
    private var scan: BandResetController? = null
    private var scanRun: String? = null
    private var serviceTicket: ForegroundAdmission.Ticket? = null
    private var activeRun: String? = null
    private var startupSettled = false
    private var setupWait: (() -> Unit)? = null
    private var waitingForSetup = false
    private var wantsToast = false
    private var serviceDone: (() -> Unit)? = null
    private var serviceRun: String? = null
    private var commandBusy: String? = null
    private var tickBusy = false
    private var foregroundUnavailableUntil = 0L
    private var serviceLease: CommandLease? = null
    private val serviceTick = Runnable { if (serviceDone != null) recoveryStep(serviceLease ?: CommandLease.foreground(), false) { scheduleServiceTick() } }
    private val setupPrefs get() = app.getSharedPreferences("bt_reset_setup", Context.MODE_PRIVATE)
    init { client.start() }

    /** Called synchronously from the NoDisplay dispatcher; any necessary setup Activity starts legitimately here. */
    fun launch(activity: Activity, verbose: Boolean) {
        requireMain()
        if (activeRun != null || serviceDone != null) {
            RunNotification.message(app, "运行正在进行", "已合并重复启动；不会重复扫描或关闭蓝牙")
            return
        }
        val id = UUID.randomUUID().toString()
        if (!ownership.acquire(id)) return
        activeRun = id
        wantsToast = verbose
        startupSettled = false
        val needsNearby = !BtPermissions.allGranted(app)
        val setupNecessary = !setupPrefs.getBoolean("notification_setup_seen", false) ||
            (needsNearby && !setupPrefs.getBoolean("nearby_attempted", false))
        waitingForSetup = setupNecessary
        // Begin probe now, independently of the Activity and BEFORE any Nearby permission request.
        StartupLaunchSequence.dispatch<SwitchCapability>(
            startup = { ready -> client.startup(callback = ready) },
            decide = {
                if (setupNecessary) {
                    activity.startActivity(Intent(activity, PermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(PermissionActivity.EXTRA_SETUP_RUN, id))
                } else if (needsNearby) {
                    // A denial does not cancel the already initialized probe or cause a repeated UI storm.
                    RunNotification.message(app, "缺少附近设备权限", "未扫描、未开关蓝牙；此前已拒绝，不重复弹窗。点击通知详情可显式申请或打开设置")
                    releaseRun(id)
                } else {
                    // Submit only during the launcher's foreground opportunity, never after a human wait.
                    if (!tryForeground(id) && startupSettled) continueStartup(id)
                }
            },
            ready = delivered@{ capability ->
                if (!ownership.current(id)) return@delivered
                startupSettled = true
                RunNotification.authorization(app, capability.reason)
                val ready = setupWait; setupWait = null
                if (waitingForSetup) ready?.invoke() else continueStartup(id)
            },
        )
    }

    fun awaitStartupForSetup(runId: String, callback: () -> Unit) {
        requireMain()
        if (!ownership.current(runId)) return
        if (startupSettled) callback() else setupWait = callback
    }
    fun detachSetupCallback() { setupWait = null } // Do not cancel application-owned authorization/probe.
    fun setupCompleted(runId: String, granted: Boolean) {
        requireMain()
        if (!ownership.current(runId)) return
        waitingForSetup = false
        setupWait = null
        if (!granted || !BtPermissions.allGranted(app)) {
            RunNotification.message(app, "附近设备权限未授予", "未开始扫描、未开关蓝牙；Shizuku 授权检查仍独立完成。详情可显式重试")
            releaseRun(runId)
        } else if (!tryForeground(runId)) continueStartup(runId)
    }

    private fun continueStartup(id: String) {
        if (!ownership.current(id) || !startupSettled || waitingForSetup || scan != null || admission.waiting(id)) return
        if (engine.read() != null) {
            RunNotification.message(app, "继续上次恢复", "存在持久化待恢复记录；不会开始新扫描/OFF")
            if (serviceDone != null) recoveryStep(serviceLease ?: CommandLease.foreground(), false) { scheduleServiceTick() }
            else {
                ReEnableScheduler.armNextTick(app)
                recoveryStep(CommandLease({ SystemClock.elapsedRealtime() }, SystemClock.elapsedRealtime() + 8_000L), true) { releaseRun(id) }
            }
        } else {
            val window = admission.scanWindow(id, ResetService.SCAN_TIMEOUT_MS, TriggerReceiver.RECEIVER_SCAN_TIMEOUT_MS) ?: return
            val foreground = serviceDone != null && serviceRun == id
            startScan(id, window,
                if (foreground) serviceLease ?: CommandLease.foreground()
                else CommandLease({ SystemClock.elapsedRealtime() }, SystemClock.elapsedRealtime() + 9_000L)) { }
        }
    }

    /** A broadcast never launches an Activity or waits for human authorization. */
    fun broadcastScan(verbose: Boolean, lease: CommandLease, done: () -> Unit) {
        requireMain()
        if (!BtPermissions.allGranted(app)) {
            RunNotification.message(app, "缺少附近设备权限", "广播未扫描/未开关。请点击通知详情或首次打开图标完成必要权限设置")
            done(); return
        }
        if (activeRun != null || engine.read() != null || commandBusy != null) { done(); return }
        val id = UUID.randomUUID().toString()
        if (!ownership.acquire(id)) { done(); return }
        activeRun = id
        wantsToast = verbose
        startupSettled = true
        if (tryForeground(id)) { done(); return }
        startScan(id, TriggerReceiver.RECEIVER_SCAN_TIMEOUT_MS, lease, done)
    }

    private fun startScan(id: String, window: Long, lease: CommandLease, done: () -> Unit) {
        if (!ownership.current(id) || scan != null || engine.read() != null || !lease.valid()) { done(); return }
        if (retirement.blocked()) {
            retirement.retryRetirement() // explicit icon/trigger run may retry failed exact cleanup, never a live/hung scan
            RunNotification.message(app, "旧扫描清理尚未确认", "本次未扫描/开关；保留旧注册安全隔离。若清理曾失败，只重试原注册清理；恢复权限后可再次启动")
            done(); releaseRun(id); finishService(); return
        }
        RunNotification.beginScan(app)
        val controller = BandResetController(app, ownership, id, lease, retirement) { summary ->
            if (!ownership.current(id) || !lease.valid()) false
            else {
                val armed = engine.arm(id, wantsToast, summary)
                if (armed == null) {
                    RunNotification.message(app, "无法安排安全恢复", "持久化/恢复闹钟安排失败；未关闭蓝牙")
                    false
                } else true
            }
        }
        scan = controller
        scanRun = id
        controller.run(window, wantsToast) { result ->
            if (scan === controller) { scan = null; scanRun = null }
            done()
            if (!ownership.current(id)) return@run
            if (result == BandResetController.Result.RESET_TRIGGERED) {
                // Receiver commits deferred OFF_READY job only. Its expired scan lease does not own that job.
                if (serviceDone != null) recoveryStep(serviceLease ?: CommandLease.foreground(), false) { scheduleServiceTick() }
                else releaseRun(id)
            } else {
                releaseRun(id)
                finishService()
            }
        }
    }

    private fun cancelScanFor(id: String) {
        if (scanRun != id) return
        val previous = scan
        // Detach exact ownership before cancel can synchronously deliver its completion callback.
        scan = null; scanRun = null
        previous?.cancel()
    }

    fun cancelBroadcastScan(lease: CommandLease) {
        requireMain()
        lease.cancel()
        if (serviceDone == null) activeRun?.let { id -> cancelScanFor(id); releaseRun(id) }
    }

    fun serviceAdmissionCurrent(ticket: ForegroundAdmission.Ticket): Boolean {
        requireMain()
        return admission.current(ticket) && (activeRun == ticket.runId || engine.read()?.runId == ticket.runId)
    }
    fun serviceStarted(ticket: ForegroundAdmission.Ticket, done: () -> Unit) {
        requireMain()
        if (!serviceAdmissionCurrent(ticket) || !admission.accept(ticket)) { done(); return }
        val id = ticket.runId
        val pending = engine.read()
        serviceTicket = ticket
        serviceRun = id
        serviceDone = done
        serviceLease = CommandLease.foreground()
        if (pending != null) recoveryStep(serviceLease!!, false) { scheduleServiceTick() }
        else if (startupSettled && !waitingForSetup) continueStartup(id)
    }
    fun serviceGone(ticket: ForegroundAdmission.Ticket) {
        requireMain()
        if (serviceTicket != ticket || !admission.admitted(ticket)) return
        handler.removeCallbacks(serviceTick)
        serviceLease?.cancel(); serviceLease = null
        serviceDone = null; serviceRun = null; serviceTicket = null
        admission.end(ticket)
        if (activeRun == ticket.runId) releaseRun(ticket.runId)
        // Native authority is retained in the record, not cleared by Service destruction.
    }

    fun alarm(lease: CommandLease, done: () -> Unit) {
        requireMain()
        val pending = engine.read() ?: run { done(); return }
        // Also prearm before a handoff which the Service may subsequently fail to promote.
        if (!ReEnableScheduler.armNextTick(app)) {
            RunNotification.runtime(app, pending, AdapterState.UNKNOWN, "恢复闹钟安排失败；未派发新命令，保留记录", final = true)
            done(); return
        }
        if (serviceDone != null) { recoveryStep(serviceLease ?: lease, false) { scheduleServiceTick() }; done(); return }
        if (BtPermissions.allGranted(app) && tryForeground(pending.runId)) { done(); return }
        recoveryStep(lease, true, done)
    }

    private fun recoveryStep(lease: CommandLease, constrained: Boolean, done: () -> Unit) {
        val pending = engine.read() ?: run { finishService(); done(); return }
        // Followup must exist BEFORE this receiver begins any bounded status/auth/RPC work.
        if (!ReEnableScheduler.armNextTick(app)) {
            RunNotification.runtime(app, pending, AdapterState.UNKNOWN, "恢复闹钟安排失败；保留记录，未开始新命令")
            finishService(); done(); return
        }
        if (!lease.valid() || tickBusy) { done(); return }
        tickBusy = true
        val gate = CompletionGate()
        fun finish() { if (gate.finish()) { tickBusy = false; done() } }
        readStateBounded(lease) { state ->
            if (!lease.valid()) { finish(); return@readStateBounded }
            val before = engine.read() ?: run { finish(); return@readStateBounded }
            val step = engine.observe(state)
            val current = engine.read()
            when (step) {
                RecoveryStep.DISPATCH_OFF, RecoveryStep.DISPATCH_ON -> {
                    if (commandBusy != null || current == null) { finish(); return@readStateBounded }
                    val enabled = step == RecoveryStep.DISPATCH_ON
                    val command = engine.dispatch(current, lease, enabled)
                    if (command == null) { finish(); return@readStateBounded }
                    commandBusy = command.commandId
                    driver.execute(enabled, lease, if (constrained) 1_200L else 5_000L,
                        if (constrained) 5_500L else 6_000L, command.ordinaryOnly,
                        { entry -> if (engine.note(command, entry)) engine.read()?.let { RunNotification.runtime(app, it, state, entry.reason) } },
                    ) { outcome ->
                        if (commandBusy == command.commandId) commandBusy = null
                        val continuation = engine.completeAndRearm(command, outcome)
                        if (continuation != null) {
                            val followup = continuation.failure ?: if (continuation.rearmed == true)
                                "已重新安排后续恢复闹钟；只在新的执行窗口继续" else "执行结束仍未确认；保留安全记录"
                            RunNotification.runtime(app, continuation.record, state,
                                "${if (enabled) "ON" else "OFF"} ${outcome.status}；${outcome.reason}；$followup",
                                final = continuation.rearmed == false)
                            // The durable accepted command owns its alarm even after receiver expiry/service teardown.
                            // A service tick uses its current independent lease, never the old callback's expired lease.
                            if (continuation.rearmed == true && serviceRun == continuation.record.runId) scheduleServiceTick()
                        }
                        finish()
                    }
                }
                RecoveryStep.RESTORED, RecoveryStep.FAILED -> {
                    RunNotification.runtime(app, before, state, if (step == RecoveryStep.RESTORED)
                        "已观察到实际 ON，蓝牙已恢复；共 ${before.attempts + 1} 次观察（不是保证秒数）" else "未观察到关闭/请求未执行；实际状态 $state", final = true)
                    ReEnableScheduler.cancel(app)
                    activeRun?.let { releaseRun(it) }; finishService(); finish()
                }
                RecoveryStep.EXHAUSTED, RecoveryStep.QUARANTINED, RecoveryStep.STORAGE_FAILED -> {
                    RunNotification.runtime(app, current ?: before, state, current?.reason ?: "存储更新失败；保留安全记录", final = true)
                    ReEnableScheduler.cancel(app)
                    activeRun?.let { releaseRun(it) }; finishService(); finish()
                }
                else -> { finish() }
            }
        }
    }

    private fun readStateBounded(lease: CommandLease, callback: (AdapterState) -> Unit) {
        val gate = CompletionGate()
        BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 600L,
            { AdapterState.UNKNOWN }, { AdapterState.UNKNOWN }, { BluetoothSwitch.state(app) },
        ) { state, _ -> if (gate.finish()) callback(state) }
    }
    private fun scheduleServiceTick() {
        handler.removeCallbacks(serviceTick)
        if (serviceDone != null) handler.postDelayed(serviceTick, ReEnableScheduler.TICK_INTERVAL_MS)
    }
    fun retryAuthorization(callback: () -> Unit) { client.startup(explicit = true) { callback() } }
    fun retryRecovery(callback: () -> Unit = {}) {
        requireMain()
        // Explicit details continuation observes hardware on a bounded worker; never borrows an expired receiver lease.
        readStateBounded(CommandLease.foreground()) { state ->
            if (state != AdapterState.OFF) {
                RunNotification.message(app, "未派发恢复开关", "当前读取状态 $state；只有实际 OFF 才显式派发 ON，不扫描/不重放 OFF")
                callback(); return@readStateBounded
            }
            if (!engine.retryRecovery(state)) {
                RunNotification.message(app, "不能继续未结束命令", "不存在安全 ON_READY/OFF_SETTLED/耗尽恢复，或旧 OFF/ON 执行权限未确认结束；不会覆盖安全隔离")
                callback(); return@readStateBounded
            }
            val pending = engine.read() ?: run { callback(); return@readStateBounded }
            val continuation = engine.rearmContinuation(pending)
            if (continuation == null || continuation.rearmed != true) {
                RunNotification.runtime(app, continuation?.record ?: pending, state,
                    continuation?.failure ?: "待恢复记录已变化/不可安全续接；未派发命令", final = true)
            } else {
                RunNotification.runtime(app, continuation.record, state, "已安排显式 ON 恢复；不会重新扫描/重放 OFF")
                // If legal foreground handoff is refused, the checked durable alarm owns the next bounded step.
                tryForeground(pending.runId)
            }
            callback()
        }
    }
    private fun tryForeground(id: String): Boolean {
        if (serviceDone != null) return serviceRun == id
        if (admission.waiting(id)) return true // coalesce the exact in-flight legal handoff
        if (SystemClock.elapsedRealtime() < foregroundUnavailableUntil) return false
        val ticket = admission.begin(id) ?: return false
        if (!ResetService.tryStart(app, ticket)) { admission.reject(ticket); return false }
        handler.postDelayed({
            if (admission.expire(ticket)) admissionFailed(ticket.runId, "服务提升回调未在有限等待内到达；晚到服务不会接管新运行")
        }, 4_000L)
        return true
    }
    fun serviceAdmissionFailed(ticket: ForegroundAdmission.Ticket, reason: String) {
        requireMain()
        if (!admission.reject(ticket)) return // stale/late failure must not cancel another run
        admissionFailed(ticket.runId, reason)
    }
    private fun admissionFailed(id: String, reason: String) {
        foregroundUnavailableUntil = SystemClock.elapsedRealtime() + 30_000L
        val pending = engine.read()
        if (pending?.runId == id) {
            ReEnableScheduler.armNextTick(app)
            RunNotification.runtime(app, pending, AdapterState.UNKNOWN,
                "前台服务提升未成功：$reason；下次闹钟走受限步骤，不启动后台界面", final = true)
        } else if (activeRun == id) {
            RunNotification.message(app, "前台运行未获接管", "已取消该运行扫描；未新增开关：$reason；无后台界面绕过")
            releaseRun(id)
        }
    }
    private fun finishService() {
        handler.removeCallbacks(serviceTick)
        val done = serviceDone
        val ticket = serviceTicket
        serviceDone = null; serviceRun = null; serviceTicket = null
        serviceLease?.cancel(); serviceLease = null
        ticket?.let { admission.end(it) }
        done?.invoke()
    }
    private fun releaseRun(id: String) {
        admission.pendingTicket(id)?.let { admission.reject(it) }
        ownership.release(id)
        if (activeRun == id) { activeRun = null; waitingForSetup = false; setupWait = null }
    }
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
    companion object {
        fun get(context: Context) = (context.applicationContext as MonitorApplication).coordinator
    }
}
