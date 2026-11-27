package com.blreay.bluetoothmonitor

import android.content.Context

/** Strict complete SharedPreferences shape; semantic policy/counter authority remains the checked store. */
object AutomaticSettingsCodec {
    private val keys = setOf("schema", "settings_revision", "enabled", "timer", "screen_on", "screen_off",
        "interval_minutes", "target_name", "alarm_generation", "pending", "pending_revision", "pending_generation")
    fun encode(record: AutomaticMonitorRecord): Map<String, Any> = linkedMapOf(
        "schema" to 1, "settings_revision" to record.settings.revision, "enabled" to record.settings.enabled,
        "timer" to record.settings.timerEnabled, "screen_on" to record.settings.screenOnEnabled,
        "screen_off" to record.settings.screenOffEnabled, "interval_minutes" to record.settings.intervalMinutes,
        "target_name" to record.settings.targetName, "alarm_generation" to record.alarmGeneration,
        "pending" to (record.pendingAlarm != null), "pending_revision" to (record.pendingAlarm?.settingsRevision ?: -1L),
        "pending_generation" to (record.pendingAlarm?.generation ?: -1L))
    fun decode(values: Map<String, *>): AutomaticSettingsRead {
        if (values.isEmpty()) return AutomaticSettingsRead.Missing
        fun invalid() = AutomaticSettingsRead.Invalid("自动配置记录不完整/类型或计数身份不可确认")
        if (values.keys != keys || values["schema"] !is Int || values["schema"] != 1) return invalid()
        for (key in listOf("settings_revision", "alarm_generation", "pending_revision", "pending_generation"))
            if (values[key] !is Long) return invalid()
        for (key in listOf("enabled", "timer", "screen_on", "screen_off", "pending"))
            if (values[key] !is Boolean) return invalid()
        if (values["interval_minutes"] !is Int || values["target_name"] !is String) return invalid()
        val revision = values["settings_revision"] as Long
        val generation = values["alarm_generation"] as Long
        val pendingRevision = values["pending_revision"] as Long
        val pendingGeneration = values["pending_generation"] as Long
        val pending = values["pending"] as Boolean
        if (revision < 0 || generation < revision || (pending && (pendingRevision != revision || pendingGeneration != generation)) ||
            (!pending && (pendingRevision != -1L || pendingGeneration != -1L))) return invalid()
        val settings = AutomaticMonitorSettings(revision, values["enabled"] as Boolean, values["timer"] as Boolean,
            values["screen_on"] as Boolean, values["screen_off"] as Boolean,
            values["interval_minutes"] as Int, values["target_name"] as String)
        return AutomaticSettingsRead.Valid(AutomaticMonitorRecord(settings, generation,
            if (pending) MonitorAlarmTicket(pendingRevision, pendingGeneration) else null))
    }
}

/** Separate from reset pending/history. One synchronous whole-record checked transaction, never apply/reread. */
class AutomaticSettingsPreferences(context: Context) : AutomaticSettingsPersistence {
    private val preferences = context.getSharedPreferences("bt_monitor_automatic", Context.MODE_PRIVATE)
    override fun read(): AutomaticSettingsRead = AutomaticSettingsCodec.decode(preferences.all)
    override fun commit(record: AutomaticMonitorRecord): Boolean {
        val editor = preferences.edit().clear()
        AutomaticSettingsCodec.encode(record).forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                else -> return false
            }
        }
        return editor.commit()
    }
}
