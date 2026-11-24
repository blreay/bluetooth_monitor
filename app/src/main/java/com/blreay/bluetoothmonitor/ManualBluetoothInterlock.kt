package com.blreay.bluetoothmonitor

/** Process-local ownership; callers claim/release on the main thread. Old scans never become valid again. */
class ManualBluetoothInterlock {
    private var generation = 0L
    var manualActive = false
        private set

    fun legacyToken(): Long? = if (manualActive) null else generation
    fun isLegacyCurrent(token: Long): Boolean = !manualActive && token == generation

    fun claimManual(): Long? {
        if (manualActive) return null
        generation++
        manualActive = true
        return generation
    }

    fun isManualCurrent(token: Long): Boolean = manualActive && token == generation

    fun releaseManual(token: Long) {
        if (isManualCurrent(token)) manualActive = false
    }

    companion object { val shared = ManualBluetoothInterlock() }
}
