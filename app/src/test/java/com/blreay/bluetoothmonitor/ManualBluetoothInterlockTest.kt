package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class ManualBluetoothInterlockTest {
    @Test fun `manual claim invalidates an old legacy scan`() {
        val lock = ManualBluetoothInterlock()
        val old = lock.legacyToken()!!
        assertNotNull(lock.claimManual())
        assertFalse(lock.isLegacyCurrent(old))
    }

    @Test fun `new legacy runs are blocked during a manual operation`() {
        val lock = ManualBluetoothInterlock()
        lock.claimManual()
        assertTrue(lock.manualActive)
        assertNull(lock.legacyToken())
        assertNull(lock.claimManual())
    }

    @Test fun `release does not revive old scan tokens`() {
        val lock = ManualBluetoothInterlock()
        val old = lock.legacyToken()!!
        val manual = lock.claimManual()!!
        lock.releaseManual(manual)
        assertFalse(lock.isLegacyCurrent(old))
        assertTrue(lock.isLegacyCurrent(lock.legacyToken()!!))
    }

    @Test fun `stale terminal callback cannot release a newer manual operation`() {
        val lock = ManualBluetoothInterlock()
        val first = lock.claimManual()!!
        lock.releaseManual(first)
        val second = lock.claimManual()!!
        lock.releaseManual(first)
        assertTrue(lock.isManualCurrent(second))
        assertTrue(lock.manualActive)
        lock.releaseManual(second)
        assertFalse(lock.manualActive)
    }
}
