package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class MonitorPeriodicTransportTest {
    private class Backend : MonitorPeriodicBackend {
        var exact = false
        var failExact = false
        var denyExact = false
        var failInexact = false
        val active = linkedSetOf<MonitorAlarmIdentity>()
        val effects = mutableListOf<String>()
        override fun canScheduleExact(): Boolean = exact
        override fun arm(identity: MonitorAlarmIdentity, elapsedAt: Long) {
            effects.add("arm-${identity.origin}")
            if (identity.origin == MonitorAlarmOrigin.EXACT && denyExact) throw SecurityException("denied")
            if ((identity.origin == MonitorAlarmOrigin.EXACT && failExact) ||
                (identity.origin == MonitorAlarmOrigin.INEXACT && failInexact)) throw IllegalStateException("unavailable")
            active.add(identity)
        }
        override fun cancel(identity: MonitorAlarmIdentity) { effects.add("cancel-${identity.origin}"); active.remove(identity) }
    }
    @Test fun `periodic receipt validates explicit action component canonical ticket and immutable origin URI`() {
        val ticket = MonitorAlarmTicket(2, 9)
        val exact = MonitorAlarmIdentity(ticket, MonitorAlarmOrigin.EXACT)
        assertEquals("btmonitor://periodic/2/9/exact", MonitorAlarmEncoding.uri(exact))
        assertEquals(exact, MonitorAlarmEncoding.decode("pkg.action.MONITOR_TICK", "com.blreay.bluetoothmonitor.MonitorAlarmReceiver",
            "btmonitor://periodic/2/9/exact", "pkg"))
        for (uri in listOf("btmonitor://periodic/2/9/inexact?wasExact=true", "btmonitor://periodic/02/9/exact",
            "btmonitor://periodic/2/1/exact", "btmonitor://periodic/-1/9/exact", "btmonitor://periodic/2/9/exact#x"))
            assertNull(MonitorAlarmEncoding.decode("pkg.action.MONITOR_TICK", "com.blreay.bluetoothmonitor.MonitorAlarmReceiver", uri, "pkg"))
        assertNull(MonitorAlarmEncoding.decode("wrong", "com.blreay.bluetoothmonitor.MonitorAlarmReceiver", MonitorAlarmEncoding.uri(exact), "pkg"))
        assertNull(MonitorAlarmEncoding.decode("pkg.action.MONITOR_TICK", "Other", MonitorAlarmEncoding.uri(exact), "pkg"))
    }
    @Test fun `current inexact origin stays inexact when successor arms exactly`() {
        val b = Backend(); val transport = MonitorPeriodicTransport(b)
        val old = MonitorAlarmTicket(1, 1); assertTrue(transport.arm(old, 1_000))
        val receipt = b.active.single(); assertEquals(MonitorAlarmOrigin.INEXACT, receipt.origin)
        b.exact = true; assertTrue(transport.arm(old.copy(generation = 2), 2_000))
        assertEquals(MonitorAlarmOrigin.INEXACT, receipt.origin)
        assertEquals(MonitorAlarmOrigin.EXACT, transport.snapshot().origin)
    }
    @Test fun `same persisted ticket exact failure cannot relabel previously inexact token`() {
        val b = Backend(); val transport = MonitorPeriodicTransport(b); val ticket = MonitorAlarmTicket(1, 1)
        assertTrue(transport.arm(ticket, 1_000)); val receipt = b.active.single()
        b.exact = true; b.failExact = true
        assertFalse(transport.arm(ticket, 2_000))
        assertEquals(setOf(receipt), b.active)
        assertEquals("btmonitor://periodic/1/1/inexact", MonitorAlarmEncoding.uri(receipt))
        assertEquals(MonitorAlarmOrigin.INEXACT, receipt.origin)
    }
    @Test fun `safe exact refusal falls back with a distinct inexact identity and truthful degraded status`() {
        val b = Backend(); b.exact = true; b.denyExact = true
        val transport = MonitorPeriodicTransport(b); val ticket = MonitorAlarmTicket(3, 4)
        assertTrue(transport.arm(ticket, 1_000))
        assertEquals(MonitorAlarmOrigin.INEXACT, b.active.single().origin)
        assertEquals(MonitorAlarmOrigin.INEXACT, transport.snapshot().origin)
        assertEquals(listOf("arm-EXACT", "arm-INEXACT", "cancel-EXACT"), b.effects)
    }
    @Test fun `cancellation covers only both identities of this periodic ticket`() {
        val b = Backend(); val transport = MonitorPeriodicTransport(b)
        val a = MonitorAlarmTicket(1, 2); val other = MonitorAlarmTicket(2, 3)
        assertTrue(transport.arm(a, 100)); assertTrue(transport.arm(other, 200))
        transport.cancel(a)
        assertEquals(setOf(MonitorAlarmIdentity(other, MonitorAlarmOrigin.INEXACT)), b.active)
        assertEquals(listOf("cancel-EXACT", "cancel-INEXACT"), b.effects.takeLast(2))
    }
}

class AutomaticTriggerRuntimeTest {
    private class Fixture {
        var now = 100L
        var busy = false
        var startAllowed = true
        var starts = 0
        var laterThrows = false
        var laterRefused = false
        var lastStart: MonitorHostStart? = null
        var accepted = true
        var scans = 0
        var destroyed: MonitorChildTicket? = null
        val targets = mutableListOf<TargetSnapshot>()
        val effects = mutableListOf<String>()
        var disk: AutomaticMonitorRecord? = null
        var writesWork = true
        val store = CheckedAutomaticSettingsStore(object : AutomaticSettingsPersistence {
            override fun read() = disk?.let { AutomaticSettingsRead.Valid(it) } ?: AutomaticSettingsRead.Missing
            override fun commit(record: AutomaticMonitorRecord): Boolean { effects.add("commit"); disk = record; return writesWork }
        })
        val alarms = object : MonitorAlarmPort {
            override fun arm(ticket: MonitorAlarmTicket, elapsedAt: Long): Boolean { effects.add("arm"); return true }
            override fun cancel(ticket: MonitorAlarmTicket) { effects.add("cancel") }
        }
        val scheduler = AutomaticMonitorScheduler(store, alarms, { now })
        val host = MonitorHostLifetime { now }
        val callbacks = mutableListOf<() -> Unit>()
        val controller = AutomaticMonitorController(store, scheduler, alarms, host, object : AutomaticMonitorRuntime {
            override fun busy() = busy
            override fun startHost(start: MonitorHostStart): Boolean { starts++; lastStart = start; return startAllowed }
            override fun later(delay: Long, task: () -> Unit) {
                if (laterThrows) throw IllegalStateException("host timeout unavailable")
                MonitorTimeoutScheduling.post(delay, task) { _, callback ->
                    if (laterRefused) false else { callbacks.add(callback); true }
                }
            }
            override fun dispatch(target: TargetSnapshot): Boolean { effects.add("dispatch"); targets.add(target); if (accepted) scans++; return accepted }
            override fun childGone(child: MonitorChildTicket) { destroyed = child }
        })
        fun enable(timer: Boolean = true, on: Boolean = true, off: Boolean = true) {
            assertEquals(AutomaticSettingsSave.SAVED, controller.configure(0,
                AutomaticMonitorSettings(enabled = true, timerEnabled = timer, screenOnEnabled = on, screenOffEnabled = off), false))
        }
        fun ready(): MonitorHostSession {
            val start = host.pendingStart(); assertNotNull("cold eligible exact opportunity should request a real host", start)
            val session = controller.hostStarted(requireNotNull(start), { }, { }); assertNotNull(session)
            return requireNotNull(session)
        }
        fun ticket() = store.snapshot().record!!.pendingAlarm!!
    }
    @Test fun `idle host stop exception cannot turn confirmed master off save into unconfirmed editor result`() {
        val f = Fixture(); f.enable(); f.controller.visibleOpportunity()
        f.controller.hostStarted(f.host.pendingStart()!!, { throw IllegalStateException("stop refused") }, {})
        val old = f.store.snapshot().record!!.settings
        var result: AutomaticSettingsSave? = null
        try { result = f.controller.configure(old.revision, old.copy(enabled = false), false) } catch (_: Exception) { }
        assertEquals(AutomaticSettingsSave.SAVED, result); assertFalse(f.store.snapshot().record!!.settings.enabled)
        assertNull(f.host.session()); assertNotNull(f.controller.snapshot().notice)
        assertEquals(0, f.scans)
    }
    @Test fun `configuration false commit and explicit resave never clear pending reset native authority or accepted child`() {
        val f = Fixture(); f.enable(); f.controller.visibleOpportunity(); f.ready()
        val child = f.host.acquireChild(ForegroundAdmission.Ticket("run", "operation"))!!
        val ledger = CheckedResetStore(MemoryResetStorage()); var serial = 0
        val engine = DurableResetEngine(ledger, { true }, { f.now }, { "command-${++serial}" })
        val accepted = engine.armAccepted(AcceptedResetMatch("run", "Original", "AA", null, "exact scan", f.now), false)!!
        val off = engine.dispatch(accepted, CommandLease({ f.now }, f.now + 15_000L), false)!!
        assertTrue(engine.nativeEntered(off, f.now)); val fence = ledger.snapshot()
        val confirmed = f.store.snapshot().record!!; f.writesWork = false; f.effects.clear()
        assertEquals(AutomaticSettingsSave.WRITE_FAILED, f.controller.configure(confirmed.settings.revision,
            confirmed.settings.copy(targetName = "Later"), false))
        assertEquals(confirmed, f.store.snapshot().record); assertTrue(f.store.snapshot().blocked)
        assertTrue(f.host.ownsChild(child)); assertEquals(fence, ledger.snapshot()); assertFalse(engine.read()!!.authorityEnded)
        assertFalse(f.controller.screen(AutomaticTrigger.SCREEN_ON)); assertTrue(f.effects.contains("cancel"))
        f.writesWork = true
        assertEquals(AutomaticSettingsSave.SAVED, f.controller.configure(confirmed.settings.revision, confirmed.settings, false))
        assertFalse(f.store.snapshot().blocked); assertEquals(fence, ledger.snapshot()); assertTrue(f.host.ownsChild(child))
        assertFalse(engine.read()!!.authorityEnded); assertEquals(0L, ledger.snapshot().history.total); assertEquals(0, f.scans)
    }
    @Test fun `false host timeout enqueue revokes own pending start and attempt before any child acceptance`() {
        val f = Fixture(); f.enable(); f.laterRefused = true
        assertFalse(f.controller.timer(MonitorAlarmIdentity(f.ticket(), MonitorAlarmOrigin.EXACT)))
        val refused = f.lastStart!!
        assertNull("Handler false must reach the logical refusal path", f.host.pendingStart())
        assertNull(f.host.session()); assertEquals(0, f.scans); assertTrue(f.callbacks.isEmpty())
        assertNull(f.controller.hostStarted(refused, {}, {})); assertNotNull(f.controller.snapshot().notice)
        f.laterRefused = false; f.controller.visibleOpportunity()
        assertNotSame(refused, f.host.pendingStart()); f.ready()
        assertEquals("failed provisional attempt must not survive into replacement readiness", 0, f.scans)
        assertTrue(f.controller.screen(AutomaticTrigger.SCREEN_ON)); assertEquals(1, f.scans)
    }
    @Test fun `host start timeout scheduling failure rejects pending capability without scan or UI workaround`() {
        val f = Fixture(); f.enable(); f.laterThrows = true
        try { f.controller.timer(MonitorAlarmIdentity(f.ticket(), MonitorAlarmOrigin.EXACT)) } catch (_: Exception) { }
        assertNull("unbounded pending host must not remain authoritative", f.host.pendingStart())
        assertNull(f.host.session()); assertEquals(0, f.scans)
        assertNotNull(f.controller.snapshot().notice)
    }
    @Test fun `all seven source combinations persist independently without immediate scan and select only live host edges`() {
        for (mask in 1..7) {
            val f = Fixture(); f.enable(timer = mask and 1 != 0, on = mask and 2 != 0, off = mask and 4 != 0)
            assertEquals(0, f.scans); f.controller.visibleOpportunity()
            var selected: AutomaticMonitorSettings? = null
            f.controller.hostStarted(f.host.pendingStart()!!, {}, { selected = it })
            assertEquals(mask and 1 != 0, selected!!.timerEnabled)
            assertEquals(mask and 2 != 0, selected!!.screenOnEnabled)
            assertEquals(mask and 4 != 0, selected!!.screenOffEnabled)
            assertEquals(mask and 1 != 0, f.store.snapshot().record!!.pendingAlarm != null)
            assertEquals(0, f.scans)
            assertEquals(mask and 2 != 0, f.controller.screen(AutomaticTrigger.SCREEN_ON))
            f.now += 30_000L
            assertEquals(mask and 4 != 0, f.controller.screen(AutomaticTrigger.SCREEN_OFF))
            assertEquals(1, f.starts)
        }
    }
    @Test fun `actual screen registration failure is separate from confirmed settings and cannot revoke accepted child`() {
        val f = Fixture(); f.enable(); f.controller.visibleOpportunity(); val session = f.ready()
        val confirmed = f.store.snapshot().record
        val child = f.host.acquireChild(ForegroundAdmission({ f.now }, 4_000L, {}).begin("accepted")!!)!!
        assertTrue(f.controller.screenRegistration(session, "registration denied"))
        assertEquals("registration denied", f.controller.snapshot().screenSourceFailure)
        assertEquals(confirmed, f.store.snapshot().record); assertTrue(f.host.ownsChild(child))
        assertFalse(f.controller.screenRegistration(MonitorHostSession(session.start), null))
        assertEquals("registration denied", f.controller.snapshot().screenSourceFailure)
        assertTrue(f.controller.screenRegistration(session, null))
        assertNull(f.controller.snapshot().screenSourceFailure)
        f.controller.screenRegistration(session, "failed again"); f.controller.hostGone(session)
        assertEquals(child, f.destroyed); assertNull(f.controller.snapshot().screenSourceFailure)
        assertFalse(f.controller.screenRegistration(session, "stale failure"))
        assertEquals(0, f.scans)
    }
    @Test fun `save and enable are not scans and explicit visible opportunity only starts host`() {
        val f = Fixture(); f.enable(); assertEquals(0, f.starts); assertEquals(0, f.scans)
        f.controller.visibleOpportunity(); assertEquals(1, f.starts); assertEquals(0, f.scans)
        f.ready(); assertEquals(0, f.scans)
    }
    @Test fun `screen without verified host never starts foreground service and actual edge borrows live host`() {
        val f = Fixture(); f.enable()
        assertFalse(f.controller.screen(AutomaticTrigger.SCREEN_ON)); assertEquals(0, f.starts)
        f.controller.visibleOpportunity(); f.ready()
        assertTrue(f.controller.screen(AutomaticTrigger.SCREEN_ON))
        assertEquals(1, f.starts); assertEquals(1, f.scans)
        assertFalse(f.controller.screen(AutomaticTrigger.SCREEN_OFF)) // accepted 30-second cooldown
    }
    @Test fun `inexact cold receipt rotates arms next but skips new work without claiming exact exemption`() {
        val f = Fixture(); f.enable(); val current = f.ticket(); f.effects.clear()
        assertFalse(f.controller.timer(MonitorAlarmIdentity(current, MonitorAlarmOrigin.INEXACT)))
        assertEquals(listOf("commit", "arm"), f.effects); assertEquals(0, f.starts); assertEquals(0, f.scans)
        assertEquals(current.generation + 1, f.ticket().generation)
        assertTrue(f.controller.snapshot().notice!!.contains("显式"))
    }
    @Test fun `exact cold host remains provisional until readiness and consumed successor does not invalidate attempt`() {
        val f = Fixture(); f.enable(); val ticket = f.ticket(); f.effects.clear()
        assertFalse(f.controller.timer(MonitorAlarmIdentity(ticket, MonitorAlarmOrigin.EXACT)))
        assertEquals(listOf("commit", "arm"), f.effects); assertEquals(1, f.starts); assertEquals(0, f.scans)
        f.ready(); assertEquals(1, f.scans)
        assertEquals(TargetSnapshot(ticket.settingsRevision, "Xiaomi Smart Band 8 35BA"), f.targets.single())
        assertEquals(listOf("commit", "arm", "dispatch"), f.effects)
        assertFalse(f.controller.timer(MonitorAlarmIdentity(ticket, MonitorAlarmOrigin.EXACT)))
        assertEquals(1, f.scans)
    }
    @Test fun `disable or retarget pending cold host invalidates attempt without later scan`() {
        for (disable in listOf(true, false)) {
            val f = Fixture(); f.enable(); f.controller.timer(MonitorAlarmIdentity(f.ticket(), MonitorAlarmOrigin.EXACT))
            val old = f.store.snapshot().record!!.settings
            f.controller.configure(old.revision, old.copy(enabled = !disable, targetName = "New target"), false)
            f.host.pendingStart()?.let { f.controller.hostStarted(it, { }, { }) }
            assertEquals(0, f.scans)
        }
    }
    @Test fun `accepted child target is frozen across retarget master off and configuration independent recovery`() {
        val f = Fixture(); f.enable(); f.controller.visibleOpportunity(); f.ready()
        assertTrue(f.controller.screen(AutomaticTrigger.SCREEN_ON))
        val frozen = f.targets.single(); val old = f.store.snapshot().record!!.settings
        f.controller.configure(old.revision, old.copy(enabled = false, targetName = "Later"), false)
        assertEquals("Xiaomi Smart Band 8 35BA", frozen.advertisementName)
        assertEquals(1, f.scans)
    }
    @Test fun `actual host start refusal and readiness busy cannot report accepted scan`() {
        val refused = Fixture(); refused.enable(); refused.startAllowed = false
        assertFalse(refused.controller.timer(MonitorAlarmIdentity(refused.ticket(), MonitorAlarmOrigin.EXACT)))
        assertEquals(0, refused.scans); assertNull(refused.host.pendingStart())
        val f = Fixture(); f.enable(); f.controller.timer(MonitorAlarmIdentity(f.ticket(), MonitorAlarmOrigin.EXACT)); f.busy = true
        f.ready(); assertEquals(0, f.scans)
    }
    @Test fun `selected combinations and busy timer still rotate while screen edge has no queue`() {
        val f = Fixture(); f.enable(on = false, off = true); f.controller.visibleOpportunity(); f.ready()
        assertFalse(f.controller.screen(AutomaticTrigger.SCREEN_ON))
        f.busy = true; val old = f.ticket(); f.effects.clear()
        assertFalse(f.controller.timer(MonitorAlarmIdentity(old, MonitorAlarmOrigin.EXACT)))
        assertEquals(listOf("commit", "arm"), f.effects)
        assertFalse(f.controller.screen(AutomaticTrigger.SCREEN_OFF))
        f.busy = false; assertEquals(0, f.scans)
        assertTrue(f.controller.screen(AutomaticTrigger.SCREEN_OFF))
    }
}

class AutomaticSettingsPreferencesTest {
    private val record = AutomaticMonitorRecord(AutomaticMonitorSettings(7, true, true, true, false, 11, "  Raw Band  "),
        9, MonitorAlarmTicket(7, 9))
    @Test fun `codec complete whole record preserves raw name revision generation and independent source selection`() {
        val decoded = AutomaticSettingsCodec.decode(AutomaticSettingsCodec.encode(record))
        assertEquals(AutomaticSettingsRead.Valid(record), decoded)
    }
    @Test fun `only entirely missing record yields defaults and partial schema types counters stay invalid`() {
        assertEquals(AutomaticSettingsRead.Missing, AutomaticSettingsCodec.decode(emptyMap<String, Any>()))
        val valid = AutomaticSettingsCodec.encode(record)
        for (bad in listOf(valid - "target_name", valid + ("schema" to 2), valid + ("enabled" to "true"),
            valid + ("settings_revision" to 7), valid + ("alarm_generation" to -1L),
            valid + ("pending_revision" to 8L), valid + ("target_name" to 1)))
            assertTrue("invalid shape must not become known defaults: $bad", AutomaticSettingsCodec.decode(bad) is AutomaticSettingsRead.Invalid)
    }
    @Test fun `codec no pending record has complete explicit absence and never recovery ledger keys`() {
        val record = AutomaticMonitorRecord(AutomaticMonitorSettings(2, false), 5)
        val encoded = AutomaticSettingsCodec.encode(record)
        assertEquals(AutomaticSettingsRead.Valid(record), AutomaticSettingsCodec.decode(encoded))
        assertFalse(encoded.keys.any { it.contains("bt_reset_pending") || it.contains("history") || it.contains("native") })
    }
    @Test fun `eager false Android style commit stays last confirmed and explicit valid resave clears configuration fault only`() {
        var disk = AutomaticSettingsCodec.encode(record)
        var works = false
        val store = CheckedAutomaticSettingsStore(object : AutomaticSettingsPersistence {
            override fun read() = AutomaticSettingsCodec.decode(disk)
            override fun commit(record: AutomaticMonitorRecord): Boolean { disk = AutomaticSettingsCodec.encode(record); return works }
        })
        assertEquals(AutomaticSettingsSave.WRITE_FAILED, store.save(7, record.settings.copy(targetName = "Later")))
        assertEquals(record, store.snapshot().record)
        assertTrue(store.snapshot().blocked)
        works = true
        assertEquals(AutomaticSettingsSave.SAVED, store.save(7, record.settings))
        assertEquals("  Raw Band  ", store.snapshot().record!!.settings.targetName)
        assertFalse(store.snapshot().blocked)
    }
}
