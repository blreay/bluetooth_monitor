package com.blreay.bluetoothmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Entry point for `sendBroadcast` callers: Tasker, MacroDroid, Termux.
 *
 * Runs the fast phase inline under goAsync() rather than starting
 * [ResetService], because Android 8+ forbids a background app from calling
 * startService() and this receiver is always in the background. goAsync()
 * only buys about ten seconds, so the scan is capped at 6s.
 *
 * The slow phase needs no help from here: [BandResetController] arms an alarm
 * chain that the system delivers even if the process is reclaimed the moment
 * goAsync() ends.
 *
 * Cannot prompt for permissions - there is no UI and Android 10+ blocks
 * starting an Activity from the background. If the grant is missing it logs
 * and gives up; launching the icon once is the only way to grant it.
 *
 * Left exported without a signature permission on purpose, so Tasker,
 * MacroDroid and Termux can all trigger it. Worst case for an uninvited
 * caller is one extra Bluetooth cycle.
 */
class TriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESET_IF_PRESENT) {
            Log.w(TAG, "ignoring unexpected action ${intent.action}")
            return
        }
        if (!BtPermissions.allGranted(context)) {
            Log.e(TAG, "Nearby-devices permission missing; launch the app icon once to grant it")
            return
        }

        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        var finished = false

        fun finishOnce() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            pending.finish()
        }

        // Backstop: never hold the goAsync() budget past its ~10s limit, even
        // if the controller somehow fails to call back. Exceeding it is an
        // ANR-grade failure, not a graceful one.
        handler.postDelayed({ finishOnce() }, GO_ASYNC_BACKSTOP_MS)

        // applicationContext: the scan callback outlives onReceive.
        val wantsToast = intent.getBooleanExtra(Notifier.EXTRA_TOAST, false)
        BandResetController(context.applicationContext).run(
            RECEIVER_SCAN_TIMEOUT_MS,
            wantsToast
        ) { result ->
            Log.i(TAG, "result=$result")
            if (result == BandResetController.Result.RESET_TRIGGERED) {
                Log.i(TAG, "Bluetooth is turning off; the alarm chain will bring it back")
            }
            finishOnce()
        }
    }

    companion object {
        private const val TAG = "BtReset.Trigger"

        const val ACTION_RESET_IF_PRESENT =
            "com.blreay.bluetoothmonitor.action.RESET_IF_PRESENT"

        /** Kept under the ~10s goAsync() budget with room to spare. */
        const val RECEIVER_SCAN_TIMEOUT_MS = 6_000L

        private const val GO_ASYNC_BACKSTOP_MS = 9_000L
    }
}
