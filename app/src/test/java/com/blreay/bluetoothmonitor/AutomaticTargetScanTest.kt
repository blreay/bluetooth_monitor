package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class AutomaticTargetScanTest {
    @Test fun `automatic packet window begins at actual entry and never extends on delayed startup receipt`() {
        val window = AutomaticPacketWindow(8_000L)
        window.captureEntry(1_000_000_000L)
        assertTrue(window.admit(1_100_000_000L, 1_200_000_000L, 1_300_000_000L))
        assertEquals(700L, window.remainingMs(8_300_000_000L))
        assertFalse(window.admit(1_100_000_000L, 1_200_000_000L, 9_000_000_000L))
        assertNull(window.remainingMs(9_000_000_000L))
    }
    @Test fun `receiver automatic observation preserves its six second bound and rejects pre entry nameless timing evidence`() {
        val window = AutomaticPacketWindow(6_000L); window.captureEntry(1_000_000_000L)
        assertFalse(window.admit(999_999_999L, 1_000_000_001L, 1_000_000_002L))
        assertFalse(window.admit(0L, 1_000_000_001L, 1_000_000_002L))
        assertFalse(window.admit(1_200_000_000L, 1_100_000_000L, 1_300_000_000L))
        assertTrue(window.admit(6_999_999_998L, 6_999_999_999L, 6_999_999_999L))
        assertFalse(window.admit(6_999_999_998L, 6_999_999_999L, 7_000_000_000L))
    }
    @Test fun `presentation failures do not suppress final summary terminal or exact retirement callbacks`() {
        val effects = mutableListOf<String>()
        ManualScanPresentation.finish({
            ManualScanPresentation.finalSnapshot({ effects.add("devices"); throw IllegalStateException("observer") },
                { effects.add("summary"); throw IllegalStateException("summary") })
        }, { effects.add("finished") }, { effects.add("retire") })
        assertEquals(listOf("devices", "summary", "finished", "retire"), effects)
        effects.clear()
        try { ManualScanPresentation.finish({ effects.add("observe"); throw IllegalStateException("observer") },
            { effects.add("finished"); throw IllegalStateException("terminal observer") }, { effects.add("retire") }) }
        catch (_: IllegalStateException) { }
        assertEquals(listOf("observe", "finished", "retire"), effects)
    }

    @Test fun `failed scan completion snapshot cannot suppress done finite slice finish or exact retirement`() {
        val queue = java.util.ArrayDeque<() -> Unit>(); val effects = mutableListOf<String>()
        val retirement = ScanRegistrationRetirement({ 100L }, { queue.add(it); true },
            onRetired = { effects.add("exact-retired") }, onFailure = { _, _ -> fail("cleanup must remain available") })
        val ticket = retirement.claim("run") { effects.add("stop-exact-scanner"); true }!!
        assertTrue(retirement.start(ticket) { effects.add("native-entry"); true })
        retirement.retire(ticket) // the production scanner requests its retained cleanup before completion
        val host = MonitorHostLifetime { 100L }; host.keepAlive(true); host.started(host.beginStart()!!) { }
        val child = host.acquireChild(ForegroundAdmission.Ticket("run", "operation"))!!
        val work = MonitorHostedWork(host, { 100L }, { object : MonitorTaskLock {
            override fun acquire(timeoutMs: Long) { effects.add("lock") }
            override fun release() { effects.add("unlock") }
        } }, { _, _ -> }, {})
        val slice = work.initial(child)!!
        var continued = false
        try {
            AutomaticScanCompletionDelivery.finish({ effects.add("snapshot-producer"); throw IllegalStateException("snapshot") },
                { effects.add("done") }, { work.finish(slice); host.releaseChild(child); effects.add("settled") })
            continued = true
        } catch (_: IllegalStateException) { }
        queue.removeFirst()()
        assertTrue("post-scan presentation must not interrupt the coordinator handoff", continued)
        assertEquals(listOf("native-entry", "lock", "snapshot-producer", "done", "unlock", "settled", "stop-exact-scanner", "exact-retired"), effects)
        assertNull(work.activeSlice()); assertFalse(slice.lease.valid()); assertFalse(host.hasChild()); assertFalse(retirement.blocked())
    }
    @Test fun `throwing scan done still performs exact finite work settlement`() {
        val effects = mutableListOf<String>()
        try {
            AutomaticScanCompletionDelivery.finish({ effects.add("observe") },
                { effects.add("done"); throw IllegalStateException("consumer") }, { effects.add("settle") })
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("observe", "done", "settle"), effects)
    }
    @Test fun `collector retains raw advertisement name independently of trimmed display`() {
        val devices = DiscoveredDevices(100)
        devices.add("aa", "  Exact Band  ", -40, "BLE")
        assertEquals("  Exact Band  ", devices.snapshot().single().advertisementName)
        assertEquals("Exact Band", devices.snapshot().single().name)
    }

    @Test fun `summary uses frozen raw full name and never suffix or prefix`() {
        val observation = ScanSummaryObservation(TargetSnapshot(7, "  Exact Band  "))
        observation.admitted("11:22:33:44:35:BA", "Xiaomi Smart Band 8 35BA")
        observation.admitted("AA", "Exact Band")
        assertEquals(false, observation.close(2, false, true, "成功")!!.targetSeen)
        val exact = ScanSummaryObservation(TargetSnapshot(7, "  Exact Band  "))
        exact.admitted("AA", "  Exact Band  ")
        assertEquals(true, exact.close(1, false, true, "成功")!!.targetSeen)
    }

    @Test fun `unknown configured target stays unknown even during real manual observation`() {
        val observation = ScanSummaryObservation(null)
        observation.admitted("11:22:33:44:35:BA", "Xiaomi Smart Band 8 35BA")
        assertNull(observation.close(1, false, true, "成功")!!.targetSeen)
    }

    @Test fun `target after collector cap latches before storage and nameless current packet does not match`() {
        val collector = DiscoveredDevices(100)
        val target = TargetSnapshot(3, "Band")
        val observation = ScanSummaryObservation(target)
        repeat(100) { collector.add("device-$it", "Other", -40, "BLE") }
        observation.admitted("101", "Band")
        collector.add("101", "Band", -40, "BLE")
        assertEquals(100, collector.snapshot().size)
        assertEquals(true, observation.close(100, collector.overflow, true, "成功")!!.targetSeen)
        collector.clear(); collector.add("AA", "Band", -40, "BLE"); collector.add("AA", null, -41, "BLE")
        assertEquals("Band", collector.snapshot().single().advertisementName)
        assertFalse(target.matches(null))
    }
}
