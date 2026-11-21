package com.blreay.bandreset.gpt;

import org.junit.Test;
import static org.junit.Assert.*;

public class InvocationHandshakeTest {
    @Test public void readyKeepsActivityUntilTerminal() {
        InvocationHandshake h = new InvocationHandshake(); int[] finishes = {0};
        h.attach(() -> finishes[0]++); assertTrue(h.request(100)); h.ready();
        assertEquals(0, finishes[0]); assertFalse(h.isFinished());
        h.terminal("RESET_COMPLETE"); assertEquals(1, finishes[0]);
    }
    @Test public void terminalBeforeReadyAndDuplicateCallbacksAreIdempotent() {
        InvocationHandshake h = new InvocationHandshake(); int[] finishes = {0};
        h.attach(() -> finishes[0]++); h.request(0); h.terminal("ERROR"); h.ready();
        h.terminal("RESET_COMPLETE"); h.timeout(90000);
        assertEquals("ERROR", h.outcome()); assertEquals(1, finishes[0]);
    }
    @Test public void startupTimeoutDoesNotIncludePermissionTime() {
        InvocationHandshake h = new InvocationHandshake(); h.timeout(999999); assertFalse(h.isFinished());
        h.request(1000000); h.timeout(1003499); assertFalse(h.isFinished());
        h.timeout(1003500); assertEquals("SERVICE_READY_TIMEOUT", h.outcome());
    }
    @Test public void readyOnlyCancelsStartupDeadlineNotOperationWatchdog() {
        InvocationHandshake h = new InvocationHandshake(); h.request(100); h.ready();
        h.timeout(3600); assertFalse(h.isFinished()); h.ready();
        h.timeout(90099); assertFalse(h.isFinished()); h.timeout(90100);
        assertEquals("OPERATION_TIMEOUT", h.outcome());
    }
    @Test public void detachDoesNotEndOperationAndRecreatedCallbackGetsTerminal() {
        InvocationHandshake h = new InvocationHandshake(); int[] old = {0}, fresh = {0};
        Runnable original = () -> old[0]++; h.attach(original); h.request(0); h.detach(original);
        h.ready(); h.terminal("RESET_COMPLETE"); assertEquals(0, old[0]);
        h.attach(() -> fresh[0]++); assertEquals(1, fresh[0]);
    }
    @Test public void staleDetachCannotRemoveRecreatedActivity() {
        InvocationHandshake h = new InvocationHandshake(); int[] finishes = {0};
        Runnable old = () -> fail("destroyed activity retained");
        h.attach(old); h.attach(() -> finishes[0]++); h.detach(old);
        h.terminal("ERROR"); assertEquals(1, finishes[0]);
    }
    @Test public void duplicateRequestNeverRestartsDeadlines() {
        InvocationHandshake h = new InvocationHandshake(); assertTrue(h.request(0));
        assertFalse(h.request(3000)); h.timeout(3500);
        assertEquals("SERVICE_READY_TIMEOUT", h.outcome()); assertFalse(h.request(4000));
    }
    @Test public void recreationWhileRunningDoesNotRequestAgain() {
        InvocationHandshake h = new InvocationHandshake(); Runnable old = () -> {};
        h.attach(old); h.request(0); h.ready(); h.detach(old); h.attach(() -> {});
        assertFalse(h.request(5000)); assertFalse(h.isFinished());
    }

    // --- SPEC-identified gaps: recreation + ordering edge cases ---

    @Test public void readyArrivesWhileNoCallbackAttachedThenNewActivityWaitsForTerminal() {
        // Activity A requests, gets destroyed before ready, Activity B attaches after ready.
        InvocationHandshake h = new InvocationHandshake(); int[] finishes = {0};
        Runnable a = () -> fail("destroyed activity should not receive ready-then-terminal");
        h.attach(a); h.request(0);
        h.detach(a); // Activity A destroyed before ready
        h.ready();   // Ready while no callback — stored, not delivered
        assertEquals(0, finishes[0]);
        Runnable b = () -> finishes[0]++;
        h.attach(b); // Activity B attaches — should NOT get terminal yet (not finished)
        assertEquals(0, finishes[0]);
        assertFalse(h.isFinished());
        h.terminal("RESET_COMPLETE");
        assertEquals(1, finishes[0]);
        assertEquals("RESET_COMPLETE", h.outcome());
    }

    @Test public void terminalArrivesWhileNoCallbackThenNewActivityGetsImmediateDelivery() {
        InvocationHandshake h = new InvocationHandshake(); int[] finishes = {0};
        Runnable a = () -> fail("destroyed activity should not receive terminal");
        h.attach(a); h.request(0); h.detach(a);
        // Terminal while no callback — outcome stored, deliver blocked.
        h.terminal("ERROR");
        assertEquals("ERROR", h.outcome());
        assertEquals(0, finishes[0]);
        // Recreated Activity B attaches — immediately gets the cached terminal.
        Runnable b = () -> finishes[0]++;
        h.attach(b);
        assertEquals(1, finishes[0]);
    }

    @Test public void readyDeliveredBetweenTwoPreTerminalAttachesDoesNotDoubleDeliver() {
        InvocationHandshake h = new InvocationHandshake();
        int[] deliveries = {0};
        Runnable original = () -> deliveries[0]++;
        h.attach(original); h.request(0);
        h.ready();
        h.detach(original);
        // Second Activity recreated before terminal.
        h.attach(() -> deliveries[0]++);
        assertFalse(h.isFinished());
        h.terminal("RESET_COMPLETE");
        assertEquals(1, deliveries[0]);
    }

    @Test public void operationDeadlineAppliesFromRequestNotFromReady() {
        InvocationHandshake h = new InvocationHandshake();
        h.request(0);
        h.ready();
        // Operation watchdog uses absolute elapsed from request(0), not from ready().
        // Deadline = requestAt + 90000 = 90000.
        h.timeout(89999);
        assertFalse(h.isFinished());
        h.timeout(90000);
        assertEquals("OPERATION_TIMEOUT", h.outcome());
    }

    @Test public void readyTimeoutResetsWhenReadyArrivesBeforeDeadline() {
        InvocationHandshake h = new InvocationHandshake();
        h.request(2000);
        h.timeout(5499); // 3500 ms elapsed, not yet expired
        assertFalse(h.isFinished());
        h.ready();       // Ready cancels startup deadline
        h.timeout(5500); // Would be startup timeout, but ready already arrived
        assertFalse(h.isFinished());
    }

    @Test public void detachedActivityCannotCancelOngoingOperation() {
        InvocationHandshake h = new InvocationHandshake(); int[] finishes = {0};
        Runnable a = () -> finishes[0]++;  // This callback WAS delivered terminal
        h.attach(a); h.request(0); h.ready(); h.terminal("RESET_COMPLETE");
        assertEquals(1, finishes[0]);
        // Detach after terminal is harmless and does not resurrect.
        h.detach(a);
        int[] lateDeliveries = {0};
        h.attach(() -> lateDeliveries[0]++);
        // Finished handshake immediately delivers to newly attached callback.
        assertEquals(1, lateDeliveries[0]);
        assertEquals("RESET_COMPLETE", h.outcome());
    }
}
