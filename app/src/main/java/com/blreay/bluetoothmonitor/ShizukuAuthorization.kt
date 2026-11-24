package com.blreay.bluetoothmonitor

/** Grant and connection are independent: a connection failure must not turn a grant into a denial. */
class ShizukuAuthorization {
    private var binderAvailable = false
    var statusKnown = false
        private set
    var granted = false
        private set
    private var serviceReady = false
    private var statusGeneration = 0L
    private var statusPending = false
    val canToggle: Boolean get() = binderAvailable && granted && serviceReady

    fun beginStatusRead(): Long {
        statusGeneration++
        statusPending = true
        binderDied()
        return statusGeneration
    }

    fun finishStatusRead(token: Long, available: Boolean, grant: Boolean): Boolean {
        if (!statusPending || token != statusGeneration) return false
        statusPending = false
        if (available) binderReceived(grant) else binderDied()
        return true
    }

    fun timeoutStatusRead(token: Long): Boolean {
        if (!statusPending || token != statusGeneration) return false
        statusPending = false
        binderDied()
        return true
    }

    fun cancelStatusRead() { statusGeneration++; statusPending = false }

    fun binderReceived(grant: Boolean) {
        binderAvailable = true
        statusKnown = true
        granted = grant
        serviceReady = false
    }

    fun permissionResult(grant: Boolean) {
        statusKnown = binderAvailable
        granted = binderAvailable && grant
        if (!granted) serviceReady = false
    }

    fun serviceConnected() { serviceReady = binderAvailable && granted }
    fun serviceDisconnected() { serviceReady = false }
    fun binderDied() {
        binderAvailable = false
        statusKnown = false
        granted = false
        serviceReady = false
    }
}
