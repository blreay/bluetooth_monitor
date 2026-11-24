package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

/** Deterministic scheduling model: the real SDK's death callback finishes before its posted barrier. */
class ShizukuRegistrationBrokerTest {
    private class Owner : ShizukuRegistrationBroker.Owner<String> {
        val connected = mutableListOf<String>()
        var disconnected = 0
        val failures = mutableListOf<String>()
        override fun connected(endpoint: String) { connected += endpoint }
        override fun disconnected() { disconnected++ }
        override fun failed(message: String) { failures += message }
    }

    private class Harness {
        val barriers = mutableListOf<() -> Unit>()
        val destroyed = mutableListOf<String>()
        val broker = ShizukuRegistrationBroker<String>({ barriers += it }, { destroyed += it })
        fun barrier() { val action = barriers.removeAt(0); action() }
    }

    @Test fun `only one SDK registration is admitted at a time`() {
        val h = Harness()
        val first = requireNotNull(h.broker.acquire(Owner()))
        assertTrue(h.broker.isCurrent(first))
        assertNull(h.broker.acquire(Owner()))
    }

    @Test fun `reconfirming a known live slot keeps its owner and registration without cleanup`() {
        val h = Harness()
        val owner = Owner()
        val slot = requireNotNull(h.broker.acquire(owner))
        val endpoint = "current-service"
        h.broker.connected(slot, endpoint)
        h.broker.managementFinished(slot)
        repeat(3) { assertTrue(h.broker.canReconfirm(slot, endpoint)) }
        assertTrue(h.broker.isCurrent(slot))
        assertSame(owner, slot.owner)
        assertTrue(h.destroyed.isEmpty())
        assertNull(h.broker.acquire(Owner()))
        assertEquals(listOf(endpoint), owner.connected)
    }

    @Test fun `reconfirmation rejects another endpoint and a retired generation`() {
        val h = Harness()
        val slot = requireNotNull(h.broker.acquire(Owner()))
        val endpoint = "current-service"
        h.broker.connected(slot, endpoint)
        assertTrue(h.broker.canReconfirm(slot, endpoint))
        assertFalse(h.broker.canReconfirm(slot, String(endpoint.toCharArray())))
        assertTrue(h.broker.retire(slot))
        assertFalse(h.broker.canReconfirm(slot, endpoint))
    }

    @Test fun `never connected timeout remains a bounded quarantine despite management completion`() {
        val h = Harness()
        val owner = Owner()
        val slot = requireNotNull(h.broker.acquire(owner))
        assertTrue(h.broker.retire(slot))
        h.broker.managementFinished(slot) // bind returned
        h.broker.managementFinished(slot) // remove returned; neither proves local SDK cache retirement
        assertTrue(h.broker.quarantined)
        repeat(100) { assertNull(h.broker.acquire(Owner())) }
        assertFalse(h.broker.isCurrent(slot))
        assertFalse(h.broker.retire(slot)) // no growing cleanup population on repeated lifecycle stops
    }

    @Test fun `late connected endpoint is destroyed and never delivered to a retired owner`() {
        val h = Harness()
        val owner = Owner()
        val slot = requireNotNull(h.broker.acquire(owner))
        assertTrue(h.broker.retire(slot))
        h.broker.connected(slot, "late-old-service")
        assertEquals(listOf("late-old-service"), h.destroyed)
        assertTrue(owner.connected.isEmpty())
        assertNull(slot.owner) // the quarantined SDK slot cannot keep an Activity callback alive
    }

    @Test fun `retirement destroys an already observed endpoint and revokes owner immediately`() {
        val h = Harness()
        val owner = Owner()
        val slot = requireNotNull(h.broker.acquire(owner))
        h.broker.connected(slot, "service")
        assertEquals(listOf("service"), owner.connected)
        assertTrue(h.broker.retire(slot))
        assertEquals(listOf("service"), h.destroyed)
        assertNull(slot.owner)
        assertFalse(h.broker.isCurrent(slot))
        h.broker.disconnected(slot)
        assertEquals(0, owner.disconnected)
    }

    @Test fun `completed management still waits for posted SDK death cleanup barrier`() {
        val h = Harness()
        val slot = requireNotNull(h.broker.acquire(Owner()))
        assertTrue(h.broker.retire(slot))
        h.broker.managementFinished(slot)
        h.broker.managementFinished(slot)
        h.broker.disconnected(slot)
        assertNull(h.broker.acquire(Owner())) // callback returned is not yet a cleanup acknowledgment
        h.barrier()
        val next = requireNotNull(h.broker.acquire(Owner()))
        assertNotEquals(slot.generation, next.generation)
        assertTrue(h.broker.isCurrent(next))
    }

    @Test fun `completed SDK cleanup barrier still waits for both management calls`() {
        val h = Harness()
        val slot = requireNotNull(h.broker.acquire(Owner()))
        assertTrue(h.broker.retire(slot))
        h.broker.disconnected(slot)
        h.barrier()
        assertNull(h.broker.acquire(Owner()))
        h.broker.managementFinished(slot) // remove cannot overtake the in-flight bind
        assertNull(h.broker.acquire(Owner()))
        h.broker.managementFinished(slot)
        assertNotNull(h.broker.acquire(Owner()))
    }

    @Test fun `stale release barrier management and endpoint cannot alter a newer generation`() {
        val h = Harness()
        val old = requireNotNull(h.broker.acquire(Owner()))
        assertTrue(h.broker.retire(old))
        h.broker.disconnected(old)
        h.broker.disconnected(old) // even a duplicate queued acknowledgment must be stale-safe
        h.broker.managementFinished(old)
        h.broker.managementFinished(old)
        h.barrier()
        val owner = Owner()
        val next = requireNotNull(h.broker.acquire(owner))
        h.barrier()
        h.broker.managementFinished(old)
        assertFalse(h.broker.retire(old))
        h.broker.connected(old, "old-endpoint")
        h.broker.disconnected(old)
        assertTrue(h.broker.isCurrent(next))
        assertNull(h.broker.acquire(Owner()))
        assertTrue(owner.connected.isEmpty())
        assertEquals(listOf("old-endpoint"), h.destroyed)
    }

    @Test fun `management failure is delivered only while that owner retains authority`() {
        val h = Harness()
        val owner = Owner()
        val slot = requireNotNull(h.broker.acquire(owner))
        h.broker.managementFinished(slot, "bind failed")
        assertEquals(listOf("bind failed"), owner.failures)
        assertTrue(h.broker.retire(slot))
        h.broker.managementFinished(slot, "late remove failure")
        assertEquals(listOf("bind failed"), owner.failures)
        assertTrue(h.broker.quarantined)
    }
}
