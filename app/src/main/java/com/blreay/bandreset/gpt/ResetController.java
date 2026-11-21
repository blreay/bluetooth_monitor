package com.blreay.bandreset.gpt;

import java.util.Objects;

/**
 * Single-use, serialized-thread reset state machine (normally Android's main thread).
 * Live advertisement names only; the adapter must reject callbacks from old scan sessions.
 * Requests are hints, not state confirmations: even false returns retain observation.
 * Cancellation/error after a disable attempt retains recovery within the total 45-second
 * consent window. OFF first observed after its deadline cannot trigger enable; OFF at the
 * exact deadline may recover. Process death, lost permissions and OEM refusal can prevent restoration.
 */
public final class ResetController {
    public static final String TARGET_NAME = "Xiaomi Smart Band 8 35BA";
    public static final long SCAN_TIMEOUT_MS = 15000;
    public static final long DISABLE_TIMEOUT_MS = 45000;
    public static final long TRANSITION_TIMEOUT_MS = 15000;
    public static final long OFF_DELAY_MS = 3000;
    public static final long POLL_INTERVAL_MS = 250;

    public enum State { NEW, SCANNING, WAITING_OFF, OFF_DELAY, WAITING_ON, FINISHED }
    public enum BluetoothState { ON, OFF, TURNING_ON, TURNING_OFF, UNKNOWN }

    /**
     * All calls/callbacks are serialized. Operations may synchronously reenter the controller
     * (including startScan, stopScan, onMatch, disable, enable and bluetoothState); state and
     * request flags are committed before calling them. State prompts reentered during a
     * snapshot read are ignored to prevent recursion. nowMs must be a non-reentrant monotonic
     * elapsed-time clock, not wall time. schedule must enqueue, never invoke inline, and run
     * no earlier than its positive delay; cancelScheduled removes the one pending callback.
     * Already-dequeued callbacks may still arrive and are generation guarded.
     *
     * Implementations catch platform exceptions. Rejected/failed requests return false;
     * asynchronous errors may call onFailure. Cleanup and notification methods must not throw.
     * bluetoothState returns a precise snapshot, never null; read/permission errors return
     * UNKNOWN, never fabricated OFF. Broadcasts only prompt onBluetoothStateChanged, regardless
     * of their extras. finish receives stable tokens, not exception details. onMatch is one-shot
     * and precedes disable; its default keeps intermediate Android integration source compatible.
     */
    public interface Port {
        long nowMs();
        BluetoothState bluetoothState();
        boolean startScan();
        void stopScan();
        boolean disable();
        boolean enable();
        default void onMatch() { }
        void schedule(Runnable callback, long delayMs);
        void cancelScheduled();
        void finish(String outcome);
    }

    private final Port port;
    private State state = State.NEW;
    private long generation;
    private long deadline;
    private long offDelayEnd;
    private boolean timerPending;
    private boolean scanning;
    private boolean disableAttempted;
    private boolean enableAttempted;
    private boolean observing;
    private boolean starting;
    private boolean readingSnapshot;
    private boolean observeAgain;
    private String recoveryReason;

    public ResetController(Port port) { this.port = Objects.requireNonNull(port, "port"); }
    public State getState() { return state; }

    public void start() {
        if (state != State.NEW || starting) return;
        starting = true; // Commit before the initial read, which may reenter start().
        BluetoothState snapshot;
        try { snapshot = readSnapshot(); }
        finally { starting = false; }
        if (state != State.NEW) return;
        if (snapshot == BluetoothState.OFF) { finish("INITIAL_BLUETOOTH_OFF"); return; }
        if (snapshot != BluetoothState.ON) { finish("INITIAL_BLUETOOTH_NOT_ON"); return; }
        state = State.SCANNING;
        deadline = port.nowMs() + SCAN_TIMEOUT_MS;
        scanning = true;
        armPoll();
        boolean accepted = port.startScan();
        if (!accepted && state == State.SCANNING) finish("SCAN_REJECTED");
    }

    public void onAdvertisement(String liveName) {
        if (state != State.SCANNING || !TARGET_NAME.equals(liveName)) return;
        // A late scan callback cannot turn an expired run into a fresh consent window.
        if (port.nowMs() >= deadline) { observe(); return; }
        state = State.WAITING_OFF;
        clearTimer();
        stopScan();
        if (state != State.WAITING_OFF) return;
        port.onMatch();
        if (state != State.WAITING_OFF) return;
        disableAttempted = true;
        deadline = port.nowMs() + DISABLE_TIMEOUT_MS;
        armPoll();
        port.disable(); // Accepted or not, observe reality. Never retry this request.
        observe();
    }

    /** Broadcast extras are not authoritative; every prompt reads the actual adapter. */
    public void onBluetoothStateChanged() {
        if (readingSnapshot) return;
        observe();
    }
    /** Compatibility wrappers; names deliberately do not assert a state. */
    public void onBluetoothOff() { onBluetoothStateChanged(); }
    public void onBluetoothOn() { onBluetoothStateChanged(); }

    /** Diagnostic input only; outcomes deliberately remain stable tokens. */
    public void onFailure(String reason) { abort("ERROR"); }
    public void cancel() { abort("CANCELLED"); }

    private void abort(String reason) {
        if (state == State.FINISHED) return;
        if (!disableAttempted) { finish(reason); return; }
        // Repeated cancellation/error does not replace the first reason or any deadline.
        if (recoveryReason == null) recoveryReason = reason;
    }

    private BluetoothState readSnapshot() {
        readingSnapshot = true;
        try { return port.bluetoothState(); }
        finally { readingSnapshot = false; }
    }

    private void observe() {
        if (state == State.NEW || state == State.FINISHED) return;
        if (observing) { observeAgain = true; return; }
        observing = true;
        try {
            do {
                observeAgain = false;
                observeSnapshot();
            } while (observeAgain && state != State.FINISHED);
        } finally { observing = false; }
        armPoll();
    }

    private void observeSnapshot() {
        State expected = state;
        BluetoothState snapshot = readSnapshot();
        if (state != expected || state == State.FINISHED) return;
        long now = port.nowMs();
        switch (state) {
            case SCANNING:
                if (snapshot == BluetoothState.OFF) finish("BLUETOOTH_OFF_DURING_SCAN");
                else if (now >= deadline) finish("TARGET_NOT_FOUND");
                break;
            case WAITING_OFF:
                if (!disableAttempted) return;
                // A final genuine OFF at the deadline wins, but delayed delivery cannot extend consent.
                if (now > deadline) {
                    finish(disableTimeoutOutcome());
                    return;
                }
                if (snapshot == BluetoothState.OFF) {
                    state = State.OFF_DELAY;
                    offDelayEnd = now + OFF_DELAY_MS;
                    deadline = offDelayEnd;
                    clearTimer();
                } else if (now >= deadline) {
                    finish(disableTimeoutOutcome());
                }
                break;
            case OFF_DELAY:
                if (now < offDelayEnd) return;
                state = State.WAITING_ON;
                deadline = offDelayEnd + TRANSITION_TIMEOUT_MS;
                clearTimer();
                observeOn(snapshot, now, true);
                break;
            case WAITING_ON:
                observeOn(snapshot, now, false);
                break;
            default:
                break;
        }
    }

    private void observeOn(BluetoothState snapshot, long now, boolean delayJustEnded) {
        // Final snapshot wins over timeout, including ON exactly at the deadline.
        if (snapshot == BluetoothState.ON) {
            finish(restoredOutcome(delayJustEnded ? "RESTORED_EXTERNALLY" : "RESET_COMPLETE"));
        } else if (now >= deadline) {
            finish(outcome("ENABLE_TIMEOUT"));
        } else if (snapshot == BluetoothState.OFF && !enableAttempted) {
            enableAttempted = true;
            // WAITING_ON and its absolute deadline already exist for synchronous callbacks.
            port.enable();
        }
        // UNKNOWN/TURNING_OFF remain observable; TURNING_ON needs no duplicate request.
    }

    private String disableTimeoutOutcome() {
        return "DISABLE_TIMEOUT".equals(recoveryReason) ? recoveryReason : outcome("DISABLE_TIMEOUT");
    }
    private String outcome(String suffix) {
        return recoveryReason == null ? suffix : recoveryReason + "_" + suffix;
    }
    private String restoredOutcome(String normal) {
        return recoveryReason == null ? normal : recoveryReason + "_RESTORED";
    }

    private void armPoll() {
        if (timerPending || state == State.NEW || state == State.FINISHED) return;
        if (state == State.WAITING_OFF && !disableAttempted) return;
        long delay = Math.min(POLL_INTERVAL_MS, deadline - port.nowMs());
        if (delay <= 0) { observe(); return; }
        long token = generation;
        State expected = state;
        timerPending = true;
        port.schedule(() -> {
            if (token != generation || state != expected) return;
            // Invalidate before observation: even a duplicate dequeued callback is inert.
            clearTimer();
            observe();
        }, delay);
    }

    private void clearTimer() {
        generation++;
        timerPending = false;
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
