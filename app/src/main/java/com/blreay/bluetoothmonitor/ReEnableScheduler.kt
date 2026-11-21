package com.blreay.bluetoothmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Arms one tick of the re-enable chain.
 *
 * A chain rather than a single delayed callback, because the thing being
 * waited for has no predictable duration: MIUI shows a permission dialog
 * before the radio even starts turning off, and then gracefully tears down
 * every Bluetooth connection. v1 guessed 3 seconds, called enable() while
 * the state was still TURNING_OFF, and the framework rejected it.
 *
 * Each tick re-reads the state via [ReEnableReceiver] and re-arms itself
 * until [ReEnablePolicy] says stop. Nothing here knows how long Bluetooth
 * takes to turn off, and that is the point.
 *
 * The chain lives entirely in AlarmManager plus [PendingResetStore]. There
 * is no in-process timer any more: a Handler callback dies with the process,
 * and MIUI reclaims this one readily.
 */
object ReEnableScheduler {

    /**
     * One second per tick. With [ReEnablePolicy.MAX_ATTEMPTS] that is a two
     * minute ceiling - long enough for a human to read and tap the MIUI
     * dialog, short enough that a stuck chain stops by itself.
     */
    const val TICK_INTERVAL_MS = 1_000L

    private const val TAG = "BtReset.Sched"
    private const val REQUEST_CODE = 1

    /**
     * Arms the next tick. Called once to start the chain and then once per
     * tick to continue it.
     *
     * Deliberately logs nothing on success: [ReEnableReceiver] already logs
     * every tick it receives, so a chain that stops arming is visible as a
     * gap in those logs. Logging here too would add a line per second.
     *
     * @param context must be an application context - the PendingIntent and
     *                the alarm both outlive the caller.
     */
    fun armNextTick(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            // Fatal for the slow phase: nothing else can re-enable Bluetooth.
            Log.e(TAG, "AlarmManager unavailable; Bluetooth will stay OFF and needs a manual toggle")
            return
        }

        val triggerAt = System.currentTimeMillis() + TICK_INTERVAL_MS
        val pending = tickPendingIntent(context)

        if (am.canScheduleExactAlarms()) {
            // setExact, NOT setExactAndAllowWhileIdle: the latter is throttled
            // to roughly one firing per 9 seconds while idle, which would
            // stretch a 1s retry chain into something unusable. The screen is
            // on and the user just triggered this, so Doze is not a concern.
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            // SCHEDULE_EXACT_ALARM is granted by default at targetSdk 32, so
            // this should not happen. If it does the chain still runs, just
            // coarsely, and Bluetooth may stay off longer than two minutes.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            Log.e(
                TAG,
                "exact alarms not permitted; ticks will be inexact - " +
                    "Bluetooth may stay OFF longer than the two minute ceiling"
            )
        }
    }

    /**
     * Cancels any armed tick. Every terminal path - success and all three
     * give-ups - must call this, otherwise a stale alarm fires later and
     * finds nothing in the store.
     */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(tickPendingIntent(context))
        Log.i(TAG, "chain cancelled")
    }

    private fun tickPendingIntent(context: Context): PendingIntent {
        // Explicit intent to the class: exempt from the Android 8+ ban on
        // implicit broadcasts to manifest-declared receivers, and it lets the
        // system start the process to deliver the tick after MIUI reclaimed it.
        val intent = Intent(context, ReEnableReceiver::class.java)
            .setAction(ReEnableReceiver.ACTION_REENABLE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            // FLAG_IMMUTABLE is mandatory on API 31+, which is our minSdk.
            // FLAG_UPDATE_CURRENT makes re-arming replace the previous tick
            // instead of stacking, so at most one alarm is ever pending.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
