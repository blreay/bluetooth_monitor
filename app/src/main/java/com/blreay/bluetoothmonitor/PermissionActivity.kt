package com.blreay.bluetoothmonitor

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * Requests the Nearby-devices grant, then starts the fast phase.
 *
 * This is the only window the app ever creates, and it only ever appears on
 * first run - the grant persists across launches and across overwrite
 * installs, because the signing key is fixed.
 *
 * It has to be a separate Activity because [MainActivity] uses
 * Theme.NoDisplay, which requires finish() before onResume(). A permission
 * request cannot survive that: onRequestPermissionsResult only arrives after
 * the system dialog returns, which is well past onResume().
 *
 * Extends android.app.Activity, not AppCompatActivity: there is no UI beyond
 * the system dialog, and the app has no androidx dependencies at all.
 */
class PermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BtPermissions.allGranted(this)) {
            // Granted between MainActivity's check and here, or this Activity
            // was started directly. Either way, just run.
            ResetService.start(this, wantsToast())
            finish()
            return
        }
        Log.i(TAG, "requesting Nearby-devices permissions (first run only)")
        requestPermissions(BtPermissions.REQUIRED, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) {
            finish()
            return
        }
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            Log.i(TAG, "permissions granted")
            ResetService.start(this, wantsToast())
        } else {
            // Shown unconditionally, ignoring the debug flag: without this
            // grant nothing works, and a silent no-op would leave the user
            // with no idea why. Not retried and not looped - one message,
            // then finish.
            Log.e(TAG, "permission denied; cannot scan or toggle Bluetooth")
            Notifier.always(this, getString(R.string.toast_permission_denied))
        }
        finish()
    }

    private fun wantsToast(): Boolean =
        intent?.getBooleanExtra(Notifier.EXTRA_TOAST, false) ?: false

    companion object {
        private const val TAG = "BtReset.Perm"
        private const val REQUEST_CODE_PERMISSIONS = 1
    }
}
