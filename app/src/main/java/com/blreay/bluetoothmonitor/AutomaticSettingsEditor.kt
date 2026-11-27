package com.blreay.bluetoothmonitor

data class AutomaticSettingsDraft(val baseRevision: Long, val enabled: Boolean, val timer: Boolean, val screenOn: Boolean,
    val screenOff: Boolean, val intervalText: String, val retainedInterval: Int, val targetText: String,
    val selectionStart: Int = 0, val selectionEnd: Int = 0, val dirty: Boolean = false)
data class AutomaticTargetChoice(val mac: String, val rawName: String?) {
    val selectable get() = !rawName.isNullOrBlank()
    override fun toString() = "${rawName?.takeIf { it.isNotBlank() } ?: "无广播名（不可选择）"} · $mac"
}

/** Presentation draft only. Neither observing settings nor replacing an adapter is an edit/save. */
class AutomaticSettingsEditor(snapshot: AutomaticSettingsSnapshot, restored: AutomaticSettingsDraft? = null) {
    private var confirmed = snapshot
    private var draft = restored ?: snapshot.record?.settings?.let {
        AutomaticSettingsDraft(it.revision, it.enabled, it.timerEnabled, it.screenOnEnabled, it.screenOffEnabled,
            it.intervalMinutes.toString(), it.intervalMinutes, it.targetName)
    }
    private var choices = emptyList<AutomaticTargetChoice>()
    private var savedDraft: AutomaticMonitorSettings? = null
    fun state(): AutomaticSettingsDraft? = draft
    fun observe(snapshot: AutomaticSettingsSnapshot) { confirmed = snapshot } // NEVER replace draft/base/text/cursor
    fun candidates(snapshot: ManualScanCandidatesSnapshot) {
        choices = snapshot.devices.map { AutomaticTargetChoice(it.mac, it.advertisementName) }
    }
    fun choices(): List<AutomaticTargetChoice> = choices
    fun text(value: String, start: Int, end: Int) {
        draft = draft?.let { it.copy(targetText = value, selectionStart = start.coerceIn(0, value.length),
            selectionEnd = end.coerceIn(0, value.length), dirty = it.dirty || value != it.targetText) }
    }
    fun interval(value: String) {
        draft = draft?.let { it.copy(intervalText = value,
            retainedInterval = value.toIntOrNull()?.takeIf { n -> n in 1..1440 } ?: it.retainedInterval,
            dirty = it.dirty || value != it.intervalText) }
    }
    fun sources(enabled: Boolean, timer: Boolean, on: Boolean, off: Boolean) {
        draft = draft?.let { it.copy(enabled = enabled, timer = timer, screenOn = on, screenOff = off,
            dirty = it.dirty || enabled != it.enabled || timer != it.timer || on != it.screenOn || off != it.screenOff) }
    }
    fun intervalEnabled(): Boolean = draft?.timer == true
    fun choose(choice: AutomaticTargetChoice): Boolean {
        if (!choice.selectable || draft == null || choice !in choices) return false
        val raw = requireNotNull(choice.rawName)
        text(raw, raw.length, raw.length)
        return true
    }
    fun settingsDraft(): AutomaticMonitorSettings? {
        val value = draft ?: return null
        val interval = if (value.timer) value.intervalText.toIntOrNull() ?: return null else value.retainedInterval
        return AutomaticMonitorSettings(value.baseRevision, value.enabled, value.timer, value.screenOn, value.screenOff, interval, value.targetText)
    }
    fun save(commit: (Long?, AutomaticMonitorSettings) -> AutomaticSettingsSave): AutomaticSettingsSave {
        val value = draft ?: return AutomaticSettingsSave.BLOCKED
        val settings = settingsDraft() ?: return AutomaticSettingsSave.INVALID
        val result = commit(value.baseRevision, settings)
        savedDraft = settings.takeIf { result == AutomaticSettingsSave.SAVED }
        if (result == AutomaticSettingsSave.UNCHANGED) draft = value.copy(dirty = false)
        return result
    }
    fun confirmSaved(snapshot: AutomaticSettingsSnapshot) {
        observe(snapshot)
        val submitted = savedDraft ?: return
        val settings = confirmed.record?.settings ?: return
        if (submitted.revision == Long.MAX_VALUE || settings != submitted.copy(revision = submitted.revision + 1)) return
        draft = draft?.copy(baseRevision = settings.revision, retainedInterval = settings.intervalMinutes, dirty = false)
        savedDraft = null
    }
}
