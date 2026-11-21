package com.blreay.bandreset.gpt;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Combined integration tests covering the BandResetActivity+ResetService wiring
 * pattern: InvocationHandshake terminal delivery linked with SessionFeedback
 * one-shot Toast decisions. These exercise the actual helper composition used
 * in production, without requiring the Android framework.
 */
public class InvocationFeedbackIntegrationTest {

    // --- Terminal → handshake + feedback combined flow ---

    @Test public void matchThenRestoredTerminalSuppressesWarningAndDeliversToActivity() {
        InvocationHandshake handshake = new InvocationHandshake();
        SessionFeedback feedback = new SessionFeedback();
        int[] finishes = {0};
        handshake.attach(() -> finishes[0]++);
        handshake.request(0);
        handshake.ready();

        // Band matched — single Toast shown.
        assertTrue(feedback.match());
        assertFalse(feedback.match()); // one-shot

        // Session finishes with restored outcome and actual ON snapshot.
        handshake.terminal("RESET_COMPLETE");
        assertFalse(feedback.terminal("RESET_COMPLETE", ResetController.BluetoothState.ON));
        assertEquals(1, finishes[0]); // Activity finished
        assertEquals("RESET_COMPLETE", handshake.outcome());
    }

    @Test public void matchThenEnableTimeoutWarnsAndDeliversToActivity() {
        InvocationHandshake handshake = new InvocationHandshake();
        SessionFeedback feedback = new SessionFeedback();
        int[] finishes = {0};
        handshake.attach(() -> finishes[0]++);
        handshake.request(0);
        handshake.ready();

        assertTrue(feedback.match());

        handshake.terminal("ENABLE_TIMEOUT");
        // Snapshot is OFF → warning should be shown.
        assertTrue(feedback.terminal("ENABLE_TIMEOUT", ResetController.BluetoothState.OFF));
        assertEquals(1, finishes[0]);
    }

    @Test public void noMatchThenTerminalNeverWarnsRegardlessOfSnapshot() {
        for (ResetController.BluetoothState snapshot : ResetController.BluetoothState.values()) {
            InvocationHandshake handshake = new InvocationHandshake();
            SessionFeedback feedback = new SessionFeedback();
            int[] finishes = {0};
            handshake.attach(() -> finishes[0]++);
            handshake.request(0);
            handshake.ready();

            handshake.terminal("TARGET_NOT_FOUND");
            assertFalse("no-match must not warn for " + snapshot,
                    feedback.terminal("TARGET_NOT_FOUND", snapshot));
            assertEquals(1, finishes[0]);
        }
    }

    @Test public void matchThenLateSnapshotOnSuppressesWarning() {
        InvocationHandshake handshake = new InvocationHandshake();
        SessionFeedback feedback = new SessionFeedback();
        int[] finishes = {0};
        handshake.attach(() -> finishes[0]++);
        handshake.request(0);
        handshake.ready();

        assertTrue(feedback.match());

        // Terminal arrives but final snapshot check shows ON — BT actually recovered.
        handshake.terminal("ENABLE_TIMEOUT");
        assertFalse(feedback.terminal("ENABLE_TIMEOUT", ResetController.BluetoothState.ON));
        assertEquals(1, finishes[0]);
    }

    // --- Feedback ordering: match must precede terminal in real flow ---

    @Test public void terminalBeforeMatchMeansNoMatchFeedback() {
        InvocationHandshake handshake = new InvocationHandshake();
        SessionFeedback feedback = new SessionFeedback();
        handshake.attach(() -> {});
        handshake.request(0);

        // Terminal arrives without any match (e.g., scan timeout).
        handshake.terminal("TARGET_NOT_FOUND");
        // match() was never called → feedback acts as if no match.
        assertFalse(feedback.terminal("TARGET_NOT_FOUND", ResetController.BluetoothState.OFF));
    }

    @Test public void matchThenRecoveryReasonRestoredDoesNotWarn() {
        InvocationHandshake handshake = new InvocationHandshake();
        SessionFeedback feedback = new SessionFeedback();
        int[] finishes = {0};
        handshake.attach(() -> finishes[0]++);
        handshake.request(0);
        handshake.ready();

        assertTrue(feedback.match());

        // Recovery reason like DISABLE_TIMEOUT still results in RESTORED outcome.
        handshake.terminal("DISABLE_TIMEOUT_RESTORED");
        assertFalse(feedback.terminal("DISABLE_TIMEOUT_RESTORED", ResetController.BluetoothState.UNKNOWN));
        assertEquals(1, finishes[0]);
    }

    // --- Terminal delivery ordering: terminal-before-ready is idempotent ---

    @Test public void terminalBeforeReadyDoesNotDoubleDeliverWhenReadyArrivesLater() {
        InvocationHandshake handshake = new InvocationHandshake();
        SessionFeedback feedback = new SessionFeedback();
        int[] finishes = {0};
        handshake.attach(() -> finishes[0]++);
        handshake.request(0);

        // Service startup failed before ready.
        handshake.terminal("SERVICE_START_FAILED");
        assertEquals(1, finishes[0]);

        // Late ready from another thread — must not re-deliver or reset state.
        handshake.ready();
        assertEquals("SERVICE_START_FAILED", handshake.outcome());
        assertEquals(1, finishes[0]);
    }

    @Test public void multipleTerminalOutcomesOnlyFirstCountsForFeedbackAndHandshake() {
        InvocationHandshake handshake = new InvocationHandshake();
        SessionFeedback feedback = new SessionFeedback();
        int[] finishes = {0};
        handshake.attach(() -> finishes[0]++);
        handshake.request(0);
        handshake.ready();

        assertTrue(feedback.match());

        // First terminal.
        handshake.terminal("ENABLE_TIMEOUT");
        assertTrue(feedback.terminal("ENABLE_TIMEOUT", ResetController.BluetoothState.OFF));
        assertEquals(1, finishes[0]);

        // Second terminal — handshake ignores, feedback already finished.
        handshake.terminal("RESET_COMPLETE");
        assertFalse(feedback.terminal("RESET_COMPLETE", ResetController.BluetoothState.ON));
        assertEquals("ENABLE_TIMEOUT", handshake.outcome());
        assertEquals(1, finishes[0]);
    }
}