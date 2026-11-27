package com.blreay.bluetoothmonitor

import java.util.Collections
import java.util.Locale

data class ManualScanIdentity(val operationId: String, val commandId: String)
/** Raw current-scan packet name, never BluetoothDevice.name or the display placeholder. */
data class ManualScanCandidate(val mac: String, val advertisementName: String?)
data class ManualScanCandidatesSnapshot(
    val identity: ManualScanIdentity? = null,
    val closed: Boolean = false,
    val devices: List<ManualScanCandidate> = emptyList(),
    val overflow: Boolean = false,
)

/**
 * Independent process-local selection snapshot. No store/Activity hydration or scanner lives here.
 * Use on the coordinator lane; begin ONLY after real SCAN acceptance, not from rejected clicks.
 */
class ManualScanCandidates(private val onChanged: (ManualScanCandidatesSnapshot) -> Unit = {}) {
    private var current = ManualScanCandidatesSnapshot()

    fun snapshot(): ManualScanCandidatesSnapshot = current

    fun begin(operation: ManualOperation): Boolean {
        if (operation.action != ManualAction.SCAN || operation.operationId.isBlank() || operation.commandId.isBlank()) return false
        val identity = ManualScanIdentity(operation.operationId, operation.commandId)
        if (current.identity == identity) return false
        publish(ManualScanCandidatesSnapshot(identity))
        return true
    }

    /** The caller supplies the current bounded collector snapshot, not persisted manual_results. */
    fun update(identity: ManualScanIdentity, devices: Iterable<ManualScanCandidate>, overflow: Boolean = false): Boolean {
        if (current.identity != identity || current.closed) return false
        val bounded = linkedMapOf<String, ManualScanCandidate>()
        var exceeded = overflow
        for (device in devices) {
            val address = device.mac.trim().uppercase(Locale.ROOT)
            if (address.isEmpty()) continue
            if (address !in bounded && bounded.size >= LIMIT) { exceeded = true; continue }
            bounded[address] = device.copy(mac = address) // names remain exact, including null/blank/case/spaces
        }
        val immutable = Collections.unmodifiableList(ArrayList(bounded.values))
        publish(current.copy(devices = immutable, overflow = exceeded))
        return true
    }

    /** Terminal results survive controller clearing its live result; closed callbacks can no longer mutate them. */
    fun close(identity: ManualScanIdentity): Boolean {
        if (current.identity != identity || current.closed) return false
        publish(current.copy(closed = true))
        return true
    }

    private fun publish(next: ManualScanCandidatesSnapshot) {
        if (next == current) return
        current = next
        try { onChanged(next) } catch (_: Exception) { /* Presentation is not lifecycle authority. */ }
    }

    private companion object { const val LIMIT = 100 }
}
