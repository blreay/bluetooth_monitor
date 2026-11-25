package com.blreay.bluetoothmonitor

/** Invocation truth is independent of hardware truth. Cancellation is NOT an execution fence. */
data class SwitchOutcome(
    val status: Status,
    val mode: SwitchMode,
    val reason: String,
    val authorityEnded: Boolean,
) {
    enum class Status { NOT_INVOKED, SUBMITTED, REJECTED, UNCERTAIN, OBSERVING }
    companion object {
        fun submitted(mode: SwitchMode, reason: String = "请求已提交；硬件状态尚未确认") =
            SwitchOutcome(Status.SUBMITTED, mode, reason, true)
        fun uncertain(mode: SwitchMode, reason: String) = SwitchOutcome(Status.UNCERTAIN, mode, reason, false)
        fun notInvoked(reason: String) = SwitchOutcome(Status.NOT_INVOKED, SwitchMode.NONE, reason, true)
        fun rejected(mode: SwitchMode, reason: String) = SwitchOutcome(Status.REJECTED, mode, reason, true)
    }
}

data class SwitchCapability(val mode: SwitchMode?, val reason: String)

/** Android implements these with bounded process-wide workers and checks leases again inside workers. */
interface SwitchBackend {
    fun readState(lease: CommandLease, callback: (AdapterState) -> Unit)
    fun preflight(lease: CommandLease, budgetMs: Long, callback: (SwitchCapability) -> Unit)
    fun privileged(enabled: Boolean, lease: CommandLease, budgetMs: Long, callback: (SwitchOutcome) -> Unit)
    fun ordinary(enabled: Boolean, lease: CommandLease, callback: (SwitchOutcome) -> Unit)
}

/** The single OFF/ON route. Never replays a possibly-invoked native command. No UI dependency. */
class BluetoothSwitchDriver(private val backend: SwitchBackend) {
    fun execute(
        enabled: Boolean,
        lease: CommandLease,
        preflightMs: Long = 5_000L,
        rpcMs: Long = 6_000L,
        ordinaryOnly: Boolean = false,
        onEvent: (BackendEntry) -> Unit = {},
        callback: (SwitchOutcome) -> Unit,
    ) {
        fun expired(): Boolean {
            if (lease.valid()) return false
            callback(SwitchOutcome.notInvoked("执行窗口已过期；未开始新命令"))
            return true
        }
        fun ordinary(reason: String) {
            if (expired()) return
            // Report fallback before the call; never await a notification tap.
            onEvent(BackendEntry(SwitchMode.NONE, "自动改用普通方式：$reason"))
            backend.ordinary(enabled, lease) { outcome ->
                callback(outcome.copy(reason = "$reason；${outcome.reason}".take(1024)))
            }
        }
        if (expired()) return
        backend.readState(lease) { state ->
            if (expired()) return@readState
            val target = if (enabled) AdapterState.ON else AdapterState.OFF
            val transition = if (enabled) AdapterState.TURNING_ON else AdapterState.TURNING_OFF
            if (state == target || state == transition) {
                callback(SwitchOutcome(SwitchOutcome.Status.OBSERVING, SwitchMode.NONE,
                    "实际状态 $state；只观察，不重复提交", true))
                return@readState
            }
            if (state == AdapterState.UNKNOWN || (enabled && state != AdapterState.OFF) ||
                (!enabled && state != AdapterState.ON)) {
                callback(SwitchOutcome.notInvoked("实际状态 $state；缺少可读稳定状态，未切换"))
                return@readState
            }
            backend.preflight(lease, preflightMs) { capability ->
                if (expired()) return@preflight
                when {
                    ordinaryOnly -> ordinary("先前高权限执行已结束，但稳定状态未达目标；可能受系统/OEM 限制。${capability.reason}")
                    capability.mode == null -> ordinary(capability.reason)
                    else -> backend.privileged(enabled, lease, rpcMs) { outcome ->
                        if (outcome.status == SwitchOutcome.Status.NOT_INVOKED && outcome.authorityEnded) ordinary(outcome.reason)
                        else callback(outcome)
                    }
                }
            }
        }
    }
}
