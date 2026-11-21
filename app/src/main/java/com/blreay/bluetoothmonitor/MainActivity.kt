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
 * Re-enabling Bluetooth is owned by an alarm chain now, so this Activity no
 * longer has to stay alive to protect an in-process timer. It reports as soon
 * as the fast phase is done.
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
        // applicationContext, not `this`: the scan callback outlives this
        // Activity.
        BandResetController(applicationContext).run(
            ACTIVITY_SCAN_TIMEOUT_MS,
            wantsToast
        ) { result -> report(result) }
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
        private const val TOAST_LINGER_MS = 2_500L
    }
}
