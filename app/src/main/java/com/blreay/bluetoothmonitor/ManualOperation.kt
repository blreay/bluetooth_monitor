package com.blreay.bluetoothmonitor

enum class ManualAction { ON, OFF, SCAN }
enum class ManualStage { READY, DISPATCHED, RETIRING, SETTLED, QUARANTINED }
enum class ManualResultStatus { COMPLETED, REJECTED, UNCERTAIN, FAILED, OBSERVING }

/** One current durable fence, independent of the three per-action last-result cards. */
data class ManualOperation(
    val operationId: String,
    val commandId: String,
    val revision: Long = 0L,
    val action: ManualAction,
    val stage: ManualStage = ManualStage.READY,
    val nativeEntered: Boolean = false,
    val authorityEnded: Boolean = true,
    val scannerIdentity: String? = null,
    val scannerRetired: Boolean = false,
    val reason: String = "尚未开始调用",
    val begunAt: Long? = null,
) {
    fun canFinish(): Boolean = authorityEnded && (action != ManualAction.SCAN ||
        (scannerIdentity == null && !nativeEntered) || (scannerIdentity != null && scannerRetired))
}

data class ManualResult(
    val operationId: String,
    val action: ManualAction,
    val status: ManualResultStatus,
    val reason: String,
    val observedState: AdapterState? = null,
    val completedAt: Long? = null,
    val modeBits: Int = 0,
    val backendHistory: List<BackendEntry> = emptyList(),
    val begunAt: Long? = null,
    val requestedAt: Long? = null,
    val nativeEnteredAt: Long? = null,
    val submittedAt: Long? = null,
    val authorityEndedAt: Long? = null,
    val observedAt: Long? = null,
    val scanStartedAt: Long? = null,
    val scanReturnedAt: Long? = null,
    val scanEndedAt: Long? = null,
    val devices: List<DiscoveredDevices.Device> = emptyList(),
    val overflow: Boolean = false,
)

/** Runtime supplies the volatile owners; persistence independently checks its durable owners. */
data class OperationAdmission(
    val pending: ResetRecord? = null,
    val manualFence: ManualOperation? = null,
    val activeScan: Boolean = false,
    val scanRetirement: Boolean = false,
    val automaticOwner: Boolean = false,
    val setupWaiting: Boolean = false,
    val serviceOwned: Boolean = false,
    val storageFailed: Boolean = false,
    val integrityBlocked: Boolean = false,
) {
    fun allowsNewOperation(): Boolean = pending == null && manualFence == null &&
        !activeScan && !scanRetirement && !automaticOwner && !setupWaiting && !serviceOwned &&
        !storageFailed && !integrityBlocked
}
