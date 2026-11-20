package com.blreay.bluetoothmonitor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Transparent permission/foreground-launch bridge, deliberately without an app page. */
public final class TriggerActivity extends Activity {
    public static final String ACTION_RUN = "com.blreay.bluetoothmonitor.RUN";
    private static final String TAG = "BluetoothMonitor";
    private static final int REQUEST_CONNECT = 1;
    private static final int REQUEST_SCAN = 2;
    private static final long READY_TIMEOUT_MS = 3500;
    private static final String DENIED_PREFS = "denied_permissions";
    // Main-thread-only ownership also blocks a second entry during permission dialogs.
    private static Invocation activeInvocation;
    private Invocation invocation;
    private boolean resumed;

    private static final class Invocation {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        TriggerActivity activity;
        boolean connectRequested;
        boolean scanRequested;
        boolean permissionInFlight;
        boolean serviceRequested;
        boolean finished;
        Runnable timeout;
        final ResultReceiver readyReceiver = new ResultReceiver(mainHandler) {
            @Override protected void onReceiveResult(int resultCode, Bundle resultData) {
                complete(resultCode == ResetService.RESULT_READY ? "SERVICE_READY" : "SERVICE_START_FAILED");
            }
        };

        void complete(String reason) {
            if (finished) return;
            finished = true;
            Log.i(TAG, reason);
            if (timeout != null) mainHandler.removeCallbacks(timeout);
            timeout = null;
            if (activeInvocation == this) activeInvocation = null;
            if (activity != null) activity.finishQuietly();
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof Invocation) {
            invocation = (Invocation) retained;
        } else {
            if (ResetService.isActive() || activeInvocation != null) {
                Log.i(TAG, "DUPLICATE_TRIGGER_IGNORED");
                finishQuietly();
                return;
            }
            invocation = new Invocation();
            if (savedInstanceState != null) {
                invocation.connectRequested = savedInstanceState.getBoolean("connectRequested");
                invocation.scanRequested = savedInstanceState.getBoolean("scanRequested");
                invocation.permissionInFlight = savedInstanceState.getBoolean("permissionInFlight");
                // A process-recreated launch must not start another session after handoff.
                if (savedInstanceState.getBoolean("serviceRequested")) {
                    invocation.finished = true;
                }
            }
            activeInvocation = invocation;
        }
        invocation.activity = this;
        if (invocation.finished) {
            if (activeInvocation == invocation) activeInvocation = null;
            finishQuietly();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        advance();
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    // CONNECT is explicitly gated below before getState; granted() wraps checkSelfPermission.
    @SuppressLint("MissingPermission")
    private void advance() {
        if (!resumed || isFinishing() || invocation == null || invocation.finished
                || invocation.permissionInFlight || invocation.serviceRequested) return;
        if (ResetService.isActive()) {
            invocation.complete("DUPLICATE_TRIGGER_IGNORED");
            return;
        }
        // CONNECT is needed to read getState on S+. Do not request scan/location until
        // a genuine initial ON snapshot has been obtained, and never offer enable UI.
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            if (invocation.connectRequested) {
                invocation.complete("CONNECT_PERMISSION_DENIED");
            } else {
                invocation.connectRequested = true;
                requestOnce(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_CONNECT);
            }
            return;
        }
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) {
                invocation.complete("BLUETOOTH_UNAVAILABLE");
                return;
            }
            int state = adapter.getState();
            if (state != BluetoothAdapter.STATE_ON) {
                invocation.complete(state == BluetoothAdapter.STATE_OFF
                        ? "INITIAL_BLUETOOTH_OFF" : "INITIAL_BLUETOOTH_NOT_ON");
                return;
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "INITIAL_STATE_READ_FAILED", error);
            invocation.complete("INITIAL_STATE_READ_FAILED");
            return;
        }
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_SCAN)) {
            missing.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Android 12+ requires COARSE and FINE in the same request, even if COARSE
            // was already granted. Approximate-only authorization is insufficient here.
            if (Build.VERSION.SDK_INT >= 31) missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            if (invocation.scanRequested) {
                invocation.complete("SCAN_OR_LOCATION_PERMISSION_DENIED");
            } else {
                invocation.scanRequested = true;
                requestOnce(missing.toArray(new String[0]), REQUEST_SCAN);
            }
            return;
        }
        try {
            if (!AndroidBluetoothSession.isLocationEnabled(this)) {
                invocation.complete("LOCATION_DISABLED");
                return;
            }
            // Only onResume (or a permission callback while resumed) may initiate FGS.
            invocation.serviceRequested = true;
            Invocation current = invocation;
            current.timeout = () -> current.complete("SERVICE_READY_TIMEOUT");
            current.mainHandler.postDelayed(current.timeout, READY_TIMEOUT_MS);
            Intent intent = new Intent(this, ResetService.class);
            intent.putExtra(ResetService.EXTRA_READY_RECEIVER, current.readyReceiver);
            startForegroundService(intent);
        } catch (RuntimeException error) {
            Log.e(TAG, "SERVICE_START_FAILED", error);
            invocation.complete("SERVICE_START_FAILED");
        }
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestOnce(String[] permissions, int requestCode) {
        SharedPreferences denied = getSharedPreferences(DENIED_PREFS, MODE_PRIVATE);
        for (String permission : permissions) {
            if (!granted(permission) && denied.getBoolean(permission, false)) {
                invocation.complete("PERMISSION_PREVIOUSLY_DENIED_MANUAL_GRANT_REQUIRED");
                return;
            }
        }
        invocation.permissionInFlight = true;
        try {
            requestPermissions(permissions, requestCode);
        } catch (RuntimeException error) {
            invocation.permissionInFlight = false;
            Log.e(TAG, "PERMISSION_REQUEST_FAILED", error);
            invocation.complete("PERMISSION_REQUEST_FAILED");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (invocation == null || invocation.finished
                || (requestCode != REQUEST_CONNECT && requestCode != REQUEST_SCAN)) return;
        invocation.permissionInFlight = false;
        SharedPreferences.Editor denied = getSharedPreferences(DENIED_PREFS, MODE_PRIVATE).edit();
        boolean allGranted = permissions.length > 0 && permissions.length == grantResults.length;
        for (int i = 0; i < permissions.length; i++) {
            boolean allowed = i < grantResults.length && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            denied.putBoolean(permissions[i], !allowed);
            allGranted &= allowed;
        }
        denied.apply();
        if (!allGranted) invocation.complete("PERMISSION_DENIED");
        else advance();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        return invocation;
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (invocation == null) return;
        outState.putBoolean("connectRequested", invocation.connectRequested);
        outState.putBoolean("scanRequested", invocation.scanRequested);
        outState.putBoolean("permissionInFlight", invocation.permissionInFlight);
        outState.putBoolean("serviceRequested", invocation.serviceRequested);
    }

    @Override protected void onDestroy() {
        if (invocation != null && invocation.activity == this) {
            invocation.activity = null;
            if (!isChangingConfigurations() && !invocation.finished) {
                invocation.complete("TRIGGER_DESTROYED");
            }
        }
        super.onDestroy();
    }

    private void finishQuietly() {
        finish();
        overridePendingTransition(0, 0);
    }
}
