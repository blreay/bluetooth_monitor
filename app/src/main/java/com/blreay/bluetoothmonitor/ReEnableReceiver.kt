package com.blreay.bluetoothmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Cold alarms get one bounded dispatch/observation step, never a human grant wait or nested ten-second wait. */
class ReEnableReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = java.util.UUID.randomUUID().toString()
        MibandLog.event("receiver.ingress", "requestId" to requestId, "receiver" to "alarm", "action" to intent.action,
            "component" to intent.component?.flattenToShortString())
        if (intent.action != ReEnableScheduler.action(context)) {
            MibandLog.event("receiver.refused", "requestId" to requestId, "reason" to "action mismatch")
            return
        }
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        val lease = CommandLease({ SystemClock.elapsedRealtime() }, SystemClock.elapsedRealtime() + 7_900L)
        val completion = CompletionGate()
        val backstop = Runnable { lease.cancel(); if (completion.finish()) pending.finish() }
        handler.postDelayed(backstop, 8_000L)
        ResetCoordinator.get(context).alarm(lease, requestId) {
            if (completion.finish()) {
                handler.removeCallbacks(backstop)
                lease.cancel()
                pending.finish()
            }
        }
    }
}
