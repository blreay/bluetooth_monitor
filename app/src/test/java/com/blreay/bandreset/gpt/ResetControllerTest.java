package com.blreay.bandreset.gpt;

import org.junit.Test;
import com.blreay.bandreset.gpt.ResetController.BluetoothState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import static org.junit.Assert.*;

/** Tests drive real elapsed time, not an arbitrarily fired single timeout. */
public class ResetControllerTest {
    private final Fake port = new Fake();
    private final ResetController controller = new ResetController(port);

    private void match() { controller.start(); controller.onAdvertisement(ResetController.TARGET_NAME); }
    // Compatibility entry points must also read actual snapshots, never trust the event name.
    private void off() { port.snapshot = BluetoothState.OFF; controller.onBluetoothOff(); }
    private void on() { port.snapshot = BluetoothState.ON; controller.onBluetoothOn(); }
    private void enabling() { match(); off(); port.advance(3000); assertEquals(1, port.enables); }
    private void restored(String expected) { on(); result(expected); }
    private void result(String expected) {
        assertEquals(expected, port.result);
        assertEquals(1, port.finishes);
        assertEquals(ResetController.State.FINISHED, controller.getState());
        assertFalse(port.scanning);
        assertEquals("controller timer queue must be empty", 0, port.timers.size());
    }

    @Test public void exactConstantsAndInitialState() {
        assertEquals("Xiaomi Smart Band 8 35BA", ResetController.TARGET_NAME);
        assertEquals(15000, ResetController.SCAN_TIMEOUT_MS);
        assertEquals(15000, ResetController.TRANSITION_TIMEOUT_MS);
        assertEquals(3000, ResetController.OFF_DELAY_MS);
        assertEquals(45000, ResetController.DISABLE_TIMEOUT_MS);
        assertEquals(250, ResetController.POLL_INTERVAL_MS);
        assertEquals(ResetController.State.NEW, controller.getState());
    }
    @Test public void initiallyOffNeverEnables() {
        port.snapshot = BluetoothState.OFF; controller.start(); result("INITIAL_BLUETOOTH_OFF");
        assertEquals(0, port.starts); assertEquals(0, port.disables); assertEquals(0, port.enables);
    }
    @Test public void initiallyUnknownNeverEnables() { initialNotOn(BluetoothState.UNKNOWN); }
    @Test public void initiallyTurningOffNeverEnables() { initialNotOn(BluetoothState.TURNING_OFF); }
    @Test public void initiallyTurningOnNeverEnables() { initialNotOn(BluetoothState.TURNING_ON); }
    private void initialNotOn(BluetoothState state) {
        port.snapshot = state; controller.start(); result("INITIAL_BLUETOOTH_NOT_ON");
        assertEquals(0, port.starts); assertEquals(0, port.disables); assertEquals(0, port.enables);
    }
    @Test public void scanHasAbsoluteFifteenSecondDeadline() {
        controller.start(); port.advance(14999); assertNull(port.result);
        port.advance(1); result("TARGET_NOT_FOUND"); assertEquals(0, port.disables);
    }
    @Test public void scanPollsActualStateWithoutBroadcasts() {
        controller.start(); port.advance(1); port.snapshot = BluetoothState.OFF;
        port.advance(248); assertNull(port.result);
        port.advance(1); result("BLUETOOTH_OFF_DURING_SCAN"); assertEquals(0, port.enables);
    }
    @Test public void unknownDuringScanIsNotOff() {
        controller.start(); port.snapshot = BluetoothState.UNKNOWN; port.advance(1000);
        assertNull(port.result); assertEquals(0, port.disables);
        port.advance(14000); result("TARGET_NOT_FOUND");
    }
    @Test public void onlyExactLiveNameMatches() {
        controller.start();
        for (String name : new String[] { null, "", "xiaomi Smart Band 8 35BA",
                ResetController.TARGET_NAME + " ", "Xiaomi Smart Band 8", " " + ResetController.TARGET_NAME }) {
            controller.onAdvertisement(name);
        }
        assertEquals(0, port.disables); assertEquals(0, port.matches);
        controller.onAdvertisement(ResetController.TARGET_NAME);
        assertEquals(1, port.disables); assertEquals(1, port.matches);
    }
    @Test public void matchNotificationOnceBeforeDisable() {
        match(); controller.onAdvertisement(ResetController.TARGET_NAME); controller.start();
        assertEquals("start,stop,match,disable", String.join(",", port.actions));
        assertEquals(1, port.matches); assertEquals(1, port.disables);
    }
    @Test public void falseDisableContinuesObservingDelayedOff() {
        port.disableAccepted = false; match(); assertNull(port.result);
        port.advance(1000); off(); port.advance(2999); assertEquals(0, port.enables);
        port.advance(1); assertEquals(1, port.enables); restored("RESET_COMPLETE");
    }
    @Test public void falseDisableWithoutOffNeverEnablesAndIsBounded() {
        port.disableAccepted = false; match(); port.advance(44999); assertNull(port.result);
        port.advance(1); result("DISABLE_TIMEOUT"); assertEquals(1, port.disables); assertEquals(0, port.enables);
    }
    @Test public void consentWindowIsFortyFiveSeconds() {
        match(); port.advance(44000); assertNull(port.result); off(); port.advance(3000);
        restored("RESET_COMPLETE"); assertEquals(1, port.disables);
    }
    @Test public void noBroadcastsPollOffAndOnEvery250ms() {
        match(); port.advance(1); port.snapshot = BluetoothState.OFF;
        port.advance(248); assertEquals(ResetController.State.WAITING_OFF, controller.getState());
        port.advance(1); assertEquals(ResetController.State.OFF_DELAY, controller.getState());
        port.advance(2999); assertEquals(0, port.enables); port.advance(1); assertEquals(1, port.enables);
        port.snapshot = BluetoothState.ON; port.advance(249); assertNull(port.result);
        port.advance(1); result("RESET_COMPLETE");
    }
    @Test public void delayStartsAtFirstObservedOffNotDisableOrPhysicalChange() {
        match(); port.advance(501); port.snapshot = BluetoothState.OFF;
        port.advance(249); // OFF first observed at 750.
        port.advance(2999); assertEquals(0, port.enables);
        port.advance(1); assertEquals(3750, port.enabledAt); restored("RESET_COMPLETE");
    }
    @Test public void promptOffAtUnalignedTimeGetsExactThreeSecondDelay() {
        match(); port.advance(137); off(); port.advance(2999); assertEquals(0, port.enables);
        port.advance(1); assertEquals(3137, port.enabledAt); restored("RESET_COMPLETE");
    }
    @Test public void offBroadcastCannotFabricateOff() {
        match(); controller.onBluetoothOff(); port.advance(3000);
        assertEquals(ResetController.State.WAITING_OFF, controller.getState()); assertEquals(0, port.enables);
    }
    @Test public void onBroadcastCannotFabricateOn() {
        enabling(); controller.onBluetoothOn(); assertNull(port.result);
        port.advance(15000); result("ENABLE_TIMEOUT");
    }
    @Test public void misleadingOffPromptStillReadsActualOn() {
        enabling(); port.snapshot = BluetoothState.ON; controller.onBluetoothOff(); result("RESET_COMPLETE");
    }
    @Test public void misleadingOnPromptStillReadsActualOff() {
        match(); port.snapshot = BluetoothState.OFF; controller.onBluetoothOn();
        port.advance(3000); restored("RESET_COMPLETE");
    }
    @Test public void falseEnableContinuesObservingDelayedOn() {
        port.enableAccepted = false; enabling(); assertNull(port.result);
        port.advance(1000); port.snapshot = BluetoothState.ON; port.advance(250); result("RESET_COMPLETE");
        assertEquals(1, port.enables);
    }
    @Test public void falseEnableWithoutOnHasFullBoundedWindow() {
        port.enableAccepted = false; enabling(); port.advance(14999); assertNull(port.result);
        port.advance(1); result("ENABLE_TIMEOUT"); assertEquals(1, port.enables);
    }
    @Test public void enableDeadlineReadsActualOnAtBoundary() {
        enabling(); port.at(18000, () -> port.snapshot = BluetoothState.ON);
        port.advance(15000); result("RESET_COMPLETE");
    }
    @Test public void offAtConsentBoundaryStartsFullDelay() {
        match(); port.at(45000, () -> port.snapshot = BluetoothState.OFF); port.advance(45000);
        port.advance(2999); assertEquals(0, port.enables); port.advance(1); restored("RESET_COMPLETE");
    }
    @Test public void offAfterConsentDeadlineCannotRestartFinishedRun() {
        match(); port.advance(45000); result("DISABLE_TIMEOUT");
        port.advance(1); off(); port.advance(3000);
        result("DISABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void offPromptFirstObservedAt45001msCannotStartRecovery() {
        match(); port.now = 45001; off();
        result("DISABLE_TIMEOUT"); port.advance(3000); assertEquals(0, port.enables);
    }
    @Test public void nominalRunFitsWithinSeventyEightSeconds() {
        controller.start(); port.advance(14999);
        controller.onAdvertisement(ResetController.TARGET_NAME);
        port.at(59999, () -> port.snapshot = BluetoothState.OFF); port.advance(45000);
        port.advance(2999); assertEquals(0, port.enables);
        port.advance(1); assertEquals(62999, port.enabledAt);
        port.advance(14999); assertNull(port.result);
        port.advance(1); result("ENABLE_TIMEOUT"); assertEquals(77999, port.now);
        assertTrue(port.now <= 78000); assertEquals(1, port.disables); assertEquals(1, port.enables);
    }
    @Test public void persistentUnknownNeverFabricatesOff() { noOff(BluetoothState.UNKNOWN); }
    @Test public void persistentTurningOffNeverFabricatesOff() { noOff(BluetoothState.TURNING_OFF); }
    @Test public void persistentTurningOnNeverFabricatesOff() { noOff(BluetoothState.TURNING_ON); }
    private void noOff(BluetoothState snapshot) {
        match(); port.snapshot = snapshot; port.advance(44999); assertNull(port.result);
        port.advance(1); result("DISABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void temporaryUnknownAfterDelayRechecksWithinEnableWindow() { temporaryAfterDelay(BluetoothState.UNKNOWN); }
    @Test public void temporaryTurningOffAfterDelayRechecksWithinEnableWindow() { temporaryAfterDelay(BluetoothState.TURNING_OFF); }
    private void temporaryAfterDelay(BluetoothState snapshot) {
        match(); off(); port.snapshot = snapshot; port.advance(3000);
        assertNull(port.result); assertEquals(0, port.enables);
        port.advance(1); port.snapshot = BluetoothState.OFF; port.advance(249);
        assertEquals(1, port.enables); assertEquals(3250, port.enabledAt); restored("RESET_COMPLETE");
    }
    @Test public void persistentUnknownAfterDelayTimesOutWithoutEnable() { persistentAfterDelay(BluetoothState.UNKNOWN); }
    @Test public void persistentTurningOffAfterDelayTimesOutWithoutEnable() { persistentAfterDelay(BluetoothState.TURNING_OFF); }
    private void persistentAfterDelay(BluetoothState snapshot) {
        match(); off(); port.snapshot = snapshot; port.advance(17999); assertNull(port.result);
        port.advance(1); result("ENABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void delayedEnableDoesNotMoveWindowEnd() {
        match(); off(); port.snapshot = BluetoothState.UNKNOWN; port.advance(17000);
        port.snapshot = BluetoothState.OFF; port.advance(250); assertEquals(1, port.enables);
        port.advance(749); assertNull(port.result); port.advance(1); result("ENABLE_TIMEOUT");
    }
    @Test public void offOnlyAtEnableDeadlineDoesNotIssueFutileRequest() {
        match(); off(); port.snapshot = BluetoothState.UNKNOWN;
        port.at(18000, () -> port.snapshot = BluetoothState.OFF); port.advance(18000);
        result("ENABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void externalTurningOnIsObservedWithoutRequest() {
        match(); off(); port.snapshot = BluetoothState.TURNING_ON; port.advance(3000);
        assertEquals(0, port.enables); port.snapshot = BluetoothState.ON; port.advance(250);
        result("RESET_COMPLETE"); assertEquals(0, port.enables);
    }
    @Test public void turningOnThenOffWithinWindowAllowsOneRequest() {
        match(); off(); port.snapshot = BluetoothState.TURNING_ON; port.advance(3000);
        port.snapshot = BluetoothState.OFF; port.advance(250); assertEquals(1, port.enables);
        restored("RESET_COMPLETE");
    }
    @Test public void externallyOnAtEndOfDelayDoesNotEnable() {
        match(); off(); port.snapshot = BluetoothState.ON; port.advance(2999); assertNull(port.result);
        port.advance(1); result("RESTORED_EXTERNALLY"); assertEquals(0, port.enables);
    }
    @Test public void onDuringOffDelayCannotSkipMinimumDelay() {
        match(); off(); port.advance(100); on(); assertNull(port.result);
        port.advance(2899); assertNull(port.result); port.advance(1); result("RESTORED_EXTERNALLY");
    }
    @Test public void duplicateOffAndCancellationDoNotExtendDelay() {
        match(); off(); port.advance(2999); off(); controller.cancel(); controller.cancel(); controller.onFailure("later");
        port.advance(1); assertEquals(3000, port.enabledAt); restored("CANCELLED_RESTORED");
    }
    @Test public void duplicatePromptsDoNotExtendEnableDeadline() {
        enabling(); port.advance(14999);
        controller.onBluetoothOff(); controller.onBluetoothOn(); controller.cancel(); controller.onFailure("later");
        port.advance(1); result("CANCELLED_ENABLE_TIMEOUT"); assertEquals(1, port.enables);
    }
    @Test public void duplicateAbortDoesNotExtendConsentDeadline() {
        match(); controller.cancel(); port.advance(44999); controller.cancel(); controller.onFailure("later");
        assertNull(port.result); port.advance(1); result("CANCELLED_DISABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void cancellationAfterFalseDisableStillRecovers() {
        port.disableAccepted = false; match(); controller.cancel(); port.advance(1000); off(); port.advance(3000);
        restored("CANCELLED_RESTORED");
    }
    @Test public void failureAfterDisableStillRecovers() {
        match(); controller.onFailure("private details"); controller.onFailure("again"); off(); port.advance(3000);
        restored("ERROR_RESTORED");
    }
    @Test public void cancelledBeforeStartNeverScansOrEnables() {
        controller.cancel(); controller.start(); result("CANCELLED"); assertEquals(0, port.starts); assertEquals(0, port.enables);
    }
    @Test public void cancelledDuringScanCleansUp() {
        controller.start(); controller.cancel(); result("CANCELLED"); assertEquals(0, port.disables);
    }
    @Test public void scanRejectedCleansUp() {
        port.scanAccepted = false; controller.start(); result("SCAN_REJECTED"); assertEquals(1, port.stops);
    }
    @Test public void failureDuringScanCleansUp() {
        controller.start(); controller.onFailure("secret exception"); result("ERROR"); assertEquals(0, port.disables);
    }
    @Test public void failureReenteredFromStopDoesNotDisable() {
        port.onStop = () -> controller.onFailure("stop"); match(); result("ERROR"); assertEquals(0, port.disables);
    }
    @Test public void cancellationReenteredFromMatchDoesNotDisable() {
        port.onMatch = controller::cancel; match(); result("CANCELLED"); assertEquals(1, port.matches); assertEquals(0, port.disables);
    }
    @Test public void synchronousOffOnAndFalseReturnsAreSafe() {
        port.disableAccepted = false; port.enableAccepted = false;
        port.onDisable = this::off; port.onEnable = this::on; match();
        port.advance(3000); result("RESET_COMPLETE"); assertEquals(1, port.disables); assertEquals(1, port.enables);
    }
    @Test public void synchronousFailureDuringDisableKeepsRecovery() {
        port.onDisable = () -> { controller.onFailure("disable"); off(); }; match();
        port.advance(3000); restored("ERROR_RESTORED");
    }
    @Test public void synchronousFailureDuringEnableDoesNotRetry() {
        port.onEnable = () -> controller.onFailure("enable"); enabling(); port.advance(15000);
        result("ERROR_ENABLE_TIMEOUT"); assertEquals(1, port.enables);
    }
    @Test public void synchronousAdvertisementFromScanStartIsSafe() {
        port.onStart = () -> controller.onAdvertisement(ResetController.TARGET_NAME);
        port.scanAccepted = false; controller.start(); off(); port.advance(3000); restored("RESET_COMPLETE");
    }
    @Test public void snapshotReadOneShotReentrantStartReadsOnce() {
        port.onRead = () -> { port.onRead = null; controller.start(); };
        assertReentrantStartIsSingleUse();
    }
    @Test public void snapshotReadPersistentReentrantStartDoesNotRecurse() {
        port.onRead = controller::start;
        assertReentrantStartIsSingleUse();
    }
    private void assertReentrantStartIsSingleUse() {
        controller.start();
        assertEquals("initial snapshot must be read once", 1, port.reads);
        assertEquals(ResetController.State.SCANNING, controller.getState());
        assertEquals(1, port.starts);
        assertEquals(1, port.timers.size());
        controller.start();
        assertEquals(1, port.reads);
        port.advance(15000);
        result("TARGET_NOT_FOUND");
        assertEquals(1, port.starts); assertEquals(1, port.stops);
        assertEquals(0, port.disables); assertEquals(0, port.enables);
    }
    @Test public void snapshotReadReentrantCancellationDoesNotResurrectRun() {
        port.onRead = controller::cancel; controller.start(); result("CANCELLED"); assertEquals(0, port.starts);
    }
    @Test public void snapshotReadReentrantStatePromptDoesNotRecurse() {
        match(); port.snapshot = BluetoothState.OFF; port.onRead = controller::onBluetoothOff;
        controller.onBluetoothOff(); port.onRead = null;
        port.advance(3000); restored("RESET_COMPLETE");
    }
    @Test public void staleScanTimerCannotAbortReset() {
        controller.start(); Runnable stale = port.currentTimer(); controller.onAdvertisement(ResetController.TARGET_NAME);
        stale.run(); assertEquals(ResetController.State.WAITING_OFF, controller.getState());
        off(); port.advance(3000); restored("RESET_COMPLETE");
    }
    @Test public void staleOffPollCannotSkipDelay() {
        match(); Runnable stale = port.currentTimer(); off(); stale.run();
        port.advance(2999); assertEquals(0, port.enables); port.advance(1); restored("RESET_COMPLETE");
    }
    @Test public void duplicateDequeuedTimerCannotRunAgain() {
        match(); off(); Runnable stale = port.currentTimer(); port.advance(250); stale.run();
        port.advance(2750); assertEquals(1, port.enables); stale.run(); restored("RESET_COMPLETE");
        stale.run(); assertEquals(1, port.enables);
    }
    @Test public void terminalCleanupAndAllStaleCallbacksAreIdempotent() {
        controller.start(); Runnable stale = port.currentTimer(); port.onStop = () -> controller.onFailure("cleanup");
        controller.cancel(); stale.run(); controller.cancel(); controller.onFailure("late");
        controller.onAdvertisement(ResetController.TARGET_NAME); off(); on(); port.advance(100000);
        result("CANCELLED"); assertEquals(1, port.stops); assertEquals(0, port.disables); assertEquals(0, port.enables);
    }
    @Test public void nonzeroMonotonicOriginDoesNotChangeDurations() {
        port.now = 900000000L; match(); port.advance(123); off(); port.advance(3000);
        assertEquals(900003123L, port.enabledAt); restored("RESET_COMPLETE");
    }
    @Test public void lateTimerDeliveryDoesNotExtendEnableWindow() {
        match(); off(); port.jumpTo(20000); result("ENABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void delayedDeadlineDeliveryFirstReadsOffAt45001msWithoutRecovery() {
        match(); port.advance(44750); port.snapshot = BluetoothState.OFF; port.jumpTo(45001);
        result("DISABLE_TIMEOUT"); port.advance(3000); assertEquals(0, port.enables);
    }
    @Test public void delayedDeadlineDeliveryStillUsesOriginalConsentBoundary() {
        match(); port.advance(44750); port.jumpTo(45001);
        result("DISABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void muchLaterConsentTimerCannotExtendDeadlineEvenWithOff() {
        match(); port.snapshot = BluetoothState.OFF; port.jumpTo(59000);
        result("DISABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void advertisementAfterExpiredScanCannotRequestDisable() {
        controller.start(); port.now = 15000;
        controller.onAdvertisement(ResetController.TARGET_NAME); result("TARGET_NOT_FOUND");
        assertEquals(0, port.matches); assertEquals(0, port.disables);
    }
    @Test public void newStatePromptReadsActualSnapshot() {
        match(); port.snapshot = BluetoothState.OFF; controller.onBluetoothStateChanged();
        port.advance(3000); port.snapshot = BluetoothState.ON; controller.onBluetoothStateChanged();
        result("RESET_COMPLETE");
    }

    private static final class Event {
        final long due, sequence;
        final Runnable callback;
        Event(long due, long sequence, Runnable callback) { this.due = due; this.sequence = sequence; this.callback = callback; }
    }
    private final class Fake implements ResetController.Port {
        BluetoothState snapshot = BluetoothState.ON;
        boolean scanning, scanAccepted = true, disableAccepted = true, enableAccepted = true;
        int starts, stops, disables, enables, finishes, matches, reads;
        long now, sequence, enabledAt = -1;
        String result;
        Runnable onStart, onStop, onDisable, onEnable, onMatch, onRead;
        final List<String> actions = new ArrayList<>();
        final PriorityQueue<Event> timers = new PriorityQueue<>(Comparator.comparingLong((Event e) -> e.due).thenComparingLong(e -> e.sequence));
        final PriorityQueue<Event> external = new PriorityQueue<>(Comparator.comparingLong((Event e) -> e.due).thenComparingLong(e -> e.sequence));
        @Override public long nowMs() { return now; }
        @Override public void onMatch() { matches++; actions.add("match"); if (onMatch != null) onMatch.run(); }
        public BluetoothState bluetoothState() { reads++; if (onRead != null) onRead.run(); return snapshot; }
        public boolean startScan() {
            assertEquals(ResetController.State.SCANNING, controller.getState());
            starts++; scanning = true; actions.add("start"); if (onStart != null) onStart.run(); return scanAccepted;
        }
        public void stopScan() { scanning = false; stops++; actions.add("stop"); if (onStop != null) onStop.run(); }
        public boolean disable() {
            assertEquals(ResetController.State.WAITING_OFF, controller.getState()); assertFalse(scanning);
            disables++; actions.add("disable"); if (onDisable != null) onDisable.run(); return disableAccepted;
        }
        public boolean enable() {
            assertEquals(ResetController.State.WAITING_ON, controller.getState()); assertEquals(BluetoothState.OFF, snapshot);
            enables++; enabledAt = now; if (onEnable != null) onEnable.run(); return enableAccepted;
        }
        public void schedule(Runnable callback, long delayMs) {
            assertTrue("one live controller timer only", timers.isEmpty());
            assertTrue("timers must run in the future", delayMs > 0);
            timers.add(new Event(now + delayMs, sequence++, callback));
        }
        public void cancelScheduled() { timers.clear(); }
        public void finish(String outcome) { finishes++; result = outcome; }
        Runnable currentTimer() { assertFalse(timers.isEmpty()); return timers.peek().callback; }
        void at(long time, Runnable callback) { external.add(new Event(time, sequence++, callback)); }
        void advance(long duration) {
            long end = now + duration;
            int deliveries = 0;
            while ((!timers.isEmpty() && timers.peek().due <= end) || (!external.isEmpty() && external.peek().due <= end)) {
                assertTrue("event loop must stay bounded", ++deliveries < 10000);
                // Physical state changes due at the same instant precede the controller's final read.
                boolean physical = !external.isEmpty() && (timers.isEmpty() || external.peek().due <= timers.peek().due);
                Event event = (physical ? external : timers).remove(); now = event.due; event.callback.run();
            }
            now = end;
        }
        void jumpTo(long time) {
            now = time;
            if (!timers.isEmpty() && timers.peek().due <= now) timers.remove().callback.run();
        }
    }
}
