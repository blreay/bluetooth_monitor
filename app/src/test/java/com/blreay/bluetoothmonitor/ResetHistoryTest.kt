package com.blreay.bluetoothmonitor

import org.junit.Assert.*
import org.junit.Test

class ResetHistoryTest {
    private fun match(run: String) = AcceptedResetMatch(run, "Xiaomi Band", "AA:BB:CC:DD:EE:FF",
        RunReport.ScanFacts(8_000L, 1_000L, 4, 2, 0, true), "observed advertisement", 100L)

    private fun proven(run: String) = ResetRecord(run, "on-$run", stage = ResetStage.ON_SETTLED,
        phase = ReEnablePolicy.Phase.WAITING_ON,
        firstOffObservedAt = 200L, firstOnObservedAt = 400L,
        offEvidence = ResetCommandEvidence(run, "off-$run", 150L, true, true, 200L),
        onEvidence = ResetCommandEvidence(run, "on-$run", 300L, true, true, 400L),
        onEnteredAfterOffObservation = true)

    @Test fun `four accepted runs have stable unique ordinals and count each only once`() {
        var history = ResetHistory()
        repeat(4) { index ->
            val run = "run-$index"
            history = history.accept(match(run))
            history = history.terminal(proven(run), ResetDisposition.RESTORED)
            val completed = history
            history = history.terminal(proven(run), ResetDisposition.RESTORED)
            assertEquals(completed, history)
        }
        assertEquals(4L, history.total)
        assertEquals(listOf(1L, 2L, 3L, 4L), history.rows.map { it.sequence })
        assertEquals(4, history.rows.map { it.runId }.distinct().size)
        assertTrue(history.rows.all { it.counted })
    }

    @Test fun `accepted duplicates and retries update one row and preserve original scan identity`() {
        val first = ResetHistory().accept(match("run"))
        assertEquals(first, first.accept(match("run").copy(advertisementName = "late name")))
        val retry = proven("run").copy(commandId = "retry-on",
            onEvidence = ResetCommandEvidence("run", "retry-on", 350L, true, true, 400L))
        val terminal = first.terminal(retry, ResetDisposition.RESTORED)
        assertEquals(1, terminal.rows.size)
        assertEquals(first.rows.single().sequence, terminal.rows.single().sequence)
        assertEquals("Xiaomi Band", terminal.rows.single().advertisementName)
        assertEquals(1L, terminal.total)
    }

    @Test fun `only same run entered submitted ordered observed and ended evidence counts`() {
        val good = proven("run")
        val exclusions = listOf(
            good.copy(offEvidence = null),
            good.copy(onEvidence = null),
            good.copy(offEvidence = good.offEvidence!!.copy(enteredAt = null)),
            good.copy(onEvidence = good.onEvidence!!.copy(enteredAt = null)),
            good.copy(offEvidence = good.offEvidence!!.copy(submitted = false)),
            good.copy(onEvidence = good.onEvidence!!.copy(submitted = false)),
            good.copy(offEvidence = good.offEvidence!!.copy(runId = "other")),
            good.copy(onEvidence = good.onEvidence!!.copy(commandId = "stale-command")),
            good.copy(offEvidence = good.offEvidence!!.copy(authorityEnded = false)),
            good.copy(authorityEnded = false),
            good.copy(offEvidence = good.offEvidence!!.copy(observedAt = 100L)),
            good.copy(onEvidence = good.onEvidence!!.copy(observedAt = 250L)),
            good.copy(onEnteredAfterOffObservation = false),
            good.copy(onEvidence = good.onEvidence!!.copy(enteredAt = 190L)),
            good.copy(firstOffObservedAt = null),
            good.copy(firstOnObservedAt = null),
            good.copy(stage = ResetStage.ON_READY),
            good.copy(phase = ReEnablePolicy.Phase.WAITING_OFF),
        )
        exclusions.forEachIndexed { index, record ->
            val history = ResetHistory().accept(match("run")).terminal(record, ResetDisposition.RESTORED)
            assertEquals("exclusion $index", 0L, history.total)
            assertFalse("exclusion $index", history.rows.single().counted)
        }
        assertEquals(0L, ResetHistory().terminal(good, ResetDisposition.RESTORED).total) // no accepted automatic row
        assertEquals(0L, ResetHistory().accept(match("run")).terminal(good, ResetDisposition.FAILED).total)
        val legacy = ResetRecord("run", "on", stage = ResetStage.ON_SETTLED,
            phase = ReEnablePolicy.Phase.WAITING_ON)
        assertEquals(0L, ResetHistory().accept(match("run")).terminal(legacy, ResetDisposition.RESTORED).total)
    }

    @Test fun `completed cap protects pending and late updates cannot recreate a pruned run`() {
        var history = ResetHistory().accept(match("protected"))
        history = history.terminal(proven("protected"), ResetDisposition.RESTORED, setOf("protected"))
        repeat(104) { index ->
            val run = "run-$index"
            history = history.accept(match(run), setOf("protected"))
            history = history.terminal(proven(run), ResetDisposition.RESTORED, setOf("protected"))
        }
        assertEquals(101, history.rows.size) // protected completed + 100 other completed
        assertNotNull(history.rows.find { it.runId == "protected" })
        assertNull(history.rows.find { it.runId == "run-0" })
        val frozen = history
        assertEquals(frozen, history.updateExisting(proven("run-0")))
        assertEquals(frozen, history.terminal(proven("run-0"), ResetDisposition.RESTORED))
        assertEquals(105L, history.total) // independent of retention
        history = history.accept(match("active"), setOf("protected", "active"))
        assertNotNull(history.rows.find { it.runId == "active" })
    }

    @Test fun `dismissal changes no total or evidence and remains dismissed after terminal update`() {
        val accepted = ResetHistory().accept(match("run"))
        val dismissed = accepted.dismiss("run")
        assertEquals(accepted.total, dismissed.total)
        assertEquals(accepted.nextSequence, dismissed.nextSequence)
        assertEquals(accepted.rows.single().copy(dismissed = true), dismissed.rows.single())
        val terminal = dismissed.terminal(proven("run"), ResetDisposition.RESTORED)
        assertTrue(terminal.rows.single().dismissed)
        assertEquals(1L, terminal.total)
        assertEquals(terminal, terminal.dismiss("missing"))
    }

    @Test fun `unknown and overflow total never manufacture zero or wrap negative`() {
        val unknown = ResetHistory(total = null).accept(match("run")).terminal(proven("run"), ResetDisposition.RESTORED)
        assertNull(unknown.total)
        assertNull(unknown.rows.single().asOfTotal)
        assertFalse(unknown.rows.single().counted)
        val overflow = ResetHistory(total = Long.MAX_VALUE).accept(match("run"))
            .terminal(proven("run"), ResetDisposition.RESTORED)
        assertNull(overflow.total)
        assertFalse(overflow.rows.single().counted)
    }

    @Test fun `row summaries and backend history are bounded while mixed mode bits survive`() {
        val accepted = ResetHistory().accept(match("run").copy(scanSummary = "s".repeat(4_000)))
        val record = proven("run").copy(modeBits = (1 shl SwitchMode.SHIZUKU_SHELL.ordinal) or (1 shl SwitchMode.ORDINARY.ordinal),
            history = List(30) { BackendEntry(SwitchMode.ORDINARY, "x".repeat(2_000)) })
        val row = accepted.updateExisting(record).rows.single()
        assertEquals(1_024, row.scanSummary.length)
        assertEquals(12, row.backendHistory.size)
        assertTrue(row.backendHistory.all { it.reason.length <= 512 })
        assertEquals(record.modeBits, row.modeBits)
    }
}
