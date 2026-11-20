package com.blreay.bluetoothmonitor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * The only place in the app that touches the deprecated
 * [BluetoothAdapter.enable] and [BluetoothAdapter.disable].
 *
 * Android 13 closed these to third-party apps targeting API 33+; this app
 * targets 32 specifically to keep them. They can still return false when the
 * OEM refuses, so every caller must check the result rather than assume the
 * radio actually changed state.
 */
object BluetoothSwitch {

    private const val TAG = "BtReset.Switch"

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Turns Bluetooth on if it is currently off. Idempotent, so the in-process
     * timer and the system alarm can both call it without coordinating.
     */
    fun enableIfOff(context: Context) {
        val adapter = adapter(context)
        if (adapter == null) {
            Log.e(TAG, "no BluetoothAdapter; cannot re-enable")
            return
        }
        if (adapter.isEnabled) {
            Log.i(TAG, "Bluetooth already on; nothing to do")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "BLUETOOTH_CONNECT not granted; Bluetooth is OFF and needs a manual toggle")
            return
        }
        @Suppress("DEPRECATION")
        val ok = try {
            adapter.enable()
        } catch (t: Throwable) {
            Log.e(TAG, "enable() threw", t)
            false
        }
        if (ok) {
            Log.i(TAG, "Bluetooth turned back on")
        } else {
            Log.e(TAG, "enable() returned false; Bluetooth is OFF and needs a manual toggle")
        }
    }

    /**
     * @return true only if the system accepted the request. A false result
     *         means Bluetooth is still ON, so callers must not schedule a
     *         re-enable - that is the invariant which keeps this app from
     *         ever leaving the radio in the wrong state.
     */
    fun disable(adapter: BluetoothAdapter): Boolean {
        @Suppress("DEPRECATION")
        return try {
            adapter.disable()
        } catch (t: Throwable) {
            Log.e(TAG, "disable() threw", t)
            false
        }
    }
}
