package com.blreay.bandreset.gpt;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import com.blreay.bandreset.gpt.shizuku.ShizukuManager;

/**
 * Minimal permission bridge — opens, requests every needed permission in one batch,
 * launches the foreground service, and finishes. All scanning / toggling happens
 * inside {@link ResetService} on a background notification channel.
 * <p>
 * The Activity uses a small dialog theme, never overlays touch, and stays alive for
 * at most the duration of a single permission-request round-trip.
 * <p>
 * When Shizuku is installed but not yet authorized, the Activity stays alive until
 * Shizuku's authorization result arrives — the {@link ShizukuManager} manages the
 * entire listener lifecycle. The Shizuku authorization UI requires the calling
 * Activity to remain alive.
 */
public final class BandResetActivity extends Activity {
    private static final String TAG = "BandResetGPT";
    private static final int REQUEST_ALL = 1;
    private static final String DENIED_PREFS = "denied_permissions";
    private boolean launched;
    private boolean waitingForShizuku;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.app_name));

        // Register Shizuku listeners once per process — idempotent after the first call.
        ShizukuManager.start(getApplicationContext());

        if (ResetService.isActive()) {
            shortToast(R.string.already_running);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (launched || isFinishing() || ResetService.isActive()) return;

        // If returning from the Shizuku authorization activity, the
        // OnRequestPermissionResultListener inside ShizukuManager has already
        // fired — check the resolved state.
        if (waitingForShizuku) {
            waitingForShizuku = false;
            if (ShizukuManager.isAvailable()) {
                Log.i(TAG, "SHIZUKU_PERMISSION_GRANTED_ON_RESUME");
            } else {
                Log.i(TAG, "SHIZUKU_PERMISSION_STILL_DENIED_ON_RESUME");
            }
            proceedToService();
            return;
        }

        List<String> missing = collectMissing();
        if (!missing.isEmpty()) {
            SharedPreferences denied = getSharedPreferences(DENIED_PREFS, MODE_PRIVATE);
            for (String p : missing) {
                if (!granted(p) && denied.getBoolean(p, false)) {
                    shortToast(R.string.permission_manually_required);
                    finish();
                    return;
                }
            }
            requestPermissions(missing.toArray(new String[0]), REQUEST_ALL);
        } else {
            checkShizukuThenProceed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_ALL) return;
        SharedPreferences.Editor denied = getSharedPreferences(DENIED_PREFS, MODE_PRIVATE).edit();
        for (int i = 0; i < permissions.length; i++) {
            boolean ok = i < grantResults.length && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            denied.putBoolean(permissions[i], !ok);
        }
        denied.apply();
        checkShizukuThenProceed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    /**
     * If Shizuku is installed but not yet authorized, request authorization and
     * wait for the result. Otherwise proceed directly to the foreground service.
     * <p>
     * {@link ShizukuManager#requestAuthorization(Activity, Runnable)} handles the
     * full lifecycle: binder probe, permission check, {@code Shizuku.requestPermission},
     * and the result callback.  The Activity is kept alive during the flow.
     */
    private void checkShizukuThenProceed() {
        if (launched || isFinishing()) return;

        try {
            if (ShizukuManager.isInstalled(this) && !ShizukuManager.isAvailable()) {
                Log.i(TAG, "SHIZUKU_PERMISSION_REQUIRED");
                waitingForShizuku = true;
                ShizukuManager.requestAuthorization(this, () -> {
                    if (!isFinishing()) proceedToService();
                });
                // Do NOT finish — Shizuku needs the Activity alive to show its authorization UI.
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "SHIZUKU_CHECK_FAILED type=" + e.getClass().getSimpleName());
        }
        proceedToService();
    }

    private void proceedToService() {
        if (launched || isFinishing()) return;
        launched = true;

        // Try to bind the Shizuku UserService before the controller starts.
        // This is best-effort — if it fails, the session will fall back to
        // the normal BluetoothAdapter API.
        if (ShizukuManager.isAvailable()) {
            try {
                ShizukuManager.bindService(getApplicationContext());
                Log.i(TAG, "SHIZUKU_USER_SERVICE_BIND_REQUESTED");
            } catch (Exception e) {
                Log.w(TAG, "SHIZUKU_USER_SERVICE_BIND_FAILED type=" + e.getClass().getSimpleName());
            }
        }

        try {
            startForegroundService(new Intent(this, ResetService.class));
        } catch (RuntimeException e) {
            Log.e(TAG, "FOREGROUND_SERVICE_LAUNCH_FAILED type=" + e.getClass().getSimpleName());
            shortToast(R.string.service_launch_failed);
        }
        finish();
    }

    // region permission helpers

    private List<String> collectMissing() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_SCAN))
            missing.add(Manifest.permission.BLUETOOTH_SCAN);
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (Build.VERSION.SDK_INT >= 31) missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 34 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        return missing;
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void shortToast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }
}