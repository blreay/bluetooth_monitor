package com.blreay.bluetoothmonitor

/**
 * The slow phase's decision table: given what the radio is doing right now,
 * what the chain is waiting for, and how many ticks have already passed,
 * decide the single next action.
 *
 * Deliberately free of any android.* import so it is unit-testable on a
 * plain JVM. The framework int from BluetoothAdapter.getState() is mapped to
 * [AdapterState] by [BluetoothSwitch.state] before it reaches here.
 *
 * The invariant this class exists to enforce: enable() is only ever called
 * when the state is exactly OFF. Never during TURNING_OFF - that is what v1
 * did, and the framework documents enable() as returning false on an
 * "immediate problem", so the call was silently rejected and nothing retried.
 */
object ReEnablePolicy {

    /** What the alarm chain is currently waiting to observe. */
    enum class Phase {
        /** disable() returned true; waiting for the radio to actually go off. */
        WAITING_OFF,

        /** We saw OFF and called enable(); waiting for the radio to come up. */
        WAITING_ON,
    }

    enum class Action {
        /** Call enable(), move to WAITING_ON, count the tick, arm the next one. */
        ENABLE_AND_RETRY,

        /** Count the tick and arm the next one. Never calls enable(). */
        RETRY,

        /** Bluetooth is back on. Toast, clear the store, cancel the chain. */
        DONE_RESTORED,

        /** No adapter, or getState() is unusable. Nothing can be done. */
        GIVE_UP_NO_ADAPTER,

        /** The cap expired while still waiting for OFF - the user denied, or MIUI refused. */
        GIVE_UP_NEVER_TURNED_OFF,

        /** The cap expired while still waiting for ON - enable() is being rejected. */
        GIVE_UP_NEVER_TURNED_BACK_ON,
    }

    /**
     * 120 ticks x 1s = a two minute ceiling.
     *
     * This must stay larger than the time a human needs to read and tap the
     * MIUI "toggle Bluetooth" permission dialog. Set it too low and the chain
     * gives up before the radio has started turning off, then Bluetooth goes
     * off and never comes back - strictly worse than the bug being fixed.
     */
    const val MAX_ATTEMPTS = 120

    /**
     * Order matters, and both orderings are covered by tests:
     *
     * 1. UNKNOWN first - no adapter means no tick can ever make progress.
     * 2. Success before the cap - otherwise the tick that finally restores
     *    Bluetooth would be reported as a timeout.
     * 3. The cap before the RETRY fallthrough - otherwise a user who tapped
     *    "deny" leaves the chain arming a new alarm every second forever.
     */
    fun decide(state: AdapterState, phase: Phase, attempts: Int): Action {
        if (state == AdapterState.UNKNOWN) return Action.GIVE_UP_NO_ADAPTER
        if (phase == Phase.WAITING_ON && state == AdapterState.ON) return Action.DONE_RESTORED
        if (attempts >= MAX_ATTEMPTS) {
            return if (phase == Phase.WAITING_OFF) {
                Action.GIVE_UP_NEVER_TURNED_OFF
            } else {
                Action.GIVE_UP_NEVER_TURNED_BACK_ON
            }
        }
        return if (state == AdapterState.OFF) Action.ENABLE_AND_RETRY else Action.RETRY
    }
}
