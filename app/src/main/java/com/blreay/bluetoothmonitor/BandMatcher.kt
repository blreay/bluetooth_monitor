package com.blreay.bluetoothmonitor

/**
 * Decides whether a scanned BLE device is the band this app cares about.
 *
 * Kept free of Android types so it can be unit tested on the JVM. Callers
 * pass in the two Strings they have already extracted from a ScanResult.
 */
object BandMatcher {

    /** Full advertised name of the target band. */
    const val TARGET_NAME = "Xiaomi Smart Band 8 35BA"

    /** Prefix shared by every Xiaomi Smart Band advertisement. */
    const val TARGET_NAME_PREFIX = "Xiaomi Smart Band"

    /**
     * Last two octets of the band's MAC address. Xiaomi bands append these to
     * the advertised name, so the MAC survives cases where the name is
     * truncated by advertising limits or moved to the scan response.
     */
    const val TARGET_MAC_SUFFIX = "35:BA"

    /**
     * @param mac  device address as reported by `BluetoothDevice.getAddress`,
     *             upper case and colon separated; may be null
     * @param name advertised name, or null when the advertisement carried none
     */
    fun isTarget(mac: String?, name: String?): Boolean {
        // A full name match is decisive on its own.
        if (name != null && name.contains(TARGET_NAME)) return true

        // Otherwise fall back to the MAC, which is the more stable key.
        if (mac == null || !mac.endsWith(TARGET_MAC_SUFFIX, ignoreCase = true)) return false

        // MAC matches. Only reject when a name is present and clearly belongs
        // to a different product line - a null name is still a hit.
        return name == null || name.startsWith(TARGET_NAME_PREFIX, ignoreCase = true)
    }
}
