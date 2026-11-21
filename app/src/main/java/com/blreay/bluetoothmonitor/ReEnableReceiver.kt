package com.blreay.bluetoothmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * One tick of the re-enable chain: read the radio, ask the policy, act.
 *
 * Declared in the manifest rather than registered at runtime, so the system
 * can start the process to deliver a tick even after MIUI has reclaimed it.
 * Reached only by an explicit PendingIntent aimed at this class, which is
 * why exported=false is safe and why the Android 8+ implicit-broadcast ban
 * does not apply.
 *
 * This may be the first thing the process has ever done, so nothing is read
 * from memory: the phase, the tick count and the debug flag all come from
 * [PendingResetStore].
 *
 * Every path either re-arms the chain or terminates it. There is no path
 * that returns without doing one of those, or the chain would stall with
 * Bluetooth off.
 */
class ReEnableReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REENABLE) {
            Log.w(TAG, "ignoring unexpected action ${intent.action}")
            return
        }

        // applicationContext: the store and the scheduler both outlive
        // onReceive, and a receiver context must not leak into a PendingIntent.
        val app = context.applicationContext

        val pending = PendingResetStore.read(app)
        if (pending == null) {
            // Normal after a success or a give-up: a tick that was already in
            // flight when the chain terminated. Not an error.
            Log.i(TAG, "nothing armed; this tick is stale, ignoring")
            return
        }

        val state = BluetoothSwitch.state(app)
        val action = ReEnablePolicy.decide(state, pending.phase, pending.attempts)
        val nextAttempts = pending.attempts + 1
        Log.i(
            TAG,
            "tick attempts=${pending.attempts} phase=${pending.phase} state=$state -> $action"
        )

        when (action) {
            ReEnablePolicy.Action.ENABLE_AND_RETRY -> {
                val adapter = BluetoothSwitch.adapter(app)
                if (adapter == null) {
                    // state() would have returned UNKNOWN and the policy would
                    // have given up, so this is a race, not a path. Bail safely,
                    // and report it as the give-up it effectively is: with no
                    // adapter the chain cannot continue, and ENABLE_AND_RETRY
                    // deliberately renders no notification of its own.
                    Log.e(TAG, "adapter vanished between state() and enable(); terminating")
                    terminate(
                        app,
                        pending.wantsToast,
                        ReEnablePolicy.Action.GIVE_UP_NO_ADAPTER,
                        nextAttempts,
                    )
                    return
                }
                // The return value is logged by BluetoothSwitch and then
                // deliberately ignored: whether enable() "worked" is answered
                // by the next tick reading STATE_ON, not by this boolean.
                BluetoothSwitch.enable(adapter)
                PendingResetStore.write(
                    app,
                    ReEnablePolicy.Phase.WAITING_ON,
                    nextAttempts,
                    pending.wantsToast
                )
                ReEnableScheduler.armNextTick(app)
            }

            ReEnablePolicy.Action.RETRY -> {
                PendingResetStore.write(app, pending.phase, nextAttempts, pending.wantsToast)
                ReEnableScheduler.armNextTick(app)
            }

            ReEnablePolicy.Action.DONE_RESTORED -> {
                Log.i(TAG, "Bluetooth is back on after $nextAttempts tick(s)")
                Notifier.bluetoothRestored(app)
                terminate(app, pending.wantsToast, action, nextAttempts)
            }

            ReEnablePolicy.Action.GIVE_UP_NO_ADAPTER,
            ReEnablePolicy.Action.GIVE_UP_NEVER_TURNED_OFF,
            ReEnablePolicy.Action.GIVE_UP_NEVER_TURNED_BACK_ON -> {
                // NEVER_TURNED_OFF means the radio never left STATE_ON: the
                // user tapped deny on the MIUI dialog, or MIUI refused.
                // Bluetooth is still on and usable - not a stranded state.
                // NEVER_TURNED_BACK_ON is the bad one: it is off and needs a
                // manual toggle.
                Log.e(
                    TAG,
                    "giving up ($action) after $nextAttempts tick(s), last state=$state" +
                        if (action == ReEnablePolicy.Action.GIVE_UP_NEVER_TURNED_BACK_ON) {
                            " - Bluetooth is OFF and needs a manual toggle"
                        } else {
                            ""
                        }
                )
                terminate(app, pending.wantsToast, action, nextAttempts)
            }
        }
    }

    /**
     * Clears the store and cancels the chain, then reports.
     *
     * The notification is unconditional; the toast still honours the debug flag.
     * This is the only place the slow phase reports anything, which is why both
     * give-up families and the success all route through here - a terminal path
     * that skipped it would leave Bluetooth off with nothing on screen to say so.
     */
    private fun terminate(
        context: Context,
        wantsToast: Boolean,
        action: ReEnablePolicy.Action,
        attempts: Int,
    ) {
        Notifier.outcome(context, wantsToast, action.name)
        RunNotification.slowPhase(context, action, attempts)
        PendingResetStore.clear(context)
        ReEnableScheduler.cancel(context)
    }

    companion object {
        const val ACTION_REENABLE = "com.blreay.bluetoothmonitor.action.REENABLE"
        private const val TAG = "BtReset.ReEnable"
    }
}
