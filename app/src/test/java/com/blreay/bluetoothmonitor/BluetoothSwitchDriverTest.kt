package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class BluetoothSwitchDriverTest {
    private class Backend : SwitchBackend {
        var state = AdapterState.ON
        var capability = SwitchCapability(SwitchMode.SHIZUKU_SHELL, "SDK grant read")
        var result = SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)
        var ordinaryAccepted = true
        var preflights = 0
        var privilegedCalls = 0
        var ordinaryCalls = 0
        var queued: (() -> Unit)? = null
        var defer = false
        override fun readState(lease: CommandLease, callback: (AdapterState) -> Unit) { callback(state) }
        override fun preflight(lease: CommandLease, budgetMs: Long, callback: (SwitchCapability) -> Unit) {
            preflights++
            if (defer) queued = { callback(capability) } else callback(capability)
        }
        override fun privileged(enabled: Boolean, lease: CommandLease, budgetMs: Long, callback: (SwitchOutcome) -> Unit) {
            privilegedCalls++
            callback(result)
        }
        override fun ordinary(enabled: Boolean, lease: CommandLease, callback: (SwitchOutcome) -> Unit) {
            ordinaryCalls++
            callback(if (ordinaryAccepted) SwitchOutcome.submitted(SwitchMode.ORDINARY)
                else SwitchOutcome.rejected(SwitchMode.ORDINARY, "OEM refused"))
        }
    }

    @Test fun `every actual off and on freshly preflight shizuku`() {
        val b = Backend()
        val driver = BluetoothSwitchDriver(b)
        driver.execute(false, CommandLease.foreground()) { }
        b.state = AdapterState.OFF
        driver.execute(true, CommandLease.foreground()) { }
        assertEquals(2, b.preflights)
        assertEquals(2, b.privilegedCalls)
        assertEquals(0, b.ordinaryCalls)
    }

    @Test fun `unavailable denied unsupported and bind failure automatically use ordinary with reason`() {
        for (reason in listOf("not connected", "denied", "version unsupported", "bind failed", "quarantined")) {
            val b = Backend()
            b.capability = SwitchCapability(null, reason)
            var outcome: SwitchOutcome? = null
            BluetoothSwitchDriver(b).execute(false, CommandLease.foreground()) { outcome = it }
            assertEquals(0, b.privilegedCalls)
            assertEquals(1, b.ordinaryCalls)
            assertTrue(outcome!!.reason.contains(reason))
            assertEquals(SwitchMode.ORDINARY, outcome!!.mode)
        }
    }

    @Test fun `submitted privilege never duplicates ordinary and uncertainty never replays off`() {
        for (result in listOf(SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL),
            SwitchOutcome.uncertain(SwitchMode.SHIZUKU_SHELL, "RPC native may remain in flight"))) {
            val b = Backend()
            b.result = result
            BluetoothSwitchDriver(b).execute(false, CommandLease.foreground()) { }
            assertEquals(0, b.ordinaryCalls)
        }
    }

    @Test fun `definitely not invoked privileged failure falls back immediately`() {
        val b = Backend()
        b.result = SwitchOutcome.notInvoked("service terminal before spawn")
        var outcome: SwitchOutcome? = null
        BluetoothSwitchDriver(b).execute(false, CommandLease.foreground()) { outcome = it }
        assertEquals(1, b.ordinaryCalls)
        assertTrue(outcome!!.reason.contains("terminal before spawn"))
    }

    @Test fun `this call not invoked while older service command busy is still unsafe to replay`() {
        val b = Backend()
        b.result = SwitchOutcome(SwitchOutcome.Status.NOT_INVOKED, SwitchMode.NONE, "older command busy", false)
        BluetoothSwitchDriver(b).execute(false, CommandLease.foreground()) { }
        assertEquals(0, b.ordinaryCalls)
    }

    @Test fun `already target or transitioning towards target only observe no duplicate command`() {
        for (state in listOf(AdapterState.OFF, AdapterState.TURNING_OFF)) {
            val b = Backend()
            b.state = state
            BluetoothSwitchDriver(b).execute(false, CommandLease.foreground()) { }
            assertEquals(0, b.ordinaryCalls + b.privilegedCalls)
        }
    }

    @Test fun `expired queued preflight may not later invoke ordinary or privileged`() {
        val b = Backend()
        b.defer = true
        val lease = CommandLease.foreground()
        BluetoothSwitchDriver(b).execute(false, lease) { }
        lease.cancel()
        b.queued!!.invoke()
        assertEquals(0, b.privilegedCalls + b.ordinaryCalls)
    }

    @Test fun `stable wrong state fallback still fresh checks but only invokes same target ordinary`() {
        val b = Backend()
        BluetoothSwitchDriver(b).execute(false, CommandLease.foreground(), ordinaryOnly = true) { }
        assertEquals(1, b.preflights)
        assertEquals(0, b.privilegedCalls)
        assertEquals(1, b.ordinaryCalls)
    }
}
