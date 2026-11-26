package com.blreay.bluetoothmonitor

/** One current-command annotation, never an authority proof or a second recovery policy. */
class RuntimeAudit(private val sink: AuditSink) {
    private data class Current(val ownerId: String, val commandId: String, val enabled: Boolean,
                               var mode: SwitchMode? = SwitchMode.NONE, var uncertain: Boolean = false, var final: Boolean = false)
    private var current: Current? = null
    fun begin(ownerId: String, commandId: String, enabled: Boolean) {
        if (!matches(ownerId, commandId)) current = Current(ownerId, commandId, enabled)
    }
    fun matches(ownerId: String, commandId: String) = current?.let { it.ownerId == ownerId && it.commandId == commandId } == true
    fun entered(ownerId: String, commandId: String) {
        if (matches(ownerId, commandId) && current?.mode == SwitchMode.NONE) current?.mode = null
    }
    fun outcome(ownerId: String, commandId: String, value: SwitchOutcome) {
        if (matches(ownerId, commandId)) current?.mode = value.mode
    }
    fun end(ownerId: String, commandId: String, result: String, reliable: Boolean) {
        if (!matches(ownerId, commandId)) return // cold replay and stale callbacks have no observation ownership
        val value = current ?: return
        if (value.final || (!reliable && value.uncertain)) return
        if (reliable) value.final = true else value.uncertain = true
        val phrase = if (result == "成功" && value.mode == SwitchMode.NONE) "已是目标状态，未调用" else result
        sink.event(CompactLogEntry.BluetoothEnded(value.enabled, value.mode, phrase))
    }
}
