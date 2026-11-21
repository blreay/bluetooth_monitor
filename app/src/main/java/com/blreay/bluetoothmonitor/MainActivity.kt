package com.blreay.bluetoothmonitor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * The launcher icon, and nothing else. Dispatches, then finishes.
 *
 * Theme.NoDisplay, and finish() inside onCreate() as that theme requires.
 * The system never creates a window for this Activity, so it never takes
 * input focus.
 *
 * That last part is the fix. v1 used a translucent theme and deliberately
 * stayed alive for 3.5s after a match - 8s when nothing was found - to
 * protect an in-process re-enable timer. Translucent keeps the home screen
 * visible while the Activity on top owns the focus, so the desktop was
 * visible but dead to touch for that whole window. Replacing the timer with
 * an alarm chain removed the reason to stay alive, which removed the jank.
 *
 * A launcher icon requires an Activity: launchers enumerate activities with
 * MAIN/LAUNCHER, and a Service or Receiver never appears on the home screen.
 * So the Activity stays, and is instead made free.
 *
 * Extends android.app.Activity, not AppCompatActivity: there is no UI, and
 * the app has no androidx dependencies at all.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wantsToast = intent?.getBooleanExtra(Notifier.EXTRA_TOAST, false) ?: false
        if (BtPermissions.allGranted(this)) {
            Log.i(TAG, "dispatching to ResetService (wantsToast=$wantsToast)")
            ResetService.start(this, wantsToast)
        } else {
            Log.i(TAG, "dispatching to PermissionActivity (first run only)")
            startActivity(
                Intent(this, PermissionActivity::class.java)
                    .putExtra(Notifier.EXTRA_TOAST, wantsToast)
            )
        }
        // Unconditional, and after every dispatch path. Theme.NoDisplay
        // requires finish() before onResume(), and finishing immediately is
        // the entire point: this Activity must never own a window or hold
        // focus, or the home screen becomes visible-but-dead for the length
        // of the scan.
        finish()
    }

    companion object {
        private const val TAG = "BtReset.Main"
    }
}
