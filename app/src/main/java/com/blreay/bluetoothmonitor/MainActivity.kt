package com.blreay.bluetoothmonitor

import android.app.Activity
import android.os.Bundle

/** True NoDisplay dispatcher: dispatch once and finish in onCreate, before resume. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val coordinator = ResetCoordinator.get(this)
            coordinator.launch(this, intent.getBooleanExtra(Notifier.EXTRA_TOAST, false))
        }
        finish()
    }
}
