package com.blreay.bluetoothmonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * The Nearby-devices runtime permissions this app needs.
 *
 * minSdk is 31, so BLUETOOTH_SCAN (declared with neverForLocation) plus
 * BLUETOOTH_CONNECT is the complete set - ACCESS_FINE_LOCATION is never
 * required and the legacy BLUETOOTH / BLUETOOTH_ADMIN permissions do not
 * apply.
 *
 * These cannot be granted silently at install time. The scan button requests
 * missing grants; opening the UI and manual Shizuku toggles never request them.
 * The legacy broadcast cannot prompt from the background and simply gives up.
 */
object BtPermissions {

    val REQUIRED: Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    fun missing(context: Context): List<String> = REQUIRED.filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
}
