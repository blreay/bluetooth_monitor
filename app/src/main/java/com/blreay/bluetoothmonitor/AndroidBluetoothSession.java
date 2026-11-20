package com.blreay.bluetoothmonitor;

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
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** One main-thread-owned Android port; no cached or bonded device-name lookup.
 * Runtime grants are checked by TriggerActivity and at scan start; revocations are caught
 * at every Bluetooth operation, including cleanup. The API checks span helper methods.
 */
@SuppressLint("MissingPermission")
final class AndroidBluetoothSession implements ResetController.Port {
    private static final String TAG = "BluetoothMonitor";
    private final Context context;
    private final Handler mainHandler;
    private final Consumer<String> completion;
    private final ResetController controller;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private Runnable scheduled;
    private long timerGeneration;
    private boolean receiverRegistered;
    private boolean closed;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (closed || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int advertised = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            // The exported receiver accepts privileged Bluetooth senders. Never trust its extras
            // alone: spoofed/stale/transition broadcasts cannot confirm an OFF or ON state.
            ResetController.BluetoothState actual = bluetoothState();
            if (advertised == BluetoothAdapter.STATE_OFF && actual == ResetController.BluetoothState.OFF) {
                controller.onBluetoothOff();
            } else if (advertised == BluetoothAdapter.STATE_ON && actual == ResetController.BluetoothState.ON) {
                controller.onBluetoothOn();
            }
        }
    };

    AndroidBluetoothSession(Context context, Handler mainHandler, Consumer<String> completion) {
        this.context = context;
        this.mainHandler = mainHandler;
        this.completion = completion;
        controller = new ResetController(this);
    }

    void start() {
        requireMainThread();
        if (closed || controller.getState() != ResetController.State.NEW) return;
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
        } catch (RuntimeException error) {
            Log.e(TAG, "SESSION_START_FAILED", error);
            controller.onFailure("SESSION_START_FAILED");
        }
    }

    @Override public ResetController.BluetoothState bluetoothState() {
        requireMainThread();
        if (adapter == null) return ResetController.BluetoothState.UNKNOWN;
        try {
            return switch (adapter.getState()) {
                case BluetoothAdapter.STATE_ON -> ResetController.BluetoothState.ON;
                case BluetoothAdapter.STATE_OFF -> ResetController.BluetoothState.OFF;
                case BluetoothAdapter.STATE_TURNING_ON -> ResetController.BluetoothState.TURNING_ON;
                case BluetoothAdapter.STATE_TURNING_OFF -> ResetController.BluetoothState.TURNING_OFF;
                default -> ResetController.BluetoothState.UNKNOWN;
            };
        } catch (RuntimeException error) {
            reportFailure("BLUETOOTH_STATE_READ_FAILED", error);
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
                Log.w(TAG, "SCAN_PERMISSION_MISSING");
                return false;
            }
            if (!isLocationEnabled(context)) {
                Log.w(TAG, "LOCATION_DISABLED");
                return false;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                Log.w(TAG, "BLE_SCANNER_UNAVAILABLE");
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
                        Log.e(TAG, "BLE_SCAN_FAILED code=" + errorCode);
                        controller.onFailure("BLE_SCAN_FAILED");
                    });
                }
            };
            scanCallback = callback;
            // A named filter keeps screen-off scans eligible. Shortened-name-only packets
            // cannot satisfy the full exact name and intentionally do not trigger a reset.
            ScanFilter filter = new ScanFilter.Builder().setDeviceName(ResetController.TARGET_NAME).build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build();
            scanner.startScan(Collections.singletonList(filter), settings, callback);
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "SCAN_START_FAILED", error);
            stopScan();
            return false;
        }
    }

    private void acceptResult(ScanCallback source, ScanResult result) {
        if (closed || scanCallback != source || result == null) return;
        try {
            ScanRecord record = result.getScanRecord();
            if (record != null) controller.onAdvertisement(record.getDeviceName());
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
        try {
            oldScanner.stopScan(oldCallback);
        } catch (RuntimeException error) {
            reportFailure("SCAN_STOP_FAILED", error);
        }
    }

    @SuppressWarnings("deprecation")
    @Override public boolean disable() {
        requireMainThread();
        try {
            boolean accepted = adapter != null && adapter.disable();
            if (!accepted) Log.w(TAG, "BLUETOOTH_DISABLE_REJECTED");
            return accepted;
        } catch (RuntimeException error) {
            Log.e(TAG, "BLUETOOTH_DISABLE_FAILED", error);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    @Override public boolean enable() {
        requireMainThread();
        try {
            boolean accepted = adapter != null && adapter.enable();
            if (!accepted) Log.w(TAG, "BLUETOOTH_ENABLE_REJECTED");
            return accepted;
        } catch (RuntimeException error) {
            Log.e(TAG, "BLUETOOTH_ENABLE_FAILED", error);
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
        if (closed) return;
        close();
        completion.accept(outcome);
    }

    /** Terminal cleanup only, not cancellation: destruction cannot await bounded recovery. */
    void close() {
        requireMainThread();
        if (closed) return;
        closed = true;
        cancelScheduled();
        stopScan();
        if (receiverRegistered) {
            receiverRegistered = false;
            try {
                context.unregisterReceiver(stateReceiver);
            } catch (RuntimeException error) {
                Log.w(TAG, "RECEIVER_CLEANUP_FAILED", error);
            }
        }
    }

    private void reportFailure(String reason, RuntimeException error) {
        Log.e(TAG, reason, error);
        if (!closed) mainHandler.post(() -> {
            if (!closed) controller.onFailure(reason);
        });
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
    }
}
