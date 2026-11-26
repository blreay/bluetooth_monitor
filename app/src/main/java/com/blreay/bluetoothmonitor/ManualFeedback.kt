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
    val scanSummary: ScanSummary? = null,
) {
    fun conciseMessage(): String {
        if (action == ManualAction.SCAN && type != ManualFeedbackType.ACCEPTED) {
            val count = scanSummary?.count?.let { "${if (scanSummary.overflow) "100+" else it}个设备" } ?: "设备数未知"
            val target = when (scanSummary?.targetSeen) { true -> "有"; false -> "无"; null -> "未知" }
            val completion = when (type) {
                ManualFeedbackType.STORAGE_FAILED -> "存储失败，结果未确认"
                ManualFeedbackType.QUARANTINED -> "执行/清理未确认（部分结果）"
                ManualFeedbackType.REJECTED -> "未执行"
                else -> if (status == ManualResultStatus.COMPLETED && scanSummary?.result == "成功") "完成"
                    else scanSummary?.result?.takeUnless { it == "成功" } ?: if (scanSummary?.count == null) "未执行" else "失败（部分结果）"
            }
            return "手动 SCAN：$count，目标手环：$target，${completion.take(300)}".take(300)
        }
        val route = when (mode) {
            null -> "" // accepted/rejected before execution has no proven backend
            SwitchMode.NONE -> "（${mode.label}，未调用开关）"
            else -> "（${mode.label}）"
        }
        return "手动 ${action.name}$route：$reason".take(300)
    }
}
