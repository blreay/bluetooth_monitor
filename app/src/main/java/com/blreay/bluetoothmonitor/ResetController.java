package com.blreay.bluetoothmonitor;

import java.util.Objects;

/**
 * Single-use reset state machine. Invoke all methods and timer callbacks on one serialized
 * thread (normally Android's main thread). Advertisement names must come from the current
 * scan result, never a cached device name. The adapter must filter old scan sessions.
 *
 * Cancellation/failure after an accepted disable retains bounded recovery: await OFF,
 * wait three seconds, request enable at most once, and await ON. After the disable
 * deadline, an actual OFF snapshot starts the delay; otherwise one additional bounded
 * 15-second late-OFF window is allowed. No OFF confirmation means no enable request.
 * Process death or lost permissions can still prevent restoration.
 */
public final class ResetController {
    public static final String TARGET_NAME = "Xiaomi Smart Band 8 35BA";
    public static final long SCAN_TIMEOUT_MS = 15000;
    public static final long TRANSITION_TIMEOUT_MS = 15000;
    public static final long OFF_DELAY_MS = 3000;

    public enum State { NEW, SCANNING, WAITING_OFF, OFF_DELAY, WAITING_ON, FINISHED }
    public enum BluetoothState { ON, OFF, TURNING_ON, TURNING_OFF, UNKNOWN }

    /**
     * Implementations catch Android exceptions: return false for rejected operations and
     * report asynchronous failures through onFailure. Cleanup must be nonthrowing.
     * bluetoothState returns the precise current adapter state, never null. Permission/read
     * errors return UNKNOWN, never fabricated OFF; onFailure may additionally report them.
     * Only actual OFF/ON broadcasts may call onBluetoothOff/onBluetoothOn; transition states
     * must not be translated into those callbacks. schedule replaces no existing timer; callbacks
     * run only after the specified delay. cancelScheduled cancels the current timer.
     * finish receives stable machine-readable tokens, never raw exception messages.
     */
    public interface Port {
        BluetoothState bluetoothState();
        boolean startScan();
        void stopScan();
        boolean disable();
        boolean enable();
        void schedule(Runnable callback, long delayMs);
        void cancelScheduled();
        void finish(String outcome);
    }

    private final Port port;
    private State state = State.NEW;
    private long generation;
    private boolean scanning;
    private boolean disableAttempted;
    private boolean lateOffWindow;
    private String recoveryReason;

    public ResetController(Port port) { this.port = Objects.requireNonNull(port, "port"); }
    public State getState() { return state; }

    public void start() {
        if (state != State.NEW) return;
        BluetoothState snapshot = port.bluetoothState();
        if (state != State.NEW) return;
        if (snapshot == BluetoothState.OFF) { finish("INITIAL_BLUETOOTH_OFF"); return; }
        if (snapshot != BluetoothState.ON) { finish("INITIAL_BLUETOOTH_NOT_ON"); return; }
        state = State.SCANNING;
        scanning = true;
        timer(State.SCANNING, SCAN_TIMEOUT_MS, () -> finish("TARGET_NOT_FOUND"));
        boolean accepted = port.startScan();
        if (!accepted && state == State.SCANNING) finish("SCAN_REJECTED");
    }

    public void onAdvertisement(String liveName) {
        if (state != State.SCANNING || !TARGET_NAME.equals(liveName)) return;
        state = State.WAITING_OFF;
        clearTimer();
        stopScan();
        // stopScan may synchronously report a failure/cancellation.
        if (state != State.WAITING_OFF) return;
        disableAttempted = true;
        timer(State.WAITING_OFF, TRANSITION_TIMEOUT_MS, this::disableTimeout);
        boolean accepted = port.disable();
        if (!accepted && state != State.FINISHED) {
            disableAttempted = false;
            finish(outcome("DISABLE_REJECTED"));
        }
    }

    public void onBluetoothOff() {
        if (state == State.SCANNING) finish("BLUETOOTH_OFF_DURING_SCAN");
        else if (state == State.WAITING_OFF && disableAttempted) beginOffDelay();
    }

    public void onBluetoothOn() {
        if (state == State.WAITING_ON) finish(restoredOutcome("RESET_COMPLETE"));
    }

    /** The reason is diagnostic input only; outcomes deliberately remain stable tokens. */
    public void onFailure(String reason) { abort("ERROR"); }
    public void cancel() { abort("CANCELLED"); }

    private void abort(String reason) {
        if (state == State.FINISHED) return;
        if (!disableAttempted) { finish(reason); return; }
        // Do not reset deadlines on duplicate cancellation/failure or retry enable.
        if (recoveryReason == null) recoveryReason = reason;
    }

    private void disableTimeout() {
        BluetoothState snapshot = port.bluetoothState();
        if (state != State.WAITING_OFF) return;
        if (snapshot == BluetoothState.OFF) {
            if (recoveryReason == null) recoveryReason = "DISABLE_TIMEOUT";
            beginOffDelay();
        } else if (!lateOffWindow) {
            if (recoveryReason == null) recoveryReason = "DISABLE_TIMEOUT";
            lateOffWindow = true;
            timer(State.WAITING_OFF, TRANSITION_TIMEOUT_MS, this::disableTimeout);
        } else {
            // The timeout itself is already the recovery reason: do not duplicate it.
            finish("DISABLE_TIMEOUT".equals(recoveryReason) ? recoveryReason : outcome("DISABLE_TIMEOUT"));
        }
    }

    private void beginOffDelay() {
        state = State.OFF_DELAY;
        timer(State.OFF_DELAY, OFF_DELAY_MS, this::requestEnable);
    }

    private void requestEnable() {
        BluetoothState snapshot = port.bluetoothState();
        if (state != State.OFF_DELAY) return;
        if (snapshot == BluetoothState.ON) { finish(restoredOutcome("RESTORED_EXTERNALLY")); return; }
        if (snapshot != BluetoothState.OFF && snapshot != BluetoothState.TURNING_ON) {
            // Never enable while turning off or when the state cannot be read.
            finish(outcome("ENABLE_REJECTED"));
            return;
        }
        state = State.WAITING_ON;
        timer(State.WAITING_ON, TRANSITION_TIMEOUT_MS, () -> finish(outcome("ENABLE_TIMEOUT")));
        // Another actor is enabling Bluetooth: await actual ON without a second request.
        if (snapshot == BluetoothState.TURNING_ON) return;
        boolean accepted = port.enable();
        if (!accepted && state == State.WAITING_ON) finish(outcome("ENABLE_REJECTED"));
    }

    private String outcome(String suffix) {
        return recoveryReason == null ? suffix : recoveryReason + "_" + suffix;
    }

    private String restoredOutcome(String normal) {
        return recoveryReason == null ? normal : recoveryReason + "_RESTORED";
    }

    private void timer(State expected, long delay, Runnable action) {
        clearTimer();
        long token = generation;
        port.schedule(() -> {
            if (token != generation || state != expected) return;
            // Invalidate before action, so even an already-dequeued duplicate is inert.
            clearTimer();
            action.run();
        }, delay);
    }

    private void clearTimer() {
        generation++;
        port.cancelScheduled();
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        port.stopScan();
    }

    private void finish(String result) {
        if (state == State.FINISHED) return;
        state = State.FINISHED;
        clearTimer();
        stopScan();
        port.finish(result);
    }
}
