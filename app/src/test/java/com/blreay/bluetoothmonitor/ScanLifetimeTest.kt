package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ForegroundAdmissionTest {
    private class Fixture {
        var now = 0L
        val retired = mutableListOf<String>()
        val controllers = mutableSetOf<String>()
        val ownership = RunOwnership()
        val admission = ForegroundAdmission({ now }, 4_000L) { id ->
            retired.add(id)
            controllers.remove(id)
        }
        fun window(id: String) = admission.scanWindow(id, 8_000L, 6_000L)
    }

    @Test fun `actual service lifetime cannot be finished by wrong startId owner or old ticket`() {
        val service = ExactServiceLifetime()
        val manual = ForegroundAdmission.Ticket("operation", "ticket", ForegroundAdmission.Owner.MANUAL)
        assertTrue(service.adopt(manual, 7))
        assertFalse(service.adopt(manual, 8))
        assertFalse(service.finish(manual, 8))
        assertFalse(service.finish(manual.copy(owner = ForegroundAdmission.Owner.AUTOMATIC), 7))
        assertEquals(ExactServiceLifetime.Owned(manual, 7), service.owned)
        assertTrue(service.finish(manual, 7))
        val next = ForegroundAdmission.Ticket("next", "next-ticket")
        assertTrue(service.adopt(next, 9))
        assertFalse(service.finish(manual, 7))
        assertEquals(next, service.owned!!.ticket)
    }
    @Test fun `foreground manual and automatic tickets cannot borrow each others owner even with identical run token`() {
        val f = Fixture()
        val manual = f.admission.begin("same-id", ForegroundAdmission.Owner.MANUAL)!!
        assertEquals(ForegroundAdmission.Owner.MANUAL, manual.owner)
        val automatic = manual.copy(owner = ForegroundAdmission.Owner.AUTOMATIC)
        assertFalse(f.admission.current(automatic))
        assertFalse(f.admission.accept(automatic))
        assertNull(f.admission.begin("same-id", ForegroundAdmission.Owner.AUTOMATIC))
        assertTrue(f.admission.accept(manual))
        assertFalse(f.admission.end(automatic))
        assertTrue(f.admission.admitted(manual))
    }
    @Test fun `startup ready before service admission cannot choose inline scan and success chooses eight seconds`() {
        val f = Fixture()
        assertTrue(f.ownership.acquire("run"))
        val ticket = requireNotNull(f.admission.begin("run"))
        assertNull("submitted FGS start is still pending, not an inline scan owner", f.window("run"))
        assertTrue(f.admission.accept(ticket))
        assertEquals(8_000L, f.window("run"))
    }

    @Test fun `promotion failure retires exact controller before next launch can own a scan`() {
        val f = Fixture()
        f.ownership.acquire("old")
        val old = requireNotNull(f.admission.begin("old"))
        f.controllers.add("old") // defensive cleanup of an already present old controller
        assertTrue(f.admission.reject(old))
        assertTrue("failed admission must not abandon its controller", f.controllers.isEmpty())
        assertEquals(listOf("old"), f.retired)
        f.ownership.release("old")
        assertTrue(f.ownership.acquire("new"))
        val next = requireNotNull(f.admission.begin("new"))
        assertNull(f.window("new"))
        assertTrue(f.admission.accept(next))
        assertEquals(8_000L, f.window("new"))
    }

    @Test fun `stale success failure and destruction cannot retire a newer admission`() {
        val f = Fixture()
        val old = requireNotNull(f.admission.begin("old"))
        f.admission.reject(old)
        val next = requireNotNull(f.admission.begin("new"))
        f.controllers.add("new")
        f.retired.clear()
        assertFalse(f.admission.accept(old))
        assertFalse(f.admission.reject(old))
        assertFalse(f.admission.end(old))
        assertTrue(f.controllers.contains("new"))
        assertTrue(f.retired.isEmpty())
        assertTrue(f.admission.accept(next))
        assertEquals(8_000L, f.window("new"))
    }

    @Test fun `absent service callback expires exact pending admission without admitting a late callback`() {
        val f = Fixture()
        val old = requireNotNull(f.admission.begin("run"))
        f.controllers.add("run")
        f.now = 3_999L
        assertFalse(f.admission.expire(old))
        assertNull(f.window("run"))
        f.now = 4_000L
        assertTrue(f.admission.expire(old))
        assertTrue(f.controllers.isEmpty())
        assertFalse(f.admission.accept(old))
        val next = requireNotNull(f.admission.begin("next"))
        assertTrue(f.admission.accept(next))
        assertEquals(8_000L, f.window("next"))
    }

    @Test fun `same run reattempt has different ticket and cannot accept an old service intent`() {
        val f = Fixture()
        val old = requireNotNull(f.admission.begin("run"))
        assertTrue(f.admission.reject(old))
        val next = requireNotNull(f.admission.begin("run"))
        assertNotEquals(old, next)
        assertFalse(f.admission.current(old))
        assertFalse(f.admission.accept(old))
        assertTrue(f.admission.current(next))
        assertTrue(f.admission.accept(next))
    }

    @Test fun `recreated process cannot mistake an old durable run admission for its first new ticket`() {
        val oldProcess = Fixture()
        val old = requireNotNull(oldProcess.admission.begin("durable-run"))
        val newProcess = Fixture()
        val next = requireNotNull(newProcess.admission.begin("durable-run"))
        assertNotEquals(old, next)
        assertFalse(newProcess.admission.current(old))
        assertFalse(newProcess.admission.accept(old))
        assertFalse(newProcess.admission.reject(old))
        assertTrue(newProcess.admission.accept(next))
    }

    @Test fun `pending repeated handoff is coalesced and admitted run is exact`() {
        val f = Fixture()
        val ticket = requireNotNull(f.admission.begin("run"))
        assertEquals(ticket, f.admission.begin("run"))
        assertNull(f.admission.begin("other"))
        assertTrue(f.admission.waiting("run"))
        assertTrue(f.admission.accept(ticket))
        assertFalse(f.admission.waiting("run"))
        assertTrue(f.admission.admitted(ticket))
        assertFalse(f.admission.admitted(ForegroundAdmission.Ticket("other", ticket.token)))
        assertTrue(f.admission.end(ticket))
        assertEquals(listOf("run"), f.retired)
    }
}

class ScanRetirementTest {
    private class ManualWindowFixture {
        val window = ManualObservationWindow()
        val main = java.util.ArrayDeque<() -> Unit>()
        val cleanup = java.util.ArrayDeque<() -> Unit>()
        val accepted = mutableListOf<String>()
        val scheduled = mutableListOf<Long>()
        val retired = mutableListOf<ScanRegistrationRetirement.Ticket>()
        var now = at(0L)
        var expirations = 0
        var entryEventDelivered = false
        var stopWorks = true
        val retirement = ScanRegistrationRetirement({ now / 1_000_000L }, { cleanup.add(it); true },
            onRetired = { retired.add(it) }, onFailure = { _, _ -> })
        val ticket = retirement.claim("manual-window") { stopWorks }!!
        fun at(ms: Long) = 1_000_000_000L + ms * 1_000_000L
        fun expire() { expirations++; retirement.retire(ticket) }
        fun start() {
            retirement.start(ticket) {
                window.captureEntry(now)
                main.add { entryEventDelivered = true } // timestamp capture is not delayed with this UI event
                true
            }
        }
        fun packet(label: String, sampledMs: Long) = label to at(sampledMs)
        fun callback(values: List<Pair<String, Long>>) {
            AdvertisementDelivery.deliver(values, { now }, { main.add(it) }) { value, received ->
                if (window.admit(value.second, received, now, ::expire)) accepted.add(value.first)
            }
        }
        fun startResult() { window.startResult(now, ::expire) { scheduled.add(it) } }
        fun drainMain() { while (main.isNotEmpty()) main.removeFirst()() }
    }
    @Test fun `delayed entry event and start result cannot admit queued single or batch samples outside eight seconds`() {
        val f = ManualWindowFixture(); f.start()
        assertFalse(f.entryEventDelivered)
        f.now = f.at(9_000L); f.callback(listOf(f.packet("single-9s", 9_000L)))
        f.now = f.at(11_000L); f.callback(listOf(f.packet("batch-10s", 10_000L), f.packet("batch-11s", 11_000L)))
        f.main.add { f.startResult() } // start-result delivery is behind the queued advertisements
        f.now = f.at(12_000L); f.drainMain()
        assertTrue(f.entryEventDelivered)
        assertTrue("no out-of-window advertisement may reach the observed-device list", f.accepted.isEmpty())
        assertEquals(1, f.expirations); assertTrue(f.scheduled.isEmpty())
        assertTrue(f.retirement.blocked()); assertTrue(f.retired.isEmpty())
        f.cleanup.removeFirst()(); assertEquals(listOf(f.ticket), f.retired)
    }
    @Test fun `expired start result expires inline before queued advertisements instead of posting zero delay behind them`() {
        val f = ManualWindowFixture(); f.start(); f.drainMain()
        f.main.add { f.startResult() }
        f.now = f.at(9_000L); f.callback(listOf(f.packet("queued", 9_000L)))
        f.now = f.at(12_000L); f.main.removeFirst()()
        assertEquals("retirement must be requested during start-result delivery", 1, f.expirations)
        assertTrue(f.scheduled.isEmpty()); assertEquals(1, f.main.size)
        f.drainMain(); assertTrue(f.accepted.isEmpty()); assertEquals(1, f.expirations)
    }
    @Test fun `admission distinguishes sampled receipt and delivery times and conservatively rejects unverifiable packets`() {
        val f = ManualWindowFixture(); f.start(); f.drainMain()
        fun admit(sample: Long, receipt: Long, delivery: Long) = f.window.admit(sample, receipt, delivery, f::expire)
        assertTrue(admit(f.at(0L), f.at(1L), f.at(2L)))
        assertTrue(admit(f.at(7_999L), f.at(7_999L), f.at(7_999L)))
        assertFalse(admit(0L, f.at(2L), f.at(3L))) // unknown Android sample time
        assertFalse(admit(f.at(-1L), f.at(2L), f.at(3L))) // cached/pre-entry sample
        assertFalse(admit(f.at(4L), f.at(2L), f.at(3L))) // sample after callback receipt
        assertFalse(admit(f.at(1L), f.at(4L), f.at(3L))) // receipt after delivery
        assertFalse(admit(f.at(1L), f.at(-1L), f.at(3L)))
        assertEquals(0, f.expirations)
        assertFalse(admit(f.at(7_999L), f.at(7_999L), f.at(8_000L)))
        assertEquals(1, f.expirations)
        assertFalse(admit(f.at(1L), f.at(2L), f.at(12_000L))); assertEquals(1, f.expirations)
    }
    @Test fun `early packets delayed past deadline are rejected and actual entry never moves to main delivery`() {
        val f = ManualWindowFixture(); f.start()
        f.now = f.at(1_000L); f.callback(listOf(f.packet("early", 900L)))
        f.now = f.at(12_000L); f.drainMain()
        assertTrue(f.accepted.isEmpty()); assertEquals(1, f.expirations)
        val window = ManualObservationWindow()
        val scheduled = mutableListOf<Long>()
        window.captureEntry(f.at(0L)); window.captureEntry(f.at(3_000L)) // duplicate delivery cannot extend the first capture
        window.startResult(f.at(3_000L), { fail("still in the original window") }) { scheduled.add(it) }
        assertEquals(listOf(5_000L), scheduled)
        val missing = ManualObservationWindow()
        assertFalse(missing.admit(f.at(1L), f.at(2L), f.at(3L)) { fail("no entry proof") })
        var expired = false
        missing.startResult(f.at(3L), { expired = true }) { fail("missing entry must not allocate a new eight seconds") }
        assertTrue(expired)
    }
    @Test fun `in window batch preserves receipt and samples despite later entry event and start result delivery`() {
        val f = ManualWindowFixture(); f.start()
        f.now = f.at(2_000L)
        val packets = mutableListOf(f.packet("one", 1_000L), f.packet("two", 1_900L))
        f.callback(packets); packets.clear()
        f.main.add { f.startResult() }; f.now = f.at(3_000L); f.drainMain()
        assertEquals(listOf("one", "two"), f.accepted); assertEquals(listOf(5_000L), f.scheduled)
        assertEquals(0, f.expirations); assertTrue(f.retirement.blocked()); assertTrue(f.cleanup.isEmpty())
    }
    @Test fun `observation expiry during unreturned start does not end native authority or exact retirement fence`() {
        val f = ManualWindowFixture(); f.stopWorks = false
        var actualStartReturned = false
        f.retirement.start(f.ticket) {
            f.window.captureEntry(f.now)
            f.now = f.at(12_000L)
            assertFalse(f.window.admit(f.at(9_000L), f.at(10_000L), f.now, f::expire))
            assertEquals(1, f.expirations); assertFalse(actualStartReturned)
            assertTrue(f.retirement.blocked()); assertTrue(f.cleanup.isEmpty()); assertTrue(f.retired.isEmpty())
            assertNull(f.retirement.claim("replacement") { fail("unknown start cannot be replaced"); true })
            true
        }
        actualStartReturned = true
        assertEquals(1, f.cleanup.size); f.cleanup.removeFirst()()
        assertTrue(actualStartReturned); assertTrue(f.retirement.blocked()); assertTrue(f.retired.isEmpty())
        f.stopWorks = true; assertTrue(f.retirement.retryRetirement()); f.cleanup.removeFirst()()
        assertEquals(listOf(f.ticket), f.retired); assertFalse(f.retirement.blocked())
    }
    @Test fun `batch advertisement receipt remains original worker time across delayed main delivery`() {
        var now = 100L
        var pending: (() -> Unit)? = null
        val original = mutableListOf("first", "target", "third")
        val observed = mutableListOf<Pair<String, Long>>()
        AdvertisementDelivery.deliver(original, { now }, { pending = it }) { value, receivedAt -> observed.add(value to receivedAt) }
        assertTrue(observed.isEmpty())
        now = 10_000L; original.clear(); pending!!()
        assertEquals(listOf("first", "target", "third"), observed.map { it.first })
        assertEquals(listOf(100L, 100L, 100L), observed.map { it.second })
    }
    @Test fun `old automatic and same run serial success cannot clear or stop newer manual scanner owner`() {
        data class Scanner(val ticket: ScanRegistrationRetirement.Ticket)
        val owner = ExactScannerOwner<Scanner> { it.ticket }
        val old = Scanner(ScanRegistrationRetirement.Ticket("manual", 1))
        val newer = Scanner(ScanRegistrationRetirement.Ticket("manual", 2))
        owner.install("manual", old); owner.install("manual", newer)
        var calls = 0
        assertFalse(owner.retired(ScanRegistrationRetirement.Ticket("automatic", 3)) { calls++ })
        assertFalse(owner.retired(old.ticket) { calls++ })
        assertFalse(owner.retire("automatic") { calls++ })
        assertSame(newer, owner.current); assertEquals(0, calls)
        assertTrue(owner.retire("manual") { assertSame(newer, it); calls++ })
        assertTrue(owner.retired(newer.ticket) { assertSame(newer, it); calls++ })
        assertNull(owner.current); assertEquals(2, calls)
        assertFalse(owner.retired(newer.ticket) { fail("duplicate cleanup cannot be delivered") })
    }
    @Test fun `exact cleanup callback cannot clear replacement installed reentrantly`() {
        data class Scanner(val ticket: ScanRegistrationRetirement.Ticket)
        val owner = ExactScannerOwner<Scanner> { it.ticket }
        val old = Scanner(ScanRegistrationRetirement.Ticket("old", 1))
        val newer = Scanner(ScanRegistrationRetirement.Ticket("new", 2))
        owner.install("old", old)
        assertTrue(owner.retired(old.ticket) { owner.install("new", newer) })
        assertSame(newer, owner.current)
    }
    @Test fun `manual observed advertisements retain at most hundred devices and still update duplicates after overflow`() {
        val devices = DiscoveredDevices(100)
        repeat(150) { devices.add("address-$it", "advertisement-$it", -80, "BLE") }
        assertEquals(100, devices.snapshot().size)
        assertTrue(devices.overflow)
        devices.add("address-0", "Xiaomi Smart Band 8 35BA", -30, "BLE")
        assertEquals(100, devices.snapshot().size)
        assertEquals("Xiaomi Smart Band 8 35BA", devices.snapshot().first().name)
        assertEquals(-30, devices.snapshot().first().rssi)
    }
    @Test fun `exact retirement success signal only follows real cleanup or rejection before entry`() {
        val queue = java.util.ArrayDeque<() -> Unit>()
        val retired = mutableListOf<ScanRegistrationRetirement.Ticket>()
        var stopWorks = false
        val retirement = ScanRegistrationRetirement({ 1L }, { queue.add(it); true },
            onRetired = { retired.add(it) }, onFailure = { _, _ -> })
        val old = retirement.claim("manual") { stopWorks }!!
        retirement.start(old) { true }
        retirement.retire(old)
        assertTrue(retired.isEmpty())
        queue.removeFirst()()
        assertTrue(retired.isEmpty()); assertTrue(retirement.blocked())
        stopWorks = true; assertTrue(retirement.retryRetirement())
        queue.removeFirst()()
        assertEquals(listOf(old), retired)
        val next = retirement.claim("next") { true }!!
        retirement.retire(next)
        assertEquals(listOf(old, next), retired)
        assertFalse(retirement.start(next) { fail("retired ticket cannot enter"); true })
    }
    private class Fixture {
        var now = 0L
        var adapterBusy = true
        var enqueueWorks = true
        val cleanupQueue = java.util.ArrayDeque<() -> Unit>()
        val failures = mutableListOf<String>()
        val retirement = ScanRegistrationRetirement({ now }, { task ->
            if (!enqueueWorks) false else { cleanupQueue.add(task); true }
        }) { _, reason -> failures.add(reason) }
        fun drain() { while (cleanupQueue.isNotEmpty()) cleanupQueue.removeFirst().invoke() }
    }

    @Test fun `expired observer and occupied adapter cannot expire exact cleanup or admit replacement`() {
        val f = Fixture()
        var stops = 0
        val old = requireNotNull(f.retirement.claim("old") { stops++; true })
        assertTrue(f.retirement.start(old) { true })
        val lease = CommandLease({ f.now }, 9_000L)
        f.now = 9_000L
        lease.cancel()
        assertFalse(lease.valid())
        assertTrue(f.retirement.retire(old))
        assertTrue(f.retirement.unresolved(old))
        assertNull("retiring native registration still owns the only scan slot", f.retirement.claim("new") { true })
        f.now += 10_000L // exceeds the old cleanup admission deadline, but not cleanup authority
        assertEquals(10_000L, f.retirement.retirementAge(old))
        assertTrue(f.adapterBusy)
        f.adapterBusy = false // later adapter release must not discard the coalesced retirement
        f.drain()
        assertEquals(1, stops)
        assertFalse(f.retirement.unresolved(old))
        assertNotNull(f.retirement.claim("new") { true })
    }

    @Test fun `queued cleanup still executes after the old one second admission deadline`() {
        val f = Fixture()
        var stops = 0
        val ticket = requireNotNull(f.retirement.claim("old") { stops++; true })
        f.retirement.start(ticket) { true }
        f.retirement.retire(ticket)
        f.now = 10_001L
        f.drain()
        assertEquals("retirement cannot silently expire while queued", 1, stops)
    }

    @Test fun `duplicate teardown coalesces exact cleanup on an independent lane`() {
        val f = Fixture()
        var stops = 0
        val ticket = requireNotNull(f.retirement.claim("run") { stops++; true })
        f.retirement.start(ticket) { true }
        repeat(4) { assertTrue(f.retirement.retire(ticket)) }
        assertEquals(1, f.cleanupQueue.size)
        f.drain() // cleanup is allowed even while an ADAPTER read remains blocked
        assertTrue(f.adapterBusy)
        assertEquals(1, stops)
        assertFalse(f.retirement.unresolved(ticket))
        assertFalse(f.retirement.retire(ticket))
        assertTrue(f.cleanupQueue.isEmpty())
    }

    @Test fun `retirement before native start prevents late queued scan without cleanup or replacement collision`() {
        val f = Fixture()
        var starts = 0
        var stops = 0
        val old = requireNotNull(f.retirement.claim("old") { stops++; true })
        assertTrue(f.retirement.retire(old))
        val next = requireNotNull(f.retirement.claim("next") { true })
        assertFalse(f.retirement.start(old) { starts++; true })
        assertEquals(0, starts)
        assertEquals(0, stops)
        assertTrue(f.cleanupQueue.isEmpty())
        assertTrue(f.retirement.unresolved(next))
    }

    @Test fun `retired entered start is retained until it returns and then gets exact cleanup`() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var stops = 0
        val ticket = requireNotNull(f.retirement.claim("old") { stops++; true })
        val worker = Thread {
            f.retirement.start(ticket) { entered.countDown(); release.await(); true }
        }
        worker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            f.retirement.retire(ticket)
            assertTrue(f.cleanupQueue.isEmpty())
            assertNull(f.retirement.claim("next") { true })
            release.countDown()
            worker.join(1_000L)
            assertFalse(worker.isAlive)
            assertEquals(1, f.cleanupQueue.size)
            f.drain()
            assertEquals(1, stops)
            assertNotNull(f.retirement.claim("next") { true })
        } finally { release.countDown(); worker.join(1_000L) }
    }

    @Test fun `hung cleanup keeps registration fenced without replacement workers or duplicate jobs`() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ticket = requireNotNull(f.retirement.claim("old") { entered.countDown(); release.await(); true })
        f.retirement.start(ticket) { true }
        f.retirement.retire(ticket)
        val cleanup = f.cleanupQueue.removeFirst()
        val worker = Thread { cleanup() }
        worker.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(3) { f.retirement.retire(ticket); assertFalse(f.retirement.retryRetirement()) }
            assertTrue(f.cleanupQueue.isEmpty())
            assertNull(f.retirement.claim("next") { true })
            release.countDown()
            worker.join(1_000L)
            assertFalse(worker.isAlive)
            assertFalse(f.retirement.unresolved(ticket))
            assertNotNull(f.retirement.claim("next") { true })
        } finally { release.countDown(); worker.join(1_000L) }
    }

    @Test fun `permission race cleanup failure keeps exact registration until explicit successful retry`() {
        val f = Fixture()
        var granted = false
        var stops = 0
        val ticket = requireNotNull(f.retirement.claim("old") {
            stops++
            if (!granted) throw SecurityException("permission revoked")
            true
        })
        f.retirement.start(ticket) { true }
        f.retirement.retire(ticket)
        f.drain()
        assertTrue(f.retirement.unresolved(ticket))
        assertNull(f.retirement.claim("next") { true })
        assertEquals(1, f.failures.size)
        assertTrue(f.failures.single().contains("未确认"))
        f.retirement.retire(ticket)
        assertTrue(f.cleanupQueue.isEmpty()) // another teardown is not an automatic retry storm
        granted = true
        assertTrue(f.retirement.retryRetirement())
        assertFalse(f.retirement.retryRetirement())
        assertEquals(1, f.cleanupQueue.size)
        f.drain()
        assertEquals(2, stops)
        assertFalse(f.retirement.unresolved(ticket))
        assertNotNull(f.retirement.claim("next") { true })
    }

    @Test fun `cleanup queue rejection retains safety record and can later retry without a new scan`() {
        val f = Fixture()
        val ticket = requireNotNull(f.retirement.claim("old") { true })
        f.retirement.start(ticket) { true }
        f.enqueueWorks = false
        f.retirement.retire(ticket)
        assertTrue(f.retirement.unresolved(ticket))
        assertNull(f.retirement.claim("next") { true })
        assertTrue(f.failures.single().contains("未确认"))
        f.enqueueWorks = true
        assertTrue(f.retirement.retryRetirement())
        f.drain()
        assertFalse(f.retirement.unresolved(ticket))
    }

    @Test fun `failed or throwing scan start is not proof that native registration never existed`() {
        for (throws in listOf(false, true)) {
            val f = Fixture()
            var stops = 0
            val ticket = requireNotNull(f.retirement.claim("old") { stops++; true })
            assertFalse(f.retirement.start(ticket) { if (throws) throw SecurityException("revoked"); false })
            assertTrue(f.retirement.unresolved(ticket))
            assertNull(f.retirement.claim("next") { true })
            assertEquals(1, f.cleanupQueue.size)
            f.drain()
            assertEquals(1, stops)
            assertFalse(f.retirement.unresolved(ticket))
        }
    }

    @Test fun `old cleanup and explicit retry cannot stop a newer live registration`() {
        val f = Fixture()
        val old = requireNotNull(f.retirement.claim("old") { true })
        f.retirement.start(old) { true }
        f.retirement.retire(old)
        f.drain()
        var stops = 0
        val next = requireNotNull(f.retirement.claim("next") { stops++; true })
        f.retirement.start(next) { true }
        assertFalse(f.retirement.retire(old))
        assertFalse(f.retirement.retryRetirement())
        assertFalse(f.retirement.start(old) { fail("old start must not execute"); true })
        assertEquals(0, stops)
        assertTrue(f.retirement.unresolved(next))
    }
}

/** Injects readiness inline into the same startup/decision orchestration used by Android launch. */
class StartupLaunchSequenceTest {
    private enum class Handoff { PENDING, ACCEPT_INLINE, DENIED }
    private class Fixture {
        var now = 0L
        val ownership = RunOwnership()
        val admission = ForegroundAdmission({ now }, 4_000L) { }
        val events = mutableListOf<String>()
        val scans = mutableListOf<Long>()
        val recoveries = mutableListOf<String>()
        var ticket: ForegroundAdmission.Ticket? = null
        var laterReady: (() -> Unit)? = null
        var startupReady = false
        var setupReady = false
        private var setupPending = false
        private var pendingRecovery = false
        private var workRecorded = false
        private var id = ""

        fun launch(runId: String = "run", inline: Boolean = true, setup: Boolean = false,
                   nearby: Boolean = true, handoff: Handoff = Handoff.PENDING, pending: Boolean = false) {
            assertTrue(ownership.acquire(runId))
            id = runId
            setupPending = setup
            pendingRecovery = pending
            startupReady = false
            setupReady = false
            workRecorded = false
            ticket = null
            StartupLaunchSequence.dispatch<Unit>(
                startup = { ready -> events.add("probe"); if (inline) ready(Unit) else laterReady = { ready(Unit) } },
                decide = {
                    events.add("decision")
                    when {
                        setup -> events.add("setup")
                        !nearby -> { events.add("nearby-denied"); ownership.release(runId) }
                        else -> requestForeground(handoff)
                    }
                },
                ready = delivered@{
                    if (!ownership.current(runId)) return@delivered
                    startupReady = true
                    events.add("ready")
                    if (setupPending) setupReady = true else selectWork()
                },
            )
        }
        private fun requestForeground(handoff: Handoff) {
            events.add("admission")
            ticket = requireNotNull(admission.begin(id))
            when (handoff) {
                Handoff.ACCEPT_INLINE -> admit()
                Handoff.DENIED -> { admission.reject(ticket!!); events.add("restricted"); if (startupReady) selectWork() }
                Handoff.PENDING -> Unit
            }
        }
        fun admit() { assertTrue(admission.accept(ticket!!)); events.add("admitted"); selectWork() }
        fun failAdmission() { assertTrue(admission.reject(ticket!!)); ownership.release(id) }
        fun expireAdmission() { now = 4_000L; assertTrue(admission.expire(ticket!!)); ownership.release(id) }
        fun completeSetup(granted: Boolean, handoff: Handoff = Handoff.PENDING) {
            assertTrue("Nearby gate must wait actual startup readiness", setupReady)
            events.add("nearby-gate")
            setupPending = false
            if (granted) requestForeground(handoff) else ownership.release(id)
        }
        private fun selectWork() {
            if (!ownership.current(id) || setupPending || (!startupReady && !pendingRecovery) || workRecorded || admission.waiting(id)) return
            if (pendingRecovery) {
                recoveries.add(if (ticket?.let { admission.admitted(it) } == true) "foreground" else "restricted")
                events.add("recovery")
            } else {
                val window = admission.scanWindow(id, 8_000L, 6_000L) ?: return
                scans.add(window)
                events.add("scan:$window")
            }
            workRecorded = true
        }
    }

    @Test fun `inline startup completion waits launcher admission and asynchronous success chooses eight seconds`() {
        val f = Fixture()
        f.launch() // startup callback fires inline BEFORE the fake service submission returns
        assertEquals(listOf("probe", "decision", "admission", "ready"), f.events)
        assertTrue("readiness cannot start inline six second scan before admission", f.scans.isEmpty())
        f.admit()
        assertEquals(listOf(8_000L), f.scans)
    }

    @Test fun `inline startup and reentrant service acceptance still select service owned eight second scan`() {
        val f = Fixture()
        f.launch(handoff = Handoff.ACCEPT_INLINE)
        assertEquals(listOf(8_000L), f.scans)
        assertTrue(f.events.indexOf("scan:8000") > f.events.indexOf("admitted"))
        assertEquals(1, f.events.count { it.startsWith("scan:") })
    }

    @Test fun `inline startup with real handoff denial selects six seconds only after the decision`() {
        val f = Fixture()
        f.launch(handoff = Handoff.DENIED)
        assertEquals(listOf(6_000L), f.scans)
        assertTrue("restricted scan needs established denial, not missing admission state",
            f.events.indexOf("scan:6000") > f.events.indexOf("restricted"))
    }

    @Test fun `inline readiness and promotion failure leave no scan and next launch can use foreground owner`() {
        val f = Fixture()
        f.launch()
        f.failAdmission()
        assertTrue(f.scans.isEmpty())
        f.launch("next", handoff = Handoff.ACCEPT_INLINE)
        assertEquals(listOf(8_000L), f.scans)
    }

    @Test fun `inline readiness with missing admission callback cannot create a restricted scan`() {
        val f = Fixture()
        f.launch()
        val old = f.ticket!!
        f.expireAdmission()
        assertTrue(f.scans.isEmpty())
        assertFalse(f.admission.accept(old))
        assertFalse(f.ownership.current("run"))
    }

    @Test fun `inline startup remains before first setup nearby gate and later setup acceptance uses eight seconds`() {
        val f = Fixture()
        f.launch(setup = true, nearby = false)
        assertEquals("probe", f.events.first())
        assertTrue(f.setupReady)
        assertTrue(f.scans.isEmpty())
        assertNull(f.ticket)
        f.completeSetup(true, Handoff.ACCEPT_INLINE)
        assertTrue(f.events.indexOf("nearby-gate") > f.events.indexOf("ready"))
        assertEquals(listOf(8_000L), f.scans)
    }

    @Test fun `inline startup with previously denied nearby permission cannot scan before refusal branch`() {
        val f = Fixture()
        f.launch(nearby = false)
        assertTrue(f.scans.isEmpty())
        assertNull(f.ticket)
        assertFalse(f.ownership.current("run"))
        assertEquals(listOf("probe", "decision", "nearby-denied"), f.events)
    }

    @Test fun `asynchronous startup after service acceptance still starts only one service scan`() {
        val f = Fixture()
        f.launch(inline = false)
        f.admit()
        assertTrue(f.scans.isEmpty())
        f.laterReady!!.invoke()
        assertEquals(listOf(8_000L), f.scans)
    }

    @Test fun `pending durable recovery also waits launch decision and never becomes a new scan`() {
        val f = Fixture()
        f.launch(pending = true)
        assertTrue(f.recoveries.isEmpty())
        assertTrue(f.scans.isEmpty())
        f.admit()
        assertEquals(listOf("foreground"), f.recoveries)
        assertTrue(f.scans.isEmpty())
    }

    @Test fun `startup delivery is once even for duplicate inline and late callbacks`() {
        val events = mutableListOf<String>()
        var later: (() -> Unit)? = null
        StartupLaunchSequence.dispatch<Unit>(
            startup = { ready -> ready(Unit); ready(Unit); later = { ready(Unit) } },
            decide = { events.add("decision") },
            ready = { events.add("ready") },
        )
        later!!.invoke()
        assertEquals(listOf("decision", "ready"), events)
    }

    @Test fun `routing failure cannot flush inline readiness into a scan`() {
        var readyCalls = 0
        try {
            StartupLaunchSequence.dispatch<Unit>(
                startup = { ready -> ready(Unit) },
                decide = { throw IllegalStateException("setup launch failed") },
                ready = { readyCalls++ },
            )
            fail("routing error must propagate")
        } catch (_: IllegalStateException) {
            assertEquals(0, readyCalls)
        }
    }

    @Test fun `late asynchronous startup cannot resurrect a run rejected for nearby permission`() {
        val f = Fixture()
        f.launch(inline = false, nearby = false)
        f.laterReady!!.invoke()
        assertFalse(f.startupReady)
        assertTrue(f.scans.isEmpty())
        assertTrue(f.recoveries.isEmpty())
        assertNull(f.ticket)
    }
}
