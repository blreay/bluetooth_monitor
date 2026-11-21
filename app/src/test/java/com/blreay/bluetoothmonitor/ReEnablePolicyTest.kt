package com.blreay.bluetoothmonitor

import com.blreay.bluetoothmonitor.ReEnablePolicy.Action
import com.blreay.bluetoothmonitor.ReEnablePolicy.MAX_ATTEMPTS
import com.blreay.bluetoothmonitor.ReEnablePolicy.Phase.WAITING_OFF
import com.blreay.bluetoothmonitor.ReEnablePolicy.Phase.WAITING_ON
import com.blreay.bluetoothmonitor.ReEnablePolicy.decide
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The slow phase's entire brain. Pure function, no Android, so every cell of
 * (phase x state) can be pinned down - v1 had zero coverage here and shipped
 * a re-enable path that could never work.
 */
class ReEnablePolicyTest {

    // --- WAITING_OFF: disable() returned true, waiting for the radio to go off

    @Test
    fun `off while waiting off is the only state that may enable`() {
        assertEquals(Action.ENABLE_AND_RETRY, decide(AdapterState.OFF, WAITING_OFF, 0))
    }

    @Test
    fun `turning off while waiting off must retry not enable`() {
        // This is the exact cell v1 got wrong: it called enable() here, hit
        // the documented "immediate problem", and never retried.
        assertEquals(Action.RETRY, decide(AdapterState.TURNING_OFF, WAITING_OFF, 0))
    }

    @Test
    fun `still on while waiting off retries`() {
        assertEquals(Action.RETRY, decide(AdapterState.ON, WAITING_OFF, 3))
    }

    @Test
    fun `turning on while waiting off retries`() {
        assertEquals(Action.RETRY, decide(AdapterState.TURNING_ON, WAITING_OFF, 0))
    }

    @Test
    fun `unknown state while waiting off gives up on the first tick`() {
        assertEquals(Action.GIVE_UP_NO_ADAPTER, decide(AdapterState.UNKNOWN, WAITING_OFF, 0))
    }

    // --- WAITING_ON: we saw OFF and called enable(), waiting for it to come up

    @Test
    fun `on while waiting on is done`() {
        assertEquals(Action.DONE_RESTORED, decide(AdapterState.ON, WAITING_ON, 4))
    }

    @Test
    fun `still off while waiting on enables again`() {
        assertEquals(Action.ENABLE_AND_RETRY, decide(AdapterState.OFF, WAITING_ON, 4))
    }

    @Test
    fun `turning on while waiting on retries`() {
        assertEquals(Action.RETRY, decide(AdapterState.TURNING_ON, WAITING_ON, 4))
    }

    @Test
    fun `turning off while waiting on retries`() {
        assertEquals(Action.RETRY, decide(AdapterState.TURNING_OFF, WAITING_ON, 4))
    }

    @Test
    fun `unknown state while waiting on gives up`() {
        assertEquals(Action.GIVE_UP_NO_ADAPTER, decide(AdapterState.UNKNOWN, WAITING_ON, 4))
    }

    // --- evaluation order

    @Test
    fun `success beats the attempt cap`() {
        // The tick that finally restores Bluetooth must not be reported as a
        // timeout just because it happened to be the last allowed one.
        assertEquals(Action.DONE_RESTORED, decide(AdapterState.ON, WAITING_ON, MAX_ATTEMPTS))
    }

    @Test
    fun `no adapter beats the attempt cap`() {
        assertEquals(
            Action.GIVE_UP_NO_ADAPTER,
            decide(AdapterState.UNKNOWN, WAITING_ON, MAX_ATTEMPTS)
        )
    }

    // --- the cap itself

    @Test
    fun `waiting off at the cap gives up as never turned off`() {
        assertEquals(
            Action.GIVE_UP_NEVER_TURNED_OFF,
            decide(AdapterState.TURNING_OFF, WAITING_OFF, MAX_ATTEMPTS)
        )
    }

    @Test
    fun `still on at the cap gives up instead of retrying forever`() {
        // Without the cap being checked before RETRY, a user who tapped
        // "deny" on the MIUI dialog would leave the alarm chain looping
        // once a second forever. This test is the reason the cap check sits
        // above the RETRY fallthrough.
        assertEquals(Action.GIVE_UP_NEVER_TURNED_OFF, decide(AdapterState.ON, WAITING_OFF, MAX_ATTEMPTS))
    }

    @Test
    fun `waiting on at the cap gives up as never turned back on`() {
        assertEquals(
            Action.GIVE_UP_NEVER_TURNED_BACK_ON,
            decide(AdapterState.OFF, WAITING_ON, MAX_ATTEMPTS)
        )
    }

    // --- boundary: one tick either side of the cap

    @Test
    fun `one tick below the cap still retries`() {
        assertEquals(
            Action.RETRY,
            decide(AdapterState.TURNING_OFF, WAITING_OFF, MAX_ATTEMPTS - 1)
        )
    }

    @Test
    fun `one tick below the cap still enables when waiting on`() {
        assertEquals(
            Action.ENABLE_AND_RETRY,
            decide(AdapterState.OFF, WAITING_ON, MAX_ATTEMPTS - 1)
        )
    }

    @Test
    fun `the cap is two minutes of one second ticks`() {
        // MAX_ATTEMPTS must stay larger than the time a human needs to read
        // and tap the MIUI "toggle Bluetooth" dialog. If it is too small the
        // policy gives up before the radio has even started turning off,
        // which is worse than v1: Bluetooth then goes off and never returns.
        assertEquals(120, MAX_ATTEMPTS)
    }
}
