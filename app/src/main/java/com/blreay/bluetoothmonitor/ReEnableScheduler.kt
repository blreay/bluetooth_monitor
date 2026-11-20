package com.blreay.bluetoothmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log

/**
 * Arranges for Bluetooth to be switched back on after
 * [BandResetController] has turned it off.
 *
 * Two mechanisms are scheduled at once, deliberately:
 *
 *  - an in-process [Handler] delay, which is precise and works while the
 *    calling Activity is still alive;
 *  - an exact alarm delivered by the system, which still fires when MIUI has
 *    reclaimed the process during the wait.
 *
 * [BluetoothSwitch.enableIfOff] is idempotent, so whichever fires first wins
 * and the other is a harmless no-op.
 *
 * The broadcast path relies on the alarm alone: once goAsync() ends the
 * process can be reclaimed at any moment, taking the Handler with it.
 */
object ReEnableScheduler {

    /** Delay for the in-process fast path. This is the spec's "wait 3 seconds". */
    const val IN_PROCESS_DELAY_MS = 3_000L

    /**
     * Delay for the system alarm. Slightly later than the in-process path so
     * the precise one normally wins the race.
     */
    const val ALARM_DELAY_MS = 3_500L

    private const val TAG = "BtReset.Sched"
    private const val REQUEST_CODE = 1

    /**
     * @param context must be an application context - the PendingIntent and
     *                the posted Runnable both outlive the caller
     * @param handler main-looper handler owned by the caller's session
     */
    fun schedule(context: Context, handler: Handler) {
        handler.postDelayed({
            Log.i(TAG, "in-process re-enable firing")
            BluetoothSwitch.enableIfOff(context)
        }, IN_PROCESS_DELAY_MS)

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            Log.w(TAG, "AlarmManager unavailable; only the in-process path can re-enable Bluetooth")
            return
        }

        val triggerAt = System.currentTimeMillis() + ALARM_DELAY_MS
        val pending = reEnablePendingIntent(context)

        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            Log.i(TAG, "exact alarm set for +${ALARM_DELAY_MS}ms")
        } else {
            // SCHEDULE_EXACT_ALARM is granted by default at targetSdk 32, so
            // this should not happen. If it does, the broadcast path is at
            // risk of leaving Bluetooth off, and the log says so loudly.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            Log.e(
                TAG,
                "exact alarms not permitted; inexact alarm may fire minutes late - " +
                    "Bluetooth could stay OFF if this process is killed"
            )
        }
    }

    // No cancel() on purpose. The flow never schedules a re-enable unless
    // disable() returned true, and FLAG_UPDATE_CURRENT with a fixed
    // REQUEST_CODE means a second run simply replaces the first alarm.
    private fun reEnablePendingIntent(context: Context): PendingIntent {
        // Explicit intent to the class: exempt from the Android 8+ ban on
        // implicit broadcasts to manifest-declared receivers.
        val intent = Intent(context, ReEnableReceiver::class.java)
            .setAction(ReEnableReceiver.ACTION_REENABLE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            // FLAG_IMMUTABLE is mandatory on API 31+, which is our minSdk.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
