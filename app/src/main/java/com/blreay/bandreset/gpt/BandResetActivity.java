package com.blreay.bandreset.gpt;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal permission bridge — opens, requests every needed permission in one batch,
 * launches the foreground service, and finishes immediately. All scanning / toggling
 * happens inside {@link ResetService} on a background notification channel.
 * <p>
 * The Activity uses a small dialog theme, never overlays touch, and stays alive for
 * at most the duration of a single permission-request round-trip.
 */
public final class BandResetActivity extends Activity {
    private static final String TAG = "BandResetGPT";
    private static final int REQUEST_ALL = 1;
    private static final String DENIED_PREFS = "denied_permissions";
    private boolean launched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A tiny visible surface satisfies the foreground-service-launch eligibility
        // rule on API 31+ without a translucent overlay that can steal touch.
        setTitle(getString(R.string.app_name));
        if (ResetService.isActive()) {
            shortToast(R.string.already_running);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (launched || isFinishing() || ResetService.isActive()) return;
        List<String> missing = collectMissing();
        if (!missing.isEmpty()) {
            // Any previously-denied-and-not-regranted permission blocks auto-retry.
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
            launchAndFinish();
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
        // Launch unconditionally — the service will report any remaining permission gaps.
        launchAndFinish();
    }

    private void launchAndFinish() {
        if (launched || isFinishing()) return;
        launched = true;
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
        // CONNECT is needed to read adapter state on API 31+.
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        // SCAN + FINE location are required for BLE discovery.
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