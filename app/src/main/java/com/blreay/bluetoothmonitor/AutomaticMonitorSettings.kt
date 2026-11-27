package com.blreay.bluetoothmonitor

data class AutomaticMonitorSettings(
    val revision: Long = 0,
    val enabled: Boolean = false,
    val timerEnabled: Boolean = true,
    val screenOnEnabled: Boolean = false,
    val screenOffEnabled: Boolean = false,
    val intervalMinutes: Int = 3,
    val targetName: String = "Xiaomi Smart Band 8 35BA",
)

data class TargetSnapshot(val settingsRevision: Long, val advertisementName: String) {
    fun matches(currentAdvertisementName: String?): Boolean = currentAdvertisementName == advertisementName
}

enum class AutomaticTrigger { TIMER, SCREEN_ON, SCREEN_OFF }
data class MonitorAlarmTicket(val settingsRevision: Long, val generation: Long)
data class AutomaticMonitorRecord(
    val settings: AutomaticMonitorSettings,
    val alarmGeneration: Long = 0,
    val pendingAlarm: MonitorAlarmTicket? = null,
)

sealed interface AutomaticSettingsRead {
    data object Missing : AutomaticSettingsRead
    data class Valid(val record: AutomaticMonitorRecord) : AutomaticSettingsRead
    data class Invalid(val reason: String) : AutomaticSettingsRead
}

interface AutomaticSettingsPersistence {
    fun read(): AutomaticSettingsRead
    fun commit(record: AutomaticMonitorRecord): Boolean
}

interface MonitorAlarmPort {
    fun arm(ticket: MonitorAlarmTicket, elapsedAt: Long): Boolean
    fun cancel(ticket: MonitorAlarmTicket)
}

enum class AutomaticSettingsFault { INVALID_RECORD, READ_FAILED, WRITE_FAILED, COUNTER_OVERFLOW }
enum class AutomaticSettingsSave { SAVED, UNCHANGED, INVALID, CONFLICT, WRITE_FAILED, OVERFLOW, BLOCKED }
data class AutomaticSettingsSnapshot(val record: AutomaticMonitorRecord?, val fault: AutomaticSettingsFault? = null) {
    val blocked: Boolean get() = fault != null || record == null
}

/** Independent configuration only: no pending reset, manual fence or native authority can be cleared here. */
class CheckedAutomaticSettingsStore(private val persistence: AutomaticSettingsPersistence) {
    private var confirmed: AutomaticMonitorRecord? = null
    private var fault: AutomaticSettingsFault? = null

    init {
        val restored = try { persistence.read() } catch (_: Exception) { null }
        when (restored) {
            AutomaticSettingsRead.Missing -> confirmed = AutomaticMonitorRecord(AutomaticMonitorSettings())
            is AutomaticSettingsRead.Valid -> if (validRecord(restored.record)) confirmed = restored.record
                else fault = AutomaticSettingsFault.INVALID_RECORD
            is AutomaticSettingsRead.Invalid -> fault = AutomaticSettingsFault.INVALID_RECORD
            null -> fault = AutomaticSettingsFault.READ_FAILED
        }
    }

    @Synchronized fun snapshot(): AutomaticSettingsSnapshot = AutomaticSettingsSnapshot(confirmed, fault)

    /** Draft revision is the editor's base revision, not a user-editable counter. */
    @Synchronized fun save(expectedRevision: Long?, draft: AutomaticMonitorSettings): AutomaticSettingsSave {
        if (!validSettings(draft)) return AutomaticSettingsSave.INVALID
        // Unknown counters cannot safely be reset: a corrupt old alarm must never alias a new ticket.
        val current = confirmed ?: return AutomaticSettingsSave.BLOCKED
        if (expectedRevision != current.settings.revision || draft.revision != expectedRevision)
            return AutomaticSettingsSave.CONFLICT
        if (fault == null && draft == current.settings) return AutomaticSettingsSave.UNCHANGED
        if (current.settings.revision == Long.MAX_VALUE || current.alarmGeneration == Long.MAX_VALUE) {
            fault = AutomaticSettingsFault.COUNTER_OVERFLOW
            return AutomaticSettingsSave.OVERFLOW
        }
        val revised = draft.copy(revision = current.settings.revision + 1)
        val generation = current.alarmGeneration + 1
        val next = AutomaticMonitorRecord(revised, generation,
            if (revised.enabled && revised.timerEnabled) MonitorAlarmTicket(revised.revision, generation) else null)
        return if (commit(next)) AutomaticSettingsSave.SAVED else AutomaticSettingsSave.WRITE_FAILED
    }

    /** Exact consume is checked and durable before the scheduler can arm or offer any new work. */
    @Synchronized fun consumeAlarm(expected: MonitorAlarmTicket): MonitorAlarmTicket? {
        val current = confirmed ?: return null
        if (fault != null || current.pendingAlarm != expected) return null
        if (current.alarmGeneration == Long.MAX_VALUE) {
            fault = AutomaticSettingsFault.COUNTER_OVERFLOW
            return null
        }
        val nextTicket = expected.copy(generation = current.alarmGeneration + 1)
        return if (commit(current.copy(alarmGeneration = nextTicket.generation, pendingAlarm = nextTicket))) nextTicket else null
    }

    private fun commit(next: AutomaticMonitorRecord): Boolean {
        val success = try { persistence.commit(next) } catch (_: Exception) { false }
        if (!success) {
            // SharedPreferences may already expose an eagerly changed cache. Never reread it as proof.
            fault = AutomaticSettingsFault.WRITE_FAILED
            return false
        }
        confirmed = next
        fault = null
        return true
    }

    private fun validRecord(record: AutomaticMonitorRecord): Boolean {
        val settings = record.settings
        if (!validSettings(settings) || record.alarmGeneration < settings.revision) return false
        if (settings.revision == 0L && record != AutomaticMonitorRecord(AutomaticMonitorSettings())) return false
        val expected = if (settings.enabled && settings.timerEnabled)
            MonitorAlarmTicket(settings.revision, record.alarmGeneration) else null
        return record.pendingAlarm == expected
    }

    private fun validSettings(settings: AutomaticMonitorSettings): Boolean {
        val name = settings.targetName
        return settings.revision >= 0 && settings.intervalMinutes in 1..1440 &&
            (!settings.enabled || settings.timerEnabled || settings.screenOnEnabled || settings.screenOffEnabled) &&
            name.isNotBlank() && name.none { it.isISOControl() || it == ' ' || it == ' ' } &&
            java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(name) &&
            name.toByteArray(Charsets.UTF_8).size <= 248
    }
}
