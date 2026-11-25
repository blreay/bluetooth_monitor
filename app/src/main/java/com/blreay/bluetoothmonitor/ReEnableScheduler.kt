package com.blreay.bluetoothmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

object ReEnableScheduler {
    const val TICK_INTERVAL_MS = 1_000L
    private const val REQUEST_CODE = 10

    /** Scheduling is part of the OFF transaction: failure is not log-and-success. */
    fun armNextTick(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
            val triggerAt = SystemClock.elapsedRealtime() + TICK_INTERVAL_MS
            val pending = tickPendingIntent(context)
            if (am.canScheduleExactAlarms()) am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
            else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
                Log.w("BtReset.Sched", "inexact alarm; Doze/OEM may delay observations, ticks are not seconds")
            }
            true
        } catch (e: Exception) {
            Log.e("BtReset.Sched", "cannot arm recovery; no new OFF is permitted", e)
            false
        }
    }

    fun cancel(context: Context) {
        try { (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(tickPendingIntent(context)) }
        catch (e: Exception) { Log.e("BtReset.Sched", "alarm cancellation failed", e) }
    }

    private fun tickPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, ReEnableReceiver::class.java).setAction(action(context)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    fun action(context: Context) = "${context.packageName}.action.REENABLE"
}
