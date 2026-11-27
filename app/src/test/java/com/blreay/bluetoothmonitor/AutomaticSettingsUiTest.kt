package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class AutomaticSettingsUiTest {
    private fun snapshot(settings: AutomaticMonitorSettings = AutomaticMonitorSettings()) = AutomaticSettingsSnapshot(AutomaticMonitorRecord(settings))
    @Test fun `known defaults are off timer only three minutes and raw full target while corruption stays unknown`() {
        val known = AutomaticSettingsEditor(snapshot())
        assertEquals(0L, known.state()!!.baseRevision)
        assertFalse(known.state()!!.enabled); assertTrue(known.state()!!.timer)
        assertFalse(known.state()!!.screenOn); assertFalse(known.state()!!.screenOff)
        assertEquals("3", known.state()!!.intervalText)
        assertEquals("Xiaomi Smart Band 8 35BA", known.state()!!.targetText)
        val unknown = AutomaticSettingsEditor(AutomaticSettingsSnapshot(null, AutomaticSettingsFault.INVALID_RECORD))
        assertNull(unknown.state()); assertNull(unknown.settingsDraft())
    }
    @Test fun `observer and dropdown updates never overwrite dirty raw text cursor or base revision`() {
        val editor = AutomaticSettingsEditor(snapshot())
        editor.text("  Typed Band  ", 3, 6)
        editor.observe(snapshot(AutomaticMonitorSettings(4, targetName = "Other")))
        editor.candidates(ManualScanCandidatesSnapshot(devices = listOf(ManualScanCandidate("AA", "New"))))
        assertEquals("  Typed Band  ", editor.state()!!.targetText)
        assertEquals(3, editor.state()!!.selectionStart); assertEquals(6, editor.state()!!.selectionEnd)
        assertEquals(0L, editor.state()!!.baseRevision); assertTrue(editor.state()!!.dirty)
    }
    @Test fun `same name different addresses store raw name alone and genuine unknown name remains valid`() {
        val editor = AutomaticSettingsEditor(snapshot())
        editor.candidates(ManualScanCandidatesSnapshot(devices = listOf(ManualScanCandidate("AA", "  Band  "),
            ManualScanCandidate("BB", "  Band  "), ManualScanCandidate("CC", "未知设备"))))
        assertEquals(3, editor.choices().size)
        assertTrue(editor.choose(editor.choices()[0])); assertEquals("  Band  ", editor.settingsDraft()!!.targetName)
        assertTrue(editor.choose(editor.choices()[1])); assertEquals("  Band  ", editor.settingsDraft()!!.targetName)
        assertTrue(editor.choose(editor.choices()[2])); assertEquals("未知设备", editor.settingsDraft()!!.targetName)
    }
    @Test fun `null blank placeholder choice cannot replace typed draft or cursor`() {
        val editor = AutomaticSettingsEditor(snapshot()); editor.text("Typed", 2, 2)
        editor.candidates(ManualScanCandidatesSnapshot(devices = listOf(ManualScanCandidate("AA", null), ManualScanCandidate("BB", "  "))))
        assertEquals(2, editor.choices().size)
        assertFalse(editor.choose(editor.choices()[0])); assertFalse(editor.choose(editor.choices()[1]))
        assertEquals("Typed", editor.state()!!.targetText); assertEquals(2, editor.state()!!.selectionStart)
    }
    @Test fun `timer unchecked retains valid custom interval and never resets to default`() {
        val editor = AutomaticSettingsEditor(snapshot()); editor.interval("17"); editor.sources(true, false, true, false)
        assertEquals("17", editor.state()!!.intervalText); assertEquals(17, editor.settingsDraft()!!.intervalMinutes)
        assertFalse(editor.intervalEnabled()); editor.sources(true, true, true, false)
        assertEquals(17, editor.settingsDraft()!!.intervalMinutes); assertTrue(editor.intervalEnabled())
    }
    @Test fun `invalid conflict false save retain draft and do not change base while saved uses confirmed revision only`() {
        val editor = AutomaticSettingsEditor(snapshot()); editor.text("Raw", 1, 1)
        for (outcome in listOf(AutomaticSettingsSave.INVALID, AutomaticSettingsSave.CONFLICT, AutomaticSettingsSave.WRITE_FAILED)) {
            assertEquals(outcome, editor.save { _, _ -> outcome })
            assertEquals("Raw", editor.state()!!.targetText); assertEquals(1, editor.state()!!.selectionStart)
            assertEquals(0L, editor.state()!!.baseRevision); assertTrue(editor.state()!!.dirty)
        }
        assertEquals(AutomaticSettingsSave.SAVED, editor.save { base, draft ->
            assertEquals(0L, base); assertEquals(base, draft.revision); AutomaticSettingsSave.SAVED
        })
        editor.confirmSaved(snapshot(AutomaticMonitorSettings(1, targetName = "Raw")))
        assertEquals(1L, editor.state()!!.baseRevision); assertFalse(editor.state()!!.dirty)
        assertEquals("Raw", editor.state()!!.targetText); assertEquals(1, editor.state()!!.selectionStart)
    }
    @Test fun `recreation preserves draft and base but never hydrates candidates`() {
        val editor = AutomaticSettingsEditor(snapshot()); editor.text(" Draft ", 2, 3); editor.interval("9")
        editor.candidates(ManualScanCandidatesSnapshot(devices = listOf(ManualScanCandidate("AA", "Band"))))
        val recreated = AutomaticSettingsEditor(snapshot(AutomaticMonitorSettings(5, targetName = "Later")), editor.state())
        assertEquals(editor.state(), recreated.state()); assertTrue(recreated.choices().isEmpty())
    }
}
