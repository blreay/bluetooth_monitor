package com.blreay.bluetoothmonitor

/** Transient facts from a live request, never reconstructed from persisted presentation. */
enum class ManualFeedbackType { ACCEPTED, REJECTED, QUARANTINED, TERMINAL, STORAGE_FAILED }

data class ManualFeedback(
    val action: ManualAction,
    val operationId: String,
    val requestId: String,
    val type: ManualFeedbackType,
    val status: ManualResultStatus,
    val reason: String,
    val mode: SwitchMode? = null,
    val observedState: AdapterState? = null,
    val authorityEnded: Boolean? = null,
) {
    fun conciseMessage(): String {
        val route = when (mode) {
            null -> "" // accepted/rejected before execution has no proven backend
            SwitchMode.NONE -> "（${mode.label}，未调用开关）"
            else -> "（${mode.label}）"
        }
        return "手动 ${action.name}$route：$reason".take(300)
    }
}
