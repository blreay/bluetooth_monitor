package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class MonitorHostTest {
    private class Fixture {
        var now = 100L
        var stops = 0
        val host = MonitorHostLifetime({ now })
        fun live(): MonitorHostSession {
            val proposed = host.beginStart()
            assertNotNull("a legal explicit host start must issue a process-local ticket", proposed)
            val start = requireNotNull(proposed)
            val session = host.started(start) { stops++ }
            assertNotNull("real foreground completion must verify the exact host", session)
            return requireNotNull(session)
        }
        fun operation(id: String = "run") = ForegroundAdmission.Ticket(id, "operation-$id")
    }
    @Test fun `preferences and pending start are not verified host capabilities`() {
        val f = Fixture()
        f.host.keepAlive(true)
        assertNull(f.host.session())
        val proposed = f.host.beginStart(); assertNotNull(proposed); val start = requireNotNull(proposed)
        assertSame(start, f.host.pending(start.token))
        assertNull(f.host.acquireChild(f.operation()))
        assertNotNull(f.host.started(start) { f.stops++ })
    }
    @Test fun `idle host owns no operation and allows legal manual admission`() {
        val f = Fixture(); f.host.keepAlive(true); f.live()
        assertFalse(f.host.hasChild())
        assertTrue(OperationAdmission().allowsNewOperation())
    }
    @Test fun `screen child borrows exact existing host without starting another foreground service`() {
        val f = Fixture(); f.host.keepAlive(true); val session = f.live()
        assertNull(f.host.beginStart())
        val child = f.host.acquireChild(f.operation())!!
        assertSame(session, child.hostSession)
        assertTrue(f.host.ownsChild(child))
        assertNull(f.host.acquireChild(f.operation("other")))
        assertFalse(f.host.releaseChild(MonitorChildTicket(child.operation, session, child.childToken)))
        assertTrue(f.host.releaseChild(child))
        assertEquals(0, f.stops)
    }
    @Test fun `late host start and old start token cannot establish capability`() {
        val f = Fixture(); val proposed = f.host.beginStart(); assertNotNull(proposed); val old = requireNotNull(proposed)
        f.now += 4_000
        assertNull(f.host.started(old) { f.stops++ })
        assertTrue(f.host.failStart(old))
        val next = f.host.beginStart()!!
        assertNull(f.host.pending(old.token))
        assertNull(f.host.started(old) { f.stops++ })
        assertNotNull(f.host.started(next) { f.stops++ })
    }
    @Test fun `master off drains exact child and stale host destruction cannot revoke replacement`() {
        val f = Fixture(); f.host.keepAlive(true); val old = f.live()
        val child = f.host.acquireChild(f.operation())!!
        f.host.keepAlive(false)
        assertTrue(f.host.ownsChild(child)); assertEquals(0, f.stops)
        assertTrue(f.host.releaseChild(child)); assertEquals(1, f.stops)
        assertNull(f.host.destroy(old))
        f.host.keepAlive(true); val next = f.live()
        val replacement = f.host.acquireChild(f.operation("new"))!!
        assertNull(f.host.destroy(old))
        assertSame(next, f.host.session()); assertTrue(f.host.ownsChild(replacement))
    }
    @Test fun `actual external destruction returns only exact owned child`() {
        val f = Fixture(); f.host.keepAlive(true); val session = f.live()
        val child = f.host.acquireChild(f.operation())!!
        assertSame(child, f.host.destroy(session))
        assertNull(f.host.session()); assertFalse(f.host.ownsChild(child))
        assertNull(f.host.destroy(session))
    }
}

class MonitorChildAdmissionTest {
    @Test fun `failed binding and throwing host stop cannot strand admission or run cleanup`() {
        var released = 0
        val admission = ForegroundAdmission({ 1L }, 4_000L) { }
        val host = MonitorHostLifetime { 1L }; host.keepAlive(true)
        host.started(host.beginStart()!!) { throw IllegalStateException("Android stop failure") }
        try {
            MonitorChildAdmission.accept(admission, host, TargetSnapshot(1, "Band"), "run", { true }, { true }, { released++ },
                { host.keepAlive(false); true }, { _, _ -> false })
        } catch (_: IllegalStateException) { }
        assertFalse("host stop is not an operation fence", admission.busy())
        assertEquals(1, released); assertFalse(host.hasChild()); assertNull(host.session())
    }
    @Test fun `readiness refusal after real child admission is cleaned without losing accepted dispatch truth`() {
        val admission = ForegroundAdmission({ 1L }, 4_000L) { }
        val host = MonitorHostLifetime { 1L }; host.keepAlive(true); host.started(host.beginStart()!!) { }
        val child = MonitorChildAdmission.accept(admission, host, TargetSnapshot(1, "Band"), "run", { true }, { true }, {},
            { true }, { _, _ -> true })!!
        var readiness = 0; var failures = 0
        MonitorChildAdmission.startAccepted(child, { actual ->
            assertSame(child, actual); readiness++; throw IllegalStateException("preflight transport")
        }, { actual ->
            assertSame(child, actual); failures++; admission.end(actual.operation); host.releaseChild(actual)
        })
        assertEquals(1, readiness); assertEquals(1, failures)
        assertFalse(admission.busy()); assertFalse(host.hasChild())
    }

    @Test fun `false task timeout enqueue refuses child and cleans exact claim admission binding and lock`() {
        var now = 100L; var releases = 0; var acquisitions = 0
        val ownership = RunOwnership(); val admission = ForegroundAdmission({ now }, 4_000L) { }
        val owner = OperationForegroundOwner(); val host = MonitorHostLifetime { now }
        host.keepAlive(true); host.started(host.beginStart()!!) { }
        val work = MonitorHostedWork(host, { now }, { object : MonitorTaskLock {
            override fun acquire(timeoutMs: Long) { acquisitions++ }
            override fun release() { releases++ }
        } }, { delay, task -> MonitorTimeoutScheduling.post(delay, task) { _, _ -> false } }, {})
        val child = MonitorChildAdmission.accept(admission, host, TargetSnapshot(1, "Band"), "refused",
            { true }, { ownership.acquire("refused") }, { ownership.release("refused") }, { true }, { actual, target ->
                assertTrue(owner.installMonitor(actual, target))
                if (work.initial(actual) == null) {
                    owner.clear(owner.binding!!); admission.end(actual.operation); host.releaseChild(actual)
                    false
                } else true
            })
        assertNull("a physical WakeLock timeout is not logical admission", child)
        assertEquals(1, acquisitions); assertEquals(1, releases)
        assertNull(owner.binding); assertNull(work.activeSlice()); assertFalse(admission.busy())
        assertFalse(host.hasChild()); assertFalse(ownership.current("refused")); assertTrue(ownership.acquire("next"))
    }
    @Test fun `actual gate claim admission host acquire revalidation acceptance binding occur before dispatch truth`() {
        var now = 1L; val effects = mutableListOf<String>()
        val admission = ForegroundAdmission({ now }, 4_000L) { effects.add("retire") }
        val host = MonitorHostLifetime { now }; host.keepAlive(true); host.started(host.beginStart()!!) { }
        val owner = OperationForegroundOwner()
        val child = MonitorChildAdmission.accept(admission, host, TargetSnapshot(5, " Raw "), "run",
            { effects.add("gate"); true }, { effects.add("claim"); true }, { effects.add("release") },
            { ticket -> effects.add("revalidate"); admission.current(ticket) },
            { exact, target -> effects.add("bind"); owner.installMonitor(exact, target) })
        assertNotNull("real child must be admitted, not just a requested service", child)
        assertEquals(listOf("gate", "claim", "revalidate", "bind"), effects)
        assertTrue(admission.admitted(child!!.operation)); assertTrue(host.ownsChild(child))
        assertEquals(" Raw ", (owner.binding as OperationForegroundBinding.MonitorChild).target.advertisementName)
    }
    @Test fun `pending fence storage retirement and unknown native gate refuse without claiming host child`() {
        val blocked = listOf(OperationAdmission(pending = ResetRecord("old", "command")),
            OperationAdmission(manualFence = ManualOperation("manual", "command", action = ManualAction.OFF)),
            OperationAdmission(scanRetirement = true), OperationAdmission(storageFailed = true),
            OperationAdmission(integrityBlocked = true), OperationAdmission(automaticOwner = true))
        for (state in blocked) {
            val admission = ForegroundAdmission({ 1L }, 4_000L) { }
            val host = MonitorHostLifetime { 1L }; host.keepAlive(true); host.started(host.beginStart()!!) { }
            var claims = 0
            assertNull(MonitorChildAdmission.accept(admission, host, TargetSnapshot(1, "Band"), "run",
                { state.allowsNewOperation() }, { claims++; true }, { }, { true }, { _, _ -> true }))
            assertEquals(0, claims); assertFalse(host.hasChild()); assertFalse(admission.busy())
        }
    }
    @Test fun `late revalidation or lost actual host returns no admission and exact cleanup leaves no fake startId`() {
        for (loseHost in listOf(true, false)) {
            var now = 1L; val admission = ForegroundAdmission({ now }, 4_000L) { }
            val host = MonitorHostLifetime { now }; host.keepAlive(true); val session = host.started(host.beginStart()!!) { }!!
            var binds = 0
            assertNull(MonitorChildAdmission.accept(admission, host, TargetSnapshot(1, "Band"), "run", { true }, { true }, { },
                { if (loseHost) host.destroy(session) else now += 4_000; true }, { _, _ -> binds++; true }))
            assertEquals(0, binds); assertFalse(admission.busy()); assertFalse(host.hasChild())
        }
    }
    @Test fun `binding rejects wrong child original startId and stale completion without clearing replacement`() {
        val owner = OperationForegroundOwner(); val ticket = ForegroundAdmission.Ticket("run", "ticket")
        assertTrue(owner.installOriginal(ticket, 7) { })
        val original = owner.binding!!
        assertFalse(owner.originalCurrent(ticket, 8)); assertFalse(owner.originalCurrent(ticket.copy(token = "wrong"), 7))
        assertNull(owner.clear(OperationForegroundBinding.ResetServiceOwner(ticket, 7) { }))
        assertSame(original, owner.clear(original))
        val host = MonitorHostLifetime { 1L }; host.keepAlive(true); val session = host.started(host.beginStart()!!) { }!!
        val child = host.acquireChild(ticket)!!
        assertTrue(owner.installMonitor(child, TargetSnapshot(1, "Band")))
        assertFalse(owner.childCurrent(MonitorChildTicket(ticket, session, child.childToken)))
        assertNull(owner.clear(original)); assertTrue(owner.childCurrent(child))
    }
}

class MonitorRecoveryHandoffTest {
    private class Lock : MonitorTaskLock {
        var acquired = 0; var released = 0
        override fun acquire(timeoutMs: Long) { acquired++ }
        override fun release() { released++ }
    }
    private class Fixture {
        var now = 100L; var clockOverride: Long? = null; var clockThrows = false
        var timeoutRefused = false; var timeoutThrows = false
        var stops = 0; var ticksPending = false; var setupWaiting = false
        var activeTarget: TargetSnapshot? = null
        var restoreAlarm = false; var restoreArms = 0; var switchEntries = 0; var scannerEntries = 0
        @Volatile var stopAllowed = false
        var stopWait: java.util.concurrent.CountDownLatch? = null
        var stopEntered: java.util.concurrent.CountDownLatch? = null
        val cleanup = java.util.ArrayDeque<() -> Unit>()
        val workTimers = mutableListOf<Pair<Long, () -> Unit>>()
        val waitTimers = mutableListOf<Pair<Long, () -> Unit>>()
        val locks = mutableListOf<Lock>()
        val host = MonitorHostLifetime { now }; val owner = OperationForegroundOwner(); val ownership = RunOwnership()
        val admission = ForegroundAdmission({ now }, 4_000L) { }
        val ledger = CheckedResetStore(MemoryResetStorage()); private var commandSerial = 0; private var runSerial = 0
        val engine = DurableResetEngine(ledger, { restoreAlarm = true; restoreArms++; true }, { now }, { "command-${++commandSerial}" })
        val retirement = ScanRegistrationRetirement({ now }, { cleanup.add(it); true }, onFailure = { _, _ -> })
        val gate = RuntimeOperationGate {
            OperationAdmission(pending = ledger.snapshot().pending, scanRetirement = retirement.blocked(),
                automaticOwner = owner.binding != null, serviceOwned = admission.busy(), setupWaiting = setupWaiting)
        }
        val work = MonitorHostedWork(host, { now }, { Lock().also { locks.add(it) } },
            { ms, task -> workTimers.add(ms to task) }, {})
        val handoff = MonitorRecoveryHandoff(owner, { if (clockThrows) throw IllegalStateException("clock"); clockOverride ?: now },
            { ms, task -> MonitorTimeoutScheduling.post(ms, task) { delay, callback ->
                if (timeoutThrows) throw IllegalStateException("enqueue")
                waitTimers.add(delay to callback); !timeoutRefused
            } }, { finishExact(it) })
        private var configDisk: AutomaticMonitorRecord? = null
        val config = CheckedAutomaticSettingsStore(object : AutomaticSettingsPersistence {
            override fun read() = configDisk?.let { AutomaticSettingsRead.Valid(it) } ?: AutomaticSettingsRead.Missing
            override fun commit(record: AutomaticMonitorRecord): Boolean { configDisk = record; return true }
        })
        private val periodic = object : MonitorAlarmPort {
            override fun arm(ticket: MonitorAlarmTicket, elapsedAt: Long) = true
            override fun cancel(ticket: MonitorAlarmTicket) { }
        }
        val automatic = AutomaticMonitorController(config, AutomaticMonitorScheduler(config, periodic) { now }, periodic, host,
            object : AutomaticMonitorRuntime {
                override fun busy() = !gate.newRequest()
                override fun startHost(start: MonitorHostStart) = true
                override fun later(delay: Long, task: () -> Unit) { }
                override fun dispatch(target: TargetSnapshot): Boolean {
                    val id = "run-${++runSerial}"
                    return MonitorChildAdmission.accept(admission, host, target, id, { gate.newRequest() },
                        { ownership.acquire(id) }, { ownership.release(id) }, { true }, { child, frozen ->
                            activeTarget = frozen; owner.installMonitor(child, frozen) && work.initial(child) != null
                        }) != null
                }
                override fun childGone(child: MonitorChildTicket) { finishExact(child) }
            })
        lateinit var child: MonitorChildTicket; lateinit var scannerTicket: ScanRegistrationRetirement.Ticket
        fun matchedScan(): ResetRecord {
            val old = config.snapshot().record!!.settings
            assertEquals(AutomaticSettingsSave.SAVED, automatic.configure(old.revision,
                old.copy(enabled = true, screenOnEnabled = true, targetName = " Frozen "), false))
            automatic.visibleOpportunity()
            host.pendingStart()?.let { assertNotNull(automatic.hostStarted(it, { stops++ }, {})) }
            assertTrue(automatic.screen(AutomaticTrigger.SCREEN_ON))
            child = (owner.binding as OperationForegroundBinding.MonitorChild).child
            val permit = host.scanPermit(child)!!
            scannerTicket = retirement.claim(child.operation.runId) { stopEntered?.countDown(); stopWait?.await(); stopAllowed }!!
            assertTrue(retirement.start(scannerTicket) { if (!permit.tryEnter()) false else { scannerEntries++; true } })
            val target = activeTarget!!
            assertTrue(target.matches(" Frozen ")); assertTrue(ownership.claimMatch(child.operation.runId))
            assertTrue(retirement.retire(scannerTicket)) // claim precedes retained exact teardown, like the real scanner
            val pending = engine.armAccepted(AcceptedResetMatch(child.operation.runId, target.advertisementName,
                "AA", null, "actual accepted scan", now), false)!!
            assertEquals(ResetStage.OFF_READY, pending.stage); assertTrue(restoreAlarm)
            assertTrue(work.finish(work.activeSlice()!!)) // coordinator finishes initial observation slice before handoff
            return pending
        }
        fun masterOff() {
            val old = config.snapshot().record!!.settings
            assertEquals(AutomaticSettingsSave.SAVED, automatic.configure(old.revision, old.copy(enabled = false, targetName = "Later"), false))
        }
        fun entrance(): MonitorWorkSlice? {
            val binding = owner.binding as? OperationForegroundBinding.MonitorChild ?: return null
            val pending = engine.read() ?: return null
            assertTrue(MonitorRecoveryAdmission.permits(binding.child, pending))
            if (!gate.recovery(pending.runId)) {
                if (handoff.blocked(binding.child)) { restoreAlarm = true; restoreArms++; ticksPending = true }
                return null
            }
            if (!handoff.ready(binding.child)) return null
            return work.recovery(binding.child, gate.recovery(pending.runId))
        }
        fun finishExact(actual: MonitorChildTicket) {
            if (!owner.childCurrent(actual)) return
            ownership.release(actual.operation.runId); activeTarget = null; ticksPending = false
            owner.clear(owner.binding!!); handoff.cancel(actual); work.cancel(actual)
            admission.end(actual.operation); host.releaseChild(actual)
        }
        fun cleanupSucceeds() {
            stopAllowed = true
            if (cleanup.isEmpty()) assertTrue(retirement.retryRetirement())
            cleanup.removeFirst()(); assertFalse(retirement.blocked())
        }
        fun restoreNormally(beforeOn: () -> Unit = {}) {
            val offSlice = entrance()!!
            assertEquals(RecoveryStep.DISPATCH_OFF, engine.observe(AdapterState.ON, now))
            val off = engine.dispatch(engine.read()!!, offSlice.lease, false)!!
            assertTrue(engine.nativeEntered(off, now)); switchEntries++
            assertNotNull(engine.completeAndRearm(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL))); work.finish(offSlice)
            beforeOn()
            val onSlice = entrance()!!
            assertEquals(RecoveryStep.DISPATCH_ON, engine.observe(AdapterState.OFF, ++now))
            val on = engine.dispatch(engine.read()!!, onSlice.lease, true)!!
            assertTrue(engine.nativeEntered(on, now)); switchEntries++
            assertNotNull(engine.completeAndRearm(on, SwitchOutcome.submitted(SwitchMode.SHIZUKU_ROOT)))
            assertEquals(RecoveryStep.RESTORED, engine.observe(AdapterState.ON, ++now))
            work.finish(onSlice); finishExact(child)
        }
    }
    @Test fun `hung exact scan retirement after OFF_READY ends only child at nonrenewing wait deadline`() {
        val f = Fixture(); val pending = f.matchedScan(); val confirmed = f.ledger.snapshot()
        val waiting = java.util.concurrent.CountDownLatch(1); val releaseStop = java.util.concurrent.CountDownLatch(1)
        f.stopWait = releaseStop; f.stopEntered = waiting
        val stopTask = java.util.concurrent.FutureTask<Unit> { f.cleanup.removeFirst()() }
        Thread(stopTask, "test-hung-exact-retirement").start()
        assertTrue(waiting.await(2, java.util.concurrent.TimeUnit.SECONDS))
        try {
            assertNull(f.entrance()); f.masterOff()
            assertEquals(15_000L, f.waitTimers.single().first)
            repeat(14) { index ->
                f.now += 1_000L
                assertNull(f.entrance()); assertFalse(f.retirement.retryRetirement())
                val settings = f.config.snapshot().record!!.settings
                assertEquals(AutomaticSettingsSave.SAVED, f.automatic.configure(settings.revision,
                    settings.copy(intervalMinutes = index + 1, targetName = "Later-$index"), false))
                assertTrue(f.owner.childCurrent(f.child)); assertEquals(1, f.waitTimers.size)
            }
            f.now += 1_000L; assertNull(f.entrance())
            assertNull("blocked recovery must not retain an unbounded foreground child", f.owner.binding)
            assertFalse(f.admission.busy()); assertFalse(f.host.hasChild()); assertNull(f.host.session()); assertEquals(1, f.stops)
            assertFalse(f.ticksPending); assertFalse(f.ownership.current(f.child.operation.runId)); assertNull(f.activeTarget)
            assertEquals(1, f.locks.size); assertEquals(1, f.locks.single().released); assertNull(f.work.activeSlice())
            assertEquals(0, f.switchEntries); assertEquals(1, f.scannerEntries)
            assertEquals(confirmed, f.ledger.snapshot()); assertEquals(pending, f.engine.read()); assertEquals(0L, f.ledger.snapshot().history.total)
            assertTrue(f.retirement.unresolved(f.scannerTicket)); assertTrue(f.restoreAlarm)
            f.workTimers.single().second(); f.waitTimers.single().second()
            assertEquals(confirmed, f.ledger.snapshot()); assertEquals(1, f.stops)
        } finally { releaseStop.countDown(); stopTask.get(2, java.util.concurrent.TimeUnit.SECONDS) }
        assertTrue("timeout is never retained scanner cleanup evidence", f.retirement.unresolved(f.scannerTicket))
    }
    @Test fun `failed retirement handoff timeout survives already finished initial slice without acquiring lock`() {
        val f = Fixture(); f.matchedScan(); f.cleanup.removeFirst()(); assertTrue(f.retirement.blocked())
        val confirmed = f.ledger.snapshot(); assertNull(f.entrance()); f.masterOff()
        f.now += 15_000L; f.waitTimers.single().second()
        assertNull("the independent handoff timeout must still close this exact binding", f.owner.binding)
        assertFalse(f.admission.busy()); assertEquals(1, f.stops); assertFalse(f.ticksPending)
        assertEquals(confirmed, f.ledger.snapshot()); assertTrue(f.retirement.unresolved(f.scannerTicket)); assertTrue(f.restoreAlarm)
        assertEquals(1, f.locks.size); assertEquals(1, f.locks.single().released); assertEquals(0, f.switchEntries)
    }
    @Test fun `gate recovered before handoff deadline resumes same child OFF to ON beyond obsolete wait timeout`() {
        val f = Fixture(); f.matchedScan(); f.cleanup.removeFirst()(); assertNull(f.entrance()); f.masterOff()
        val actual = f.child; f.now += 14_000L; f.cleanupSucceeds()
        f.restoreNormally {
            assertTrue(f.owner.childCurrent(actual)); assertTrue(f.admission.admitted(actual.operation))
            f.now += 2_000L; f.waitTimers.single().second()
            assertTrue("resolved handoff timeout cannot truncate accepted safe restoration", f.owner.childCurrent(actual))
        }
        assertNull(f.ledger.snapshot().pending); assertEquals(1L, f.ledger.snapshot().history.total)
        assertEquals(2, f.switchEntries); assertEquals(listOf(1, 1, 1), f.locks.map { it.released }); assertEquals(1, f.stops)
    }
    @Test fun `stale timeout and copied child cannot end replacement or exact original ResetService`() {
        val f = Fixture(); f.matchedScan(); assertNull(f.entrance()); val old = f.child; val oldTimeout = f.waitTimers.single().second
        f.masterOff(); f.cleanupSucceeds(); f.restoreNormally(); f.now += 30_000L
        f.matchedScan(); assertNull(f.entrance()); val replacement = f.child
        val copied = MonitorChildTicket(replacement.operation, replacement.hostSession, replacement.childToken)
        assertFalse(f.handoff.blocked(copied)); assertFalse(f.handoff.ready(old)); f.handoff.cancel(old); oldTimeout()
        assertTrue(f.owner.childCurrent(replacement)); assertTrue(f.admission.admitted(replacement.operation)); assertEquals(2, f.waitTimers.size)
        val pending = f.ledger.snapshot(); f.finishExact(replacement)
        val original = f.admission.begin(replacement.operation.runId)!!; assertTrue(f.admission.accept(original))
        var originalDone = 0; assertTrue(f.owner.installOriginal(original, 77) { originalDone++ })
        f.waitTimers.forEach { it.second() }; assertFalse(f.handoff.blocked(replacement))
        assertTrue(f.owner.originalCurrent(original, 77)); assertTrue(f.admission.admitted(original)); assertEquals(0, originalDone)
        assertEquals(pending, f.ledger.snapshot())
    }
    @Test fun `false or throwing handoff timeout enqueue closes own lifetime without touching OFF_READY retirement or alarm`() {
        for (throwing in listOf(false, true)) {
            val f = Fixture(); f.matchedScan(); val pending = f.ledger.snapshot(); f.masterOff()
            f.timeoutRefused = !throwing; f.timeoutThrows = throwing
            assertNull(f.entrance())
            assertNull("a refused timeout cannot leave an unlimited blocked child", f.owner.binding)
            assertFalse(f.admission.busy()); assertFalse(f.host.hasChild()); assertEquals(1, f.stops); assertFalse(f.ticksPending)
            assertNull(f.activeTarget); assertFalse(f.ownership.current(f.child.operation.runId))
            assertEquals(pending, f.ledger.snapshot()); assertTrue(f.retirement.unresolved(f.scannerTicket)); assertTrue(f.restoreAlarm)
            assertEquals(1, f.locks.size); assertEquals(1, f.locks.single().released); assertEquals(0, f.switchEntries)
            f.waitTimers.forEach { it.second() }; assertNull(f.owner.binding)
        }
    }
    @Test fun `invalid negative throwing overflowing or regressing handoff clock fails closed without native end proof`() {
        for (mode in listOf("negative", "throw", "overflow", "regression")) {
            val f = Fixture(); f.matchedScan(); val pending = f.ledger.snapshot(); f.masterOff()
            if (mode == "regression") { assertNull(f.entrance()); f.clockOverride = f.now - 1 }
            else if (mode == "negative") f.clockOverride = -1L
            else if (mode == "overflow") f.clockOverride = Long.MAX_VALUE
            else f.clockThrows = true
            assertNull(f.entrance())
            assertNull("untrusted handoff clock $mode cannot retain child", f.owner.binding)
            assertFalse(f.admission.busy()); assertEquals(1, f.stops); assertEquals(pending, f.ledger.snapshot())
            assertTrue(f.retirement.unresolved(f.scannerTicket)); assertTrue(f.restoreAlarm); assertEquals(0, f.switchEntries)
        }
    }
    @Test fun `later blocked handoff cannot renew original deadline and expiry preserves original entered late proof`() {
        val f = Fixture(); f.matchedScan(); assertNull(f.entrance()); f.masterOff(); val began = f.now
        f.now += 1_000L; f.cleanupSucceeds(); val slice = f.entrance()!!
        assertEquals(RecoveryStep.DISPATCH_OFF, f.engine.observe(AdapterState.ON, f.now))
        val off = f.engine.dispatch(f.engine.read()!!, slice.lease, false)!!; assertTrue(f.engine.nativeEntered(off, f.now))
        f.work.finish(slice); f.setupWaiting = true; f.now += 1_000L; assertNull(f.entrance())
        val entered = f.ledger.snapshot(); assertFalse(f.engine.read()!!.authorityEnded); assertEquals(1, f.waitTimers.size)
        f.now = began + 15_000L; assertNull(f.entrance())
        assertNull("a later block cannot extend the same child's handoff deadline", f.owner.binding)
        assertEquals(entered, f.ledger.snapshot()); assertFalse(f.engine.read()!!.authorityEnded); assertEquals(off.commandId, f.engine.read()!!.commandId)
        assertNotNull(f.engine.completeAndRearm(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_ROOT)))
        assertTrue(f.engine.read()!!.authorityEnded); assertEquals(ResetStage.OFF_SETTLED, f.engine.read()!!.stage)
        assertEquals(0L, f.ledger.snapshot().history.total); assertTrue(f.restoreAlarm); assertEquals(2, f.locks.size)
    }
    @Test fun `actual coordinator uses bounded blocked handoff before rearm and clears only exact child on termination`() {
        val coordinator = java.io.File("src/main/java/com/blreay/bluetoothmonitor/ResetCoordinator.kt").readText()
        assertTrue(coordinator.contains("MonitorRecoveryHandoff(foreground,"))
        assertTrue(coordinator.contains("monitorHandoff.blocked(binding.child)")); assertTrue(coordinator.contains("monitorHandoff.ready(binding.child)"))
        assertTrue(coordinator.indexOf("monitorHandoff.ready(binding.child)") < coordinator.indexOf("monitorWork.recovery(binding.child,"))
        assertTrue(coordinator.contains("monitorHandoff.cancel(binding.child)")); assertTrue(coordinator.contains("monitorHandoff.cancel(child)"))
    }
}

class MonitorScanDrainTest {
    private class Lock : MonitorTaskLock {
        var releases = 0
        override fun acquire(timeoutMs: Long) { }
        override fun release() { releases++ }
    }
    private class Fixture {
        var now = 100L; var disk: AutomaticMonitorRecord? = null; var writes = true; var writeThrows = false
        var duringWrite: ((AutomaticMonitorRecord) -> Unit)? = null
        var stops = 0; var preparations = 0
        val host = MonitorHostLifetime { now }
        val store = CheckedAutomaticSettingsStore(MonitorSettingsWriteHandoff(object : AutomaticSettingsPersistence {
            override fun read() = disk?.let { AutomaticSettingsRead.Valid(it) } ?: AutomaticSettingsRead.Missing
            override fun commit(record: AutomaticMonitorRecord): Boolean {
                duringWrite?.invoke(record)
                if (writeThrows) throw IllegalStateException("settings write")
                disk = record; return writes
            }
        }, { host.revokeNewScan() }))
        val alarms = object : MonitorAlarmPort {
            override fun arm(ticket: MonitorAlarmTicket, elapsedAt: Long) = true
            override fun cancel(ticket: MonitorAlarmTicket) { }
        }
        val scheduler = AutomaticMonitorScheduler(store, alarms) { now }
        val ownership = RunOwnership(); val admission = ForegroundAdmission({ now }, 4_000L) { }
        val owner = OperationForegroundOwner(); val locks = mutableListOf<Lock>()
        val work = MonitorHostedWork(host, { now }, { Lock().also { locks.add(it) } }, { _, _ -> }, {})
        var readiness: (() -> Unit)? = null
        var preparedPermit: MonitorScanEntryPermit? = null
        val controller = AutomaticMonitorController(store, scheduler, alarms, host, object : AutomaticMonitorRuntime {
            override fun busy() = admission.busy()
            override fun startHost(start: MonitorHostStart) = true
            override fun later(delay: Long, task: () -> Unit) { }
            override fun dispatch(target: TargetSnapshot): Boolean {
                val child = MonitorChildAdmission.accept(admission, host, target, "run", { true },
                    { ownership.acquire("run") }, { ownership.release("run") }, { true },
                    { actual, frozen -> owner.installMonitor(actual, frozen) && work.initial(actual) != null }) ?: return false
                val slice = work.activeSlice()!!
                MonitorChildAdmission.startAccepted(child, { actual ->
                    readiness = ready@{
                        if (!owner.childCurrent(actual) || !host.ownsChild(actual)) return@ready
                        if (!slice.lease.valid() || !host.newScanEligible(actual)) { finish(actual); return@ready }
                        preparedPermit = host.scanPermit(actual); preparations++
                    }
                }, { finish(it) })
                return true
            }
            override fun childGone(child: MonitorChildTicket) { finish(child) }
        })
        fun accepted(): MonitorChildTicket {
            assertEquals(AutomaticSettingsSave.SAVED, controller.configure(0,
                AutomaticMonitorSettings(enabled = true, screenOnEnabled = true, targetName = "  Frozen Band  "), false))
            controller.visibleOpportunity()
            assertNotNull(controller.hostStarted(host.pendingStart()!!, { stops++ }, {}))
            assertTrue("real accepted dispatch precedes deferred preflight readiness", controller.screen(AutomaticTrigger.SCREEN_ON))
            return (owner.binding as OperationForegroundBinding.MonitorChild).child
        }
        fun off(): AutomaticSettingsSave {
            val old = store.snapshot().record!!.settings
            return controller.configure(old.revision, old.copy(enabled = false, targetName = "Later"), false)
        }
        fun finish(child: MonitorChildTicket) {
            if (!owner.childCurrent(child)) return
            owner.clear(owner.binding!!); work.cancel(child); admission.end(child.operation)
            ownership.release(child.operation.runId); host.releaseChild(child)
        }
    }
    @Test fun `accepted child with outstanding readiness drains without new scan but keeps acceptance cooldown frozen truth`() {
        val f = Fixture(); val child = f.accepted(); val binding = f.owner.binding as OperationForegroundBinding.MonitorChild
        val slice = f.work.activeSlice()!!
        assertEquals(AutomaticSettingsSave.SAVED, f.off())
        assertTrue(f.host.ownsChild(child)); assertTrue(f.admission.admitted(child.operation)); assertTrue(slice.lease.valid())
        assertEquals(TargetSnapshot(1, "  Frozen Band  "), binding.target)
        f.readiness!!()
        assertNull("master OFF before readiness must not prepare another scan", f.preparedPermit)
        assertNull(f.owner.binding); assertFalse(f.admission.busy()); assertFalse(f.host.hasChild()); assertEquals(1, f.stops)
        assertEquals(1, f.locks.single().releases)
        val disabled = f.store.snapshot().record!!.settings
        assertEquals(AutomaticSettingsSave.SAVED, f.controller.configure(disabled.revision, disabled.copy(enabled = true), false))
        f.now += 29_999L
        assertNull("drained accepted child still consumed its actual acceptance cooldown", f.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        assertEquals(0, f.preparations)
    }
    @Test fun `queued native scan entry after confirmed OFF reads only revoked exact permit and original lease`() {
        val f = Fixture(); val child = f.accepted(); f.readiness!!()
        val permit = f.preparedPermit!!; val slice = f.work.activeSlice()!!
        val cleanup = java.util.ArrayDeque<() -> Unit>(); var stopped = 0
        val retirement = ScanRegistrationRetirement({ 100L }, { cleanup.add(it); true }, onFailure = { _, _ -> })
        val ticket = retirement.claim("run") { stopped++; true }!!
        val prepared = java.util.concurrent.CountDownLatch(1); val enter = java.util.concurrent.CountDownLatch(1)
        val nativeEntries = java.util.concurrent.atomic.AtomicInteger()
        val queued = java.util.concurrent.FutureTask<Boolean> {
            prepared.countDown(); enter.await()
            retirement.start(ticket) {
                if (!slice.lease.valid() || !permit.tryEnter()) false
                else { nativeEntries.incrementAndGet(); true }
            }
        }
        val worker = Thread(queued, "test-exact-scan-entry"); worker.start()
        assertTrue(prepared.await(2, java.util.concurrent.TimeUnit.SECONDS))
        try {
            assertEquals(AutomaticSettingsSave.SAVED, f.off())
            assertTrue("drain revokes SCAN, not the accepted recovery lease", slice.lease.valid())
            assertTrue(f.host.ownsChild(child)); assertTrue(f.admission.admitted(child.operation))
        } finally { enter.countDown() }
        assertFalse("queued-before-OFF native registration must not enter after confirmed OFF", queued.get(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, nativeEntries.get()); cleanup.removeFirst()(); assertEquals(1, stopped)
        AutomaticScanCompletionDelivery.finish({}, {}, { f.finish(child) })
        assertFalse(retirement.blocked()); assertNull(f.work.activeSlice()); assertFalse(f.host.hasChild())
        assertEquals(1, f.locks.single().releases)
    }
    @Test fun `scan permit revokes before disabled record commits rather than after scheduler cancellation returns`() {
        val f = Fixture(); val child = f.accepted(); f.readiness!!(); val permit = f.preparedPermit!!
        var enteredDuringWrite: Boolean? = null
        f.duringWrite = { record -> if (!record.settings.enabled) enteredDuringWrite = permit.tryEnter() }
        assertEquals(AutomaticSettingsSave.SAVED, f.off())
        assertEquals("no gap between durable master OFF and worker entry", false, enteredDuringWrite)
        assertTrue(f.host.ownsChild(child)); assertTrue(f.work.activeSlice()!!.lease.valid())
    }
    @Test fun `write false or thrown config fault drains queued scan and explicit resave cannot revive old permit`() {
        for (throwing in listOf(false, true)) {
            val f = Fixture(); val child = f.accepted(); f.readiness!!(); val permit = f.preparedPermit!!
            val confirmed = f.store.snapshot().record!!; f.writes = false; f.writeThrows = throwing
            assertEquals(AutomaticSettingsSave.WRITE_FAILED, f.controller.configure(confirmed.settings.revision,
                confirmed.settings.copy(targetName = "Later"), false))
            assertTrue(f.store.snapshot().blocked); assertEquals(confirmed, f.store.snapshot().record)
            assertFalse("configuration fault cannot leave new native scan entry open", permit.tryEnter())
            assertTrue(f.host.ownsChild(child)); assertTrue(f.work.activeSlice()!!.lease.valid())
            f.writes = true; f.writeThrows = false
            assertEquals(AutomaticSettingsSave.SAVED, f.controller.configure(confirmed.settings.revision, confirmed.settings, false))
            assertFalse(f.store.snapshot().blocked); assertFalse(permit.tryEnter()); assertTrue(f.host.ownsChild(child))
        }
    }
    @Test fun `counter overflow config fault revokes scan before periodic cancellation without cancelling child lease`() {
        val settings = AutomaticMonitorSettings(Long.MAX_VALUE, enabled = true, screenOnEnabled = true, targetName = "Frozen")
        val ticket = MonitorAlarmTicket(Long.MAX_VALUE, Long.MAX_VALUE)
        val record = AutomaticMonitorRecord(settings, Long.MAX_VALUE, ticket)
        val store = CheckedAutomaticSettingsStore(object : AutomaticSettingsPersistence {
            override fun read() = AutomaticSettingsRead.Valid(record)
            override fun commit(record: AutomaticMonitorRecord): Boolean { fail("overflow must not write fabricated counters"); return false }
        })
        val host = MonitorHostLifetime { 100L }; host.keepAlive(true); host.started(host.beginStart()!!) { }
        val child = host.acquireChild(ForegroundAdmission.Ticket("accepted", "operation"))!!
        val permit = host.scanPermit(child)!!; var enteredDuringCancel: Boolean? = null
        val work = MonitorHostedWork(host, { 100L }, { Lock() }, { _, _ -> }, {}); val slice = work.initial(child)!!
        val alarms = object : MonitorAlarmPort {
            override fun arm(ticket: MonitorAlarmTicket, elapsedAt: Long) = true
            override fun cancel(ticket: MonitorAlarmTicket) { enteredDuringCancel = permit.tryEnter() }
        }
        val controller = AutomaticMonitorController(store, AutomaticMonitorScheduler(store, alarms) { 100L }, alarms, host,
            object : AutomaticMonitorRuntime {
                override fun busy() = false
                override fun startHost(start: MonitorHostStart) = false
                override fun later(delay: Long, task: () -> Unit) { }
                override fun dispatch(target: TargetSnapshot): Boolean { fail("fault cannot dispatch"); return false }
                override fun childGone(child: MonitorChildTicket) { fail("configuration cannot destroy accepted child") }
            })
        assertFalse(controller.timer(MonitorAlarmIdentity(ticket, MonitorAlarmOrigin.EXACT)))
        assertEquals(AutomaticSettingsFault.COUNTER_OVERFLOW, store.snapshot().fault)
        assertEquals(false, enteredDuringCancel); assertFalse(permit.tryEnter())
        assertTrue(host.ownsChild(child)); assertTrue(slice.lease.valid()); assertEquals(record, store.snapshot().record)
    }
    @Test fun `invalid conflict unchanged and enabled retarget do not revoke accepted frozen scan permit`() {
        val f = Fixture(); val child = f.accepted(); f.readiness!!(); val permit = f.preparedPermit!!
        val old = f.store.snapshot().record!!.settings
        assertEquals(AutomaticSettingsSave.INVALID, f.controller.configure(old.revision, old.copy(enabled = false, targetName = ""), false))
        assertEquals(AutomaticSettingsSave.CONFLICT, f.controller.configure(0, old.copy(revision = 0, enabled = false), false))
        assertEquals(AutomaticSettingsSave.UNCHANGED, f.controller.configure(old.revision, old, false))
        assertTrue(f.host.newScanEligible(child))
        assertEquals(AutomaticSettingsSave.SAVED, f.controller.configure(old.revision, old.copy(targetName = "Later"), false))
        assertTrue(permit.tryEnter()); assertFalse(permit.tryEnter())
        assertEquals(TargetSnapshot(1, "  Frozen Band  "), (f.owner.binding as OperationForegroundBinding.MonitorChild).target)
    }
    @Test fun `already entered frozen scan survives OFF and original engine restores accepted OFF to ON in finite slices`() {
        val f = Fixture(); val child = f.accepted(); f.readiness!!(); val permit = f.preparedPermit!!
        val initial = f.work.activeSlice()!!; assertTrue(permit.tryEnter())
        assertEquals(AutomaticSettingsSave.SAVED, f.off())
        assertTrue(initial.lease.valid()); assertTrue(f.host.ownsChild(child)); assertFalse(permit.tryEnter())
        val target = (f.owner.binding as OperationForegroundBinding.MonitorChild).target
        assertTrue(target.matches("  Frozen Band  ")); assertFalse(target.matches("Later")); assertTrue(f.ownership.claimMatch("run"))
        val ledger = CheckedResetStore(MemoryResetStorage()); var serial = 0
        val engine = DurableResetEngine(ledger, { true }, { f.now }, { "command-${++serial}" })
        val accepted = engine.armAccepted(AcceptedResetMatch("run", target.advertisementName, "AA", null, "already entered scan", f.now), false)!!
        f.work.finish(initial)
        assertTrue(MonitorRecoveryAdmission.permits(child, accepted))
        val offSlice = f.work.recovery(child, true)!!; val off = engine.dispatch(accepted, offSlice.lease, false)!!
        assertTrue(engine.nativeEntered(off, f.now)); assertNotNull(engine.completeAndRearm(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)))
        f.work.finish(offSlice)
        assertEquals(RecoveryStep.DISPATCH_ON, engine.observe(AdapterState.OFF, ++f.now))
        assertTrue(MonitorRecoveryAdmission.permits(child, engine.read()))
        val onSlice = f.work.recovery(child, true)!!; val on = engine.dispatch(engine.read()!!, onSlice.lease, true)!!
        assertTrue(engine.nativeEntered(on, f.now)); assertNotNull(engine.completeAndRearm(on, SwitchOutcome.submitted(SwitchMode.SHIZUKU_ROOT)))
        assertEquals(0L, ledger.snapshot().history.total)
        assertEquals(RecoveryStep.RESTORED, engine.observe(AdapterState.ON, ++f.now)); assertEquals(1L, ledger.snapshot().history.total)
        assertNull(ledger.snapshot().pending); f.work.finish(onSlice); f.finish(child)
        assertEquals(listOf(1, 1, 1), f.locks.map { it.releases }); assertEquals(1, f.stops)
    }
    @Test fun `copied or released child cannot obtain worker permit and stale revocation cannot affect replacement`() {
        val f = Fixture(); val child = f.accepted(); val permit = f.host.scanPermit(child)!!
        val copied = MonitorChildTicket(child.operation, child.hostSession, child.childToken)
        assertNull(f.host.scanPermit(copied)); assertFalse(f.host.newScanEligible(copied))
        permit.revoke(copied); assertTrue(permit.eligible(child))
        f.finish(child); assertFalse(permit.tryEnter())
        val next = f.host.acquireChild(ForegroundAdmission.Ticket("next", "next"))!!
        val nextPermit = f.host.scanPermit(next)!!
        permit.revoke(child); assertTrue(nextPermit.eligible(next)); assertTrue(nextPermit.tryEnter())
    }
}

class MonitorWorkWindowTest {
    private class Lock : MonitorTaskLock {
        var acquired = 0; var released = 0; var timeout = 0L
        override fun acquire(timeoutMs: Long) { acquired++; timeout = timeoutMs }
        override fun release() { released++ }
    }
    private class Fixture {
        var now = 100L
        val locks = mutableListOf<Lock>()
        val expired = mutableListOf<MonitorWorkSlice>()
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val host = MonitorHostLifetime({ now })
        val work = MonitorHostedWork(host, { now }, { Lock().also { locks.add(it) } },
            { delay, callback -> scheduled.add(delay to callback) }, { expired.add(it) })
        fun child(): MonitorChildTicket {
            host.keepAlive(true)
            val start = host.beginStart(); assertNotNull(start)
            val session = host.started(requireNotNull(start)) { }; assertNotNull(session)
            val child = host.acquireChild(ForegroundAdmission.Ticket("run", "ticket")); assertNotNull(child)
            return requireNotNull(child)
        }
    }
    @Test fun `idle and pending host have no lock and initial admitted task has thirty seconds`() {
        val f = Fixture(); assertTrue(f.locks.isEmpty())
        f.host.beginStart(); assertTrue(f.locks.isEmpty())
        val pending = f.host.pendingStart(); assertNotNull(pending)
        val start = requireNotNull(pending); f.host.started(start) { }
        val child = f.host.acquireChild(ForegroundAdmission.Ticket("run", "ticket"))!!
        val slice = f.work.initial(child)!!
        assertEquals(30_000L, f.locks.single().timeout)
        assertEquals(30_000L, f.scheduled.single().first)
        f.now += 29_999; assertTrue(slice.lease.valid())
        f.now++; assertFalse(slice.lease.valid())
        f.scheduled.single().second()
        assertEquals(1, f.locks.single().released); assertSame(slice, f.expired.single())
    }
    @Test fun `slice completion expiry cancellation and stale callbacks release once with no overlap`() {
        val f = Fixture(); val child = f.child(); val initial = f.work.initial(child)!!
        assertNull(f.work.recovery(child, true)); assertNull(f.work.initial(child))
        assertTrue(f.work.finish(initial)); assertFalse(initial.lease.valid())
        val recovery = f.work.recovery(child, true)!!
        assertEquals(15_000L, f.locks.last().timeout)
        f.scheduled.first().second() // stale initial timer cannot cancel replacement
        assertTrue(recovery.lease.valid()); assertEquals(0, f.locks.last().released)
        f.work.cancel(child); f.work.finish(recovery); f.scheduled.last().second()
        assertEquals(listOf(1, 1), f.locks.map { it.released })
        assertFalse(recovery.lease.valid())
    }
    @Test fun `recovery slice requires actual eligibility exact child and no active slice`() {
        val f = Fixture(); val child = f.child(); val initial = f.work.initial(child)!!
        f.work.finish(initial)
        assertNull(f.work.recovery(child, false))
        assertNull(f.work.recovery(MonitorChildTicket(child.operation, child.hostSession, child.childToken), true))
        assertTrue(f.locks.size == 1)
        assertNotNull(f.work.recovery(child, true))
    }
    @Test fun `master off draining forbids replacement but keeps same child safe recovery slice until completion`() {
        val f = Fixture(); val child = f.child(); val initial = f.work.initial(child)!!
        f.host.keepAlive(false)
        assertTrue(f.host.ownsChild(child)); assertNull(f.host.acquireChild(ForegroundAdmission.Ticket("new", "new")))
        f.work.finish(initial)
        val recovery = f.work.recovery(child, true)
        assertNotNull("master OFF cannot cut accepted OFF to ON between finite slices", recovery)
        assertEquals(15_000L, f.locks.last().timeout)
        f.work.finish(requireNotNull(recovery)); f.host.releaseChild(child)
        assertNull(f.host.session()); assertNull(f.work.recovery(child, true))
        assertEquals(listOf(1, 1), f.locks.map { it.released })
    }
    @Test fun `accepted OFF drains after master off and original engine restores ON across distinct finite slices`() {
        val f = Fixture(); val child = f.child(); f.work.finish(f.work.initial(child)!!)
        val store = CheckedResetStore(MemoryResetStorage()); var serial = 0
        val engine = DurableResetEngine(store, { true }, { f.now }, { "command-${++serial}" })
        val accepted = engine.armAccepted(AcceptedResetMatch("run", "Band", "AA", null, "frozen scan", f.now), false)!!
        val gate = RuntimeOperationGate { OperationAdmission(pending = store.snapshot().pending, automaticOwner = true, serviceOwned = true) }
        val offSlice = f.work.recovery(child, gate.recovery("run"))!!
        val off = engine.dispatch(accepted, offSlice.lease, false)!!
        assertTrue(engine.nativeEntered(off, f.now)); assertNotNull(engine.completeAndRearm(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)))
        f.work.finish(offSlice); f.host.keepAlive(false)
        assertFalse(gate.newRequest()); assertNull(f.host.acquireChild(ForegroundAdmission.Ticket("replacement", "new")))
        val onSlice = f.work.recovery(child, gate.recovery("run"))!!
        assertEquals(RecoveryStep.DISPATCH_ON, engine.observe(AdapterState.OFF, ++f.now))
        val on = engine.dispatch(engine.read()!!, onSlice.lease, true)!!
        assertNotEquals(off.commandId, on.commandId); assertTrue(engine.nativeEntered(on, f.now))
        assertNotNull(engine.completeAndRearm(on, SwitchOutcome.submitted(SwitchMode.SHIZUKU_SHELL)))
        assertEquals(0L, store.snapshot().history.total)
        assertEquals(RecoveryStep.RESTORED, engine.observe(AdapterState.ON, ++f.now))
        assertEquals(1L, store.snapshot().history.total); assertNull(store.snapshot().pending)
        f.work.finish(onSlice); assertTrue(f.host.releaseChild(child)); assertNull(f.host.session())
        assertEquals(listOf(30_000L, 15_000L, 15_000L), f.locks.map { it.timeout })
        assertEquals(listOf(1, 1, 1), f.locks.map { it.released })
    }
    @Test fun `original engine unresolved timeout quarantines and next monitor tick acquires zero locks while late proof survives`() {
        val f = Fixture(); val child = f.child(); f.work.finish(f.work.initial(child)!!)
        val ledger = CheckedResetStore(MemoryResetStorage()); var serial = 0; val alarmCommands = mutableListOf<String>()
        val engine = DurableResetEngine(ledger, { alarmCommands.add("arm"); true }, { f.now }, { "command-${++serial}" })
        val accepted = engine.armAccepted(AcceptedResetMatch("run", "Frozen", "AA", null, "actual scan", f.now), false)!!
        val slice = f.work.recovery(child, true)!!; val off = engine.dispatch(accepted, slice.lease, false)!!
        assertTrue(engine.nativeEntered(off, f.now))
        val completion = engine.completeAndRearm(off, SwitchOutcome.uncertain(SwitchMode.SHIZUKU_SHELL, "entered RPC timeout"))!!
        assertEquals(ResetStage.QUARANTINED, completion.record.stage); assertNull(completion.rearmed)
        f.work.finish(slice)
        val quarantined = ledger.snapshot(); val lockCount = f.locks.size; val alarms = alarmCommands.toList()
        val gate = RuntimeOperationGate { OperationAdmission(pending = ledger.snapshot().pending, serviceOwned = true, automaticOwner = true) }
        assertTrue("the original exact pending-run gate is not a hosting lifecycle grant", gate.recovery("run"))
        f.host.keepAlive(false)
        fun monitorTick() {
            val pending = engine.read()
            if (!MonitorRecoveryAdmission.permits(child, pending)) { f.work.cancel(child); f.host.releaseChild(child); return }
            f.work.recovery(child, gate.recovery(pending!!.runId))
        }
        monitorTick()
        assertEquals("QUARANTINED must stop before lock creation", lockCount, f.locks.size)
        assertNull(f.work.activeSlice()); assertFalse(f.host.hasChild()); assertNull(f.host.session())
        assertEquals(quarantined, ledger.snapshot()); assertEquals(alarms, alarmCommands)
        assertFalse(engine.read()!!.authorityEnded); assertEquals(off.commandId, engine.read()!!.commandId)
        assertEquals(0L, ledger.snapshot().history.total)
        assertNotNull("closing the finite host child must retain original exact late completion", engine.completeAndRearm(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_ROOT)))
        assertTrue(engine.read()!!.authorityEnded); assertEquals(ResetStage.OFF_SETTLED, engine.read()!!.stage)
        assertEquals(RecoveryStep.DISPATCH_ON, engine.observe(AdapterState.OFF, ++f.now))
        assertEquals(lockCount, f.locks.size); assertEquals(listOf(1, 1), f.locks.map { it.released })
    }
    @Test fun `terminal exhaustion quarantine arm failure and wrong pending run never grant monitor renewal`() {
        val f = Fixture(); val child = f.child(); f.work.finish(f.work.initial(child)!!)
        for (stage in listOf(ResetStage.QUARANTINED, ResetStage.EXHAUSTED, ResetStage.ARM_FAILED)) {
            val pending = ResetRecord("run", "command", stage = stage)
            assertFalse("original terminal lifecycle $stage cannot renew a hosting task", MonitorRecoveryAdmission.permits(child, pending))
        }
        assertFalse(MonitorRecoveryAdmission.permits(child, null))
        assertFalse(MonitorRecoveryAdmission.permits(child, ResetRecord("other", "command", stage = ResetStage.ON_READY)))
        for (stage in listOf(ResetStage.OFF_READY, ResetStage.OFF_DISPATCHED, ResetStage.OFF_SETTLED,
            ResetStage.ON_READY, ResetStage.ON_DISPATCHED, ResetStage.ON_SETTLED))
            assertTrue("nonterminal exact lifecycle still uses the original recovery gate", MonitorRecoveryAdmission.permits(child, ResetRecord("run", "command", stage = stage)))
        assertEquals(1, f.locks.size)
    }
    @Test fun `eligible unknown entered authority permits bounded observation without new or reverse dispatch`() {
        val f = Fixture(); val child = f.child(); f.work.finish(f.work.initial(child)!!)
        val ledger = CheckedResetStore(MemoryResetStorage()); var serial = 0
        val engine = DurableResetEngine(ledger, { true }, { f.now }, { "command-${++serial}" })
        val accepted = engine.armAccepted(AcceptedResetMatch("run", "Frozen", "AA", null, "actual scan", f.now), false)!!
        val enteredSlice = f.work.recovery(child, true)!!; val off = engine.dispatch(accepted, enteredSlice.lease, false)!!
        assertTrue(engine.nativeEntered(off, f.now)); f.work.finish(enteredSlice)
        val pending = engine.read()!!; assertFalse(pending.authorityEnded); assertEquals(ResetStage.OFF_DISPATCHED, pending.stage)
        assertTrue(MonitorRecoveryAdmission.permits(child, pending))
        val observation = f.work.recovery(child, true)!!
        assertEquals(15_000L, f.locks.last().timeout)
        assertEquals(RecoveryStep.OBSERVE, engine.observe(AdapterState.UNKNOWN, ++f.now))
        assertNull(engine.dispatch(engine.read()!!, observation.lease, false)); assertNull(engine.dispatch(engine.read()!!, observation.lease, true))
        assertEquals(off.commandId, engine.read()!!.commandId); assertFalse(engine.read()!!.authorityEnded)
        f.work.finish(observation); assertEquals(listOf(1, 1, 1), f.locks.map { it.released })
    }
    @Test fun `expired slice and host destruction cannot end native authority but exact late proof remains accepted`() {
        val f = Fixture(); val child = f.child(); f.work.finish(f.work.initial(child)!!)
        val store = CheckedResetStore(MemoryResetStorage()); var serial = 0
        val engine = DurableResetEngine(store, { true }, { f.now }, { "command-${++serial}" })
        val accepted = engine.armAccepted(AcceptedResetMatch("run", "Band", "AA", null, "accepted", f.now), false)!!
        val slice = f.work.recovery(child, true)!!; val off = engine.dispatch(accepted, slice.lease, false)!!
        assertTrue(engine.nativeEntered(off, f.now)); val entered = store.snapshot().pending!!
        f.now += 15_000L; f.scheduled.last().second()
        assertFalse(slice.lease.valid()); assertEquals(entered, store.snapshot().pending); assertFalse(engine.read()!!.authorityEnded)
        assertSame(child, f.host.destroy(child.hostSession)); f.work.cancel(child)
        assertNull(f.work.recovery(child, true)); assertNull(engine.dispatch(engine.read()!!, slice.lease, true))
        assertEquals(entered.commandId, engine.read()!!.commandId); assertFalse(engine.read()!!.authorityEnded)
        assertNotNull(engine.completeAndRearm(off, SwitchOutcome.submitted(SwitchMode.SHIZUKU_ROOT)))
        assertTrue(engine.read()!!.authorityEnded); assertEquals(RecoveryStep.DISPATCH_ON, engine.observe(AdapterState.OFF, ++f.now))
        assertEquals(0L, store.snapshot().history.total); assertEquals(2, f.locks.size)
        assertEquals(listOf(1, 1), f.locks.map { it.released })
    }
    @Test fun `false recovery timeout enqueue cancels only attempted slice and releases its lock once`() {
        val f = Fixture(); val child = f.child(); var refused = false; var delayed: (() -> Unit)? = null
        val work = MonitorHostedWork(f.host, { f.now }, { Lock().also { f.locks.add(it) } }, { delay, task ->
            MonitorTimeoutScheduling.post(delay, task) { _, callback -> delayed = callback; !refused }
        }, { f.expired.add(it) })
        val initial = work.initial(child)!!; work.finish(initial)
        refused = true
        assertNull("false must not leave a recovery slice logically live", work.recovery(child, true))
        assertNull(work.activeSlice()); assertEquals(listOf(1, 1), f.locks.map { it.released })
        val refusedTimeout = delayed!!
        refused = false; val replacement = work.recovery(child, true)!!
        refusedTimeout() // even a refused adapter retaining a callback cannot expire a newer slice
        val ownTimeout = delayed!!
        assertTrue(replacement.lease.valid()); assertEquals(listOf(1, 1, 0), f.locks.map { it.released })
        ownTimeout(); work.cancel(child)
        assertFalse(replacement.lease.valid()); assertEquals(listOf(1, 1, 1), f.locks.map { it.released })
        assertEquals(listOf(replacement), f.expired)
    }
    @Test fun `task lock factory or timeout scheduling failure leaves no active lease and no overlapping renewal`() {
        for (factory in listOf(true, false)) {
            val f = Fixture(); val child = f.child(); var releases = 0
            val work = MonitorHostedWork(f.host, { f.now }, {
                if (factory) throw IllegalStateException("power unavailable")
                object : MonitorTaskLock { override fun acquire(timeoutMs: Long) {} ; override fun release() { releases++ } }
            }, { _, _ -> throw IllegalStateException("timeout unavailable") }, {})
            assertNull(work.initial(child)); assertNull(work.activeSlice()); assertEquals(if (factory) 0 else 1, releases)
            assertNull(work.initial(child))
        }
    }
    @Test fun `lock acquisition failure cancels lease releases once and installs no active slice`() {
        var releases = 0
        val host = MonitorHostLifetime({ 1L }); host.keepAlive(true)
        val start = host.beginStart(); assertNotNull(start)
        host.started(requireNotNull(start)) { }
        val child = host.acquireChild(ForegroundAdmission.Ticket("run", "ticket"))!!
        val work = MonitorHostedWork(host, { 1L }, { object : MonitorTaskLock {
            override fun acquire(timeoutMs: Long) { throw IllegalStateException("not available") }
            override fun release() { releases++ }
        } }, { _, _ -> }, { })
        assertNull(work.initial(child)); assertNull(work.activeSlice()); assertEquals(1, releases)
    }
}
