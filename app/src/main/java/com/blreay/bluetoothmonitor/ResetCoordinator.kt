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
    private val runtimeAudit = RuntimeAudit(audit)
    private val runRequests = linkedMapOf<String, String>()
    private fun rememberRequest(id: String, requestId: String) {
        runRequests[id] = requestId
        while (runRequests.size > 128) runRequests.remove(runRequests.keys.first())
    }
    private fun runtimeIdentity(): RuntimeIdentity {
        val snapshot = store.snapshot()
        snapshot.manualFence?.let { return RuntimeIdentity("manual", it.operationId, it.commandId) }
        snapshot.pending?.let { return RuntimeIdentity("automatic", it.runId, it.commandId, runRequests[it.runId]) }
        return RuntimeIdentity("automatic", activeRun.orEmpty(), "", activeRun?.let { runRequests[it] })
    }
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
        } },
        audit = audit, onFailure = { _, reason -> handler.post { notice("扫描清理未确认", reason) } })
    private val engine = DurableResetEngine(store,
        { ReEnableScheduler.armNextTick(app) }, { System.currentTimeMillis() }, { UUID.randomUUID().toString() }, audit)
    private val subscriptions = VisibleDetailsSubscription { detailsSnapshot() }
    val client = ShizukuBluetoothClient(app) {
        audit.event("shizuku.status", "reason" to it)
        RunNotification.authorization(app, it); publishDetails()
    }
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
    private fun observedDriver(identity: () -> RuntimeIdentity) = BluetoothSwitchDriver(runtimeAudit.backend(backend, identity))
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
            var commandId = operation.commandId
            val driver = observedDriver { RuntimeIdentity("manual", operation.operationId, commandId) }
            driver.execute(operation.action == ManualAction.ON, lease, ordinaryOnly = ordinaryOnly, onEvent = event,
                invocation = SwitchInvocation(entered, { previous ->
                    val s = store.snapshot()
                    if (!lease.valid() || s.storageFailed || s.integrityBlocked || s.pending != null ||
                        s.manualFence?.operationId != operation.operationId || serviceTicket?.owner != ForegroundAdmission.Owner.MANUAL ||
                        serviceRun != operation.operationId || serviceTicket?.runId != operation.operationId)
                        false else {
                            val allowed = previous == null || nextAttempt(previous)
                            if (allowed) commandId = store.snapshot().manualFence?.takeIf { it.operationId == operation.operationId }?.commandId ?: commandId
                            allowed
                        }
                }), callback = result)
        }
        override fun sample(lease: CommandLease, result: (StateSample) -> Unit) = readSampleBounded(lease, result)
        override fun scan(operation: ManualOperation, lease: CommandLease, events: ManualScanEvents) {
            val s = store.snapshot()
            if (s.storageFailed || s.integrityBlocked || s.pending != null || s.manualFence?.operationId != operation.operationId) {
                events.finished("安全存储/手动身份不可确认；未开始扫描", true); return
            }
            val scanner = ManualBleScanner(app, operation, lease, retirement, events, audit)
            manualScanners.install(operation.operationId, scanner)
            scanner.start()
        }
        override fun retireScan(operationId: String) { manualScanners.retire(operationId) { it.retire() } }
        override fun later(delayMs: Long, task: () -> Unit) { handler.postDelayed(task, delayMs) }
        override fun endForeground(operationId: String) {
            if (serviceTicket?.owner == ForegroundAdmission.Owner.MANUAL && serviceRun == operationId) finishService()
        }
    }, { System.currentTimeMillis() }, { SystemClock.elapsedRealtime() }, { UUID.randomUUID().toString() }, { publishDetails() },
        feedback = { value ->
            // SCAN results remain in their one result card. Never toast advertisements or replay snapshots.
            if (value.action != ManualAction.SCAN || value.type in setOf(ManualFeedbackType.REJECTED, ManualFeedbackType.STORAGE_FAILED) ||
                value.status == ManualResultStatus.FAILED || value.status == ManualResultStatus.UNCERTAIN)
                Notifier.always(app, value.conciseMessage())
        }, audit = audit)
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
        audit.event("runtime.notice", "title" to title, "reason" to text)
        latestNotice = "$title：$text"; RunNotification.notice(app, title, text); publishDetails()
    }
    private fun report(record: ResetRecord, state: AdapterState, message: String, final: Boolean = false) {
        audit.event("automatic.report", "runId" to record.runId, "commandId" to record.commandId, "version" to record.version,
            "state" to state, "authorityEnded" to record.authorityEnded, "reason" to message, "finalPresentation" to final)
        RunNotification.runtime(app, record, state, message, final); publishDetails()
    }

    /** NoDisplay dispatcher: setup/FGS opportunity is synchronous, never delayed until a human response. */
    fun launch(activity: Activity, verbose: Boolean, requestId: String = UUID.randomUUID().toString()) {
        requireMain()
        val source = when (activity.intent.action) {
            Intent.ACTION_MAIN -> "launcher"
            "${app.packageName}.action.RUN" -> "RUN"
            else -> "activity" // no reliable caller identification for arbitrary explicit intents
        }
        runtimeAudit.ingress(source, requestId, activity.intent.action, activity.intent.component?.flattenToShortString())
        val s = store.snapshot()
        if (!gate.newRequest() && !(s.pending != null && activeRun == null && serviceDone == null &&
                    !admission.busy() && gate.recovery(s.pending.runId))) {
            if (retirement.blocked()) retirement.retryRetirement()
            audit.event("runtime.launch.refused", "requestId" to requestId, "reason" to "operation gate/retirement/storage occupied",
                "pendingRunId" to s.pending?.runId, "manualOperationId" to s.manualFence?.operationId)
            notice("新运行未执行", "已有运行/待恢复/手动或扫描清理隔离，或存储不可确认；立即拒绝，不排队/替换")
            return
        }
        val id = s.pending?.runId ?: UUID.randomUUID().toString()
        if (!ownership.acquire(id)) {
            audit.event("runtime.launch.refused", "requestId" to requestId, "runId" to id, "reason" to "run owner occupied")
            return
        }
        rememberRequest(id, requestId)
        audit.event("runtime.launch.accepted", "requestId" to requestId, "runId" to id, "recoveryReuse" to (s.pending != null))
        activeRun = id; wantsToast = verbose; startupSettled = false
        val needsNearby = !BtPermissions.allGranted(app)
        val setupNecessary = !setupPrefs.getBoolean("notification_setup_seen", false) ||
            (needsNearby && !setupPrefs.getBoolean("nearby_attempted", false))
        waitingForSetup = setupNecessary
        audit.event("runtime.startup.route", "requestId" to requestId, "runId" to id, "setupNecessary" to setupNecessary, "needsNearby" to needsNearby)
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
                audit.event("shizuku.startup.result", "requestId" to requestId, "runId" to id, "mode" to capability.mode, "reason" to capability.reason)
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
        audit.event("runtime.setup.completed", "runId" to runId, "granted" to granted, "ownerCurrent" to ownership.current(runId))
        if (!ownership.current(runId)) return
        if (!gate.automaticContinuation(runId, activeRun, true)) { releaseRun(runId); return }
        waitingForSetup = false; setupWait = null
        if (!granted || !BtPermissions.allGranted(app)) {
            notice("附近设备权限未授予", "未开始扫描、未开关蓝牙；Shizuku 授权检查独立完成。详情可显式重试")
            releaseRun(runId)
        } else if (!tryForeground(runId)) continueStartup(runId)
    }
    private fun continueStartup(id: String) {
        audit.event("runtime.startup.continuation", "runId" to id, "requestId" to runRequests[id], "startupSettled" to startupSettled,
            "waitingForSetup" to waitingForSetup, "servicePending" to admission.waiting(id))
        if (!ownership.current(id) || !gate.automaticContinuation(id, activeRun) || !startupSettled || waitingForSetup ||
            scan != null || admission.waiting(id)) {
            audit.event("runtime.startup.continuation.refused", "runId" to id, "reason" to "owner/gate/startup/setup/scan/admission not ready")
            return
        }
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
    fun broadcastScan(verbose: Boolean, lease: CommandLease, requestId: String = UUID.randomUUID().toString(), done: () -> Unit) {
        requireMain()
        runtimeAudit.ingress("broadcast", requestId, TriggerReceiver.action(app), TriggerReceiver::class.java.name)
        if (!BtPermissions.allGranted(app)) {
            audit.event("runtime.broadcast.refused", "requestId" to requestId, "reason" to "nearby permission missing")
            notice("缺少附近设备权限", "广播未扫描/未开关。请点击通知详情或首次打开图标完成必要权限设置")
            done(); return
        }
        if (!gate.newRequest() || commandBusy != null) {
            if (retirement.blocked()) retirement.retryRetirement()
            audit.event("runtime.broadcast.refused", "requestId" to requestId, "reason" to "operation gate/command occupied")
            notice("广播新运行未执行", "已有运行/恢复/手动/扫描清理或存储隔离；不排队/替换")
            done(); return
        }
        val id = UUID.randomUUID().toString()
        if (!ownership.acquire(id)) {
            audit.event("runtime.broadcast.refused", "requestId" to requestId, "runId" to id, "reason" to "run owner occupied")
            done(); return
        }
        rememberRequest(id, requestId)
        audit.event("runtime.broadcast.accepted", "requestId" to requestId, "runId" to id)
        activeRun = id; wantsToast = verbose; startupSettled = true
        if (tryForeground(id)) { done(); return }
        startScan(id, TriggerReceiver.RECEIVER_SCAN_TIMEOUT_MS, lease, done)
    }
    private fun startScan(id: String, window: Long, lease: CommandLease, done: () -> Unit) {
        audit.event("automatic.scan.request", "runId" to id, "requestId" to runRequests[id], "windowMs" to window)
        if (!ownership.current(id) || !gate.automaticContinuation(id, activeRun) || scan != null ||
            store.snapshot().pending != null || !lease.valid()) {
            audit.event("automatic.scan.refused", "runId" to id, "reason" to "owner/gate/pending/lease invalid")
            done(); return
        }
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
                audit.event("automatic.arm.result", "runId" to id, "armed" to (armed != null),
                    "commandId" to armed?.commandId, "reason" to store.snapshot().disclosure, at = match.acceptedAt)
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
        audit.event("foreground.service.ingress", "runId" to ticket.runId, "admissionId" to ticket.token, "owner" to ticket.owner, "startId" to startId)
        if (!serviceAdmissionCurrent(ticket) || !admission.accept(ticket)) {
            audit.event("foreground.service.refused", "runId" to ticket.runId, "admissionId" to ticket.token, "startId" to startId, "reason" to "exact admission not current")
            done(); return
        }
        audit.event("foreground.service.accepted", "runId" to ticket.runId, "admissionId" to ticket.token, "owner" to ticket.owner, "startId" to startId)
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
    fun alarm(lease: CommandLease, requestId: String = UUID.randomUUID().toString(), done: () -> Unit) {
        requireMain()
        runtimeAudit.ingress("alarm", requestId, ReEnableScheduler.action(app), ReEnableReceiver::class.java.name)
        val pending = engine.read() ?: run {
            audit.event("recovery.alarm.refused", "requestId" to requestId, "reason" to "no pending record")
            done(); return
        }
        audit.event("recovery.alarm.pending", "requestId" to requestId, "runId" to pending.runId,
            "commandId" to pending.commandId, "stage" to pending.stage, "authorityEnded" to pending.authorityEnded)
        if (!gate.recovery(pending.runId)) {
            if (!store.snapshot().storageFailed && !store.snapshot().integrityBlocked) ReEnableScheduler.armNextTick(app)
            audit.event("recovery.alarm.refused", "requestId" to requestId, "runId" to pending.runId, "reason" to "recovery gate occupied")
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
        audit.event("recovery.step.request", "runId" to pending.runId, "commandId" to pending.commandId,
            "stage" to pending.stage, "constrained" to constrained, "authorityEnded" to pending.authorityEnded)
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
            runtimeAudit.event("recovery.decision", RuntimeIdentity("automatic", before.runId, before.commandId, runRequests[before.runId]),
                "step" to step, "state" to state, "authorityEnded" to before.authorityEnded, "nextCommandId" to current?.commandId,
                "reason" to current?.reason, "sampledElapsedMs" to sample.sampledElapsed, at = sample.sampledAt)
            publishDetails()
            when (step) {
                RecoveryStep.DISPATCH_OFF, RecoveryStep.DISPATCH_ON -> {
                    if (commandBusy != null || current == null || !gate.recovery(current.runId)) { finish(); return@sampled }
                    val enabled = step == RecoveryStep.DISPATCH_ON
                    var command = engine.dispatch(current, lease, enabled) ?: run { finish(); return@sampled }
                    commandBusy = command.commandId
                    val commandRequestId = runRequests[command.runId]
                    val driver = observedDriver { RuntimeIdentity("automatic", command.runId, command.commandId, commandRequestId) }
                    runtimeAudit.event("automatic.switch.request", RuntimeIdentity("automatic", command.runId, command.commandId, runRequests[command.runId]),
                        "enabled" to enabled, "ordinaryOnly" to command.ordinaryOnly, "version" to command.version, at = command.dispatchedAt)
                    driver.execute(enabled, lease, if (constrained) 1_200L else 5_000L,
                        if (constrained) 5_500L else 6_000L, command.ordinaryOnly,
                        onEvent = { entry ->
                            runtimeAudit.event("automatic.backend.route", RuntimeIdentity("automatic", command.runId, command.commandId, commandRequestId),
                                "mode" to entry.mode, "reason" to entry.reason)
                            if (engine.note(command, entry)) engine.read()?.let { report(it, state, entry.reason) }
                        },
                        invocation = SwitchInvocation(entered = { at ->
                            runtimeAudit.event("automatic.native.entered", RuntimeIdentity("automatic", command.runId, command.commandId, commandRequestId),
                                "boundary" to "client RPC/API entry, not service spawn or hardware instant", at = at)
                            if (engine.nativeEntered(command, at)) { RunNotification.history(app, command.runId); publishDetails() }
                        }, beforeAttempt = attempt@{ previous ->
                            if (!lease.valid() || !gate.recovery(command.runId)) return@attempt false
                            if (previous != null) {
                                val next = engine.nextPhysicalAttempt(command, previous) ?: return@attempt false
                                runtimeAudit.event("automatic.command.next", RuntimeIdentity("automatic", next.runId, next.commandId, commandRequestId),
                                    "previousCommandId" to command.commandId, "reason" to next.reason, at = next.dispatchedAt)
                                command = next; commandBusy = next.commandId
                            }
                            store.snapshot().pending?.commandId == command.commandId
                        })) { outcome ->
                        if (commandBusy == command.commandId) commandBusy = null
                        runtimeAudit.outcome("automatic.outcome", RuntimeIdentity("automatic", command.runId, command.commandId, commandRequestId), outcome)
                        val continuation = engine.completeAndRearm(command, outcome)
                        runtimeAudit.event("automatic.completion.save", RuntimeIdentity("automatic", command.runId, command.commandId, commandRequestId),
                            "saved" to (continuation != null), "rearmed" to continuation?.rearmed, "reason" to continuation?.failure)
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
        val captured = runtimeIdentity() // request-boundary owner, never queried after asynchronous delivery
        val completion = CompletionGate()
        BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.ADAPTER, lease, 600L,
            { StateSample(AdapterState.UNKNOWN) }, { StateSample(AdapterState.UNKNOWN) }, {
                val state = BluetoothSwitch.state(app)
                StateSample(state, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }) { sample, _ ->
                runtimeAudit.sample(captured, sample)
                if (completion.finish()) callback(sample)
            }
    }
    private fun readStateBounded(lease: CommandLease, callback: (AdapterState) -> Unit) = readSampleBounded(lease) { callback(it.state) }
    private fun scheduleServiceTick() {
        handler.removeCallbacks(serviceTick)
        if (serviceDone != null && serviceTicket?.owner == ForegroundAdmission.Owner.AUTOMATIC)
            handler.postDelayed(serviceTick, ReEnableScheduler.TICK_INTERVAL_MS)
    }
    fun retryAuthorization(callback: () -> Unit = {}) {
        requireMain()
        val requestId = UUID.randomUUID().toString()
        audit.event("shizuku.retry.request", "requestId" to requestId)
        client.startup(explicit = true) {
            audit.event("shizuku.retry.result", "requestId" to requestId, "mode" to it.mode, "reason" to it.reason)
            publishDetails(); callback()
        }
    }
    fun retryRecovery(callback: () -> Unit = {}) {
        requireMain()
        val requestId = UUID.randomUUID().toString()
        audit.event("recovery.retry.request", "requestId" to requestId)
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
            // No delayed foreground start based on an Activity that may already have closed.
            callback()
        }
    }
    private fun tryForeground(id: String, owner: ForegroundAdmission.Owner = ForegroundAdmission.Owner.AUTOMATIC): Boolean {
        audit.event("foreground.request", "runId" to id, "owner" to owner)
        if (serviceDone != null) return (serviceRun == id && serviceTicket?.owner == owner).also {
            audit.event("foreground.reuse", "runId" to id, "owner" to owner, "accepted" to it, "reason" to "existing service owner")
        }
        if (admission.waiting(id)) return (admission.pendingTicket(id)?.owner == owner).also {
            audit.event("foreground.reuse", "runId" to id, "owner" to owner, "accepted" to it, "reason" to "existing exact pending admission")
        }
        if (SystemClock.elapsedRealtime() < foregroundUnavailableUntil) {
            audit.event("foreground.refused", "runId" to id, "owner" to owner, "reason" to "admission cooldown")
            return false
        }
        val ticket = admission.begin(id, owner) ?: run {
            audit.event("foreground.refused", "runId" to id, "owner" to owner, "reason" to "admission slot occupied")
            return false
        }
        if (!ResetService.tryStart(app, ticket)) {
            audit.event("foreground.refused", "runId" to id, "admissionId" to ticket.token, "owner" to owner, "reason" to "legal FGS start refused")
            admission.reject(ticket); return false
        }
        audit.event("foreground.pending", "runId" to id, "admissionId" to ticket.token, "owner" to owner, "timeoutMs" to 4_000L)
        handler.postDelayed({ if (admission.expire(ticket)) admissionFailed(ticket, "服务提升回调未在4秒内到达；晚到服务不会接管新运行") }, 4_000L)
        return true
    }
    fun serviceAdmissionFailed(ticket: ForegroundAdmission.Ticket, reason: String) {
        requireMain()
        if (admission.reject(ticket)) admissionFailed(ticket, reason)
    }
    private fun admissionFailed(ticket: ForegroundAdmission.Ticket, reason: String) {
        audit.event("foreground.failed", "runId" to ticket.runId, "admissionId" to ticket.token, "owner" to ticket.owner, "reason" to reason)
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
