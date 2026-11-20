package com.blreay.bluetoothmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires from the alarm [ReEnableScheduler] set and makes sure Bluetooth is
 * back on.
 *
 * Declared in the manifest rather than registered at runtime, so the system
 * can start the process to deliver the alarm even after MIUI has reclaimed
 * it. Reached only by an explicit PendingIntent aimed at this class, which is
 * why exported=false is safe and why the Android 8+ implicit-broadcast ban
 * does not apply.
 */
class ReEnableReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REENABLE) {
            Log.w(TAG, "ignoring unexpected action ${intent.action}")
            return
        }
        Log.i(TAG, "alarm fired; ensuring Bluetooth is back on")
        BluetoothSwitch.enableIfOff(context)
    }

    companion object {
        const val ACTION_REENABLE = "com.blreay.bluetoothmonitor.action.REENABLE"
        private const val TAG = "BtReset.ReEnable"
    }
}
