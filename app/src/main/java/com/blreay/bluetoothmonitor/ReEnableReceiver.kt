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
        if (intent.action != ReEnableScheduler.action(context)) return
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        val lease = CommandLease({ SystemClock.elapsedRealtime() }, SystemClock.elapsedRealtime() + 7_900L)
        val completion = CompletionGate()
        val backstop = Runnable { lease.cancel(); if (completion.finish()) pending.finish() }
        handler.postDelayed(backstop, 8_000L)
        ResetCoordinator.get(context).alarm(lease) {
            if (completion.finish()) {
                handler.removeCallbacks(backstop)
                lease.cancel()
                pending.finish()
            }
        }
    }
}
