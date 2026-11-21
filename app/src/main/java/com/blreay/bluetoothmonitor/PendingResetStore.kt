package com.blreay.bluetoothmonitor

import android.content.Context
import android.util.Log

/**
 * The slow phase's durable state, and the reason the alarm chain survives
 * MIUI reclaiming the process.
 *
 * Every tick of [ReEnableReceiver] may be the first thing this process has
 * ever done - the system starts it to deliver the alarm and nothing is in
 * memory. So the phase, the tick count and the debug flag all live here
 * rather than in a field.
 *
 * A single record on purpose: the app does one thing, and two concurrent
 * resets would fight over the same radio. Arming while already armed just
 * restarts the count, which is harmless because enable() is only ever called
 * at STATE_OFF.
 */
object PendingResetStore {

    private const val TAG = "BtReset.Store"
    private const val PREFS = "bt_reset_pending"
    private const val KEY_ARMED = "armed"
    private const val KEY_PHASE = "phase"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_WANTS_TOAST = "wants_toast"

    /**
     * @param wantsToast the `--ez toast true` debug flag, carried here because
     *        the tick that finally reports the outcome runs in a different
     *        process invocation than the one that started the reset, and so
     *        has no access to the original Intent.
     */
    data class Pending(
        val phase: ReEnablePolicy.Phase,
        val attempts: Int,
        val wantsToast: Boolean,
    )

    /** Arms a fresh chain: WAITING_OFF, zero ticks so far. */
    fun arm(context: Context, wantsToast: Boolean) {
        write(context, ReEnablePolicy.Phase.WAITING_OFF, 0, wantsToast)
        Log.i(TAG, "armed: phase=WAITING_OFF attempts=0 wantsToast=$wantsToast")
    }

    /**
     * @return null when nothing is armed, or when the stored phase cannot be
     *         parsed. A corrupt record must read as "nothing to do", never as
     *         a crash inside a BroadcastReceiver the system just started.
     */
    fun read(context: Context): Pending? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ARMED, false)) return null
        val rawPhase = prefs.getString(KEY_PHASE, null) ?: return null
        val phase = try {
            ReEnablePolicy.Phase.valueOf(rawPhase)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "unreadable phase '$rawPhase'; treating as not armed", e)
            return null
        }
        return Pending(
            phase = phase,
            attempts = prefs.getInt(KEY_ATTEMPTS, 0),
            wantsToast = prefs.getBoolean(KEY_WANTS_TOAST, false),
        )
    }

    fun write(
        context: Context,
        phase: ReEnablePolicy.Phase,
        attempts: Int,
        wantsToast: Boolean,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ARMED, true)
            .putString(KEY_PHASE, phase.name)
            .putInt(KEY_ATTEMPTS, attempts)
            .putBoolean(KEY_WANTS_TOAST, wantsToast)
            .apply()
    }

    /** Every give-up path and the success path must call this, or alarms leak. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        Log.i(TAG, "cleared")
    }
}
