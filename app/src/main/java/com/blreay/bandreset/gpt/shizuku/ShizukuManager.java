package com.blreay.bandreset.gpt.shizuku;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * Detects Shizuku availability and executes privileged Bluetooth toggle commands.
 * <p>
 * Strategy:
 * <ol>
 *   <li>Check Shizuku installed + running + permission granted.</li>
 *   <li>If available, use {@code svc bluetooth enable/disable} via
 *       {@link Shizuku#newProcess} to bypass OEM confirmation dialogs.</li>
 *   <li>If Shizuku fails at any point, fall back silently; callers use the
 *       normal {@code BluetoothAdapter} API.</li>
 * </ol>
 * <p>
 * {@code newProcess} is deprecated in Shizuku 13 but still functional on
 * most devices (Qualcomm / Xiaomi). If it throws, the fallback handles it.
 */
public final class ShizukuManager {
    private static final String TAG = "BandResetGPT";

    // Cache: once we know Shizuku is unavailable this boot, stop probing.
    private static volatile boolean probed;
    private static volatile boolean available;
    private static volatile BluetoothControlMode lastMode = BluetoothControlMode.UNKNOWN;

    private ShizukuManager() {}

    // region detection

    /**
     * Non-blocking check: is Shizuku fully usable right now?
     * Callers should cache the result for the current session.
     */
    public static boolean isAvailable() {
        if (probed) return available;
        probed = true;
        try {
            if (!Shizuku.pingBinder()) {
                Log.i(TAG, "SHIZUKU_NOT_RUNNING");
                return available = false;
            }
            int perm = Shizuku.checkSelfPermission();
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "SHIZUKU_PERMISSION_DENIED code=" + perm);
                return available = false;
            }
            Log.i(TAG, "SHIZUKU_AVAILABLE");
            return available = true;
        } catch (NoClassDefFoundError | Exception e) {
            // Shizuku API not on classpath at runtime (e.g. proguard stripped it).
            Log.w(TAG, "SHIZUKU_API_UNAVAILABLE type=" + e.getClass().getSimpleName());
            return available = false;
        }
    }

    /** Reset the probe cache — call when Shizuku state may have changed. */
    public static void reset() {
        probed = false;
        available = false;
        lastMode = BluetoothControlMode.UNKNOWN;
    }

    /** True if Shizuku is likely installed (package visible). */
    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // endregion

    // region permission

    /** Start the Shizuku authorization flow. Only call from an Activity. */
    public static void requestPermission(android.app.Activity activity) {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return;
            Shizuku.requestPermission(0);
        } catch (Exception e) {
            Log.w(TAG, "SHIZUKU_REQUEST_PERMISSION_FAILED type=" + e.getClass().getSimpleName());
        }
    }

    /** Non-blocking check. */
    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    // endregion

    // region privileged Bluetooth toggle

    /**
     * Enable Bluetooth through Shizuku. Returns true if the command succeeded
     * (exit code 0). On failure the caller should fall back to the normal API.
     */
    public static boolean enableBluetooth() {
        return exec("svc bluetooth enable");
    }

    /**
     * Disable Bluetooth through Shizuku. Returns true if the command succeeded
     * (exit code 0). On failure the caller should fall back to the normal API.
     */
    public static boolean disableBluetooth() {
        return exec("svc bluetooth disable");
    }

    /** The mode that was in effect for the most recent toggle attempt. */
    public static BluetoothControlMode lastMode() {
        return lastMode;
    }

    // endregion

    // region internal

    /**
     * Execute a shell command via Shizuku's privileged process.
     * <p>
     * {@code Shizuku.newProcess} was deprecated and made private in v13.
     * We use reflection to access it — this is acceptable for a personal
     * utility and avoids the complexity of raw binder transactions.
     */
    private static boolean exec(String command) {
        try {
            Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            Process process = (Process) newProcess.invoke(null,
                    new String[]{"/system/bin/sh", "-c", command}, null, null);
            if (process == null) {
                Log.w(TAG, "SHIZUKU_NEW_PROCESS_RETURNED_NULL command=" + command);
                lastMode = BluetoothControlMode.NORMAL;
                return false;
            }
            // Drain stdout/stderr so the process doesn't block.
            drain(process);
            int exit = process.waitFor();
            boolean ok = exit == 0;
            lastMode = ok ? BluetoothControlMode.SHIZUKU : BluetoothControlMode.NORMAL;
            Log.i(TAG, "SHIZUKU_EXEC command=" + command + " exit=" + exit + " mode=" + lastMode);
            return ok;
        } catch (NoSuchMethodError | NoSuchMethodException e) {
            // newProcess was removed entirely from this Shizuku version.
            Log.w(TAG, "SHIZUKU_NEW_PROCESS_NOT_FOUND command=" + command);
            lastMode = BluetoothControlMode.NORMAL;
            return false;
        } catch (Exception e) {
            Log.w(TAG, "SHIZUKU_EXEC_FAILED command=" + command
                    + " type=" + e.getClass().getSimpleName());
            lastMode = BluetoothControlMode.NORMAL;
            return false;
        }
    }

    private static void drain(Process process) {
        // Don't wait for these; they're best-effort to avoid deadlock.
        Thread stdout = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (r.readLine() != null) { /* discard */ }
            } catch (Exception ignored) { }
        }, "shizuku-stdout");
        Thread stderr = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                while (r.readLine() != null) { /* discard */ }
            } catch (Exception ignored) { }
        }, "shizuku-stderr");
        stdout.setDaemon(true);
        stderr.setDaemon(true);
        stdout.start();
        stderr.start();
    }

    // endregion
}