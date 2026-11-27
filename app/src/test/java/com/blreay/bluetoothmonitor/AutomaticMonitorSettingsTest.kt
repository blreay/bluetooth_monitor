package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

internal class AutomaticSettingsMemory(var value: AutomaticSettingsRead = AutomaticSettingsRead.Missing) : AutomaticSettingsPersistence {
    var reads = 0
    var writes = 0
    var works = true
    var throwsOnRead = false
    var throwsOnWrite = false
    var eagerFailure = false
    var onCommit: (AutomaticMonitorRecord) -> Unit = {}
    override fun read(): AutomaticSettingsRead {
        reads++
        if (throwsOnRead) throw IllegalStateException("read unavailable")
        return value
    }
    override fun commit(record: AutomaticMonitorRecord): Boolean {
        writes++
        onCommit(record)
        if (works || eagerFailure) value = AutomaticSettingsRead.Valid(record)
        if (throwsOnWrite) throw IllegalStateException("write unavailable")
        return works
    }
}

class AutomaticMonitorSettingsTest {
    private fun active() = AutomaticMonitorSettings(enabled = true)
    private fun saved(settings: AutomaticMonitorSettings = active(), generation: Long = 1): AutomaticMonitorRecord {
        val revised = settings.copy(revision = 1)
        return AutomaticMonitorRecord(revised, generation,
            if (revised.enabled && revised.timerEnabled) MonitorAlarmTicket(1, generation) else null)
    }

    @Test fun `missing configuration has conservative exact original defaults without writing`() {
        val memory = AutomaticSettingsMemory()
        val snapshot = CheckedAutomaticSettingsStore(memory).snapshot()
        assertEquals(AutomaticMonitorRecord(AutomaticMonitorSettings()), snapshot.record)
        val record = requireNotNull(snapshot.record)
        assertEquals("Xiaomi Smart Band 8 35BA", record.settings.targetName)
        assertFalse(record.settings.enabled)
        assertTrue(record.settings.timerEnabled)
        assertFalse(record.settings.screenOnEnabled)
        assertFalse(record.settings.screenOffEnabled)
        assertEquals(3, record.settings.intervalMinutes)
        assertNull(record.pendingAlarm)
        assertFalse(snapshot.blocked)
        assertNull(snapshot.fault)
        assertEquals(0, memory.writes)
    }

    @Test fun `changed settings commit the complete next revision and independent generation`() {
        val memory = AutomaticSettingsMemory()
        val store = CheckedAutomaticSettingsStore(memory)
        val draft = active().copy(screenOnEnabled = true, intervalMinutes = 7, targetName = "  Mi BAND  ")
        assertEquals(AutomaticSettingsSave.SAVED, store.save(0, draft))
        val record = store.snapshot().record!!
        assertEquals(draft.copy(revision = 1), record.settings)
        assertEquals(1L, record.alarmGeneration)
        assertEquals(MonitorAlarmTicket(1, 1), record.pendingAlarm)
        assertEquals(AutomaticSettingsRead.Valid(record), memory.value)
        assertEquals(1, memory.writes)
        assertFalse(store.snapshot().blocked)
    }

    @Test fun `all seven enabled source combinations and all disabled master configuration are valid`() {
        for (bits in 1..7) {
            val store = CheckedAutomaticSettingsStore(AutomaticSettingsMemory())
            val draft = active().copy(timerEnabled = bits and 1 != 0,
                screenOnEnabled = bits and 2 != 0, screenOffEnabled = bits and 4 != 0)
            assertEquals("sources $bits", AutomaticSettingsSave.SAVED, store.save(0, draft))
            assertEquals("sources $bits", draft.copy(revision = 1), store.snapshot().record!!.settings)
            assertEquals(bits and 1 != 0, store.snapshot().record!!.pendingAlarm != null)
        }
        val store = CheckedAutomaticSettingsStore(AutomaticSettingsMemory())
        assertEquals(AutomaticSettingsSave.SAVED, store.save(0, AutomaticMonitorSettings(timerEnabled = false)))
        assertNull(store.snapshot().record!!.pendingAlarm)
    }

    @Test fun `enabled without any source rejects the whole draft`() {
        val memory = AutomaticSettingsMemory()
        val store = CheckedAutomaticSettingsStore(memory)
        assertEquals(AutomaticSettingsSave.INVALID, store.save(0, active().copy(timerEnabled = false)))
        assertEquals(0, memory.writes)
        assertEquals(AutomaticMonitorRecord(AutomaticMonitorSettings()), store.snapshot().record)
    }

    @Test fun `minute boundaries are inclusive even while timer is off`() {
        for (minutes in listOf(1, 1440)) {
            val store = CheckedAutomaticSettingsStore(AutomaticSettingsMemory())
            assertEquals(AutomaticSettingsSave.SAVED, store.save(0,
                AutomaticMonitorSettings(timerEnabled = false, intervalMinutes = minutes)))
            assertEquals(minutes, store.snapshot().record!!.settings.intervalMinutes)
        }
    }

    @Test fun `invalid minute values reject even while master and timer are off`() {
        for (minutes in listOf(Int.MIN_VALUE, -1, 0, 1441, Int.MAX_VALUE)) {
            val memory = AutomaticSettingsMemory()
            val store = CheckedAutomaticSettingsStore(memory)
            assertEquals("minutes $minutes", AutomaticSettingsSave.INVALID,
                store.save(0, AutomaticMonitorSettings(timerEnabled = false, intervalMinutes = minutes)))
            assertEquals(0, memory.writes)
        }
    }

    @Test fun `blank multiline control malformed unicode and oversized names reject without truncating`() {
        val badNames = listOf("", " \t ", "band\nname", "band\rname", "band\u0085name",
            "band name", "band name", "band\u0000name", "\uD800", "a".repeat(249), "中".repeat(83))
        for (name in badNames) {
            val memory = AutomaticSettingsMemory()
            assertEquals(AutomaticSettingsSave.INVALID,
                CheckedAutomaticSettingsStore(memory).save(0, active().copy(targetName = name)))
            assertEquals(0, memory.writes)
        }
    }

    @Test fun `utf8 byte boundary and meaningful raw spaces and case are preserved`() {
        for (name in listOf("a".repeat(248), "中".repeat(82) + "ab", "  Band  ", "未知设备", "😀".repeat(62))) {
            val store = CheckedAutomaticSettingsStore(AutomaticSettingsMemory())
            assertEquals(AutomaticSettingsSave.SAVED, store.save(0, active().copy(targetName = name)))
            assertEquals(name, store.snapshot().record!!.settings.targetName)
        }
    }

    @Test fun `target snapshot matches only the full current raw advertisement`() {
        val target = TargetSnapshot(7, " Xiaomi Smart Band 8 35BA ")
        assertTrue(target.matches(" Xiaomi Smart Band 8 35BA "))
        for (name in listOf(null, "", "Xiaomi Smart Band 8 35BA", " xiaomi Smart Band 8 35BA ",
            "Xiaomi Smart Band 9 35BA", "35:BA", "未知设备")) assertFalse(target.matches(name))
        assertTrue(TargetSnapshot(1, "未知设备").matches("未知设备"))
        assertFalse(TargetSnapshot(1, "未知设备").matches(null))
    }

    @Test fun `stale expected revision and invalid draft revision never write`() {
        val memory = AutomaticSettingsMemory(AutomaticSettingsRead.Valid(saved()))
        val store = CheckedAutomaticSettingsStore(memory)
        assertEquals(AutomaticSettingsSave.CONFLICT, store.save(0, active()))
        assertEquals(AutomaticSettingsSave.CONFLICT, store.save(null, active()))
        assertEquals(AutomaticSettingsSave.INVALID, store.save(1, active().copy(revision = -1)))
        assertEquals(AutomaticSettingsSave.CONFLICT, store.save(1, active().copy(revision = 0)))
        assertEquals(0, memory.writes)
        assertEquals(saved(), store.snapshot().record)
    }

    @Test fun `healthy unchanged save is not a transaction or a new generation`() {
        val record = saved(generation = 19)
        val memory = AutomaticSettingsMemory(AutomaticSettingsRead.Valid(record))
        val store = CheckedAutomaticSettingsStore(memory)
        assertEquals(AutomaticSettingsSave.UNCHANGED, store.save(1, record.settings))
        assertEquals(record, store.snapshot().record)
        assertEquals(0, memory.writes)
    }

    @Test fun `disable removes only the periodic ticket and still increments both counters`() {
        val record = saved(generation = 19)
        val store = CheckedAutomaticSettingsStore(AutomaticSettingsMemory(AutomaticSettingsRead.Valid(record)))
        assertEquals(AutomaticSettingsSave.SAVED, store.save(1, record.settings.copy(enabled = false)))
        assertEquals(2L, store.snapshot().record!!.settings.revision)
        assertEquals(20L, store.snapshot().record!!.alarmGeneration)
        assertNull(store.snapshot().record!!.pendingAlarm)
    }

    @Test fun `confirmed configuration roundtrips into a cold checked store`() {
        val memory = AutomaticSettingsMemory()
        val store = CheckedAutomaticSettingsStore(memory)
        assertEquals(AutomaticSettingsSave.SAVED, store.save(0,
            active().copy(screenOffEnabled = true, targetName = " exact NAME ", intervalMinutes = 1440)))
        assertEquals(store.snapshot(), CheckedAutomaticSettingsStore(memory).snapshot())
    }

    @Test fun `false eager commit keeps last confirmed and blocks without rereading cache`() {
        val record = saved()
        val memory = AutomaticSettingsMemory(AutomaticSettingsRead.Valid(record)).apply { works = false; eagerFailure = true }
        val store = CheckedAutomaticSettingsStore(memory)
        assertEquals(AutomaticSettingsSave.WRITE_FAILED, store.save(1, record.settings.copy(targetName = "unsaved")))
        assertEquals("unsaved", (memory.value as AutomaticSettingsRead.Valid).record.settings.targetName)
        assertEquals(record, store.snapshot().record)
        assertTrue(store.snapshot().blocked)
        assertEquals(AutomaticSettingsFault.WRITE_FAILED, store.snapshot().fault)
        assertEquals(1, memory.reads)
        assertNull(store.consumeAlarm(record.pendingAlarm!!))
        assertEquals(1, memory.writes)
    }

    @Test fun `throwing eager commit is not saved and cannot publish unconfirmed target`() {
        val record = saved()
        val memory = AutomaticSettingsMemory(AutomaticSettingsRead.Valid(record)).apply {
            works = false; eagerFailure = true; throwsOnWrite = true
        }
        val store = CheckedAutomaticSettingsStore(memory)
        assertEquals(AutomaticSettingsSave.WRITE_FAILED, store.save(1, record.settings.copy(targetName = "unsaved")))
        assertEquals(record, store.snapshot().record)
        assertTrue(store.snapshot().blocked)
        assertEquals(1, memory.reads)
    }

    @Test fun `explicit valid save retries failed config even when identical to last confirmed`() {
        val record = saved(generation = 20)
        val memory = AutomaticSettingsMemory(AutomaticSettingsRead.Valid(record)).apply { works = false; eagerFailure = true }
        val store = CheckedAutomaticSettingsStore(memory)
        assertEquals(AutomaticSettingsSave.WRITE_FAILED, store.save(1, record.settings.copy(targetName = "unsaved")))
        memory.works = true
        assertEquals(AutomaticSettingsSave.SAVED, store.save(1, record.settings))
        assertEquals(record.settings.copy(revision = 2), store.snapshot().record!!.settings)
        assertEquals(MonitorAlarmTicket(2, 21), store.snapshot().record!!.pendingAlarm)
        assertFalse(store.snapshot().blocked)
        assertNull(store.snapshot().fault)
        assertEquals(2, memory.writes)
        assertEquals(1, memory.reads)
    }

    @Test fun `invalid retry or revision conflict cannot clear a previous configuration fault`() {
        val record = saved()
        val memory = AutomaticSettingsMemory(AutomaticSettingsRead.Valid(record)).apply { works = false }
        val store = CheckedAutomaticSettingsStore(memory)
        assertEquals(AutomaticSettingsSave.WRITE_FAILED, store.save(1, record.settings.copy(targetName = "new")))
        memory.works = true
        assertEquals(AutomaticSettingsSave.INVALID, store.save(1, record.settings.copy(targetName = " ")))
        assertEquals(AutomaticSettingsSave.CONFLICT, store.save(0, record.settings))
        assertTrue(store.snapshot().blocked)
        assertEquals(1, memory.writes)
    }

    @Test fun `invalid or throwing read is not missing and has no invented confirmed target`() {
        for (memory in listOf(AutomaticSettingsMemory(AutomaticSettingsRead.Invalid("corrupt")),
            AutomaticSettingsMemory().apply { throwsOnRead = true })) {
            val store = CheckedAutomaticSettingsStore(memory)
            assertNull(store.snapshot().record)
            assertTrue(store.snapshot().blocked)
            assertNotNull(store.snapshot().fault)
            assertEquals(AutomaticSettingsSave.BLOCKED, store.save(null, active()))
            assertEquals(0, memory.writes)
            assertEquals(1, memory.reads)
        }
    }

    @Test fun `incoherent restored fields fail closed rather than replacing them with defaults`() {
        val good = saved(generation = 10)
        val invalid = listOf(
            good.copy(settings = good.settings.copy(revision = -1)),
            good.copy(settings = good.settings.copy(intervalMinutes = 0)),
            good.copy(settings = good.settings.copy(targetName = " ")),
            good.copy(settings = good.settings.copy(timerEnabled = false, screenOnEnabled = false, screenOffEnabled = false)),
            good.copy(settings = good.settings.copy(enabled = false)),
            good.copy(alarmGeneration = -1), good.copy(alarmGeneration = 0),
            good.copy(pendingAlarm = null), good.copy(pendingAlarm = MonitorAlarmTicket(2, 10)),
            good.copy(pendingAlarm = MonitorAlarmTicket(1, 9)), good.copy(pendingAlarm = MonitorAlarmTicket(-1, -1)),
            AutomaticMonitorRecord(AutomaticMonitorSettings(enabled = true), 0, MonitorAlarmTicket(0, 0)),
            AutomaticMonitorRecord(AutomaticMonitorSettings(intervalMinutes = 4)),
        )
        for ((index, record) in invalid.withIndex()) {
            val store = CheckedAutomaticSettingsStore(AutomaticSettingsMemory(AutomaticSettingsRead.Valid(record)))
            assertTrue("record $index", store.snapshot().blocked)
            assertNull("record $index", store.snapshot().record)
        }
    }

    @Test fun `non timer restored configuration has no periodic ticket`() {
        val record = saved(active().copy(timerEnabled = false, screenOnEnabled = true), 10)
        val store = CheckedAutomaticSettingsStore(AutomaticSettingsMemory(AutomaticSettingsRead.Valid(record)))
        assertEquals(record, store.snapshot().record)
        assertFalse(store.snapshot().blocked)
        assertNull(store.consumeAlarm(MonitorAlarmTicket(1, 10)))
    }

    @Test fun `configuration counter overflow cannot wrap or partially commit`() {
        for (record in listOf(
            AutomaticMonitorRecord(active().copy(revision = Long.MAX_VALUE), Long.MAX_VALUE,
                MonitorAlarmTicket(Long.MAX_VALUE, Long.MAX_VALUE)),
            saved(generation = Long.MAX_VALUE))) {
            val memory = AutomaticSettingsMemory(AutomaticSettingsRead.Valid(record))
            val store = CheckedAutomaticSettingsStore(memory)
            assertEquals(AutomaticSettingsSave.OVERFLOW, store.save(record.settings.revision,
                record.settings.copy(intervalMinutes = 4)))
            assertEquals(record, store.snapshot().record)
            assertEquals(0, memory.writes)
            assertTrue(store.snapshot().blocked)
        }
    }

    @Test fun `alarm consume commits next generation but never increments settings revision`() {
        val record = saved(generation = 20)
        val memory = AutomaticSettingsMemory(AutomaticSettingsRead.Valid(record))
        val store = CheckedAutomaticSettingsStore(memory)
        assertEquals(MonitorAlarmTicket(1, 21), store.consumeAlarm(record.pendingAlarm!!))
        assertEquals(record.settings, store.snapshot().record!!.settings)
        assertEquals(21L, store.snapshot().record!!.alarmGeneration)
        assertEquals(1, memory.writes)
        assertNull(store.consumeAlarm(record.pendingAlarm!!))
        assertNull(store.consumeAlarm(MonitorAlarmTicket(0, 21)))
        assertEquals(1, memory.writes)
    }

    @Test fun `alarm consume false and generation overflow preserve the last confirmed ticket`() {
        for (overflow in listOf(false, true)) {
            val record = saved(generation = if (overflow) Long.MAX_VALUE else 5)
            val memory = AutomaticSettingsMemory(AutomaticSettingsRead.Valid(record)).apply { works = overflow; eagerFailure = true }
            val store = CheckedAutomaticSettingsStore(memory)
            assertNull(store.consumeAlarm(record.pendingAlarm!!))
            assertEquals(record, store.snapshot().record)
            assertTrue(store.snapshot().blocked)
            assertEquals(if (overflow) 0 else 1, memory.writes)
        }
    }
}
