package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class ShizukuAuthorizationTest {
    @Test fun `status read starts unknown with no toggle availability`() {
        val auth = ShizukuAuthorization()
        auth.binderReceived(true)
        auth.serviceConnected()
        assertTrue(auth.canToggle)
        auth.beginStatusRead()
        assertFalse(auth.statusKnown)
        assertFalse(auth.granted)
        assertFalse(auth.canToggle)
    }

    @Test fun `timed out status read cannot publish a late grant`() {
        val auth = ShizukuAuthorization()
        val token = auth.beginStatusRead()
        assertTrue(auth.timeoutStatusRead(token))
        assertFalse(auth.finishStatusRead(token, true, true))
        assertFalse(auth.statusKnown)
        assertFalse(auth.granted)
        assertFalse(auth.canToggle)
    }

    @Test fun `superseded status completion cannot clobber a newer grant`() {
        val auth = ShizukuAuthorization()
        val old = auth.beginStatusRead()
        auth.cancelStatusRead()
        val current = auth.beginStatusRead()
        assertTrue(auth.finishStatusRead(current, true, true))
        assertFalse(auth.finishStatusRead(old, true, false))
        assertTrue(auth.statusKnown)
        assertTrue(auth.granted)
    }

    @Test fun `a stale timeout cannot revoke newer authorization and readiness`() {
        val auth = ShizukuAuthorization()
        val old = auth.beginStatusRead()
        auth.cancelStatusRead()
        val current = auth.beginStatusRead()
        assertTrue(auth.finishStatusRead(current, true, true))
        auth.serviceConnected()
        assertFalse(auth.timeoutStatusRead(old))
        assertTrue(auth.canToggle)
    }

    @Test fun `no grant means no toggle even with a connected service`() {
        val auth = ShizukuAuthorization()
        auth.binderReceived(false)
        auth.serviceConnected()
        assertFalse(auth.canToggle)
        assertFalse(auth.granted)
    }

    @Test fun `grant is distinct from service ready`() {
        val auth = ShizukuAuthorization()
        auth.binderReceived(true)
        assertTrue(auth.granted)
        assertFalse(auth.canToggle)
        auth.serviceConnected()
        assertTrue(auth.canToggle)
    }

    @Test fun `denied permission is not success`() {
        val auth = ShizukuAuthorization()
        auth.binderReceived(false)
        auth.permissionResult(false)
        assertFalse(auth.granted)
        assertFalse(auth.canToggle)
    }

    @Test fun `connection failure does not misreport an actual grant`() {
        val auth = ShizukuAuthorization()
        auth.binderReceived(true)
        auth.serviceConnected()
        auth.serviceDisconnected()
        assertTrue(auth.granted)
        assertFalse(auth.canToggle)
    }

    @Test fun `binder death revokes availability and restart requires fresh readiness`() {
        val auth = ShizukuAuthorization()
        auth.binderReceived(true)
        auth.serviceConnected()
        auth.binderDied()
        assertFalse(auth.canToggle)
        assertFalse(auth.granted)
        auth.binderReceived(true)
        assertFalse(auth.canToggle)
    }
}
