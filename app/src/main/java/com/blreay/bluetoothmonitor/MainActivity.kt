package com.blreay.bluetoothmonitor

import android.app.Activity
import android.os.Bundle

/** True NoDisplay dispatcher: dispatch once and finish in onCreate, before resume. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestId = java.util.UUID.randomUUID().toString()
        MibandLog.event("activity.create", "requestId" to requestId, "action" to intent.action,
            "component" to intent.component?.flattenToShortString(), "recreated" to (savedInstanceState != null))
        if (savedInstanceState == null) {
            val coordinator = ResetCoordinator.get(this)
            coordinator.launch(this, intent.getBooleanExtra(Notifier.EXTRA_TOAST, false), requestId)
            MibandLog.event("activity.dispatch.return", "requestId" to requestId)
        } else {
            MibandLog.event("activity.dispatch.skip", "requestId" to requestId, "reason" to "recreated; no replay")
        }
        MibandLog.event("activity.finish.request", "requestId" to requestId)
        finish()
    }
}
