package com.blreay.bluetoothmonitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Runs the fast phase for the launcher-icon path.
 *
 * Started, not foreground: a foreground Service would put a notification in
 * the status bar, and Android 12+ throws
 * ForegroundServiceStartNotAllowedException when a background app starts one
 * - which would break the sendBroadcast entry point outright.
 *
 * Being started from [MainActivity] while it is in the foreground is what
 * makes this legal, and the fast phase is bounded at 8s, well inside the
 * window Android 8+ gives a backgrounded app's services before stopping
 * them. If MIUI does reclaim it mid-scan, nothing has been armed yet and
 * Bluetooth is still on - there is no half-state to recover from.
 *
 * The slow phase is not here. It belongs to [ReEnableReceiver] and survives
 * this Service being stopped, which is the whole reason it was split out.
 */
class ResetService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wantsToast = intent?.getBooleanExtra(Notifier.EXTRA_TOAST, false) ?: false
        Log.i(TAG, "fast phase starting (wantsToast=$wantsToast)")

        BandResetController(applicationContext).run(SCAN_TIMEOUT_MS, wantsToast) { result ->
            Log.i(TAG, "result=$result")
            // Whatever happened, this Service is done. On RESET_TRIGGERED the
            // alarm chain now owns the outcome and does not need us alive -
            // v1 kept its Activity alive here, and holding input focus for
            // seconds is what made the home screen feel frozen.
            stopSelf()
        }

        // Not sticky: if the system kills this, restarting it would begin a
        // second scan for no reason. A slow phase already in flight is driven
        // by alarms, which do not need this Service.
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "BtReset.Svc"

        /**
         * The Service has no goAsync() budget to fit inside, unlike
         * [TriggerReceiver], so it can afford the longer window.
         */
        const val SCAN_TIMEOUT_MS = 8_000L

        /**
         * The one way to start the fast phase, shared by [MainActivity] and
         * [PermissionActivity] so they cannot drift apart.
         */
        fun start(context: Context, wantsToast: Boolean) {
            val intent = Intent(context, ResetService::class.java)
                .putExtra(Notifier.EXTRA_TOAST, wantsToast)
            context.startService(intent)
        }
    }
}
