package com.blreay.bluetoothmonitor

import com.blreay.bluetoothmonitor.RunReport.NotFoundReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the classification that turns a silent NOT_FOUND into an explanation.
 *
 * The whole point of these tests is the ordering. Several signals can be true
 * at once - a scan can fail AND report a near miss, a window can be cut short
 * AND see nothing - and the notification only gets to name one reason. Which
 * one wins is a real decision, so each precedence rule gets its own test
 * rather than being left implicit in a `when`.
 *
 * The facts here are the ones that discriminate the two causes that look
 * identical on screen: a band that is connected (so it stopped advertising)
 * and a scan the system silently starved. The first still sees plenty of
 * other devices; the second sees none at all.
 */
class RunReportTest {

    /** A scan that ran its full window and saw a normal room full of devices. */
    private fun facts(
        windowMs: Long = 8_000L,
        elapsedMs: Long = 8_000L,
        devicesSeen: Int = 14,
        namedSeen: Int = 9,
        nearMisses: Int = 0,
        targetBonded: Boolean = false,
        scanFailedCode: Int? = null,
    ) = RunReport.ScanFacts(
        windowMs = windowMs,
        elapsedMs = elapsedMs,
        devicesSeen = devicesSeen,
        namedSeen = namedSeen,
        nearMisses = nearMisses,
        targetBonded = targetBonded,
        scanFailedCode = scanFailedCode,
    )

    // --- the two cases that matter most, and must not be confused ---

    @Test
    fun `full window and plenty of devices but no band means it was not advertising`() {
        assertEquals(NotFoundReason.SEEN_BUT_NO_BAND, RunReport.whyNotFound(facts()))
    }

    @Test
    fun `full window and zero devices means the scan was starved`() {
        assertEquals(
            NotFoundReason.NOTHING_SEEN,
            RunReport.whyNotFound(facts(devicesSeen = 0, namedSeen = 0)),
        )
    }

    // --- explicit failure outranks everything ---

    @Test
    fun `an onScanFailed code is reported as a scan failure`() {
        assertEquals(
            NotFoundReason.SCAN_FAILED,
            RunReport.whyNotFound(facts(scanFailedCode = 2)),
        )
    }

    @Test
    fun `a scan failure outranks having seen a near miss`() {
        assertEquals(
            NotFoundReason.SCAN_FAILED,
            RunReport.whyNotFound(facts(nearMisses = 1, scanFailedCode = 2)),
        )
    }

    @Test
    fun `a scan failure outranks a cut short window`() {
        assertEquals(
            NotFoundReason.SCAN_FAILED,
            RunReport.whyNotFound(facts(elapsedMs = 900L, scanFailedCode = 1)),
        )
    }

    // --- near miss is the most actionable non-failure signal ---

    @Test
    fun `something band shaped was seen but did not match`() {
        assertEquals(NotFoundReason.NEAR_MISS, RunReport.whyNotFound(facts(nearMisses = 2)))
    }

    @Test
    fun `a near miss outranks a cut short window`() {
        // If we saw something band shaped the scan clearly worked, so the
        // short window is not the interesting part of the story.
        assertEquals(
            NotFoundReason.NEAR_MISS,
            RunReport.whyNotFound(facts(elapsedMs = 1_200L, nearMisses = 1)),
        )
    }

    @Test
    fun `a near miss outranks seeing nothing else`() {
        assertEquals(
            NotFoundReason.NEAR_MISS,
            RunReport.whyNotFound(facts(devicesSeen = 1, namedSeen = 1, nearMisses = 1)),
        )
    }

    // --- cut short window ---

    @Test
    fun `a window that ended early is reported as cut short`() {
        assertEquals(
            NotFoundReason.WINDOW_CUT_SHORT,
            RunReport.whyNotFound(facts(elapsedMs = 1_000L, devicesSeen = 3)),
        )
    }

    @Test
    fun `exactly at the cut short threshold is not cut short`() {
        // elapsed == 0.6 * window is the boundary; the rule is strictly less.
        assertEquals(
            NotFoundReason.SEEN_BUT_NO_BAND,
            RunReport.whyNotFound(facts(windowMs = 10_000L, elapsedMs = 6_000L)),
        )
    }

    @Test
    fun `just below the cut short threshold is cut short`() {
        assertEquals(
            NotFoundReason.WINDOW_CUT_SHORT,
            RunReport.whyNotFound(facts(windowMs = 10_000L, elapsedMs = 5_999L)),
        )
    }

    @Test
    fun `a zero length window is never cut short`() {
        // Guards the threshold arithmetic against a divide or a false positive
        // if a caller ever passes a degenerate window.
        assertEquals(
            NotFoundReason.SEEN_BUT_NO_BAND,
            RunReport.whyNotFound(facts(windowMs = 0L, elapsedMs = 0L)),
        )
    }

    // --- precedence between the two "nothing useful" cases ---

    @Test
    fun `a cut short window outranks seeing zero devices`() {
        // Both are true, but "we only got to scan for 400ms" explains the zero
        // far better than "the system starved us" would.
        assertEquals(
            NotFoundReason.WINDOW_CUT_SHORT,
            RunReport.whyNotFound(facts(elapsedMs = 400L, devicesSeen = 0, namedSeen = 0)),
        )
    }

    // --- a named-device count never changes the verdict ---

    @Test
    fun `devices seen without names still count as seen`() {
        // Advertisements frequently carry no name at all. A room full of
        // unnamed beacons is still proof the scan was working, so it must not
        // be mistaken for a starved scan.
        assertEquals(
            NotFoundReason.SEEN_BUT_NO_BAND,
            RunReport.whyNotFound(facts(devicesSeen = 20, namedSeen = 0)),
        )
    }
}
