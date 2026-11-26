package com.blreay.bluetoothmonitor

/** Immutable owner facts, copied at each physical request boundary, not looked up by late callbacks. */
data class RuntimeIdentity(val owner: String, val runId: String, val commandId: String, val requestId: String? = null)

/** Diagnostic backend decorator; preserves all six ports and the original invocation, not history policy. */
class RuntimeAudit(private val sink: AuditSink) {
    fun ingress(source: String, requestId: String, action: String?, component: String?) {
        sink.event("runtime.ingress", "source" to source, "requestId" to requestId, "action" to action, "component" to component)
    }
    fun event(name: String, identity: RuntimeIdentity, vararg fields: Pair<String, Any?>, at: Long? = null) {
        sink.event(name, "owner" to identity.owner, "runId" to identity.runId, "commandId" to identity.commandId,
            "requestId" to identity.requestId, *fields, at = at)
    }
    fun advertisement(identity: RuntimeIdentity, scannerIdentity: String?, address: String?, name: String?, rssi: Int?,
                      sampledElapsedNanos: Long, receivedAt: Long, receivedElapsedNanos: Long, deliveredElapsedNanos: Long,
                      displayOnly: Boolean) {
        event("ble.advertisement", identity, "scannerIdentity" to scannerIdentity, "address" to address, "name" to name,
            "rssi" to rssi, "sampledElapsedNanos" to sampledElapsedNanos, "receivedAt" to receivedAt,
            "receivedElapsedNanos" to receivedElapsedNanos, "deliveredElapsedNanos" to deliveredElapsedNanos,
            "target" to BandMatcher.isTarget(address, name), "displayOnly" to displayOnly, at = receivedAt)
    }
    fun sample(identity: RuntimeIdentity, sample: StateSample) {
        event("switch.sample", identity, "state" to sample.state, "sampledElapsedMs" to sample.sampledElapsed,
            "boundary" to "worker state read, not hardware instant", at = sample.sampledAt)
    }
    fun outcome(name: String, identity: RuntimeIdentity, outcome: SwitchOutcome) {
        event(name, identity, "status" to outcome.status.name, "mode" to outcome.mode, "reason" to outcome.reason,
            "authorityEnded" to outcome.authorityEnded, "enteredAt" to outcome.enteredAt,
            "submittedAt" to outcome.submittedAt, "returnedAt" to outcome.returnedAt, at = outcome.returnedAt)
    }
    fun backend(delegate: SwitchBackend, identity: () -> RuntimeIdentity): SwitchBackend = object : SwitchBackend {
        override fun readState(lease: CommandLease, callback: (AdapterState) -> Unit) {
            val captured = identity()
            event("switch.state.request", captured)
            delegate.readState(lease) { state ->
                event("switch.state", captured, "state" to state, "boundary" to "callback receipt; source sample separately recorded")
                callback(state)
            }
        }
        override fun preflight(lease: CommandLease, budgetMs: Long, callback: (SwitchCapability) -> Unit) {
            val captured = identity()
            event("switch.preflight.request", captured, "budgetMs" to budgetMs)
            delegate.preflight(lease, budgetMs) { capability ->
                event("switch.preflight", captured, "mode" to capability.mode, "reason" to capability.reason)
                callback(capability) // never feed diagnostics to the driver's stateful BackendEntry callback
            }
        }
        override fun privileged(enabled: Boolean, lease: CommandLease, budgetMs: Long, callback: (SwitchOutcome) -> Unit) {
            val captured = identity()
            event("switch.privileged.request", captured, "enabled" to enabled, "budgetMs" to budgetMs)
            delegate.privileged(enabled, lease, budgetMs) { result -> outcome("switch.privileged.outcome", captured, result); callback(result) }
        }
        override fun ordinary(enabled: Boolean, lease: CommandLease, callback: (SwitchOutcome) -> Unit) {
            val captured = identity()
            event("switch.ordinary.request", captured, "enabled" to enabled)
            delegate.ordinary(enabled, lease) { result -> outcome("switch.ordinary.outcome", captured, result); callback(result) }
        }
        override fun privilegedObserved(enabled: Boolean, lease: CommandLease, budgetMs: Long, invocation: SwitchInvocation,
                                        callback: (SwitchOutcome) -> Unit) {
            val captured = identity() // beforeAttempt already rotated the physical command, if needed
            event("switch.privileged.request", captured, "enabled" to enabled, "budgetMs" to budgetMs)
            delegate.privilegedObserved(enabled, lease, budgetMs, invocation) { result ->
                outcome("switch.privileged.outcome", captured, result); callback(result)
            }
        }
        override fun ordinaryObserved(enabled: Boolean, lease: CommandLease, invocation: SwitchInvocation, callback: (SwitchOutcome) -> Unit) {
            val captured = identity()
            event("switch.ordinary.request", captured, "enabled" to enabled)
            delegate.ordinaryObserved(enabled, lease, invocation) { result -> outcome("switch.ordinary.outcome", captured, result); callback(result) }
        }
    }
}
