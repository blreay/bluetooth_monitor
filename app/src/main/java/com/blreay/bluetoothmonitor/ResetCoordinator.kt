package com.blreay.bluetoothmonitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/** Sole main-looper owner across launcher, service, receivers, details, and exact late RPC callbacks. */
class ResetCoordinator(private val app: Context, private val audit: AuditSink = MibandLog) {
    private val handler = Handler(Looper.getMainLooper())
    private val store = PendingResetStore.persistence(app)
    private val ownership = RunOwnership()
    private val admission = ForegroundAdmission({ SystemClock.elapsedRealtime() }, 4_000L) { cancelScanFor(it) }
    private val manualScanners = ExactScannerOwner<ManualBleScanner> { it.registrationTicket() }
    private val retirement = ScanRegistrationRetirement({ SystemClock.elapsedRealtime() },
        { BoundedBluetoothWork.retireExact(it) },
        onRetired = { ticket -> handler.post {
            manualScanners.retired(ticket) { it.retirementConfirmed(ticket) }
            publishDetails()
        } }, onFailure = { _, reason -> handler.post { notice("扫描清理未确认", reason) } })
    private val engine = DurableResetEngine(store,
        { ReEnableScheduler.armNextTick(app) }, { System.currentTimeMillis() }, { UUID.randomUUID().toString() }, audit)
    private val subscriptions = VisibleDetailsSubscription { detailsSnapshot() }
    val client = ShizukuBluetoothClient(app) { RunNotification.authorization(app, it); publishDetails() }
    private val backend = object : SwitchBackend {
        override fun readState(lease: CommandLease, callback: (AdapterState) -> Unit) = readStateBounded(lease, callback)
        override fun preflight(lease: CommandLease, budgetMs: Long, callback: (SwitchCapability) -> Unit) = client.preflight(lease, budgetMs, callback)
        override fun privileged(enabled: Boolean, lease: CommandLease, budgetMs: Long, callback: (SwitchOutcome) -> Unit) =
            client.executeToggle(enabled, lease, budgetMs, callback)
        override fun privilegedObserved(enabled: Boolean, lease: CommandLease, budgetMs: Long, invocation: SwitchInvocation,
                                        callback: (SwitchOutcome) -> Unit) = client.executeToggleObserved(enabled, lease, budgetMs, invocation, callback)
        override fun ordinary(enabled: Boolean, lease: CommandLease, callback: (SwitchOutcome) -> Unit) =
            ordinaryObserved(enabled, lease, SwitchInvocation(), callback)
        override fun ordinaryObserved(enabled: Boolean, lease: CommandLease, invocation: SwitchInvocation, callback: (SwitchOutcome) -> Unit) {
            BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 1_000L,
                { SwitchOutcome.notInvoked("普通开关未进入执行；队列占用/执行窗口失效") },
                { SwitchOutcome.uncertain(SwitchMode.ORDINARY, "普通开关超时；无法确认系统请求是否仍执行") }, {
                    if (!lease.valid()) SwitchOutcome.notInvoked("普通开关排队后窗口已过期")
                    else {
                        var enteredAt: Long? = null
                        val result = BluetoothSwitch.ordinary(app, enabled) {
                            if (!invocation.canEnter(lease)) false else {
                                val at = System.currentTimeMillis(); enteredAt = at
                                handler.post { invocation.entered(at) }
                                true
                            }
                        }
                        val returnedAt = System.currentTimeMillis()
                        result.copy(enteredAt = enteredAt, returnedAt = returnedAt,
                            submittedAt = returnedAt.takeIf { result.status == SwitchOutcome.Status.SUBMITTED })
                    }
                }) { result, _ -> callback(result) }
        }
    }
    private var scan: BandResetController? = null
    private var scanRun: String? = null
    private var serviceTicket: ForegroundAdmission.Ticket? = null
    private var serviceStartId = 0
    private var activeRun: String? = null
    private var startupSettled = false
    private var setupWait: (() -> Unit)? = null
    private var waitingForSetup = false
    private var wantsToast = false
    private var serviceDone: (() -> Unit)? = null
    private var serviceRun: String? = null
    private var commandBusy: String? = null
    private var latestNotice: String? = null
    private var tickBusy = false
    private var foregroundUnavailableUntil = 0L
    private var serviceLease: CommandLease? = null
    private val runStartElapsed = mutableMapOf<String, Long>() // process-local only; absent after restart means duration UNKNOWN
    private val serviceTick = Runnable {
        if (serviceTicket?.owner == ForegroundAdmission.Owner.AUTOMATIC && serviceDone != null)
            recoveryStep(serviceLease ?: CommandLease.foreground(), false) { scheduleServiceTick() }
    }
    private val setupPrefs get() = app.getSharedPreferences("bt_reset_setup", Context.MODE_PRIVATE)
    private fun operationAdmission(): OperationAdmission {
        val s = store.snapshot()
        return OperationAdmission(s.pending, s.manualFence, scan != null, retirement.blocked(), activeRun != null,
            waitingForSetup, serviceDone != null || admission.busy(), s.storageFailed, s.integrityBlocked)
    }
    private val gate = RuntimeOperationGate { operationAdmission() }
    private val manual = ManualOperationController(store, { operationAdmission() }, object : ManualRuntime {
        override fun requestForeground(operation: ManualOperation) = tryForeground(operation.operationId, ForegroundAdmission.Owner.MANUAL)
        override fun execute(operation: ManualOperation, lease: CommandLease, ordinaryOnly: Boolean,
                             event: (BackendEntry) -> Unit, entered: (Long) -> Unit,
                             nextAttempt: (SwitchOutcome) -> Boolean, result: (SwitchOutcome) -> Unit) {
            val driver = BluetoothSwitchDriver(backend)
            driver.execute(operation.action == ManualAction.ON, lease, ordinaryOnly = ordinaryOnly, onEvent = event,
                invocation = SwitchInvocation(entered, { previous ->
                    val s = store.snapshot()
                    if (!lease.valid() || s.storageFailed || s.integrityBlocked || s.pending != null ||
                        s.manualFence?.operationId != operation.operationId || serviceTicket?.owner != ForegroundAdmission.Owner.MANUAL ||
                        serviceRun != operation.operationId || serviceTicket?.runId != operation.operationId)
                        false else previous == null || nextAttempt(previous)
                }), callback = result)
        }
        override fun sample(lease: CommandLease, result: (StateSample) -> Unit) = readSampleBounded(lease, result)
        override fun scan(operation: ManualOperation, lease: CommandLease, events: ManualScanEvents) {
            val s = store.snapshot()
            if (s.storageFailed || s.integrityBlocked || s.pending != null || s.manualFence?.operationId != operation.operationId) {
                events.finished("安全存储/手动身份不可确认；未开始扫描", true); return
            }
            val scanner = ManualBleScanner(app, operation, lease, retirement, events)
            manualScanners.install(operation.operationId, scanner)
            scanner.start()
        }
        override fun retireScan(operationId: String) { manualScanners.retire(operationId) { it.retire() } }
        override fun later(delayMs: Long, task: () -> Unit) { handler.postDelayed(task, delayMs) }
        override fun endForeground(operationId: String) {
            if (serviceTicket?.owner == ForegroundAdmission.Owner.MANUAL && serviceRun == operationId) finishService()
        }
    }, { System.currentTimeMillis() }, { SystemClock.elapsedRealtime() }, { UUID.randomUUID().toString() }, { publishDetails() },
        feedback = { value -> Notifier.always(app, value.conciseMessage()) }, audit = audit)
    init { client.start() }

    fun detailsSnapshot() = DetailsSnapshot(store.snapshot(), RunNotification.details(app), client.status,
        manual.activeResult(), manual.rejectionNotices(), latestNotice)
    fun subscribeDetails(observer: (DetailsSnapshot) -> Unit): Long { requireMain(); return subscriptions.attach(observer) }
    fun unsubscribeDetails(token: Long) { requireMain(); subscriptions.detach(token) }
    fun publishDetails() { requireMain(); subscriptions.publish() }
    fun manualRequest(action: ManualAction, visible: Boolean): Boolean {
        requireMain()
        return manual.request(action, visible, BtPermissions.allGranted(app))
    }
    fun dismissHistory(runId: String) {
        requireMain()
        if (!store.dismiss(runId)) notice("历史删除状态未保存", "原卡片删除未确认保存；安全待恢复/次数不改变。${store.snapshot().disclosure.orEmpty()}")
        publishDetails()
    }
    private fun notice(title: String, text: String) {
        latestNotice = "$title：$text"; RunNotification.notice(app, title, text); publishDetails()
    }
    private fun report(record: ResetRecord, state: AdapterState, message: String, final: Boolean = false) {
        RunNotification.runtime(app, record, state, message, final); publishDetails()
    }

    /** NoDisplay dispatcher: setup/FGS opportunity is synchronous, never delayed until a human response. */
    fun launch(activity: Activity, verbose: Boolean) {
        requireMain()
        val s = store.snapshot()
        if (!gate.newRequest() && !(s.pending != null && activeRun == null && serviceDone == null &&
                    !admission.busy() && gate.recovery(s.pending.runId))) {
            if (retirement.blocked()) retirement.retryRetirement()
            notice("新运行未执行", "已有运行/待恢复/手动或扫描清理隔离，或存储不可确认；立即拒绝，不排队/替换")
            return
        }
        val id = s.pending?.runId ?: UUID.randomUUID().toString()
        if (!ownership.acquire(id)) return
        activeRun = id; wantsToast = verbose; startupSettled = false
        val needsNearby = !BtPermissions.allGranted(app)
        val setupNecessary = !setupPrefs.getBoolean("notification_setup_seen", false) ||
            (needsNearby && !setupPrefs.getBoolean("nearby_attempted", false))
        waitingForSetup = setupNecessary
        StartupLaunchSequence.dispatch<SwitchCapability>(
            startup = { ready -> client.startup(callback = ready) },
            decide = {
                if (setupNecessary) activity.startActivity(Intent(activity, PermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(PermissionActivity.EXTRA_SETUP_RUN, id))
                else if (needsNearby) {
                    notice("缺少附近设备权限", "未扫描、未开关蓝牙；此前已拒绝，不重复弹窗。点击通知详情可显式申请或打开设置")
                    releaseRun(id)
                } else if (!tryForeground(id) && startupSettled) continueStartup(id)
            }, ready = delivered@{ capability ->
                if (!ownership.current(id) || !gate.automaticContinuation(id, activeRun, true)) return@delivered
                startupSettled = true
                RunNotification.authorization(app, capability.reason); publishDetails()
                val ready = setupWait; setupWait = null
                if (waitingForSetup) ready?.invoke() else continueStartup(id)
            })
    }
    fun awaitStartupForSetup(runId: String, callback: () -> Unit) {
        requireMain()
        if (!ownership.current(runId) || !gate.automaticContinuation(runId, activeRun, true)) return
        if (startupSettled) callback() else setupWait = callback
    }
    fun detachSetupCallback() { requireMain(); setupWait = null }
    fun setupCompleted(runId: String, granted: Boolean) {
        requireMain()
        if (!ownership.current(runId)) return
        if (!gate.automaticContinuation(runId, activeRun, true)) { releaseRun(runId); return }
        waitingForSetup = false; setupWait = null
        if (!granted || !BtPermissions.allGranted(app)) {
            notice("附近设备权限未授予", "未开始扫描、未开关蓝牙；Shizuku 授权检查独立完成。详情可显式重试")
            releaseRun(runId)
        } else if (!tryForeground(runId)) continueStartup(runId)
    }
    private fun continueStartup(id: String) {
        if (!ownership.current(id) || !gate.automaticContinuation(id, activeRun) || !startupSettled || waitingForSetup ||
            scan != null || admission.waiting(id)) return
        if (engine.read() != null) {
            notice("继续上次恢复", "存在持久化待恢复记录；不会开始新扫描/OFF")
            if (serviceDone != null) recoveryStep(serviceLease ?: CommandLease.foreground(), false) { scheduleServiceTick() }
            else {
                ReEnableScheduler.armNextTick(app)
                recoveryStep(CommandLease({ SystemClock.elapsedRealtime() }, SystemClock.elapsedRealtime() + 8_000L), true) { releaseRun(id) }
            }
        } else {
            val window = admission.scanWindow(id, ResetService.SCAN_TIMEOUT_MS, TriggerReceiver.RECEIVER_SCAN_TIMEOUT_MS) ?: return
            val foreground = serviceDone != null && serviceRun == id
            startScan(id, window, if (foreground) serviceLease ?: CommandLease.foreground()
                else CommandLease({ SystemClock.elapsedRealtime() }, SystemClock.elapsedRealtime() + 9_000L)) { }
        }
    }
    /** Broadcasts never launch Activities or wait for permission. */
    fun broadcastScan(verbose: Boolean, lease: CommandLease, done: () -> Unit) {
        requireMain()
        if (!BtPermissions.allGranted(app)) {
            notice("缺少附近设备权限", "广播未扫描/未开关。请点击通知详情或首次打开图标完成必要权限设置")
            done(); return
        }
        if (!gate.newRequest() || commandBusy != null) {
            if (retirement.blocked()) retirement.retryRetirement()
            notice("广播新运行未执行", "已有运行/恢复/手动/扫描清理或存储隔离；不排队/替换")
            done(); return
        }
        val id = UUID.randomUUID().toString()
        if (!ownership.acquire(id)) { done(); return }
        activeRun = id; wantsToast = verbose; startupSettled = true
        if (tryForeground(id)) { done(); return }
        startScan(id, TriggerReceiver.RECEIVER_SCAN_TIMEOUT_MS, lease, done)
    }
    private fun startScan(id: String, window: Long, lease: CommandLease, done: () -> Unit) {
        if (!ownership.current(id) || !gate.automaticContinuation(id, activeRun) || scan != null ||
            store.snapshot().pending != null || !lease.valid()) { done(); return }
        if (retirement.blocked()) {
            retirement.retryRetirement()
            notice("旧扫描清理尚未确认", "本次未扫描/开关；只重试原注册失败清理，不覆盖安全隔离")
            done(); releaseRun(id); finishService(); return
        }
        RunNotification.beginScan(app); publishDetails()
        val controller = BandResetController(app, ownership, id, lease, retirement, audit) { match ->
            if (!ownership.current(id) || !gate.automaticContinuation(id, activeRun) || !lease.valid()) false
            else {
                match.scanStartedElapsed?.let { runStartElapsed[id] = it }
                val armed = engine.armAccepted(match, wantsToast)
                RunNotification.history(app, id); publishDetails()
                if (armed == null) {
                    notice("无法安排安全恢复", "持久化/恢复闹钟安排失败；未关闭蓝牙。失败尝试未计数；${store.snapshot().disclosure.orEmpty()}")
                    false
                } else true
            }
        }
        scan = controller; scanRun = id
        controller.run(window, wantsToast) { result ->
            if (scan === controller) { scan = null; scanRun = null }
            controller.lastReport?.let { r -> RunNotification.fastPhase(app, result, r.facts, r.matchedLabel, r.nearMissSample, r.adapterState) }
            publishDetails(); done()
            if (!ownership.current(id)) return@run
            if (result == BandResetController.Result.RESET_TRIGGERED) {
                if (serviceDone != null) recoveryStep(serviceLease ?: CommandLease.foreground(), false) { scheduleServiceTick() }
                else releaseRun(id)
            } else { releaseRun(id); finishService() }
        }
    }
    private fun cancelScanFor(id: String) {
        if (scanRun != id) return
        val previous = scan
        scan = null; scanRun = null
        previous?.cancel()
    }
    fun cancelBroadcastScan(lease: CommandLease) {
        requireMain(); lease.cancel()
        if (serviceDone == null) activeRun?.let { cancelScanFor(it); releaseRun(it) }
    }
    fun serviceAdmissionCurrent(ticket: ForegroundAdmission.Ticket): Boolean {
        requireMain()
        if (!admission.current(ticket)) return false
        val s = store.snapshot()
        if (s.storageFailed || s.integrityBlocked) return false
        return when (ticket.owner) {
            ForegroundAdmission.Owner.MANUAL -> manual.owns(ticket.runId) && s.pending == null &&
                s.manualFence?.stage == ManualStage.READY && activeRun == null && scan == null && !retirement.blocked()
            ForegroundAdmission.Owner.AUTOMATIC -> s.manualFence == null &&
                (gate.automaticContinuation(ticket.runId, activeRun, true) || gate.recovery(ticket.runId))
        }
    }
    fun serviceStarted(ticket: ForegroundAdmission.Ticket, startId: Int, done: () -> Unit) {
        requireMain()
        if (!serviceAdmissionCurrent(ticket) || !admission.accept(ticket)) { done(); return }
        serviceTicket = ticket; serviceStartId = startId; serviceRun = ticket.runId; serviceDone = done
        serviceLease = if (ticket.owner == ForegroundAdmission.Owner.MANUAL)
            CommandLease({ SystemClock.elapsedRealtime() }, SystemClock.elapsedRealtime() + ManualOperationController.WORK_WINDOW_MS)
        else CommandLease.foreground()
        if (ticket.owner == ForegroundAdmission.Owner.MANUAL) manual.serviceStarted(ticket.runId, serviceLease!!)
        else if (store.snapshot().pending?.runId == ticket.runId) recoveryStep(serviceLease!!, false) { scheduleServiceTick() }
        else if (startupSettled && !waitingForSetup) continueStartup(ticket.runId)
        publishDetails()
    }
    fun serviceGone(ticket: ForegroundAdmission.Ticket, startId: Int) {
        requireMain()
        if (serviceTicket != ticket || serviceStartId != startId || !admission.admitted(ticket)) return
        handler.removeCallbacks(serviceTick)
        serviceLease?.cancel(); serviceLease = null
        serviceDone = null; serviceRun = null; serviceTicket = null; serviceStartId = 0
        admission.end(ticket)
        if (ticket.owner == ForegroundAdmission.Owner.MANUAL) manual.serviceEnded(ticket.runId)
        else if (activeRun == ticket.runId) releaseRun(ticket.runId)
        publishDetails() // Service destruction never clears entered native authority.
    }
    fun alarm(lease: CommandLease, done: () -> Unit) {
        requireMain()
        val pending = engine.read() ?: run { done(); return }
        if (!gate.recovery(pending.runId)) {
            if (!store.snapshot().storageFailed && !store.snapshot().integrityBlocked) ReEnableScheduler.armNextTick(app)
            notice("恢复暂未派发", "手动/扫描清理/设置或存储隔离仍占用；保留原待恢复，不借用手动身份")
            done(); return
        }
        if (!ReEnableScheduler.armNextTick(app)) {
            report(pending, AdapterState.UNKNOWN, "恢复闹钟安排失败；未派发新命令，保留记录", true); done(); return
        }
        if (serviceDone != null && serviceTicket?.owner == ForegroundAdmission.Owner.AUTOMATIC) {
            recoveryStep(serviceLease ?: lease, false) { scheduleServiceTick() }; done(); return
        }
        if (BtPermissions.allGranted(app) && tryForeground(pending.runId)) { done(); return }
        recoveryStep(lease, true, done)
    }
    private fun recoveryStep(lease: CommandLease, constrained: Boolean, done: () -> Unit) {
        val pending = engine.read() ?: run { finishService(); done(); return }
        if (!gate.recovery(pending.runId)) {
            if (!store.snapshot().storageFailed && !store.snapshot().integrityBlocked) ReEnableScheduler.armNextTick(app)
            if (store.snapshot().storageFailed || store.snapshot().integrityBlocked) finishService()
            publishDetails(); done(); return
        }
        if (!ReEnableScheduler.armNextTick(app)) {
            report(pending, AdapterState.UNKNOWN, "恢复闹钟安排失败；保留记录，未开始新命令")
            finishService(); done(); return
        }
        if (!lease.valid() || tickBusy) { done(); return }
        tickBusy = true
        val completion = CompletionGate()
        fun finish() { if (completion.finish()) { tickBusy = false; done() } }
        readSampleBounded(lease) sampled@{ sample ->
            if (!lease.valid() || !gate.recovery(pending.runId)) { finish(); return@sampled }
            val state = sample.state
            val before = engine.read() ?: run { finish(); return@sampled }
            val duration = runStartElapsed[before.runId]?.let { start -> sample.sampledElapsed?.let { (it - start).takeIf { ms -> ms >= 0L } } }
            val step = engine.observe(state, sample.sampledAt, duration)
            val current = engine.read()
            publishDetails()
            when (step) {
                RecoveryStep.DISPATCH_OFF, RecoveryStep.DISPATCH_ON -> {
                    if (commandBusy != null || current == null || !gate.recovery(current.runId)) { finish(); return@sampled }
                    val enabled = step == RecoveryStep.DISPATCH_ON
                    var command = engine.dispatch(current, lease, enabled) ?: run { finish(); return@sampled }
                    commandBusy = command.commandId
                    val driver = BluetoothSwitchDriver(backend)
                    driver.execute(enabled, lease, if (constrained) 1_200L else 5_000L,
                        if (constrained) 5_500L else 6_000L, command.ordinaryOnly,
                        onEvent = { entry ->
                            if (engine.note(command, entry)) engine.read()?.let { report(it, state, entry.reason) }
                        },
                        invocation = SwitchInvocation(entered = { at ->
                            if (engine.nativeEntered(command, at)) { RunNotification.history(app, command.runId); publishDetails() }
                        }, beforeAttempt = attempt@{ previous ->
                            if (!lease.valid() || !gate.recovery(command.runId)) return@attempt false
                            if (previous != null) {
                                val next = engine.nextPhysicalAttempt(command, previous) ?: return@attempt false
                                command = next; commandBusy = next.commandId
                            }
                            store.snapshot().pending?.commandId == command.commandId
                        })) { outcome ->
                        if (commandBusy == command.commandId) commandBusy = null
                        val continuation = engine.completeAndRearm(command, outcome)
                        if (continuation != null) {
                            val followup = continuation.failure ?: if (continuation.rearmed == true)
                                "已重新安排后续恢复闹钟；只在新的执行窗口继续" else "执行结束仍未确认；保留安全记录"
                            report(continuation.record, state, "${if (enabled) "ON" else "OFF"} ${outcome.status}；${outcome.reason}；$followup",
                                continuation.rearmed == false)
                            if (continuation.rearmed == true && serviceRun == continuation.record.runId) scheduleServiceTick()
                        } else publishDetails()
                        finish()
                    }
                }
                RecoveryStep.RESTORED, RecoveryStep.FAILED -> {
                    report(before, state, if (step == RecoveryStep.RESTORED)
                        "已观察到实际 ON，蓝牙已恢复；共 ${before.attempts + 1} 次观察（不是保证秒数）" else "未观察到关闭/请求未执行；实际状态 $state", true)
                    ReEnableScheduler.cancel(app); runStartElapsed.remove(before.runId)
                    activeRun?.let { releaseRun(it) }; finishService(); finish()
                }
                RecoveryStep.EXHAUSTED, RecoveryStep.QUARANTINED, RecoveryStep.STORAGE_FAILED -> {
                    report(current ?: before, state, current?.reason ?: "存储更新失败；保留安全记录", true)
                    ReEnableScheduler.cancel(app)
                    activeRun?.let { releaseRun(it) }; finishService(); finish()
                }
                else -> { RunNotification.history(app, before.runId); finish() }
            }
        }
    }
    private fun readSampleBounded(lease: CommandLease, callback: (StateSample) -> Unit) {
        val completion = CompletionGate()
        BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 600L,
            { StateSample(AdapterState.UNKNOWN) }, { StateSample(AdapterState.UNKNOWN) }, {
                val state = BluetoothSwitch.state(app)
                StateSample(state, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }) { sample, _ -> if (completion.finish()) callback(sample) }
    }
    private fun readStateBounded(lease: CommandLease, callback: (AdapterState) -> Unit) = readSampleBounded(lease) { callback(it.state) }
    private fun scheduleServiceTick() {
        handler.removeCallbacks(serviceTick)
        if (serviceDone != null && serviceTicket?.owner == ForegroundAdmission.Owner.AUTOMATIC)
            handler.postDelayed(serviceTick, ReEnableScheduler.TICK_INTERVAL_MS)
    }
    fun retryAuthorization(callback: () -> Unit = {}) {
        requireMain()
        client.startup(explicit = true) { publishDetails(); callback() }
    }
    fun retryRecovery(callback: () -> Unit = {}) {
        requireMain()
        val pending = store.snapshot().pending
        if (pending == null || !gate.recovery(pending.runId)) {
            notice("不能继续恢复", "无安全待恢复，或手动/扫描/设置/存储隔离仍占用；不覆盖隔离")
            callback(); return
        }
        val lease = CommandLease({ SystemClock.elapsedRealtime() }, SystemClock.elapsedRealtime() + 8_000L)
        readSampleBounded(lease) sampled@{ sample ->
            val state = sample.state
            if (!lease.valid() || !gate.recovery(pending.runId) || store.snapshot().pending?.runId != pending.runId) { callback(); return@sampled }
            if (state != AdapterState.OFF) {
                notice("未派发恢复开关", "当前读取状态 $state；只有实际 OFF 才显式派发 ON，不扫描/不重放 OFF")
                callback(); return@sampled
            }
            if (!engine.retryRecovery(state, sample.sampledAt)) {
                notice("不能继续未结束命令", "不存在安全 ON_READY/OFF_SETTLED/耗尽恢复，或旧 OFF/ON 执行权限未确认结束；不会覆盖安全隔离")
                callback(); return@sampled
            }
            val current = engine.read() ?: run { callback(); return@sampled }
            val continuation = engine.rearmContinuation(current)
            report(continuation?.record ?: current, state, continuation?.failure ?: if (continuation?.rearmed == true)
                "已安排显式 ON 恢复；不会重新扫描/重放 OFF；由安全闹钟/既有服务续接" else "待恢复记录不可安全续接；未派发命令", true)
            callback() // no delayed foreground start based on an Activity that may have closed
        }
    }
    private fun tryForeground(id: String, owner: ForegroundAdmission.Owner = ForegroundAdmission.Owner.AUTOMATIC): Boolean {
        if (serviceDone != null) return serviceRun == id && serviceTicket?.owner == owner
        if (admission.waiting(id)) return admission.pendingTicket(id)?.owner == owner
        if (SystemClock.elapsedRealtime() < foregroundUnavailableUntil) return false
        val ticket = admission.begin(id, owner) ?: return false
        if (!ResetService.tryStart(app, ticket)) { admission.reject(ticket); return false }
        handler.postDelayed({ if (admission.expire(ticket)) admissionFailed(ticket, "服务提升回调未在4秒内到达；晚到服务不会接管新运行") }, 4_000L)
        return true
    }
    fun serviceAdmissionFailed(ticket: ForegroundAdmission.Ticket, reason: String) {
        requireMain()
        if (admission.reject(ticket)) admissionFailed(ticket, reason)
    }
    private fun admissionFailed(ticket: ForegroundAdmission.Ticket, reason: String) {
        foregroundUnavailableUntil = SystemClock.elapsedRealtime() + 30_000L
        if (ticket.owner == ForegroundAdmission.Owner.MANUAL) { manual.admissionFailed(ticket.runId, reason); return }
        val pending = engine.read()
        if (pending?.runId == ticket.runId) {
            ReEnableScheduler.armNextTick(app)
            report(pending, AdapterState.UNKNOWN, "前台服务提升未成功：$reason；下次闹钟走受限步骤，不启动后台界面", true)
        } else if (activeRun == ticket.runId) {
            notice("前台运行未获接管", "已取消该运行扫描；未新增开关：$reason；无后台界面绕过")
            releaseRun(ticket.runId)
        }
    }
    private fun finishService() {
        handler.removeCallbacks(serviceTick)
        val done = serviceDone; val ticket = serviceTicket
        serviceDone = null; serviceRun = null; serviceTicket = null; serviceStartId = 0
        serviceLease?.cancel(); serviceLease = null
        ticket?.let { admission.end(it) }
        done?.invoke()
    }
    private fun releaseRun(id: String) {
        admission.pendingTicket(id)?.takeIf { it.owner == ForegroundAdmission.Owner.AUTOMATIC }?.let { admission.reject(it) }
        ownership.release(id)
        if (activeRun == id) { activeRun = null; waitingForSetup = false; setupWait = null }
    }
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
    companion object { fun get(context: Context) = (context.applicationContext as MonitorApplication).coordinator }
}
