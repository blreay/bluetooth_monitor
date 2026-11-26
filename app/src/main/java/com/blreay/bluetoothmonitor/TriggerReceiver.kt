package com.blreay.bluetoothmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Automation entry: legal FGS handoff, or a six-second scan with nine-second finishOnce backstop. */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != action(context)) return
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        val lease = CommandLease({ SystemClock.elapsedRealtime() }, SystemClock.elapsedRealtime() + 9_000L)
        val completion = CompletionGate()
        val coordinator = ResetCoordinator.get(context)
        val backstop = Runnable {
            coordinator.cancelBroadcastScan(lease)
            if (completion.finish()) pending.finish()
        }
        handler.postDelayed(backstop, 9_000L)
        coordinator.broadcastScan(intent.getBooleanExtra(Notifier.EXTRA_TOAST, false), lease) {
            if (completion.finish()) {
                handler.removeCallbacks(backstop)
                lease.cancel()
                pending.finish()
            }
        }
    }
    companion object {
        const val RECEIVER_SCAN_TIMEOUT_MS = 6_000L
        fun action(context: Context) = "${context.packageName}.action.RESET_IF_PRESENT"
    }
}
