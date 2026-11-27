package com.blreay.bluetoothmonitor

/** Application-owned ports. Android uses the shared bounded driver and exact scanner slot. */
interface ManualRuntime {
    fun requestForeground(operation: ManualOperation): Boolean
    fun execute(operation: ManualOperation, lease: CommandLease, ordinaryOnly: Boolean,
                event: (BackendEntry) -> Unit, entered: (Long) -> Unit,
                nextAttempt: (SwitchOutcome) -> Boolean, result: (SwitchOutcome) -> Unit)
    fun sample(lease: CommandLease, result: (StateSample) -> Unit)
    fun scan(operation: ManualOperation, lease: CommandLease, events: ManualScanEvents)
    fun retireScan(operationId: String)
    fun later(delayMs: Long, task: () -> Unit)
    fun endForeground(operationId: String)
}

data class StateSample(val state: AdapterState, val sampledAt: Long? = null, val sampledElapsed: Long? = null)
interface ManualScanEvents {
    fun registered(identity: String): Boolean
    fun entered(at: Long)
    fun preparationEnded(at: Long?)
    fun returned(at: Long?, started: Boolean)
    fun devices(devices: List<DiscoveredDevices.Device>, overflow: Boolean)
    fun finished(reason: String, failed: Boolean)
    fun retired(identity: String)
    fun summary(value: ScanSummary) { }
    fun summary(value: ScanSummary, finalDevices: List<DiscoveredDevices.Device>) { summary(value) }
}

/** All methods/callbacks are serialized by ResetCoordinator's main looper; no Activity command ownership. */
class ManualOperationController(
    private val store: CheckedResetStore,
    private val admission: () -> OperationAdmission,
    private val runtime: ManualRuntime,
    private val wallClock: () -> Long,
    private val monotonic: () -> Long,
    private val newId: () -> String,
    private val changed: () -> Unit = {},
    private val feedback: (ManualFeedback) -> Unit = {},
    private val audit: AuditSink = AuditSink.NONE,
    private val candidates: ManualScanCandidates = ManualScanCandidates(),
) {
    private var ownedId: String? = null // absent on cold start: even a READY fence is not replayed
    private var lease: CommandLease? = null
    private var live: ManualResult? = null
    private var outcome: SwitchOutcome? = null
    private var wrongSince: Long? = null
    private var fallback = false
    private var sampling = false
    private var scanFinished = false
    private var scanFailed = false
    private var scanSummary: ScanSummary? = null // exact current operation/command, never reconstructed from persisted devices
    private var scanLogUncertain = false
    private var scanLogFinal = false
    private val switchSummary = RuntimeAudit(audit)
    private fun log(operation: ManualOperation, result: String, reliable: Boolean = false) {
        if (operation.action != ManualAction.SCAN) switchSummary.end(operation.operationId, operation.commandId, result, reliable)
        else {
            if (scanLogFinal || (!reliable && scanLogUncertain)) return
            if (reliable) scanLogFinal = true else scanLogUncertain = true
            val value = scanSummary ?: ScanSummary(null, false, null, "未执行")
            audit.event(value.copy(result = result).entry())
        }
    }
    private val notices = mutableMapOf<ManualAction, String>()
    private val requestIds = linkedMapOf<String, String>()
    private val feedbackSent = linkedMapOf<String, MutableSet<ManualFeedbackType>>()
    private fun emitFeedback(operation: ManualOperation, type: ManualFeedbackType, status: ManualResultStatus, reason: String,
                             result: ManualResult? = live) {
        val sent = feedbackSent.getOrPut(operation.operationId) { mutableSetOf() }
        if (!sent.add(type)) return
        while (feedbackSent.size > 128) feedbackSent.remove(feedbackSent.keys.first())
        val value = ManualFeedback(operation.action, operation.operationId, requestIds[operation.operationId] ?: operation.operationId,
            type, status, reason.take(1024), outcome?.mode,
            result?.observedState, operation.authorityEnded, scanSummary.takeIf { operation.action == ManualAction.SCAN })
        try { feedback(value) } catch (_: Exception) { } // reporting cannot authorize/interrupt native work or checked commits
    }

    fun activeResult(): ManualResult? = live ?: store.snapshot().manualFence?.let {
        ManualResult(it.operationId, it.action, ManualResultStatus.UNCERTAIN,
            "冷启动手动隔离：${it.reason}；不能跨进程验证旧执行/扫描结束，不重放/不解锁。请查看系统蓝牙设置",
            begunAt = it.begunAt)
    }
    fun rejectionNotices(): Map<ManualAction, String> = notices.toMap()
    fun owns(id: String) = ownedId == id && store.snapshot().manualFence?.operationId == id

    fun request(action: ManualAction, visible: Boolean, permissions: Boolean): Boolean {
        val requestId = newId()
        val reason = when {
            !visible -> "仅可见详情窗口可提交前台手动操作；未执行"
            !permissions -> "缺少附近设备权限；请使用下方显式权限入口，授权不会自动重放本按钮"
            !admission().allowsNewOperation() || !store.newNativeAllowed() -> "自动/手动操作、待恢复、设置/服务/扫描清理或存储隔离仍占用；本次立即拒绝，不排队/不替换"
            else -> null
        }
        if (reason != null) { reject(action, reason, requestId); return false }
        val operation = ManualOperation(newId(), newId(), action = action, begunAt = wallClock())
        if (!store.beginManual(operation, admission())) { reject(action, "手动安全记录未确认保存；未执行", requestId); return false }
        requestIds[operation.operationId] = requestId
        while (requestIds.size > 128) requestIds.remove(requestIds.keys.first())
        ownedId = operation.operationId
        live = ManualResult(operation.operationId, action, ManualResultStatus.OBSERVING,
            "已保存手动安全隔离；等待本次前台服务准确接管，尚未读取/调用蓝牙", begunAt = operation.begunAt)
        outcome = null; wrongSince = null; fallback = false; sampling = false; scanFinished = false; scanFailed = false
        scanSummary = null; scanLogUncertain = false; scanLogFinal = false
        if (action != ManualAction.SCAN) switchSummary.begin(operation.operationId, operation.commandId, action == ManualAction.ON)
        notices.remove(action)
        // Acceptance precedes requestForeground: an inline service callback may already finish this operation.
        emitFeedback(operation, ManualFeedbackType.ACCEPTED, ManualResultStatus.OBSERVING, "已接受并保存安全隔离；实际状态尚未确认")
        changed()
        if (!runtime.requestForeground(operation)) {
            finish(ManualResultStatus.FAILED, "前台服务未获接管；未进入蓝牙工作，不使用后台绕过")
            return false
        }
        return true
    }
    private fun reject(action: ManualAction, reason: String, requestId: String) {
        notices[action] = reason
        var saved = false
        if (!store.snapshot().storageFailed && !store.snapshot().integrityBlocked) {
            saved = store.rejectManual(ManualResult(requestId, action, ManualResultStatus.REJECTED, reason, completedAt = wallClock()))
            if (!saved) notices[action] = "$reason；拒绝说明保存失败，当前持久隔离保留"
        }
        if (action != ManualAction.SCAN) audit.event(CompactLogEntry.BluetoothEnded(action == ManualAction.ON, SwitchMode.NONE, "未执行"))
        // Every click has its own identity; rejection is not a replay of a per-action result card.
        try { feedback(ManualFeedback(action, requestId, requestId, ManualFeedbackType.REJECTED,
            ManualResultStatus.REJECTED, notices[action]!!)) } catch (_: Exception) { }
        changed()
    }
    private fun current(): ManualOperation? = store.snapshot().manualFence?.takeIf { it.operationId == ownedId }
    private fun exact(command: String): ManualOperation? = current()?.takeIf { it.commandId == command }
    private fun update(operation: ManualOperation, transform: (ManualOperation) -> ManualOperation): Boolean {
        val ok = store.updateManual(operation, transform(operation).copy(revision = operation.revision + 1))
        if (!ok) {
            notices[operation.action] = "安全记录更新失败；保留最后已确认隔离，禁止新工作；扫描清理仍继续"
            log(operation, "存储失败，状态未确认")
            emitFeedback(operation, ManualFeedbackType.STORAGE_FAILED, ManualResultStatus.UNCERTAIN, notices[operation.action]!!)
        }
        changed()
        return ok
    }
    fun serviceStarted(operationId: String, lease: CommandLease) {
        val operation = current()?.takeIf { it.operationId == operationId && it.stage == ManualStage.READY } ?: return
        if (!lease.valid() || this.lease != null) return
        this.lease = lease
        // The checked DISPATCHED fence precedes even adapter preparation/state/preflight.
        if (!update(operation) { it.copy(stage = ManualStage.DISPATCHED, authorityEnded = false, reason = "已派发手动工作；执行结束未确认") }) {
            runtime.endForeground(operationId); return
        }
        live = live?.copy(requestedAt = wallClock(), reason = "手动 ${operation.action} 进行中；请求提交不等于实际状态成功")
        runtime.later(WORK_WINDOW_MS) { if (owns(operationId)) serviceEnded(operationId) }
        if (operation.action == ManualAction.SCAN) startScan(current()!!, lease) else execute(current()!!, lease)
        changed()
    }
    private fun execute(operation: ManualOperation, lease: CommandLease) {
        var commandId = operation.commandId
        runtime.execute(operation, lease, fallback, { entry ->
            if (exact(commandId) != null) {
                val history = BackendHistory(live?.backendHistory.orEmpty(), live?.modeBits ?: 0)
                history.add(entry.mode, entry.reason)
                live = live?.copy(modeBits = history.bits, backendHistory = history.entries, reason = entry.reason)
                changed()
            }
        }, entered@{ at ->
            val exact = exact(commandId) ?: return@entered
            switchSummary.entered(exact.operationId, exact.commandId)
            if (!exact.nativeEntered) update(exact) { it.copy(nativeEntered = true, authorityEnded = false) }
            live = live?.copy(nativeEnteredAt = live?.nativeEnteredAt ?: at)
            changed()
        }, nextAttempt = next@{ previous ->
            val exact = exact(commandId) ?: return@next false
            if (!previous.authorityEnded || !lease.valid() || this.lease == null) return@next false
            if (!update(exact) { it.copy(stage = ManualStage.SETTLED, authorityEnded = true, reason = previous.reason) }) return@next false
            val next = store.nextManualCommand(current()!!, newId()) ?: return@next false
            commandId = next.commandId
            switchSummary.begin(next.operationId, next.commandId, next.action == ManualAction.ON)
            update(next) { it.copy(stage = ManualStage.DISPATCHED, authorityEnded = false) }
        }) result@{ result ->
            val exact = exact(commandId) ?: return@result
            // A late proof is allowed to settle only this operation/command, never start work on an expired lease.
            if (exact.authorityEnded && outcome?.authorityEnded == true) return@result
            outcome = result
            switchSummary.outcome(exact.operationId, exact.commandId, result)
            val history = BackendHistory(live?.backendHistory.orEmpty(), live?.modeBits ?: 0)
            history.add(result.mode, "${operation.action} ${result.status}；${result.reason}")
            live = live?.copy(modeBits = history.bits, backendHistory = history.entries,
                submittedAt = result.submittedAt ?: live?.submittedAt,
                authorityEndedAt = result.returnedAt.takeIf { result.authorityEnded },
                reason = "请求 ${result.status}；${result.reason}；实际状态仍需观察",
                status = if (result.authorityEnded) ManualResultStatus.OBSERVING else ManualResultStatus.UNCERTAIN)
            val saved = update(exact) { it.copy(authorityEnded = result.authorityEnded,
                stage = if (result.authorityEnded) ManualStage.SETTLED else ManualStage.QUARANTINED,
                reason = result.reason) }
            if (!saved) {
                this.lease?.cancel(); this.lease = null
                runtime.endForeground(operation.operationId)
                return@result
            }
            if (!result.authorityEnded) {
                // Target observation is not proof of native authority end. Do not duplicate/reverse.
                current()?.let {
                    log(it, "执行结束未确认")
                    emitFeedback(it, ManualFeedbackType.QUARANTINED, ManualResultStatus.UNCERTAIN,
                        "执行结束未确认；${result.reason}；不重复/反向请求，请查看系统蓝牙设置")
                }
                this.lease?.cancel(); this.lease = null
                runtime.endForeground(operation.operationId)
                changed()
            } else if (!lease.valid() || this.lease == null) {
                finish(if (live?.observedState == target(operation.action)) ManualResultStatus.COMPLETED else ManualResultStatus.FAILED,
                    "迟到执行结束已确认；前台窗口已结束，不新增读取/请求；${live?.reason}")
            } else observe(commandId, lease)
        }
    }
    private fun target(action: ManualAction) = if (action == ManualAction.ON) AdapterState.ON else AdapterState.OFF
    private fun observe(command: String, lease: CommandLease) {
        if (sampling || !lease.valid() || this.lease == null || exact(command) == null) return
        if (store.snapshot().storageFailed || store.snapshot().integrityBlocked) { current()?.let { serviceEnded(it.operationId) }; return }
        sampling = true
        runtime.sample(lease) sampled@{ sample ->
            val operation = exact(command) ?: return@sampled
            sampling = false
            if (!lease.valid() || this.lease == null) { serviceEnded(operation.operationId); return@sampled }
            live = live?.copy(observedState = sample.state, observedAt = sample.sampledAt,
                reason = "实际读取 ${sample.state}（不是硬件切换瞬间）；${outcome?.status}；${outcome?.reason}")
            changed()
            if (sample.state == target(operation.action) && operation.authorityEnded) {
                finish(ManualResultStatus.COMPLETED, "已观察到实际 ${sample.state}；执行结束已确认。手动操作不创建自动恢复/计数")
                return@sampled
            }
            val wrong = if (operation.action == ManualAction.ON) AdapterState.OFF else AdapterState.ON
            if (sample.state == wrong) {
                val observedElapsed = sample.sampledElapsed ?: monotonic()
                if (wrongSince == null || observedElapsed < requireNotNull(wrongSince)) wrongSince = observedElapsed
                val result = outcome
                if (!fallback && operation.authorityEnded && result?.mode in listOf(SwitchMode.SHIZUKU_SHELL, SwitchMode.SHIZUKU_ROOT) &&
                    observedElapsed - requireNotNull(wrongSince) >= 10_000L) {
                    val next = store.nextManualCommand(operation, newId())
                    if (next == null) { serviceEnded(operation.operationId); return@sampled }
                    fallback = true; outcome = null; wrongSince = null
                    switchSummary.begin(next.operationId, next.commandId, next.action == ManualAction.ON)
                    if (!update(next) { it.copy(stage = ManualStage.DISPATCHED, authorityEnded = false) }) {
                        serviceEnded(operation.operationId); return@sampled
                    }
                    execute(current()!!, lease) // same target only, fresh identity and preflight; never fence release/reacquire
                    return@sampled
                }
            } else wrongSince = null
            runtime.later(500L) { observe(command, lease) }
        }
    }
    private fun startScan(operation: ManualOperation, lease: CommandLease) {
        candidates.begin(operation) // only after exact actual foreground acceptance and checked DISPATCHED fence
        val candidateIdentity = ManualScanIdentity(operation.operationId, operation.commandId)
        runtime.scan(operation, lease, object : ManualScanEvents {
            override fun registered(identity: String): Boolean {
                val exact = exact(operation.commandId) ?: return false
                return update(exact) { it.copy(scannerIdentity = identity, scannerRetired = false) }
            }
            override fun entered(at: Long) {
                val exact = exact(operation.commandId) ?: return
                update(exact) { it.copy(nativeEntered = true, authorityEnded = false) }
                live = live?.copy(scanStartedAt = at, nativeEnteredAt = at); changed()
            }
            override fun preparationEnded(at: Long?) {
                val exact = exact(operation.commandId) ?: return
                update(exact) { it.copy(authorityEnded = true) }
                live = live?.copy(authorityEndedAt = at)
                reconcileScan()
            }
            override fun returned(at: Long?, started: Boolean) {
                val exact = exact(operation.commandId) ?: return
                update(exact) { it.copy(authorityEnded = true) }
                live = live?.copy(scanReturnedAt = at, authorityEndedAt = at)
                if (!started) finished("BLE 注册开始失败；等待原注册真实清理", true)
                reconcileScan()
            }
            override fun devices(devices: List<DiscoveredDevices.Device>, overflow: Boolean) {
                if (exact(operation.commandId) == null || scanFinished) return
                candidates.update(candidateIdentity, devices.map { ManualScanCandidate(it.mac, it.advertisementName) }, overflow)
                live = live?.copy(devices = devices.take(100), overflow = overflow,
                    reason = "仅显示本次 BLE 广播：${devices.size} 个；不自动开启/配对/连接/重置")
                changed() // coalesced by scanner, NOT a ledger write per advertisement
            }
            override fun summary(value: ScanSummary) {
                if (exact(operation.commandId) == null || scanSummary != null) return
                scanSummary = value // serviceEnded may already have marked scanFinished; summary is not authority
            }
            override fun summary(value: ScanSummary, finalDevices: List<DiscoveredDevices.Device>) {
                if (exact(operation.commandId) == null || scanSummary != null) return
                candidates.update(candidateIdentity, finalDevices.map { ManualScanCandidate(it.mac, it.advertisementName) }, value.overflow)
                candidates.close(candidateIdentity) // final update BEFORE close, before returned/reconcile may clear live result
                live = live?.copy(devices = finalDevices.take(100), overflow = value.overflow)
                summary(value) // final snapshot alone bypasses finished; queued advertisements still cannot
            }
            override fun finished(reason: String, failed: Boolean) {
                if (exact(operation.commandId) == null || scanFinished) return
                candidates.update(candidateIdentity, live?.devices.orEmpty().map { ManualScanCandidate(it.mac, it.advertisementName) }, live?.overflow == true)
                candidates.close(candidateIdentity)
                scanFinished = true; scanFailed = failed
                live = live?.copy(scanEndedAt = wallClock(), reason = reason,
                    status = if (failed) ManualResultStatus.FAILED else ManualResultStatus.OBSERVING)
                current()?.let { update(it) { op -> op.copy(stage = ManualStage.RETIRING, reason = "$reason；扫描清理未确认") } }
                this@ManualOperationController.lease?.cancel(); this@ManualOperationController.lease = null
                runtime.retireScan(operation.operationId) // independent cleanup even if saving RETIRING failed
                runtime.endForeground(operation.operationId)
                reconcileScan()
            }
            override fun retired(identity: String) {
                val exact = exact(operation.commandId)?.takeIf { it.scannerIdentity == identity } ?: return
                update(exact) { it.copy(scannerRetired = true) }
                reconcileScan()
            }
        })
    }
    private fun reconcileScan() {
        val current = current() ?: return
        if (scanFinished && current.canFinish()) finish(if (scanFailed) ManualResultStatus.FAILED else ManualResultStatus.COMPLETED,
            "${live?.reason}；原 BLE 注册已真实清理；只展示结果，不自动重置")
    }
    fun admissionFailed(operationId: String, reason: String) {
        if (!owns(operationId) || lease != null) return
        finish(ManualResultStatus.FAILED, "本次前台接管失败；未进入蓝牙工作：$reason")
    }
    fun serviceEnded(operationId: String) {
        if (!owns(operationId)) return
        lease?.cancel(); lease = null
        val operation = current() ?: return
        if (operation.action == ManualAction.SCAN) {
            scanFinished = true; scanFailed = true
            live = live?.copy(scanEndedAt = live?.scanEndedAt ?: wallClock(), reason = "扫描窗口结束；等待原注册真实清理")
            runtime.retireScan(operationId)
            reconcileScan()
            current()?.let {
                log(it, "执行/清理未确认（部分结果）")
                emitFeedback(it, ManualFeedbackType.QUARANTINED, ManualResultStatus.UNCERTAIN, live?.reason.orEmpty())
            }
        } else if (operation.canFinish()) finish(ManualResultStatus.FAILED, "有限观察窗口结束；实际目标状态未确认；不重复/反向请求")
        else {
            update(operation) { it.copy(stage = ManualStage.QUARANTINED, reason = "工作窗口/服务结束但旧执行权限未确认结束；不重放/不解锁") }
            live = live?.copy(status = ManualResultStatus.UNCERTAIN, reason = "旧执行/扫描清理仍待确认；窗口结束不等于 authority ended")
            log(operation, "执行结束未确认")
            emitFeedback(operation, ManualFeedbackType.QUARANTINED, ManualResultStatus.UNCERTAIN, live!!.reason)
        }
        runtime.endForeground(operationId); changed()
    }
    private fun finish(status: ManualResultStatus, reason: String) {
        val operation = current() ?: return
        val result = (live ?: ManualResult(operation.operationId, operation.action, status, reason))
            .copy(status = status, reason = reason.take(1024), completedAt = wallClock())
        val saved = store.finishManual(operation, result)
        if (!saved) {
            notices[operation.action] = "结束结果保存失败；持久隔离仍保留，禁止新工作"
            live = result.copy(status = ManualResultStatus.UNCERTAIN, reason = notices[operation.action]!!)
            log(operation, "存储失败，状态未确认")
            emitFeedback(operation, ManualFeedbackType.STORAGE_FAILED, ManualResultStatus.UNCERTAIN, notices[operation.action]!!, live)
        } else {
            val phrase = if (operation.action == ManualAction.SCAN) {
                if (result.status != ManualResultStatus.COMPLETED && scanSummary?.result == "成功") "取消（部分结果）"
                else scanSummary?.result ?: "未执行"
            } else if (result.status == ManualResultStatus.COMPLETED) "成功"
                else if (outcome == null || outcome?.mode == SwitchMode.NONE) "未执行" else "失败，目标状态未确认"
            if (operation.action == ManualAction.SCAN) scanSummary = scanSummary?.copy(result = phrase)
            log(operation, phrase, true)
            live = null; ownedId = null
            emitFeedback(operation, ManualFeedbackType.TERMINAL, result.status, result.reason, result)
        }
        lease?.cancel(); lease = null
        runtime.endForeground(operation.operationId); changed()
    }
    companion object { const val WORK_WINDOW_MS = 45_000L }
}
