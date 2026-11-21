package com.blreay.bandreset.gpt;

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
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Transparent permission/foreground-launch bridge, deliberately without an app page. */
public final class BandResetActivity extends Activity {
    public static final String ACTION_RUN = "com.blreay.bandreset.gpt.RUN";
    private static final String TAG = "BandResetGPT";
    private static final int REQUEST_CONNECT = 1;
    private static final int REQUEST_SCAN = 2;
    private static final long READY_TIMEOUT_MS = InvocationHandshake.READY_TIMEOUT_MS;
    private static final long OPERATION_TIMEOUT_MS = InvocationHandshake.OPERATION_TIMEOUT_MS;
    private static final String DENIED_PREFS = "denied_permissions";
    // Main-thread-only ownership also blocks a second entry during permission dialogs.
    private static Invocation activeInvocation;
    private Invocation invocation;
    private boolean resumed;
    private final Runnable terminalCallback = this::finishQuietly;

    private static final class Invocation {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final InvocationHandshake handshake = new InvocationHandshake();
        boolean connectRequested;
        boolean scanRequested;
        boolean permissionInFlight;
        boolean serviceRequested;
        boolean finished;
        Runnable timeout;
        Runnable operationTimeout;
        final ResultReceiver resultReceiver = new ResultReceiver(mainHandler) {
            @Override protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (finished) return;
                if (resultCode == ResetService.RESULT_READY) {
                    handshake.ready();
                    if (timeout != null) mainHandler.removeCallbacks(timeout);
                    timeout = null;
                    Log.i(TAG, "SERVICE_READY_WAITING_FOR_TERMINAL");
                } else {
                    String outcome = resultData == null ? null : resultData.getString(ResetService.EXTRA_OUTCOME);
                    complete(outcome == null ? "SERVICE_START_FAILED" : outcome);
                }
            }
        };

        void checkTimeout() {
            handshake.timeout(SystemClock.elapsedRealtime());
            if (handshake.isFinished()) complete(handshake.outcome());
        }

        void complete(String reason) {
            if (finished) return;
            finished = true;
            Log.i(TAG, reason);
            if (timeout != null) mainHandler.removeCallbacks(timeout);
            timeout = null;
            if (operationTimeout != null) mainHandler.removeCallbacks(operationTimeout);
            operationTimeout = null;
            if (activeInvocation == this) activeInvocation = null;
            handshake.terminal(reason);
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
                invocation.connectRequested = savedInstanceState.getBoolean("com.blreay.bandreset.gpt.CONNECT_REQUESTED");
                invocation.scanRequested = savedInstanceState.getBoolean("com.blreay.bandreset.gpt.SCAN_REQUESTED");
                invocation.permissionInFlight = savedInstanceState.getBoolean("com.blreay.bandreset.gpt.PERMISSION_IN_FLIGHT");
                // A process-recreated launch must not start another session after handoff.
                if (savedInstanceState.getBoolean("com.blreay.bandreset.gpt.SERVICE_REQUESTED")) {
                    invocation.complete("PROCESS_RECREATED_AFTER_HANDOFF");
                }
            }
            activeInvocation = invocation;
        }
        invocation.handshake.attach(terminalCallback);
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
            Log.e(TAG, "INITIAL_STATE_READ_FAILED type=" + error.getClass().getSimpleName());
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
            Invocation current = invocation;
            if (!current.handshake.request(SystemClock.elapsedRealtime())) return;
            current.serviceRequested = true;
            current.timeout = current::checkTimeout;
            current.operationTimeout = current::checkTimeout;
            current.mainHandler.postDelayed(current.timeout, READY_TIMEOUT_MS);
            current.mainHandler.postDelayed(current.operationTimeout, OPERATION_TIMEOUT_MS);
            Intent intent = new Intent(this, ResetService.class);
            intent.putExtra(ResetService.EXTRA_RESULT_RECEIVER, current.resultReceiver);
            startForegroundService(intent);
        } catch (RuntimeException error) {
            Log.e(TAG, "SERVICE_START_FAILED type=" + error.getClass().getSimpleName());
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
            Log.e(TAG, "PERMISSION_REQUEST_FAILED type=" + error.getClass().getSimpleName());
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
        outState.putBoolean("com.blreay.bandreset.gpt.CONNECT_REQUESTED", invocation.connectRequested);
        outState.putBoolean("com.blreay.bandreset.gpt.SCAN_REQUESTED", invocation.scanRequested);
        outState.putBoolean("com.blreay.bandreset.gpt.PERMISSION_IN_FLIGHT", invocation.permissionInFlight);
        outState.putBoolean("com.blreay.bandreset.gpt.SERVICE_REQUESTED", invocation.serviceRequested);
    }

    @Override protected void onDestroy() {
        if (invocation != null) {
            invocation.handshake.detach(terminalCallback);
            // Service callbacks and absolute deadlines own an already handed-off run.
            // Neither configuration changes nor Activity destruction cancel recovery.
            if (!isChangingConfigurations() && !invocation.finished && !invocation.serviceRequested) {
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
