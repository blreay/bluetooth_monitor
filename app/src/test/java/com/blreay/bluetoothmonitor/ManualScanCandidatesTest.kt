package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class ManualScanCandidatesTest {
    private val identity = ManualScanIdentity("scan", "command")
    private fun operation(id: ManualScanIdentity = identity, action: ManualAction = ManualAction.SCAN) =
        ManualOperation(id.operationId, id.commandId, action = action)
    private fun packet(mac: String = "AA:BB", name: String? = " raw NAME ") = ManualScanCandidate(mac, name)

    @Test fun `accepted current scan publishes exact raw candidate name`() {
        val candidates = ManualScanCandidates()
        assertTrue(candidates.begin(operation()))
        assertTrue(candidates.update(identity, listOf(packet(name = "  raw NAME  "))))
        assertEquals(listOf(packet(name = "  raw NAME  ")), candidates.snapshot().devices)
    }

    @Test fun `cold candidates are empty and have no operation or durable result hydration`() {
        val previous = ManualScanCandidates()
        previous.begin(operation())
        previous.update(identity, listOf(packet()))
        previous.close(identity)
        val cold = ManualScanCandidates().snapshot()
        assertNull(cold.identity)
        assertTrue(cold.devices.isEmpty())
        assertFalse(cold.closed)
        assertFalse(cold.overflow)
    }

    @Test fun `new accepted scan clears the previous final snapshot and overflow`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        candidates.update(identity, listOf(packet()), overflow = true)
        assertTrue(candidates.close(identity))
        val next = ManualScanIdentity("next-scan", "next-command")
        assertTrue(candidates.begin(operation(next)))
        assertEquals(ManualScanCandidatesSnapshot(next), candidates.snapshot())
    }

    @Test fun `ON and OFF operations cannot clear scan candidates`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        candidates.update(identity, listOf(packet()))
        val before = candidates.snapshot()
        for (action in listOf(ManualAction.ON, ManualAction.OFF)) {
            assertFalse(candidates.begin(operation(ManualScanIdentity("other", action.name), action)))
            assertEquals(before, candidates.snapshot())
        }
    }

    @Test fun `a rejected click does not begin and leaves the current final selection intact`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        candidates.update(identity, listOf(packet()))
        candidates.close(identity)
        val before = candidates.snapshot()
        // Runtime calls begin only after actual SCAN admission, never from a rejected click.
        val admission = OperationAdmission(activeScan = true)
        if (admission.allowsNewOperation()) candidates.begin(operation(ManualScanIdentity("rejected", "rejected")))
        assertEquals(before, candidates.snapshot())
    }

    @Test fun `duplicate begin cannot erase live or terminal candidates for the same exact scan`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        candidates.update(identity, listOf(packet()))
        assertFalse(candidates.begin(operation()))
        assertEquals(listOf(packet()), candidates.snapshot().devices)
        candidates.close(identity)
        assertFalse(candidates.begin(operation()))
        assertTrue(candidates.snapshot().closed)
        assertEquals(listOf(packet()), candidates.snapshot().devices)
    }

    @Test fun `empty operation or command identity never starts a candidate lifecycle`() {
        for (id in listOf(ManualScanIdentity("", "command"), ManualScanIdentity("scan", ""), ManualScanIdentity(" ", "command"))) {
            val candidates = ManualScanCandidates()
            assertFalse(candidates.begin(operation(id)))
            assertNull(candidates.snapshot().identity)
        }
    }

    @Test fun `update and close need exact operation and command identity`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        candidates.update(identity, listOf(packet()))
        val before = candidates.snapshot()
        for (stale in listOf(identity.copy(operationId = "other"), identity.copy(commandId = "other"))) {
            assertFalse(candidates.update(stale, listOf(packet(name = "late")), true))
            assertFalse(candidates.close(stale))
            assertEquals(before, candidates.snapshot())
        }
    }

    @Test fun `old scan callbacks cannot overwrite or close a newly accepted scan`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        candidates.update(identity, listOf(packet()))
        val next = ManualScanIdentity("next", "next-command")
        candidates.begin(operation(next))
        candidates.update(next, listOf(packet(name = "new raw")))
        val before = candidates.snapshot()
        assertFalse(candidates.update(identity, listOf(packet(name = "stale"))))
        assertFalse(candidates.close(identity))
        assertEquals(before, candidates.snapshot())
    }

    @Test fun `closed final candidates remain when live controller result is cleared and reject later updates`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        val live = mutableListOf(packet())
        candidates.update(identity, live)
        assertTrue(candidates.close(identity))
        live.clear() // controller's live result is not this independently retained snapshot
        val final = candidates.snapshot()
        assertEquals(identity, final.identity)
        assertTrue(final.closed)
        assertEquals(listOf(packet()), final.devices)
        assertFalse(candidates.update(identity, emptyList()))
        assertFalse(candidates.close(identity))
        assertEquals(final, candidates.snapshot())
    }

    @Test fun `same name devices keep distinct addresses and selecting name never includes address`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        candidates.update(identity, listOf(packet("AA:01", "same"), packet("AA:02", "same")))
        assertEquals(listOf("AA:01", "AA:02"), candidates.snapshot().devices.map { it.mac })
        assertEquals(listOf("same", "same"), candidates.snapshot().devices.map { it.advertisementName })
        assertTrue(TargetSnapshot(1, "same").matches(candidates.snapshot().devices[1].advertisementName))
    }

    @Test fun `raw names retain whitespace and case while nameless placeholder is never a candidate name`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        val displayOnly = DiscoveredDevices.Device("AA:01", "未知设备", null, setOf("BLE"), advertisementName = null)
        candidates.update(identity, listOf(ManualScanCandidate(displayOnly.mac, displayOnly.advertisementName),
            packet("AA:02", "未知设备"), packet("AA:03", "  Band  "), packet("AA:04", "band"), packet("AA:05", "")))
        assertEquals(listOf(null, "未知设备", "  Band  ", "band", ""), candidates.snapshot().devices.map { it.advertisementName })
        assertFalse(TargetSnapshot(1, "未知设备").matches(candidates.snapshot().devices[0].advertisementName))
        assertTrue(TargetSnapshot(1, "未知设备").matches(candidates.snapshot().devices[1].advertisementName))
    }

    @Test fun `bounded snapshot deduplicates MAC without normalizing advertisement names`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        candidates.update(identity, listOf(packet("aa:bb", "before"), packet(" AA:BB ", " after "),
            packet("", "invalid address"), packet(" ", "invalid address")))
        assertEquals(listOf(packet("AA:BB", " after ")), candidates.snapshot().devices)
        assertFalse(candidates.snapshot().overflow)
    }

    @Test fun `at most one hundred devices are retained with truthful overflow and bounded deduplication`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        candidates.update(identity, (0 until 2500).map { packet("mac-$it", "name-$it") })
        assertEquals(100, candidates.snapshot().devices.size)
        assertTrue(candidates.snapshot().overflow)
        assertEquals("MAC-0", candidates.snapshot().devices.first().mac)
        assertEquals("MAC-99", candidates.snapshot().devices.last().mac)
        candidates.update(identity, (0 until 100).map { packet("mac-$it", "name-$it") })
        assertEquals(100, candidates.snapshot().devices.size)
        assertFalse(candidates.snapshot().overflow)
    }

    @Test fun `duplicates at capacity update existing address and do not manufacture overflow`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        val packets = (0 until 100).map { packet("mac-$it", "name-$it") } + packet("mac-0", null)
        candidates.update(identity, packets)
        assertEquals(100, candidates.snapshot().devices.size)
        assertNull(candidates.snapshot().devices.first().advertisementName)
        assertFalse(candidates.snapshot().overflow)
        candidates.update(identity, packets, overflow = true)
        assertTrue(candidates.snapshot().overflow)
    }

    @Test fun `returned snapshots and published lists are immutable and not backed by input collection`() {
        val candidates = ManualScanCandidates()
        candidates.begin(operation())
        val expected = listOf(packet(), packet("CC:DD", "second"))
        val input = expected.toMutableList()
        candidates.update(identity, input)
        val snapshot = candidates.snapshot()
        input.clear()
        assertEquals(expected, snapshot.devices)
        try {
            (snapshot.devices as MutableList<ManualScanCandidate>).clear()
            fail("published list must be immutable")
        } catch (_: UnsupportedOperationException) { }
        candidates.update(identity, listOf(packet(name = "new")))
        assertEquals(expected, snapshot.devices)
    }

    @Test fun `observer exceptions cannot change exact lifecycle or final retained candidates`() {
        var calls = 0
        val candidates = ManualScanCandidates { calls++; throw IllegalStateException("display failed") }
        assertTrue(candidates.begin(operation()))
        assertTrue(candidates.update(identity, listOf(packet())))
        assertFalse(candidates.update(identity.copy(commandId = "old"), emptyList()))
        assertTrue(candidates.close(identity))
        assertTrue(candidates.snapshot().closed)
        assertEquals(listOf(packet()), candidates.snapshot().devices)
        assertEquals(3, calls)
    }

    @Test fun `publication delivers immutable exact snapshots and unchanged update does not replay`() {
        val published = mutableListOf<ManualScanCandidatesSnapshot>()
        val candidates = ManualScanCandidates { published += it }
        candidates.begin(operation())
        candidates.update(identity, listOf(packet()))
        candidates.update(identity, listOf(packet()))
        candidates.close(identity)
        assertEquals(3, published.size)
        assertEquals(ManualScanCandidatesSnapshot(identity), published[0])
        assertEquals(listOf(packet()), published[1].devices)
        assertFalse(published[1].closed)
        assertTrue(published[2].closed)
    }

    @Test fun `update and close before an accepted scan are inert even with plausible ids`() {
        val candidates = ManualScanCandidates()
        assertFalse(candidates.update(identity, listOf(packet()), overflow = true))
        assertFalse(candidates.close(identity))
        assertEquals(ManualScanCandidatesSnapshot(), candidates.snapshot())
    }
}
