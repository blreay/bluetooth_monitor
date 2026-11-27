package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class AutomaticMonitorSchedulerTest {
    private class Rig(initial: AutomaticMonitorRecord? = null) {
        var now = 1_000L
        var armWorks = true
        var armThrows = false
        var cancelThrows = false
        var clockThrows = false
        val events = mutableListOf<String>()
        val armed = mutableListOf<Pair<MonitorAlarmTicket, Long>>()
        val cancelled = mutableListOf<MonitorAlarmTicket>()
        val memory = AutomaticSettingsMemory(initial?.let { AutomaticSettingsRead.Valid(it) } ?: AutomaticSettingsRead.Missing)
            .apply { onCommit = { events += "commit:${it.settings.revision}/${it.alarmGeneration}" } }
        val store = CheckedAutomaticSettingsStore(memory)
        val scheduler = AutomaticMonitorScheduler(store, object : MonitorAlarmPort {
            override fun arm(ticket: MonitorAlarmTicket, elapsedAt: Long): Boolean {
                events += "arm:${ticket.settingsRevision}/${ticket.generation}@$elapsedAt"
                armed += ticket to elapsedAt
                if (armThrows) throw IllegalStateException("arm unavailable")
                return armWorks
            }
            override fun cancel(ticket: MonitorAlarmTicket) {
                events += "cancel:${ticket.settingsRevision}/${ticket.generation}"
                cancelled += ticket
                if (cancelThrows) throw IllegalStateException("cancel unavailable")
            }
        }, { if (clockThrows) throw IllegalStateException("clock unavailable"); now })
        fun enable(settings: AutomaticMonitorSettings = AutomaticMonitorSettings(enabled = true)): MonitorAlarmTicket? {
            assertEquals(AutomaticSettingsSave.SAVED, scheduler.configure(0, settings))
            return store.snapshot().record!!.pendingAlarm
        }
        fun accept(attempt: TriggerAttempt): Boolean = scheduler.accept(attempt) {
            events += "dispatch:${it.settingsRevision}:${it.advertisementName}"; true
        }
    }

    private fun restored(generation: Long = 9) = AutomaticMonitorRecord(AutomaticMonitorSettings(revision = 2,
        enabled = true, screenOnEnabled = true, screenOffEnabled = true), generation, MonitorAlarmTicket(2, generation))

    @Test fun `configure enabled timer schedules after interval without scanning`() {
        val rig = Rig()
        rig.enable()
        assertEquals(listOf(MonitorAlarmTicket(1, 1) to 181_000L), rig.armed)
        assertEquals(AutomaticScheduleStatus.SCHEDULED, rig.scheduler.snapshot().status)
        assertEquals(listOf("commit:1/1", "arm:1/1@181000"), rig.events)
    }

    @Test fun `cold resume schedules the exact restored periodic ticket once without writing or scanning`() {
        val record = restored()
        val rig = Rig(record)
        assertEquals(AutomaticScheduleStatus.SCHEDULED, rig.scheduler.resume().status)
        rig.scheduler.resume()
        assertEquals(listOf(record.pendingAlarm!! to 181_000L), rig.armed)
        assertEquals(0, rig.memory.writes)
        assertTrue(rig.cancelled.isEmpty())
    }

    @Test fun `healthy unchanged configure never rearms rotates cancels or scans`() {
        val rig = Rig()
        rig.enable()
        val before = rig.events.toList()
        val record = rig.store.snapshot().record!!
        assertEquals(AutomaticSettingsSave.UNCHANGED, rig.scheduler.configure(1, record.settings))
        assertEquals(before, rig.events)
        assertEquals(MonitorAlarmTicket(1, 1), rig.store.snapshot().record!!.pendingAlarm)
    }

    @Test fun `unchanged save on cold scheduler does not pretend to resume a plan`() {
        val record = restored()
        val rig = Rig(record)
        assertEquals(AutomaticSettingsSave.UNCHANGED, rig.scheduler.configure(2, record.settings))
        assertTrue(rig.events.isEmpty())
        assertEquals(AutomaticScheduleStatus.NOT_SCHEDULED, rig.scheduler.snapshot().status)
    }

    @Test fun `changed config replaces only the exact old periodic ticket before arming new`() {
        val rig = Rig()
        val old = rig.enable()!!
        rig.events.clear()
        assertEquals(AutomaticSettingsSave.SAVED, rig.scheduler.configure(1,
            rig.store.snapshot().record!!.settings.copy(intervalMinutes = 4)))
        assertEquals(listOf("commit:2/2", "cancel:1/1", "arm:2/2@241000"), rig.events)
        assertEquals(listOf(old), rig.cancelled)
    }

    @Test fun `cold config replacement cancels the known restored ticket not a guessed identity`() {
        val record = restored()
        val rig = Rig(record)
        assertEquals(AutomaticSettingsSave.SAVED, rig.scheduler.configure(2, record.settings.copy(targetName = "new")))
        assertEquals(listOf(record.pendingAlarm!!), rig.cancelled)
        assertEquals(listOf("commit:3/10", "cancel:2/9", "arm:3/10@181000"), rig.events)
    }

    @Test fun `disable cancels periodic only and prohibits old timer and screen sources`() {
        val rig = Rig()
        val ticket = rig.enable(AutomaticMonitorSettings(enabled = true, screenOnEnabled = true, screenOffEnabled = true))!!
        val record = rig.store.snapshot().record!!
        assertEquals(AutomaticSettingsSave.SAVED, rig.scheduler.configure(1, record.settings.copy(enabled = false)))
        assertEquals(listOf(ticket), rig.cancelled)
        assertEquals(AutomaticScheduleStatus.NOT_SCHEDULED, rig.scheduler.snapshot().status)
        val events = rig.events.toList()
        assertNull(rig.scheduler.onTimer(ticket))
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF))
        assertEquals(events, rig.events)
    }

    @Test fun `exact timer delivery commits next then arms next before optional accepted dispatch`() {
        val rig = Rig()
        val ticket = rig.enable()!!
        rig.now = 200_000
        rig.events.clear()
        val attempt = requireNotNull(rig.scheduler.onTimer(ticket))
        assertEquals(listOf("commit:1/2", "arm:1/2@380000"), rig.events)
        assertTrue(rig.accept(attempt))
        assertEquals(listOf("commit:1/2", "arm:1/2@380000", "dispatch:1:Xiaomi Smart Band 8 35BA"), rig.events)
        assertEquals(1L, rig.store.snapshot().record!!.settings.revision)
        assertEquals(MonitorAlarmTicket(1, 2), rig.store.snapshot().record!!.pendingAlarm)
    }

    @Test fun `duplicate stale wrong revision and wrong generation timer deliveries have zero actions`() {
        val rig = Rig()
        val ticket = rig.enable()!!
        assertNotNull(rig.scheduler.onTimer(ticket))
        val before = rig.events.toList()
        for (stale in listOf(ticket, ticket.copy(settingsRevision = 0), ticket.copy(generation = 999),
            MonitorAlarmTicket(-1, -1))) assertNull(rig.scheduler.onTimer(stale))
        assertEquals(before, rig.events)
    }

    @Test fun `timer after configuration change never rotates the new configuration`() {
        val rig = Rig()
        val ticket = rig.enable()!!
        assertEquals(AutomaticSettingsSave.SAVED, rig.scheduler.configure(1,
            rig.store.snapshot().record!!.settings.copy(targetName = "new")))
        val before = rig.events.toList()
        assertNull(rig.scheduler.onTimer(ticket))
        assertEquals(before, rig.events)
        assertEquals(MonitorAlarmTicket(2, 2), rig.store.snapshot().record!!.pendingAlarm)
    }

    @Test fun `false and throwing commit never arm next or dispatch even when cache changed eagerly`() {
        for (throws in listOf(false, true)) {
            val rig = Rig()
            val ticket = rig.enable()!!
            rig.memory.works = false; rig.memory.eagerFailure = true; rig.memory.throwsOnWrite = throws
            rig.events.clear()
            assertNull(rig.scheduler.onTimer(ticket))
            assertEquals(listOf("commit:1/2"), rig.events)
            assertEquals(ticket, rig.store.snapshot().record!!.pendingAlarm)
            assertEquals(AutomaticScheduleStatus.WRITE_FAILED, rig.scheduler.snapshot().status)
            assertNull(rig.scheduler.onTimer(ticket))
            assertEquals(listOf("commit:1/2"), rig.events)
        }
    }

    @Test fun `false and throwing next arm never create an attempt and report scheduling failure`() {
        for (throws in listOf(false, true)) {
            val rig = Rig()
            val ticket = rig.enable()!!
            rig.armWorks = false; rig.armThrows = throws
            rig.events.clear()
            assertNull(rig.scheduler.onTimer(ticket))
            assertEquals(listOf("commit:1/2", "arm:1/2@181000"), rig.events)
            assertEquals(MonitorAlarmTicket(1, 2), rig.store.snapshot().record!!.pendingAlarm)
            assertEquals(AutomaticScheduleStatus.ARM_FAILED, rig.scheduler.snapshot().status)
            rig.armWorks = true; rig.armThrows = false
            assertEquals(AutomaticScheduleStatus.SCHEDULED, rig.scheduler.resume().status)
            assertEquals(2, rig.memory.writes)
        }
    }

    @Test fun `configuration saved is separate from failed scheduling and explicit resume can retry`() {
        val rig = Rig()
        rig.armWorks = false
        rig.enable()
        assertFalse(rig.store.snapshot().blocked)
        assertEquals(AutomaticScheduleStatus.ARM_FAILED, rig.scheduler.snapshot().status)
        val count = rig.armed.size
        assertEquals(AutomaticSettingsSave.UNCHANGED, rig.scheduler.configure(1, rig.store.snapshot().record!!.settings))
        assertEquals(count, rig.armed.size)
        rig.armWorks = true
        assertEquals(AutomaticScheduleStatus.SCHEDULED, rig.scheduler.resume().status)
    }

    @Test fun `failed cancellation never claims a healthy replacement or dispatches`() {
        val rig = Rig()
        val ticket = rig.enable()!!
        rig.cancelThrows = true
        rig.events.clear()
        assertEquals(AutomaticSettingsSave.SAVED, rig.scheduler.configure(1,
            rig.store.snapshot().record!!.settings.copy(intervalMinutes = 4)))
        assertEquals(listOf("commit:2/2", "cancel:1/1"), rig.events)
        assertEquals(AutomaticScheduleStatus.CANCEL_FAILED, rig.scheduler.snapshot().status)
        assertNull(rig.scheduler.onTimer(ticket))
        rig.cancelThrows = false
        assertEquals(AutomaticScheduleStatus.SCHEDULED, rig.scheduler.resume().status)
        assertEquals(MonitorAlarmTicket(2, 2), rig.armed.last().first)
    }

    @Test fun `busy timer advances one period but drops work with no idle backlog`() {
        val rig = Rig()
        val ticket = rig.enable()!!
        rig.now = 9_000_000
        rig.events.clear()
        assertNull(rig.scheduler.onTimer(ticket, busy = true))
        assertEquals(listOf("commit:1/2", "arm:1/2@9180000"), rig.events)
        rig.scheduler.resume()
        assertEquals(2, rig.events.size)
        assertNull(rig.scheduler.onTimer(ticket))
        assertFalse(rig.events.any { it.startsWith("dispatch") })
    }

    @Test fun `cooldown timer still advances without queuing catchup work`() {
        val rig = Rig()
        val ticket = rig.enable(AutomaticMonitorSettings(enabled = true, screenOnEnabled = true))!!
        assertTrue(rig.accept(requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))))
        rig.now += 29_999
        rig.events.clear()
        assertNull(rig.scheduler.onTimer(ticket))
        assertEquals(listOf("commit:1/2", "arm:1/2@210999"), rig.events)
        rig.now++
        rig.scheduler.resume()
        assertEquals(2, rig.events.size)
    }

    @Test fun `every enabled source combination admits only its independently selected sources`() {
        for (bits in 1..7) {
            val rig = Rig()
            val ticket = rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = bits and 1 != 0,
                screenOnEnabled = bits and 2 != 0, screenOffEnabled = bits and 4 != 0))
            for ((source, selected) in listOf(AutomaticTrigger.SCREEN_ON to (bits and 2 != 0),
                AutomaticTrigger.SCREEN_OFF to (bits and 4 != 0))) {
                val attempt = rig.scheduler.onScreen(source)
                assertEquals("sources $bits $source", selected, attempt != null)
                if (attempt != null) assertTrue(rig.scheduler.discard(attempt))
            }
            assertNull(rig.scheduler.onScreen(AutomaticTrigger.TIMER))
            assertEquals(bits and 1 != 0, ticket != null)
            assertEquals(bits and 1 != 0, rig.scheduler.onTimer(ticket ?: MonitorAlarmTicket(1, 1)) != null)
        }
    }

    @Test fun `screen events never reset timer or write settings and busy events are dropped`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, screenOnEnabled = true, screenOffEnabled = true))
        val before = rig.events.toList()
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON, busy = true))
        val attempt = requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF))
        assertEquals(before, rig.events)
        assertTrue(rig.accept(attempt))
        assertEquals(1, rig.armed.size)
        assertEquals(1, rig.memory.writes)
        assertTrue(rig.cancelled.isEmpty())
    }

    @Test fun `thirty second shared minimum interval begins only at accepted automatic admission`() {
        val rig = Rig()
        val ticket = rig.enable(AutomaticMonitorSettings(enabled = true, screenOnEnabled = true, screenOffEnabled = true))!!
        val rejected = requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        assertFalse(rig.scheduler.accept(rejected) { false })
        assertTrue(rig.accept(requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF))))
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        rig.now += 29_999
        assertNull(rig.scheduler.onTimer(ticket))
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF))
        rig.now++
        assertTrue(rig.accept(requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))))
    }

    @Test fun `busy or throwing acceptance consumes its attempt without starting cooldown`() {
        for (throws in listOf(false, true)) {
            val rig = Rig()
            rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true))
            val attempt = requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
            assertFalse(rig.scheduler.accept(attempt, busy = !throws) { throw IllegalStateException("host unavailable") })
            assertFalse(rig.accept(attempt))
            assertTrue(rig.accept(requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))))
        }
    }

    @Test fun `rotating periodic generation never invalidates the current provisional attempt`() {
        val rig = Rig()
        val ticket = rig.enable()!!
        val attempt = requireNotNull(rig.scheduler.onTimer(ticket))
        val next = rig.store.snapshot().record!!.pendingAlarm!!
        assertNull(rig.scheduler.onTimer(next)) // one live provisional attempt, not a queue
        assertEquals(3L, rig.store.snapshot().record!!.alarmGeneration)
        assertTrue(rig.accept(attempt))
        assertFalse(rig.accept(attempt))
    }

    @Test fun `new configuration or disable invalidates provisional attempt at host readiness`() {
        for (disable in listOf(false, true)) {
            val rig = Rig()
            val ticket = rig.enable()!!
            val attempt = requireNotNull(rig.scheduler.onTimer(ticket))
            assertEquals(AutomaticSettingsSave.SAVED, rig.scheduler.configure(1,
                rig.store.snapshot().record!!.settings.copy(enabled = !disable, targetName = "new target")))
            var called = false
            assertFalse(rig.scheduler.accept(attempt) { called = true; true })
            assertFalse(called)
        }
    }

    @Test fun `external checked save invalidates attempts without requiring scheduler configure call`() {
        val rig = Rig()
        val ticket = rig.enable()!!
        val attempt = requireNotNull(rig.scheduler.onTimer(ticket))
        assertEquals(AutomaticSettingsSave.SAVED, rig.store.save(1, rig.store.snapshot().record!!.settings.copy(targetName = "new")))
        assertFalse(rig.accept(attempt))
    }

    @Test fun `accepted target is frozen and later save cannot mutate accepted operation snapshot`() {
        val rig = Rig()
        val ticket = rig.enable()!!
        var frozen: TargetSnapshot? = null
        assertTrue(rig.scheduler.accept(requireNotNull(rig.scheduler.onTimer(ticket))) { frozen = it; true })
        assertEquals(AutomaticSettingsSave.SAVED, rig.scheduler.configure(1, rig.store.snapshot().record!!.settings.copy(targetName = "new")))
        assertEquals(TargetSnapshot(1, "Xiaomi Smart Band 8 35BA"), frozen)
        assertTrue(frozen!!.matches("Xiaomi Smart Band 8 35BA"))
        assertFalse(frozen!!.matches("new"))
    }

    @Test fun `attempt is one shot exact process local and only one provisional attempt is retained`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, screenOnEnabled = true, screenOffEnabled = true))
        val attempt = requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF))
        assertFalse(rig.accept(attempt.copy()))
        assertFalse(rig.scheduler.discard(attempt.copy()))
        assertTrue(rig.scheduler.discard(attempt))
        assertFalse(rig.accept(attempt))
        val fresh = requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF))
        assertNotEquals(attempt.attemptId, fresh.attemptId)
        assertTrue(rig.accept(fresh))
        assertFalse(rig.accept(fresh))
    }

    @Test fun `provisional attempt expires after finite thirty second readiness window`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true))
        val attempt = requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        rig.now += 30_000
        assertFalse(rig.accept(attempt))
        assertTrue(rig.accept(requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))))
    }

    @Test fun `clock regression cannot admit a deferred attempt or bypass accepted cooldown`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true))
        val attempt = requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        rig.now--
        assertFalse(rig.accept(attempt))
        rig.now = 1_000
        assertTrue(rig.accept(requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))))
        rig.now = 0
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
    }

    @Test fun `negative throwing or overflowing clock does not arm or consume a timer`() {
        for (clock in listOf(-1L, Long.MAX_VALUE, Long.MAX_VALUE - 60_000L)) {
            val rig = Rig(restored())
            rig.now = clock
            assertEquals(AutomaticScheduleStatus.INVALID_CLOCK, rig.scheduler.resume().status)
            assertTrue(rig.events.isEmpty())
            assertNull(rig.scheduler.onTimer(restored().pendingAlarm!!))
            assertEquals(0, rig.memory.writes)
        }
        val rig = Rig(restored())
        rig.clockThrows = true
        assertEquals(AutomaticScheduleStatus.INVALID_CLOCK, rig.scheduler.resume().status)
        assertNull(rig.scheduler.onTimer(restored().pendingAlarm!!))
        assertTrue(rig.events.isEmpty())
    }

    @Test fun `generation overflow prevents next arm and dispatch without wrapping`() {
        val rig = Rig(restored(Long.MAX_VALUE))
        assertNull(rig.scheduler.onTimer(restored(Long.MAX_VALUE).pendingAlarm!!))
        assertTrue(rig.events.isEmpty())
        assertTrue(rig.store.snapshot().blocked)
        assertEquals(AutomaticScheduleStatus.BLOCKED_SETTINGS, rig.scheduler.snapshot().status)
    }

    @Test fun `config write failure blocks provisional work and valid retry creates a fresh schedule`() {
        val rig = Rig()
        val ticket = rig.enable()!!
        val attempt = requireNotNull(rig.scheduler.onTimer(ticket))
        val before = rig.store.snapshot().record!!
        rig.memory.works = false; rig.memory.eagerFailure = true
        assertEquals(AutomaticSettingsSave.WRITE_FAILED, rig.scheduler.configure(1, before.settings.copy(targetName = "unsaved")))
        assertFalse(rig.accept(attempt))
        assertEquals(AutomaticScheduleStatus.WRITE_FAILED, rig.scheduler.snapshot().status)
        rig.memory.works = true
        assertEquals(AutomaticSettingsSave.SAVED, rig.scheduler.configure(1, before.settings))
        assertEquals(AutomaticScheduleStatus.SCHEDULED, rig.scheduler.snapshot().status)
        assertEquals(MonitorAlarmTicket(2, 3), rig.armed.last().first)
    }

    @Test fun `acceptance callback cannot reentrantly create another automatic attempt before admission result`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true, screenOffEnabled = true))
        val attempt = requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        assertTrue(rig.scheduler.accept(attempt) {
            assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF))
            true
        })
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF))
    }

    @Test fun `rejected readiness handoff does not leave a dispatch latch or cooldown`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true))
        assertFalse(rig.scheduler.accept(requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))) { false })
        assertTrue(rig.accept(requireNotNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))))
    }

    @Test fun `invalid draft and stale editor do not invalidate an otherwise valid provisional attempt`() {
        val rig = Rig()
        val ticket = rig.enable()!!
        val attempt = requireNotNull(rig.scheduler.onTimer(ticket))
        val record = rig.store.snapshot().record!!
        val events = rig.events.toList()
        assertEquals(AutomaticSettingsSave.INVALID, rig.scheduler.configure(1, record.settings.copy(targetName = " ")))
        assertEquals(AutomaticSettingsSave.CONFLICT, rig.scheduler.configure(0, record.settings))
        assertEquals(events, rig.events)
        assertTrue(rig.accept(attempt))
    }

    @Test fun `screen only attempt clock overflow is rejected without config writes or timer operations`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true))
        val events = rig.events.toList()
        rig.now = Long.MAX_VALUE - 29_999
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        assertEquals(AutomaticScheduleStatus.INVALID_CLOCK, rig.scheduler.snapshot().status)
        assertEquals(events, rig.events)
    }

    @Test fun `a cold scheduler cannot hydrate or accept another process local readiness attempt`() {
        val rig = Rig()
        val ticket = rig.enable()!!
        val attempt = requireNotNull(rig.scheduler.onTimer(ticket))
        val cold = Rig(rig.store.snapshot().record)
        assertFalse(cold.accept(attempt))
        assertTrue(cold.events.isEmpty())
        assertTrue(rig.accept(attempt))
    }

    @Test fun `backwards selected edge cannot replace an attempt and dispatch from the lower clock`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true, screenOffEnabled = true))
        val original = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
        assertNotNull(original)
        rig.now = 999
        val replacement = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF)
        var dispatches = 0
        val acceptedReplacement = replacement?.let { rig.scheduler.accept(it) { dispatches++; true } } ?: false
        assertFalse("regressing replacement must not be accepted", acceptedReplacement)
        assertNull(replacement)
        assertEquals(0, dispatches)
        assertEquals(AutomaticScheduleStatus.INVALID_CLOCK, rig.scheduler.snapshot().status)
        rig.now = 1_000
        assertFalse(rig.accept(original!!)) // observing regression conservatively retires the old provisional
        val fresh = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
        assertNotNull(fresh)
        assertTrue(rig.accept(fresh!!))
    }

    @Test fun `clock high water survives a discarded attempt and rejects later lower selected edges`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true))
        rig.now = 2_000
        val first = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
        assertNotNull(first)
        assertTrue(rig.scheduler.discard(first!!))
        rig.now = 1_999
        val lower = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
        val acceptedLower = lower?.let { rig.accept(it) } ?: false
        assertFalse("discard does not authorize a backwards clock", acceptedLower)
        assertNull(lower)
    }

    @Test fun `backwards timer sample cannot rotate arm or dispatch the current periodic ticket`() {
        val rig = Rig()
        val ticket = rig.enable(AutomaticMonitorSettings(enabled = true, screenOnEnabled = true))!!
        rig.now = 2_000
        val screen = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
        assertNotNull(screen)
        assertTrue(rig.scheduler.discard(screen!!))
        val before = rig.events.toList()
        val confirmed = rig.store.snapshot().record
        rig.now = 1_999
        val fired = rig.scheduler.onTimer(ticket)
        if (fired != null) rig.accept(fired)
        assertEquals("backwards delivery must have zero alarm or dispatch actions", before, rig.events)
        assertNull(fired)
        assertEquals(confirmed, rig.store.snapshot().record)
        assertEquals(AutomaticScheduleStatus.INVALID_CLOCK, rig.scheduler.snapshot().status)
    }

    @Test fun `negative and throwing samples cannot reset high water and permit a lower replacement`() {
        for (throws in listOf(false, true)) {
            val rig = Rig()
            rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true))
            rig.now = 2_000
            val observed = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
            assertNotNull(observed)
            assertTrue(rig.scheduler.discard(observed!!))
            rig.clockThrows = throws
            rig.now = -1
            assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
            rig.clockThrows = false
            rig.now = 1_500
            val lower = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
            assertFalse("invalid sample must not rebase monotonic time", lower?.let { rig.accept(it) } ?: false)
            assertNull(lower)
            rig.now = 2_000
            val healthy = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
            assertNotNull(healthy)
            assertTrue(rig.accept(healthy!!))
        }
    }

    @Test fun `overflowing attempt time cannot be followed by a lower clock to bypass rejection`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true))
        rig.now = Long.MAX_VALUE - 29_999
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        rig.now = 1_000
        val lower = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
        assertFalse("overflow cannot reset the observed high water", lower?.let { rig.accept(it) } ?: false)
        assertNull(lower)
        assertEquals(AutomaticScheduleStatus.INVALID_CLOCK, rig.scheduler.snapshot().status)
    }

    @Test fun `accepted cooldown starts at successful post dispatch confirmation with exact thirty second boundary`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true, screenOffEnabled = true))
        val attempt = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
        assertNotNull(attempt)
        var frozen: TargetSnapshot? = null
        assertTrue(rig.scheduler.accept(attempt!!) { frozen = it; rig.now = 11_000; true })
        assertEquals(TargetSnapshot(1, "Xiaomi Smart Band 8 35BA"), frozen)
        rig.now = 31_000
        assertNull("only twenty seconds elapsed since successful admission confirmation",
            rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF))
        rig.now = 40_999
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        rig.now = 41_000
        val boundary = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF)
        assertNotNull(boundary)
        assertTrue(rig.accept(boundary!!))
    }

    private fun assertBadAcceptedConfirmation(kind: String) {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true, screenOffEnabled = true))
        val attempt = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
        assertNotNull(attempt)
        var frozen: TargetSnapshot? = null
        var dispatches = 0
        assertTrue("already accepted child must retain true admission", rig.scheduler.accept(attempt!!) {
            frozen = it; dispatches++
            when (kind) {
                "negative" -> rig.now = -1
                "throw" -> rig.clockThrows = true
                "regression" -> rig.now = 999
                "overflow" -> rig.now = Long.MAX_VALUE
                else -> throw AssertionError("unknown fixture kind")
            }
            true
        })
        assertEquals(TargetSnapshot(1, "Xiaomi Smart Band 8 35BA"), frozen)
        assertFalse(rig.accept(attempt))
        rig.clockThrows = false
        rig.now = 31_000
        val later = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_OFF)
        val acceptedAgain = later?.let { rig.scheduler.accept(it) { dispatches++; true } } ?: false
        assertFalse("$kind confirmation cannot authorize future work from an untrusted acceptance time", acceptedAgain)
        assertNull(later)
        assertEquals(1, dispatches)
        assertEquals(AutomaticScheduleStatus.INVALID_CLOCK, rig.scheduler.snapshot().status)
        rig.now = 1_000_000
        assertNull(rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        assertEquals(TargetSnapshot(1, "Xiaomi Smart Band 8 35BA"), frozen)
    }

    @Test fun `negative post acceptance clock retains accepted truth and blocks future automatic offers`() {
        assertBadAcceptedConfirmation("negative")
    }

    @Test fun `throwing post acceptance clock retains accepted truth and blocks future automatic offers`() {
        assertBadAcceptedConfirmation("throw")
    }

    @Test fun `regressing post acceptance clock retains accepted truth and blocks future automatic offers`() {
        assertBadAcceptedConfirmation("regression")
    }

    @Test fun `overflowing post acceptance clock retains accepted truth and blocks future automatic offers`() {
        assertBadAcceptedConfirmation("overflow")
    }

    @Test fun `rejected and throwing dispatch do not start cooldown or latch a bad confirmation clock`() {
        for (throws in listOf(false, true)) {
            val rig = Rig()
            rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true))
            val attempt = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
            assertNotNull(attempt)
            assertFalse(rig.scheduler.accept(attempt!!) {
                rig.now = 11_000; rig.clockThrows = true
                if (throws) throw IllegalStateException("child not accepted")
                false
            })
            rig.clockThrows = false
            val next = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
            assertNotNull(next)
            assertTrue(rig.accept(next!!))
        }
    }

    @Test fun `bad acceptance confirmation and configuration change preserve frozen child but never clear clock block`() {
        val rig = Rig()
        rig.enable(AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true))
        val attempt = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
        assertNotNull(attempt)
        var frozen: TargetSnapshot? = null
        assertTrue(rig.scheduler.accept(attempt!!) {
            frozen = it
            assertEquals(AutomaticSettingsSave.SAVED, rig.scheduler.configure(1,
                rig.store.snapshot().record!!.settings.copy(targetName = "new target")))
            rig.clockThrows = true
            true
        })
        assertEquals(TargetSnapshot(1, "Xiaomi Smart Band 8 35BA"), frozen)
        assertEquals("new target", rig.store.snapshot().record!!.settings.targetName)
        rig.clockThrows = false
        rig.now = 31_000
        assertEquals(AutomaticSettingsSave.SAVED, rig.scheduler.configure(2,
            rig.store.snapshot().record!!.settings.copy(targetName = "another target")))
        rig.scheduler.resume()
        val later = rig.scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
        assertFalse("new config cannot erase untrusted accepted timing", later?.let { rig.accept(it) } ?: false)
        assertNull(later)
        assertEquals(AutomaticScheduleStatus.INVALID_CLOCK, rig.scheduler.snapshot().status)
        assertEquals(TargetSnapshot(1, "Xiaomi Smart Band 8 35BA"), frozen)
    }

    @Test fun `reentrant admission guard stays active while the post dispatch clock confirms acceptance`() {
        val store = CheckedAutomaticSettingsStore(AutomaticSettingsMemory())
        var now = 1_000L
        var confirming = false
        var confirmationObserved = false
        var samples = 0
        var reentrant: TriggerAttempt? = null
        lateinit var scheduler: AutomaticMonitorScheduler
        scheduler = AutomaticMonitorScheduler(store, object : MonitorAlarmPort {
            override fun arm(ticket: MonitorAlarmTicket, elapsedAt: Long): Boolean { fail("screen only config"); return false }
            override fun cancel(ticket: MonitorAlarmTicket) { fail("screen only config") }
        }, {
            samples++
            if (confirming) {
                confirming = false
                confirmationObserved = true
                reentrant = scheduler.onScreen(AutomaticTrigger.SCREEN_OFF)
                now = 11_000
            }
            now
        })
        assertEquals(AutomaticSettingsSave.SAVED, scheduler.configure(0,
            AutomaticMonitorSettings(enabled = true, timerEnabled = false, screenOnEnabled = true, screenOffEnabled = true)))
        val attempt = scheduler.onScreen(AutomaticTrigger.SCREEN_ON)
        assertNotNull(attempt)
        assertTrue(scheduler.accept(attempt!!) { confirming = true; true })
        assertTrue("successful dispatch requires confirmation bookkeeping before releasing admission guard", confirmationObserved)
        assertNull(reentrant)
        assertEquals("reentrant edge must not sample time during confirmation", 3, samples)
        now = 40_999
        assertNull(scheduler.onScreen(AutomaticTrigger.SCREEN_OFF))
        now = 41_000
        val next = scheduler.onScreen(AutomaticTrigger.SCREEN_OFF)
        assertNotNull(next)
        assertTrue(scheduler.accept(next!!) { true })
    }

    @Test fun `corrupt configuration cannot resume or dispatch any source`() {
        val memory = AutomaticSettingsMemory(AutomaticSettingsRead.Invalid("corrupt"))
        val scheduler = AutomaticMonitorScheduler(CheckedAutomaticSettingsStore(memory), object : MonitorAlarmPort {
            override fun arm(ticket: MonitorAlarmTicket, elapsedAt: Long): Boolean { fail("corrupt arm"); return false }
            override fun cancel(ticket: MonitorAlarmTicket) { fail("unknown ticket cancellation") }
        }, { 1_000 })
        assertEquals(AutomaticScheduleStatus.BLOCKED_SETTINGS, scheduler.resume().status)
        assertNull(scheduler.onTimer(MonitorAlarmTicket(1, 1)))
        assertNull(scheduler.onScreen(AutomaticTrigger.SCREEN_ON))
        assertNull(scheduler.onScreen(AutomaticTrigger.SCREEN_OFF))
        assertEquals(0, memory.writes)
    }
}
