package com.blreay.bluetoothmonitor

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Invisible entry point: launcher icon, Tasker / MacroDroid / Automate, and
 * `adb shell am start`.
 *
 * The theme is translucent rather than NoDisplay because NoDisplay requires
 * finish() before onResume(), which would abort the ~12s flow.
 *
 * This Activity deliberately stays alive until Bluetooth has been turned back
 * on. Finishing immediately after disable() would drop the process out of the
 * foreground and let MIUI reclaim it during the 3s wait, taking the
 * in-process re-enable timer down with it and leaving Bluetooth off. The
 * exact alarm would still recover it, but the precise path is worth keeping.
 *
 * Extends android.app.Activity, not AppCompatActivity: there is no UI, and
 * the app has no androidx dependencies at all.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var wantsToast = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wantsToast = intent?.getBooleanExtra(EXTRA_TOAST, false) ?: false
        if (!BtPermissions.allGranted(this)) {
            // Only ever happens on first run; the grant persists afterwards.
            Log.i(TAG, "requesting Nearby-devices permissions (first run only)")
            requestPermissions(BtPermissions.REQUIRED, REQUEST_CODE_PERMISSIONS)
            return
        }
        runController()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            Log.e(TAG, "permission denied; cannot scan or toggle Bluetooth")
            report(BandResetController.Result.NEEDS_PERMISSION)
            return
        }
        Log.i(TAG, "permissions granted")
        runController()
    }

    private fun runController() {
        // applicationContext, not `this`: the scan callback and the re-enable
        // Runnable both outlive this Activity.
        BandResetController(applicationContext).run(
            ACTIVITY_SCAN_TIMEOUT_MS
        ) { result -> onControllerResult(result) }
    }

    private fun onControllerResult(result: BandResetController.Result) {
        if (result == BandResetController.Result.RESET_TRIGGERED) {
            // Hold foreground priority until the in-process re-enable has
            // fired. See the class comment for why this matters.
            handler.postDelayed(
                { report(result) },
                ReEnableScheduler.IN_PROCESS_DELAY_MS + FINISH_GRACE_MS
            )
        } else {
            report(result)
        }
    }

    private fun report(result: BandResetController.Result) {
        Log.i(TAG, "result=$result")
        if (wantsToast) {
            Toast.makeText(this, "蓝牙重启: $result", Toast.LENGTH_LONG).show()
            // Let the Toast be read before the process goes away.
            handler.postDelayed({ finish() }, TOAST_LINGER_MS)
        } else {
            finish()
        }
    }

    companion object {
        private const val TAG = "BtReset.Main"
        private const val REQUEST_CODE_PERMISSIONS = 1

        /**
         * Debug-only extra: `am start -n com.blreay.bluetoothmonitor/.MainActivity --ez toast true`
         * shows the outcome on screen so the phone can be tested without adb.
         */
        const val EXTRA_TOAST = "toast"

        // No ACTION_RUN constant here: that action is declared only in the
        // manifest's intent-filter and this Activity runs regardless of which
        // action launched it, so a constant would be dead code.

        const val ACTIVITY_SCAN_TIMEOUT_MS = 8_000L
        private const val FINISH_GRACE_MS = 500L
        private const val TOAST_LINGER_MS = 2_500L
    }
}
