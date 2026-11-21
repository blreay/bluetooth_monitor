package com.blreay.bandreset.gpt;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * One main-thread-owned Android port; no cached or bonded device-name lookup.
 * Broadcasts are treated as prompts to read the actual adapter snapshot, never
 * as authoritative state.
 */
@SuppressLint("MissingPermission")
final class AndroidBluetoothSession implements ResetController.Port {
    private static final String TAG = "BandResetGPT";
    private final String sessionId = java.util.UUID.randomUUID().toString();
    private final long startedAt = SystemClock.elapsedRealtime();
    private final SessionFeedback feedback = new SessionFeedback();
    private ResetController.BluetoothState lastSnapshot = ResetController.BluetoothState.UNKNOWN;
    private String lastPhase;
    private boolean completed;
    private final Context context;
    private final Handler mainHandler;
    private final Consumer<String> completion;
    private final Consumer<String> phaseListener;
    private final ResetController controller;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private Runnable scheduled;
    private long timerGeneration;
    private boolean receiverRegistered;
    private boolean closed;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            if (closed || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            requireMainThread();
            try {
                int broadcast = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                ResetController.BluetoothState snapshot = bluetoothState();
                diagnostic("BROADCAST state=" + broadcast + " snapshot=" + snapshot);
            } catch (RuntimeException error) {
                reportFailure("BROADCAST_READ_FAILED", error);
            } finally {
                controller.onBluetoothStateChanged();
                logPhase();
            }
        }
    };

    AndroidBluetoothSession(Context context, Handler mainHandler,
                            Consumer<String> completion, Consumer<String> phaseListener) {
        requireMainThread();
        this.context = context;
        this.mainHandler = mainHandler;
        this.completion = completion;
        this.phaseListener = phaseListener;
        controller = new ResetController(this);
    }

    void start() {
        requireMainThread();
        if (closed || controller.getState() != ResetController.State.NEW) return;
        diagnostic("SESSION_START");
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            adapter = manager == null ? null : manager.getAdapter();
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(stateReceiver, filter, null, mainHandler, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(stateReceiver, filter, null, mainHandler);
            }
            receiverRegistered = true;
            controller.start();
            logPhase();
        } catch (RuntimeException error) {
            reportFailure("SESSION_START_FAILED", error);
            controller.onFailure("SESSION_START_FAILED");
        }
    }

    // region Port implementation

    @Override public long nowMs() { return SystemClock.elapsedRealtime(); }

    @Override
    public ResetController.BluetoothState bluetoothState() {
        requireMainThread();
        if (adapter == null) return ResetController.BluetoothState.UNKNOWN;
        try {
            ResetController.BluetoothState snapshot = switch (adapter.getState()) {
                case BluetoothAdapter.STATE_ON -> ResetController.BluetoothState.ON;
                case BluetoothAdapter.STATE_OFF -> ResetController.BluetoothState.OFF;
                case BluetoothAdapter.STATE_TURNING_ON -> ResetController.BluetoothState.TURNING_ON;
                case BluetoothAdapter.STATE_TURNING_OFF -> ResetController.BluetoothState.TURNING_OFF;
                default -> ResetController.BluetoothState.UNKNOWN;
            };
            if (snapshot != lastSnapshot) diagnostic("SNAPSHOT state=" + snapshot);
            lastSnapshot = snapshot;
            logPhase();
            return snapshot;
        } catch (RuntimeException error) {
            reportFailure("BLUETOOTH_STATE_READ_FAILED", error);
            lastSnapshot = ResetController.BluetoothState.UNKNOWN;
            return ResetController.BluetoothState.UNKNOWN;
        }
    }

    static boolean isLocationEnabled(Context context) {
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    @Override public boolean startScan() {
        requireMainThread();
        try {
            if (closed || adapter == null) return false;
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    || (Build.VERSION.SDK_INT >= 31
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)) {
                diagnostic("SCAN_PERMISSION_MISSING");
                return false;
            }
            if (!isLocationEnabled(context)) {
                diagnostic("LOCATION_DISABLED");
                return false;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                diagnostic("BLE_SCANNER_UNAVAILABLE");
                return false;
            }
            ScanCallback callback = new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {
                    mainHandler.post(() -> acceptResult(this, result));
                }
                @Override public void onBatchScanResults(List<ScanResult> results) {
                    mainHandler.post(() -> {
                        if (closed || scanCallback != this || results == null) return;
                        for (ScanResult result : results) acceptResult(this, result);
                    });
                }
                @Override public void onScanFailed(int errorCode) {
                    mainHandler.post(() -> {
                        if (closed || scanCallback != this) return;
                        diagnostic("BLE_SCAN_FAILED code=" + errorCode);
                        controller.onFailure("BLE_SCAN_FAILED");
                    });
                }
            };
            scanCallback = callback;
            ScanFilter filter = new ScanFilter.Builder().setDeviceName(ResetController.TARGET_NAME).build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build();
            scanner.startScan(Collections.singletonList(filter), settings, callback);
            return true;
        } catch (RuntimeException error) {
            reportFailure("SCAN_START_FAILED", error);
            stopScan();
            return false;
        }
    }

    private void acceptResult(ScanCallback source, ScanResult result) {
        if (closed || scanCallback != source || result == null) return;
        try {
            ScanRecord record = result.getScanRecord();
            if (record != null) controller.onAdvertisement(record.getDeviceName());
            logPhase();
        } catch (RuntimeException error) {
            reportFailure("ADVERTISEMENT_READ_FAILED", error);
        }
    }

    @Override public void stopScan() {
        requireMainThread();
        ScanCallback oldCallback = scanCallback;
        BluetoothLeScanner oldScanner = scanner;
        scanCallback = null;
        scanner = null;
        if (oldCallback == null || oldScanner == null) return;
        try { oldScanner.stopScan(oldCallback); }
        catch (RuntimeException error) { reportFailure("SCAN_STOP_FAILED", error); }
    }

    @Override public void onMatch() {
        requireMainThread();
        if (feedback.match()) {
            diagnostic("TARGET_MATCH");
            shortToast(R.string.target_found);
            // Push a phase update so the notification reflects the match.
            if (phaseListener != null) phaseListener.accept("WAITING_OFF");
        }
    }

    @SuppressWarnings("deprecation")
    @Override public boolean disable() {
        requireMainThread();
        try {
            boolean accepted = adapter != null && adapter.disable();
            diagnostic("DISABLE return=" + accepted);
            return accepted;
        } catch (RuntimeException error) {
            reportFailure("BLUETOOTH_DISABLE_FAILED return=false", error);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    @Override public boolean enable() {
        requireMainThread();
        try {
            boolean accepted = adapter != null && adapter.enable();
            diagnostic("ENABLE return=" + accepted);
            return accepted;
        } catch (RuntimeException error) {
            reportFailure("BLUETOOTH_ENABLE_FAILED return=false", error);
            return false;
        }
    }

    @Override public void schedule(Runnable callback, long delayMs) {
        requireMainThread();
        long token = ++timerGeneration;
        scheduled = () -> {
            if (closed || token != timerGeneration) return;
            scheduled = null;
            callback.run();
            logPhase();
        };
        mainHandler.postDelayed(scheduled, delayMs);
    }

    @Override public void cancelScheduled() {
        requireMainThread();
        timerGeneration++;
        if (scheduled != null) mainHandler.removeCallbacks(scheduled);
        scheduled = null;
    }

    @Override public void finish(String outcome) {
        requireMainThread();
        if (closed || completed) return;
        complete(outcome);
        close();
    }

    // endregion

    void close() {
        requireMainThread();
        if (closed) return;
        closed = true;
        if (!completed) complete("SERVICE_DESTROYED");
        diagnostic("CLEANUP_BEGIN");
        cancelScheduled();
        stopScan();
        if (receiverRegistered) {
            receiverRegistered = false;
            try { context.unregisterReceiver(stateReceiver); }
            catch (RuntimeException error) { reportFailure("RECEIVER_CLEANUP_FAILED", error); }
        }
        diagnostic("CLEANUP_COMPLETE");
    }

    private void complete(String outcome) {
        if (completed) return;
        completed = true;
        ResetController.BluetoothState snapshot = bluetoothState();
        diagnostic("TERMINAL outcome=" + outcome + " snapshot=" + snapshot);
        if (feedback.terminal(outcome, snapshot)) shortToast(R.string.bluetooth_not_restored);
        completion.accept(outcome);
    }

    private void shortToast(int resource) {
        try { Toast.makeText(context, resource, Toast.LENGTH_SHORT).show(); }
        catch (RuntimeException error) { reportFailure("TOAST_FAILED", error); }
    }

    private void logPhase() {
        String phase = controller.getState().name();
        if (!phase.equals(lastPhase)) {
            lastPhase = phase;
            diagnostic("PHASE " + phase);
            if (phaseListener != null) phaseListener.accept(phase);
        }
    }

    private void diagnostic(String message) {
        Log.i(TAG, "session=" + sessionId + " origin=" + startedAt + " elapsed="
                + (nowMs() - startedAt) + " phase=" + controller.getState() + " " + message);
    }

    private void reportFailure(String reason, RuntimeException error) {
        diagnostic(reason + " exception=" + error.getClass().getSimpleName());
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Main thread required");
    }
}