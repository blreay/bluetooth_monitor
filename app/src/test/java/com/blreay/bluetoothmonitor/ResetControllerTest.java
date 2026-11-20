package com.blreay.bluetoothmonitor;

import org.junit.Test;
import com.blreay.bluetoothmonitor.ResetController.BluetoothState;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ResetControllerTest {
    private final Fake port = new Fake();
    private final ResetController controller = new ResetController(port);

    private void match() { controller.start(); controller.onAdvertisement(ResetController.TARGET_NAME); }
    private void off() { port.bluetoothState = BluetoothState.OFF; controller.onBluetoothOff(); }
    private void on() { port.bluetoothState = BluetoothState.ON; controller.onBluetoothOn(); }
    private void restore() { off(); port.fire(); on(); }
    private void result(String expected) {
        assertEquals(expected, port.result);
        assertEquals(1, port.finishes);
        assertEquals(ResetController.State.FINISHED, controller.getState());
        assertNull(port.timer);
        assertFalse(port.scanning);
    }

    @Test public void constantsAndInitialState() {
        assertEquals("Xiaomi Smart Band 8 35BA", ResetController.TARGET_NAME);
        assertEquals(15000, ResetController.SCAN_TIMEOUT_MS);
        assertEquals(15000, ResetController.TRANSITION_TIMEOUT_MS);
        assertEquals(3000, ResetController.OFF_DELAY_MS);
        assertEquals(ResetController.State.NEW, controller.getState());
    }
    @Test public void initiallyOffDoesNotScanOrToggle() {
        port.bluetoothState = BluetoothState.OFF; controller.start(); result("INITIAL_BLUETOOTH_OFF");
        assertEquals(0, port.starts); assertEquals(0, port.disables); assertEquals(0, port.enables);
    }
    @Test public void startScansWithBoundedTimeout() {
        controller.start(); assertTrue(port.scanning);
        assertEquals(ResetController.State.SCANNING, controller.getState());
        assertEquals(15000, port.delay);
    }
    @Test public void noMatchStopsWithoutToggle() {
        controller.start(); port.fire(); result("TARGET_NOT_FOUND");
        assertEquals(0, port.disables); assertEquals(0, port.enables);
    }
    @Test public void onlyExactLiveNameMatches() {
        controller.start(); controller.onAdvertisement(null); controller.onAdvertisement("");
        controller.onAdvertisement("xiaomi Smart Band 8 35BA");
        controller.onAdvertisement(ResetController.TARGET_NAME + " ");
        controller.onAdvertisement("Xiaomi Smart Band 8");
        assertEquals(0, port.disables);
        controller.onAdvertisement(ResetController.TARGET_NAME); assertEquals(1, port.disables);
    }
    @Test public void stopsScanBeforeDisableAndSetsStateBeforePortCalls() {
        port.checkStates = true; match();
        assertEquals("start,stop,disable", String.join(",", port.actions));
        assertEquals(ResetController.State.WAITING_OFF, controller.getState());
    }
    @Test public void waitsForActualOffThenFullDelayThenActualOn() {
        match(); assertEquals(15000, port.delay); assertEquals(0, port.enables);
        off(); assertEquals(ResetController.State.OFF_DELAY, controller.getState());
        assertEquals(3000, port.delay); assertEquals(0, port.enables);
        port.fire(); assertEquals(1, port.enables);
        assertEquals(ResetController.State.WAITING_ON, controller.getState());
        assertNull(port.result); assertEquals(15000, port.delay);
        on(); result("RESET_COMPLETE");
    }
    @Test public void duplicatesCannotToggleOrFinishTwice() {
        match(); controller.start(); controller.onAdvertisement(ResetController.TARGET_NAME);
        off(); off(); port.fire(); off(); on(); on(); controller.cancel(); controller.onFailure("late");
        result("RESET_COMPLETE"); assertEquals(1, port.disables); assertEquals(1, port.enables);
    }
    @Test public void unsolicitedOnWhileWaitingOffDoesNotComplete() {
        match(); on(); assertNull(port.result); assertEquals(ResetController.State.WAITING_OFF, controller.getState());
    }
    @Test public void externalOffDuringScanNeverTurnsOn() {
        controller.start(); off(); result("BLUETOOTH_OFF_DURING_SCAN"); assertEquals(0, port.enables);
    }
    @Test public void disableRejectedNeverForcesEnable() {
        port.disableAccepted = false; match(); result("DISABLE_REJECTED");
        off(); assertEquals(0, port.enables);
    }
    @Test public void scanRejectedCleansUp() {
        port.scanAccepted = false; controller.start(); result("SCAN_REJECTED"); assertEquals(1, port.stops);
    }
    @Test public void disableTimeoutStillOnWaitsOneMoreBoundedWindow() {
        match(); port.fire(); assertNull(port.result);
        assertEquals(ResetController.State.WAITING_OFF, controller.getState());
        assertEquals(15000, port.delay); assertEquals(0, port.enables);
        port.fire(); result("DISABLE_TIMEOUT"); off(); assertEquals(0, port.enables);
    }
    @Test public void onAtFirstDeadlineThenLateOffRecoversAfterFullDelay() {
        match(); port.fire(); assertNull(port.result);
        off(); assertEquals(ResetController.State.OFF_DELAY, controller.getState());
        assertEquals(3000, port.delay); assertEquals(0, port.enables);
        port.fire(); on(); result("DISABLE_TIMEOUT_RESTORED"); assertEquals(1, port.enables);
    }
    @Test public void disableTimeoutAlreadyOffRestoresAfterDelay() {
        match(); port.bluetoothState = BluetoothState.OFF; port.fire();
        assertEquals(3000, port.delay); assertEquals(0, port.enables);
        port.fire(); on(); result("DISABLE_TIMEOUT_RESTORED"); assertEquals(1, port.enables);
    }
    @Test public void enableRejectedHasMachineReadableOutcome() {
        match(); off(); port.enableAccepted = false; port.fire(); result("ENABLE_REJECTED");
    }
    @Test public void enableTimeoutDoesNotRetry() {
        match(); off(); port.fire(); port.fire(); result("ENABLE_TIMEOUT");
        on(); assertEquals(1, port.enables);
    }
    @Test public void cancelledBeforeStartNeverScans() {
        controller.cancel(); controller.start(); result("CANCELLED"); assertEquals(0, port.starts);
    }
    @Test public void cancelledDuringScanCleansUpWithoutToggle() {
        controller.start(); controller.cancel(); result("CANCELLED"); assertEquals(0, port.disables);
    }
    @Test public void cancelledWaitingOffRestoresOnce() {
        match(); controller.cancel(); controller.cancel(); restore(); result("CANCELLED_RESTORED");
        assertEquals(1, port.enables);
    }
    @Test public void cancelledOffDelayPreservesRemainingTimer() {
        match(); off(); Runnable delay = port.timer; controller.cancel(); assertSame(delay, port.timer);
        port.fire(); on(); result("CANCELLED_RESTORED");
    }
    @Test public void cancelledWaitingOnDoesNotEnableTwice() {
        match(); off(); port.fire(); Runnable deadline = port.timer; controller.cancel();
        assertSame(deadline, port.timer); on(); result("CANCELLED_RESTORED"); assertEquals(1, port.enables);
    }
    @Test public void cancelledDisableStillOnIsBounded() {
        match(); controller.cancel(); port.fire(); port.fire(); result("CANCELLED_DISABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void cancelledDisableAlreadyOffStillRestores() {
        match(); controller.cancel(); port.bluetoothState = BluetoothState.OFF; port.fire(); port.fire(); on(); result("CANCELLED_RESTORED");
    }
    @Test public void cancelledRecoveryEnableRejectionIsBounded() {
        match(); controller.cancel(); off(); port.enableAccepted = false; port.fire(); result("CANCELLED_ENABLE_REJECTED");
    }
    @Test public void cancelledRecoveryEnableTimeoutIsBounded() {
        match(); controller.cancel(); off(); port.fire(); port.fire(); result("CANCELLED_ENABLE_TIMEOUT");
    }
    @Test public void failureDuringScanStopsWithoutToggle() {
        controller.start(); controller.onFailure("security"); result("ERROR"); assertEquals(0, port.enables);
    }
    @Test public void failureAfterDisableRestoresOnceEvenIfRepeated() {
        match(); controller.onFailure("adapter"); controller.onFailure("again"); restore();
        result("ERROR_RESTORED"); assertEquals(1, port.enables);
    }
    @Test public void staleScanDeadlineCannotAbortReset() {
        controller.start(); Runnable stale = port.timer;
        controller.onAdvertisement(ResetController.TARGET_NAME); stale.run();
        assertEquals(ResetController.State.WAITING_OFF, controller.getState()); restore(); result("RESET_COMPLETE");
    }
    @Test public void staleOffDeadlineCannotSkipDelay() {
        match(); Runnable stale = port.timer; off(); stale.run();
        assertEquals(ResetController.State.OFF_DELAY, controller.getState()); assertEquals(0, port.enables);
    }
    @Test public void staleDelayCannotEnableTwice() {
        match(); off(); Runnable stale = port.timer; port.fire(); stale.run();
        assertEquals(1, port.enables); on(); result("RESET_COMPLETE"); stale.run(); assertEquals(1, port.enables);
    }
    @Test public void staleCallbacksAfterCancelAreInert() {
        controller.start(); Runnable stale = port.timer; controller.cancel(); stale.run();
        controller.onAdvertisement(ResetController.TARGET_NAME); off(); on(); result("CANCELLED");
        assertEquals(0, port.disables); assertEquals(0, port.enables);
    }
    @Test public void alreadyOnAtEndOfDelayDoesNotSpuriouslyEnable() {
        match(); off(); on(); port.fire(); result("RESTORED_EXTERNALLY"); assertEquals(0, port.enables);
    }
    @Test public void synchronousOffAndOnCallbacksAreSafe() {
        port.synchronous = true; match(); assertEquals(ResetController.State.OFF_DELAY, controller.getState());
        port.fire(); result("RESET_COMPLETE"); assertEquals(1, port.enables);
    }
    @Test public void failureReenteredFromScanStopDoesNotDisable() {
        port.failOnStop = true; match(); result("ERROR"); assertEquals(0, port.disables);
    }

    @Test public void turningOnAfterAnotherOffWaitsForActualOn() {
        match(); off(); on(); off(); port.bluetoothState = BluetoothState.TURNING_ON; port.fire();
        assertNull(port.result); assertEquals(ResetController.State.WAITING_ON, controller.getState());
        assertEquals(15000, port.delay); assertEquals(0, port.enables); on(); result("RESET_COMPLETE");
    }
    @Test public void actualOnBluetoothStateConfirmsRestorationWithoutBroadcast() {
        match(); off(); port.bluetoothState = BluetoothState.ON; port.fire();
        result("RESTORED_EXTERNALLY"); assertEquals(0, port.enables);
    }
    @Test public void cancellationDoesNotExtendDisableDeadline() {
        match(); Runnable deadline = port.timer; controller.cancel(); controller.onFailure("later");
        assertSame(deadline, port.timer); port.fire(); port.fire(); result("CANCELLED_DISABLE_TIMEOUT");
    }
    @Test public void errorDuringOffDelayRestoresWithoutRestartingDelay() {
        match(); off(); Runnable delay = port.timer; controller.onFailure("permission");
        assertSame(delay, port.timer); port.fire(); on(); result("ERROR_RESTORED");
    }
    @Test public void errorWaitingOnIsBoundedWithoutRetry() {
        match(); off(); port.fire(); controller.onFailure("permission"); port.fire();
        result("ERROR_ENABLE_TIMEOUT"); assertEquals(1, port.enables);
    }
    @Test public void terminalCleanupStatePrecedesReentrantFailure() {
        controller.start(); port.failOnStop = true; controller.cancel(); result("CANCELLED");
    }
    @Test public void duplicateOffDoesNotRestartDelay() {
        match(); off(); Runnable delay = port.timer; off(); assertSame(delay, port.timer);
    }

    @Test public void initialTransitionsAndUnknownNeverScanOrToggle() {
        for (BluetoothState snapshot : new BluetoothState[] { BluetoothState.TURNING_ON, BluetoothState.TURNING_OFF, BluetoothState.UNKNOWN }) {
            Fake initial = new Fake(); initial.bluetoothState = snapshot;
            ResetController fresh = new ResetController(initial); fresh.start();
            assertEquals("INITIAL_BLUETOOTH_NOT_ON", initial.result);
            assertEquals(1, initial.finishes); assertNull(initial.timer);
            assertEquals(0, initial.starts); assertEquals(0, initial.disables); assertEquals(0, initial.enables);
        }
    }
    @Test public void turningOffAtDeadlineDoesNotStartDelayAndLateOffStartsExactlyThreeSeconds() {
        match(); port.bluetoothState = BluetoothState.TURNING_OFF; port.fire();
        assertEquals(ResetController.State.WAITING_OFF, controller.getState());
        assertEquals(15000, port.delay); assertNull(port.result); assertEquals(0, port.enables);
        off(); Runnable delay = port.timer;
        assertEquals(3000, port.delay); assertEquals(0, port.enables);
        off(); assertSame(delay, port.timer);
        port.fire(); on(); result("DISABLE_TIMEOUT_RESTORED"); assertEquals(1, port.enables);
    }
    @Test public void persistentTurningOffTerminatesAfterOneLateWindow() {
        match(); port.bluetoothState = BluetoothState.TURNING_OFF; port.fire();
        assertEquals(15000, port.delay); port.fire(); result("DISABLE_TIMEOUT");
        off(); assertEquals(0, port.enables);
    }
    @Test public void unknownPermissionBluetoothStateNeverFabricatesOff() {
        match(); port.bluetoothState = BluetoothState.UNKNOWN; port.fire();
        assertEquals(ResetController.State.WAITING_OFF, controller.getState());
        assertEquals(15000, port.delay); assertEquals(0, port.enables);
        port.fire(); result("DISABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void turningOnAtDisableDeadlineStillWaitsForOff() {
        match(); port.bluetoothState = BluetoothState.TURNING_ON; port.fire();
        assertEquals(ResetController.State.WAITING_OFF, controller.getState());
        port.fire(); result("DISABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void actualOffAtFinalDeadlineStartsRecoveryDelay() {
        match(); port.fire(); port.bluetoothState = BluetoothState.OFF; port.fire();
        assertEquals(3000, port.delay); assertEquals(0, port.enables);
        port.fire(); on(); result("DISABLE_TIMEOUT_RESTORED");
    }
    @Test public void turningOnAtDelayExpiryNeverCallsEnableAndHasBoundedWait() {
        match(); off(); port.bluetoothState = BluetoothState.TURNING_ON; port.fire();
        assertEquals(ResetController.State.WAITING_ON, controller.getState());
        assertEquals(0, port.enables); assertEquals(15000, port.delay);
        port.fire(); result("ENABLE_TIMEOUT"); assertEquals(0, port.enables);
    }
    @Test public void turningOffAtDelayExpirySafelyRejectsWithoutEnable() {
        match(); off(); port.bluetoothState = BluetoothState.TURNING_OFF; port.fire();
        result("ENABLE_REJECTED"); assertEquals(0, port.enables);
    }
    @Test public void unknownAtDelayExpirySafelyRejectsWithoutEnable() {
        match(); off(); port.bluetoothState = BluetoothState.UNKNOWN; port.fire();
        result("ENABLE_REJECTED"); assertEquals(0, port.enables);
    }
    @Test public void staleFirstDeadlineCannotConsumeLateWindowOrSkipLateOffDelay() {
        match(); Runnable first = port.timer; port.bluetoothState = BluetoothState.TURNING_OFF; port.fire();
        Runnable late = port.timer; first.run(); assertSame(late, port.timer);
        off(); Runnable delay = port.timer; first.run(); late.run();
        assertSame(delay, port.timer); assertEquals(3000, port.delay); assertEquals(0, port.enables);
        port.fire(); on(); result("DISABLE_TIMEOUT_RESTORED");
        first.run(); late.run(); assertEquals(1, port.enables);
    }

    private final class Fake implements ResetController.Port {
        BluetoothState bluetoothState = BluetoothState.ON;
        boolean scanning, scanAccepted = true, disableAccepted = true, enableAccepted = true;
        boolean checkStates, synchronous, failOnStop;
        int starts, stops, disables, enables, finishes;
        long delay;
        Runnable timer;
        String result;
        final List<String> actions = new ArrayList<>();
        public BluetoothState bluetoothState() { return bluetoothState; }
        public boolean startScan() {
            if (checkStates) assertEquals(ResetController.State.SCANNING, controller.getState());
            starts++; scanning = true; actions.add("start"); return scanAccepted;
        }
        public void stopScan() {
            scanning = false; stops++; actions.add("stop");
            if (failOnStop) controller.onFailure("stop");
        }
        public boolean disable() {
            if (checkStates) assertEquals(ResetController.State.WAITING_OFF, controller.getState());
            assertFalse(scanning); disables++; actions.add("disable");
            if (synchronous) off(); return disableAccepted;
        }
        public boolean enable() {
            if (checkStates) assertEquals(ResetController.State.WAITING_ON, controller.getState());
            enables++; if (synchronous) on(); return enableAccepted;
        }
        public void schedule(Runnable callback, long delayMs) { assertNull(timer); timer = callback; delay = delayMs; }
        public void cancelScheduled() { timer = null; }
        public void finish(String outcome) { finishes++; result = outcome; }
        void fire() { Runnable current = timer; assertNotNull(current); timer = null; current.run(); }
    }
}
