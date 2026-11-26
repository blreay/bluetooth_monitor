package com.blreay.bluetoothmonitor

enum class ResetStage { OFF_READY, OFF_DISPATCHED, OFF_SETTLED, ON_READY, ON_DISPATCHED, ON_SETTLED, EXHAUSTED, QUARANTINED, ARM_FAILED }
enum class RecoveryStep { OBSERVE, DISPATCH_OFF, DISPATCH_ON, RESTORED, FAILED, EXHAUSTED, QUARANTINED, STORAGE_FAILED }

data class ResetRecord(
    val runId: String,
    val commandId: String,
    val version: Long = 0,
    val stage: ResetStage = ResetStage.OFF_READY,
    val phase: ReEnablePolicy.Phase = ReEnablePolicy.Phase.WAITING_OFF,
    val attempts: Int = 0,
    val extraObservations: Int = 0,
    val wantsToast: Boolean = false,
    val offDispatchedAt: Long = 0,
    val offCompletedAt: Long = 0,
    val dispatchedAt: Long = 0,
    val commandDeadline: Long = 0,
    val authorityEnded: Boolean = true,
    val lastStatus: SwitchOutcome.Status = SwitchOutcome.Status.NOT_INVOKED,
    val lastMode: SwitchMode = SwitchMode.NONE,
    val ordinaryOnly: Boolean = false,
    val ordinaryOnAttempts: Int = 0,
    val lastOrdinaryOnAt: Long = 0,
    val modeBits: Int = 0,
    val history: List<BackendEntry> = emptyList(),
    val reason: String = "目标命中；尚未切换",
    val firstOffObservedAt: Long? = null,
    val firstOnObservedAt: Long? = null,
    val offEvidence: ResetCommandEvidence? = null,
    val onEvidence: ResetCommandEvidence? = null,
    val onEnteredAfterOffObservation: Boolean = false,
    val durationMs: Long? = null,
    val terminalRecordedAt: Long? = null,
)

data class ResetContinuation(val record: ResetRecord, val rearmed: Boolean?, val failure: String? = null)

interface ResetPersistence {
    fun read(): ResetRecord?
    /** Checked durable commit; expected identity/version must still be current. */
    fun commit(expected: ResetRecord?, next: ResetRecord?): Boolean
    /** Removal, existing-row terminal update and conditional count must be ONE checked commit. */
    fun terminal(expected: ResetRecord, terminal: ResetRecord, disposition: ResetDisposition): Boolean = false
    /** Only a newly claimed automatic match creates a row, atomically with its pending job. */
    fun accept(match: AcceptedResetMatch, pending: ResetRecord): Boolean = false
}

/** This exact engine is used by Android, serialized by the coordinator's main looper. */
class DurableResetEngine(
    private val store: ResetPersistence,
    private val alarm: () -> Boolean,
    private val clock: () -> Long,
    private val newId: () -> String,
) {
    fun read() = store.read()
    /** Task2 calls this only after the run owner's guarded claimMatch succeeds. */
    fun armAccepted(match: AcceptedResetMatch, wantsToast: Boolean): ResetRecord? {
        if (read() != null) return null
        val record = ResetRecord(match.runId, newId(), wantsToast = wantsToast,
            history = listOf(BackendEntry(SwitchMode.NONE, match.scanSummary.take(512))))
        if (!store.accept(match, record)) return null
        return finishArm(record)
    }

    /** Actual native-entry fence hook; dispatch alone is never evidence that an API was called. */
    fun nativeEntered(command: ResetRecord, enteredAt: Long = clock()): Boolean {
        val current = read() ?: return false
        if (current.runId != command.runId || current.commandId != command.commandId || current.phase != command.phase ||
            current.authorityEnded || command.stage !in listOf(ResetStage.OFF_DISPATCHED, ResetStage.ON_DISPATCHED) ||
            current.stage !in listOf(ResetStage.OFF_DISPATCHED, ResetStage.ON_DISPATCHED, ResetStage.QUARANTINED)) return false
        val off = command.stage == ResetStage.OFF_DISPATCHED
        val previous = if (off) current.offEvidence else current.onEvidence
        if (previous?.commandId == current.commandId && previous.enteredAt != null) return false
        val evidence = ResetCommandEvidence(current.runId, current.commandId, enteredAt = enteredAt,
            requestedAt = current.dispatchedAt.takeIf { it > 0L })
        return store.commit(current, current.copy(version = current.version + 1,
            offEvidence = if (off) evidence else current.offEvidence,
            onEvidence = if (off) current.onEvidence else evidence,
            onEnteredAfterOffObservation = if (off) false else current.offEvidence?.observedAt != null))
    }
    fun arm(runId: String, wantsToast: Boolean, scanSummary: String? = null): ResetRecord? {
        if (read() != null) return null
        val record = ResetRecord(runId, newId(), wantsToast = wantsToast,
            history = scanSummary?.let { listOf(BackendEntry(SwitchMode.NONE, it.take(512))) } ?: emptyList())
        if (!store.commit(null, record)) return null
        return finishArm(record)
    }

    private fun finishArm(record: ResetRecord): ResetRecord? {
        if (!alarm()) {
            store.commit(record, record.copy(version = record.version + 1, stage = ResetStage.ARM_FAILED,
                reason = "恢复闹钟未能安排；未关闭蓝牙"))
            return null
        }
        return record
    }

    fun dispatch(expected: ResetRecord, lease: CommandLease, enabled: Boolean): ResetRecord? {
        if (!lease.valid() || read() != expected) return null
        val ready = if (enabled) ResetStage.ON_READY else ResetStage.OFF_READY
        if (expected.stage != ready || !expected.authorityEnded) return null
        val now = clock()
        val next = expected.copy(version = expected.version + 1,
            stage = if (enabled) ResetStage.ON_DISPATCHED else ResetStage.OFF_DISPATCHED,
            phase = if (enabled) ReEnablePolicy.Phase.WAITING_ON else ReEnablePolicy.Phase.WAITING_OFF,
            dispatchedAt = now, commandDeadline = now + 16_000L, authorityEnded = false,
            offDispatchedAt = if (enabled) expected.offDispatchedAt else now,
            offEvidence = if (enabled) expected.offEvidence else ResetCommandEvidence(expected.runId, expected.commandId, requestedAt = now),
            onEvidence = if (enabled) ResetCommandEvidence(expected.runId, expected.commandId, requestedAt = now) else expected.onEvidence,
            reason = "${if (enabled) "ON" else "OFF"} 已持久化派发；尚未确认调用/硬件状态")
        return if (store.commit(expected, next)) next else null
    }

    /** A NOT_INVOKED, proven-ended attempt may hand off to one fresh same-target command. */
    fun nextPhysicalAttempt(command: ResetRecord, outcome: SwitchOutcome): ResetRecord? {
        if (outcome.status != SwitchOutcome.Status.NOT_INVOKED || !outcome.authorityEnded) return null
        val settled = complete(command, outcome) ?: return null
        val off = command.stage == ResetStage.OFF_DISPATCHED
        val now = clock()
        val next = settled.copy(commandId = newId(), version = settled.version + 1,
            stage = if (off) ResetStage.OFF_DISPATCHED else ResetStage.ON_DISPATCHED,
            dispatchedAt = now, commandDeadline = now + 16_000L, authorityEnded = false,
            offDispatchedAt = if (off) now else settled.offDispatchedAt,
            reason = "旧请求确定未提交且执行已结束；同目标普通请求新命令，保留同一运行")
        return if (store.commit(settled, next)) next else null
    }

    /** Observation versions may advance during RPC. Never rebase across command or phase changes. */
    fun complete(command: ResetRecord, outcome: SwitchOutcome): ResetRecord? {
        val current = read() ?: return null
        if (current.runId != command.runId || current.commandId != command.commandId || current.phase != command.phase) return null
        val off = command.stage == ResetStage.OFF_DISPATCHED
        val dispatched = if (off) ResetStage.OFF_DISPATCHED else ResetStage.ON_DISPATCHED
        if (current.stage != dispatched && current.stage != ResetStage.QUARANTINED) return null
        val history = BackendHistory(current.history, current.modeBits)
        history.add(outcome.mode, "${if (off) "OFF" else "ON"} ${outcome.status}；${outcome.reason}")
        val now = clock()
        val ordinaryOn = !off && outcome.mode == SwitchMode.ORDINARY
        val previous = (if (off) current.offEvidence else current.onEvidence)?.takeIf {
            it.runId == current.runId && it.commandId == current.commandId
        } ?: ResetCommandEvidence(current.runId, current.commandId)
        val evidence = previous.copy(submitted = outcome.status == SwitchOutcome.Status.SUBMITTED,
            authorityEnded = outcome.authorityEnded, submittedAt = outcome.submittedAt,
            endedAt = outcome.returnedAt.takeIf { outcome.authorityEnded })
        val next = current.copy(version = current.version + 1,
            stage = if (!outcome.authorityEnded) ResetStage.QUARANTINED
                else if (off) ResetStage.OFF_SETTLED else ResetStage.ON_SETTLED,
            authorityEnded = outcome.authorityEnded,
            offCompletedAt = if (off && outcome.authorityEnded) outcome.returnedAt ?: now else current.offCompletedAt,
            lastStatus = outcome.status, lastMode = outcome.mode,
            ordinaryOnAttempts = current.ordinaryOnAttempts + if (ordinaryOn) 1 else 0,
            lastOrdinaryOnAt = if (ordinaryOn) now else current.lastOrdinaryOnAt,
            offEvidence = if (off) evidence else current.offEvidence,
            onEvidence = if (off) current.onEvidence else evidence,
            modeBits = history.bits, history = history.entries, reason = outcome.reason.take(1024))
        return if (store.commit(current, next)) next else null
    }

    /** The accepted durable completion, not an expired receiver/service lease, owns its followup alarm. */
    fun completeAndRearm(command: ResetRecord, outcome: SwitchOutcome): ResetContinuation? =
        complete(command, outcome)?.let { rearmContinuation(it) }

    /** Scheduling only: never invokes a switch or treats unresolved native authority as ended. */
    fun rearmContinuation(expected: ResetRecord): ResetContinuation? {
        if (read() != expected) return null
        if (!expected.authorityEnded || expected.stage !in listOf(ResetStage.OFF_SETTLED, ResetStage.ON_SETTLED, ResetStage.ON_READY)) {
            return ResetContinuation(expected, null)
        }
        if (alarm()) return ResetContinuation(expected, true)
        val failure = "恢复闹钟重新安排失败；已结束执行的待恢复记录保留，请在详情中显式继续恢复"
        val history = BackendHistory(expected.history, expected.modeBits)
        history.add(SwitchMode.NONE, failure)
        val failed = expected.copy(version = expected.version + 1,
            reason = "${expected.reason.take(700)}；$failure", modeBits = history.bits, history = history.entries)
        val committed = store.commit(expected, failed)
        return ResetContinuation(if (committed) failed else expected, false,
            failure + if (committed) "" else "；故障详情存储更新失败，原安全记录仍保留")
    }

    fun note(command: ResetRecord, entry: BackendEntry): Boolean {
        val current = read() ?: return false
        if (current.runId != command.runId || current.commandId != command.commandId) return false
        val history = BackendHistory(current.history, current.modeBits)
        history.add(entry.mode, entry.reason)
        return store.commit(current, current.copy(version = current.version + 1,
            modeBits = history.bits, history = history.entries, reason = entry.reason.take(1024)))
    }

    fun observe(state: AdapterState, sampledAt: Long? = clock(), durationMs: Long? = null): RecoveryStep {
        val current = read() ?: return RecoveryStep.FAILED
        val now = sampledAt
        val off = current.offEvidence?.takeIf { current.phase == ReEnablePolicy.Phase.WAITING_OFF &&
            it.runId == current.runId && it.commandId == current.commandId && it.enteredAt != null }
        val on = current.onEvidence?.takeIf { current.phase == ReEnablePolicy.Phase.WAITING_ON &&
            it.runId == current.runId && it.commandId == current.commandId && it.enteredAt != null }
        var next = current.copy(version = current.version + 1, attempts = current.attempts + 1, durationMs = durationMs,
            firstOffObservedAt = current.firstOffObservedAt ?: if (state == AdapterState.OFF) now else null,
            firstOnObservedAt = current.firstOnObservedAt ?: if (state == AdapterState.ON &&
                current.phase == ReEnablePolicy.Phase.WAITING_ON) now else null,
            offEvidence = if (state == AdapterState.OFF && off != null && off.observedAt == null)
                off.copy(observedAt = now) else current.offEvidence,
            onEvidence = if (state == AdapterState.ON && on != null && on.observedAt == null)
                on.copy(observedAt = now) else current.onEvidence)
        fun save(step: RecoveryStep): RecoveryStep = if (store.commit(current, next)) step else RecoveryStep.STORAGE_FAILED
        fun end(step: RecoveryStep): RecoveryStep {
            // Capture the terminal decision boundary, NOT the earlier worker's state sample/return time.
            val terminal = next.copy(terminalRecordedAt = clock())
            return if (store.terminal(current, terminal,
                if (step == RecoveryStep.RESTORED) ResetDisposition.RESTORED
                else if (current.stage == ResetStage.ARM_FAILED) ResetDisposition.ARM_FAILED else ResetDisposition.FAILED)) step else RecoveryStep.STORAGE_FAILED
        }
        fun prepare(enabled: Boolean, ordinary: Boolean = false): RecoveryStep {
            next = next.copy(commandId = newId(), stage = if (enabled) ResetStage.ON_READY else ResetStage.OFF_READY,
                phase = if (enabled) ReEnablePolicy.Phase.WAITING_ON else ReEnablePolicy.Phase.WAITING_OFF,
                authorityEnded = true, ordinaryOnly = ordinary)
            return save(if (enabled) RecoveryStep.DISPATCH_ON else RecoveryStep.DISPATCH_OFF)
        }
        // No memory/Future, cancellation, timeout, OFF observation, or expired deadline is an execution fence.
        if (!current.authorityEnded || current.stage == ResetStage.QUARANTINED) {
            if (current.attempts >= ReEnablePolicy.MAX_ATTEMPTS || clock() > current.commandDeadline) {
                next = next.copy(stage = ResetStage.QUARANTINED,
                    reason = "旧 ${if (current.phase == ReEnablePolicy.Phase.WAITING_OFF) "OFF" else "ON"} 执行权限结束未确认；保留记录，不重放/不反向开关。请查看系统蓝牙状态与详情")
                return save(if (current.attempts >= ReEnablePolicy.MAX_ATTEMPTS) RecoveryStep.QUARANTINED else RecoveryStep.OBSERVE)
            }
            return save(RecoveryStep.OBSERVE)
        }
        if (current.stage == ResetStage.ARM_FAILED) return end(RecoveryStep.FAILED)
        if (current.stage == ResetStage.OFF_READY) return save(RecoveryStep.DISPATCH_OFF)
        if (current.phase == ReEnablePolicy.Phase.WAITING_ON && state == AdapterState.ON) return end(RecoveryStep.RESTORED)
        if (current.stage == ResetStage.ON_READY) {
            if (state == AdapterState.OFF) return save(RecoveryStep.DISPATCH_ON)
            if (state == AdapterState.UNKNOWN || current.attempts >= ReEnablePolicy.MAX_ATTEMPTS + 30) {
                next = next.copy(stage = ResetStage.EXHAUSTED, reason = "恢复重试没有可读稳定 OFF 状态；未派发新命令")
                return save(RecoveryStep.EXHAUSTED)
            }
            return save(RecoveryStep.OBSERVE)
        }
        if (current.stage == ResetStage.EXHAUSTED) return save(RecoveryStep.EXHAUSTED)
        if (state == AdapterState.UNKNOWN) {
            next = next.copy(stage = ResetStage.EXHAUSTED, reason = "蓝牙状态不可读/适配器不可用；不能确认恢复，请检查附近设备权限与系统蓝牙")
            return save(RecoveryStep.EXHAUSTED)
        }
        // The unchanged legacy table remains authoritative for ordinary transition decisions; wrapper gives
        // actual OFF a recovery opportunity even at cap, and only 30 additional transitional observations.
        val policy = ReEnablePolicy.decide(state, current.phase, current.attempts)
        if (current.phase == ReEnablePolicy.Phase.WAITING_OFF && state == AdapterState.OFF) return prepare(true)
        if (state == AdapterState.TURNING_OFF || state == AdapterState.TURNING_ON) {
            if (current.attempts < ReEnablePolicy.MAX_ATTEMPTS) return save(RecoveryStep.OBSERVE)
            if (current.extraObservations < 30) {
                next = next.copy(extraObservations = current.extraObservations + 1)
                return save(RecoveryStep.OBSERVE)
            }
            next = next.copy(stage = ResetStage.EXHAUSTED, reason = "过渡状态观察已耗尽；未确认恢复")
            return save(RecoveryStep.EXHAUSTED)
        }
        if (current.phase == ReEnablePolicy.Phase.WAITING_OFF) {
            if (current.lastStatus == SwitchOutcome.Status.NOT_INVOKED || current.lastStatus == SwitchOutcome.Status.REJECTED) return end(RecoveryStep.FAILED)
            // svc exited, not hardware success. Only stable wrong AFTER the observation window permits same-target fallback.
            if (clock() - current.offCompletedAt >= 10_000L && !current.ordinaryOnly &&
                (current.lastMode == SwitchMode.SHIZUKU_ROOT || current.lastMode == SwitchMode.SHIZUKU_SHELL)) return prepare(false, true)
            if (policy == ReEnablePolicy.Action.GIVE_UP_NEVER_TURNED_OFF) return end(RecoveryStep.FAILED)
            return save(RecoveryStep.OBSERVE)
        }
        if (state == AdapterState.OFF) {
            val since = clock() - current.dispatchedAt
            val ordinary = current.lastMode == SwitchMode.ORDINARY
            val rejected = current.lastStatus == SwitchOutcome.Status.REJECTED || current.lastStatus == SwitchOutcome.Status.NOT_INVOKED
            val delay = if (rejected) 5_000L else 10_000L
            if (since < delay || (ordinary && clock() - current.lastOrdinaryOnAt < 5_000L)) return save(RecoveryStep.OBSERVE)
            if (current.ordinaryOnAttempts >= 3 || current.attempts >= ReEnablePolicy.MAX_ATTEMPTS + 30) {
                next = next.copy(stage = ResetStage.EXHAUSTED, reason = "恢复尝试已耗尽；未确认蓝牙 ON，可点击详情显式重试或在系统中开启")
                return save(RecoveryStep.EXHAUSTED)
            }
            return prepare(true, !rejected && !ordinary)
        }
        return save(RecoveryStep.OBSERVE)
    }

    /** Explicit recovery retry cannot override unresolved OFF/ON authority. Never starts a new scan. */
    fun retryRecovery(observed: AdapterState = AdapterState.UNKNOWN, sampledAt: Long? = clock()): Boolean {
        val current = read() ?: return false
        if (!current.authorityEnded) return false
        // A failed alarm may leave the already planned ON ready. Continue that exact command;
        // repeated details taps must not allocate another command or reset recovery counters/backend.
        if (current.stage == ResetStage.ON_READY) {
            return current.phase == ReEnablePolicy.Phase.WAITING_ON && observed == AdapterState.OFF
        }
        // A late, safely settled OFF can continue explicitly only after actual OFF was observed.
        // It becomes an ON-only command: the details action cannot replay the old OFF.
        if (current.stage != ResetStage.EXHAUSTED &&
            !(current.stage == ResetStage.OFF_SETTLED && observed == AdapterState.OFF)) return false
        // The explicit bounded state read is a real observation. Save it against the current OFF
        // before rotating to ON; it neither creates native-entry proof nor ends execution authority.
        val offObservedAt = if (observed == AdapterState.OFF && current.phase == ReEnablePolicy.Phase.WAITING_OFF) sampledAt else null
        val offEvidence = current.offEvidence?.takeIf {
            it.runId == current.runId && it.commandId == current.commandId && it.enteredAt != null
        }
        return store.commit(current, current.copy(version = current.version + 1, stage = ResetStage.ON_READY,
            firstOffObservedAt = current.firstOffObservedAt ?: offObservedAt,
            offEvidence = if (offObservedAt != null && offEvidence != null && offEvidence.observedAt == null)
                offEvidence.copy(observedAt = offObservedAt) else current.offEvidence,
            phase = ReEnablePolicy.Phase.WAITING_ON, commandId = newId(), ordinaryOnAttempts = 0,
            attempts = 0, extraObservations = 0, ordinaryOnly = false, reason = "用户显式继续/重试恢复；只在实际 OFF 时派发 ON，不重放 OFF"))
    }
}
