package com.blreay.bandreset.gpt.shizuku;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.blreay.bandreset.gpt.IPrivilegedBluetoothService;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import rikka.shizuku.Shizuku;

/**
 * Manages Shizuku authorization and privileged Bluetooth toggle via
 * {@code bindUserService}.  The UserService runs inside Shizuku's own
 * shell/root process, so {@code svc bluetooth enable/disable} executes
 * without OEM confirmation dialogs.
 * <p>
 * Lifecycle: call {@link #start(Context)} once, call {@link #stop()} when done.
 * Listeners registered in {@code start()} stay active until {@code stop()}.
 * <p>
 * The pattern is modelled on the verified {@code bluetooth_monitor_shizuku}
 * branch, which uses an identical AIDL contract and binding strategy.
 */
public final class ShizukuManager {
    private static final String TAG = "BandResetGPT";
    private static final long BIND_TIMEOUT_MS = 5_000L;
    private static final long RPC_TIMEOUT_MS = 8_000L;

    // ---- state ------------------------------------------------------------

    private static volatile boolean started;
    private static volatile boolean binderAvailable;
    private static volatile boolean permissionGranted;
    private static volatile boolean serviceBound;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bt-shizuku");
        t.setDaemon(true);
        return t;
    });

    // ---- listeners --------------------------------------------------------

    private static Shizuku.OnBinderReceivedListener binderReceivedListener;
    private static Shizuku.OnBinderDeadListener binderDeadListener;
    private static Shizuku.OnRequestPermissionResultListener permissionResultListener;

    // ---- permission -------------------------------------------------------

    private static int pendingPermissionCode = -1;
    private static Runnable onPermissionComplete;

    // ---- UserService binding ----------------------------------------------

    private static IPrivilegedBluetoothService remoteService;
    private static Shizuku.UserServiceArgs serviceArgs;
    private static ServiceConnection serviceConnection;
    private static IBinder.DeathRecipient serviceDeathRecipient;

    private ShizukuManager() {}

    // ========================================================================
    // region lifecycle
    // ========================================================================

    /**
     * Register Shizuku listeners and probe current state.  Safe to call
     * multiple times — subsequent calls are no-ops.
     */
    public static void start(Context context) {
        if (started) return;
        started = true;

        binderReceivedListener = new Shizuku.OnBinderReceivedListener() {
            @Override
            public void onBinderReceived() {
                if (!started) return;
                binderAvailable = true;
                Log.i(TAG, "SHIZUKU_BINDER_RECEIVED");
                checkPermissionAsync();
            }
        };
        binderDeadListener = new Shizuku.OnBinderDeadListener() {
            @Override
            public void onBinderDead() {
                if (!started) return;
                Log.i(TAG, "SHIZUKU_BINDER_DEAD");
                binderAvailable = false;
                permissionGranted = false;
                unbindService("binder died");
            }
        };
        permissionResultListener = new Shizuku.OnRequestPermissionResultListener() {
            @Override
            public void onRequestPermissionResult(int requestCode, int grantResult) {
                if (!started || requestCode != pendingPermissionCode) return;
                Log.i(TAG, "SHIZUKU_PERMISSION_RESULT code=" + requestCode + " grant=" + grantResult);
                pendingPermissionCode = -1;
                permissionGranted = (grantResult == PackageManager.PERMISSION_GRANTED);
                if (permissionGranted) {
                    bindService(context);
                }
                Runnable cb = onPermissionComplete;
                onPermissionComplete = null;
                if (cb != null) cb.run();
            }
        };

        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
        } catch (Exception e) {
            Log.w(TAG, "SHIZUKU_LISTENER_REGISTRATION_FAILED type=" + e.getClass().getSimpleName());
        }

        // Probe initial state.
        checkPermissionAsync();
    }

    public static void stop() {
        if (!started) return;
        started = false;
        unbindService("ShizukuManager stopped");
        try {
            if (binderReceivedListener != null)
                Shizuku.removeBinderReceivedListener(binderReceivedListener);
            if (binderDeadListener != null)
                Shizuku.removeBinderDeadListener(binderDeadListener);
            if (permissionResultListener != null)
                Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        } catch (Exception e) {
            Log.w(TAG, "SHIZUKU_LISTENER_REMOVAL_FAILED");
        }
        binderReceivedListener = null;
        binderDeadListener = null;
        permissionResultListener = null;
        binderAvailable = false;
        permissionGranted = false;
        onPermissionComplete = null;
        pendingPermissionCode = -1;
    }

    // endregion

    // ========================================================================
    // region permission
    // ========================================================================

    /** True if binder is alive and permission is granted. */
    public static boolean isAvailable() {
        return started && binderAvailable && permissionGranted;
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

    /**
     * Request Shizuku authorization.  If already granted, calls
     * {@code onComplete} immediately.  Otherwise starts the Shizuku
     * permission flow — the Activity MUST stay alive until the callback.
     */
    public static void requestAuthorization(Activity activity, Runnable onComplete) {
        if (!started) {
            Log.w(TAG, "SHIZUKU_NOT_STARTED");
            if (onComplete != null) onComplete.run();
            return;
        }
        if (permissionGranted) {
            Log.i(TAG, "SHIZUKU_ALREADY_GRANTED");
            if (onComplete != null) onComplete.run();
            return;
        }
        if (!binderAvailable) {
            // Binder not yet received; poll once then give up.
            Log.i(TAG, "SHIZUKU_BINDER_NOT_AVAILABLE_YET");
            io.execute(() -> {
                boolean avail = pingBinderFor(BIND_TIMEOUT_MS);
                mainHandler.post(() -> {
                    if (!started) return;
                    binderAvailable = avail;
                    if (avail) {
                        checkPermissionAndRequest(activity, onComplete);
                    } else {
                        Log.w(TAG, "SHIZUKU_BINDER_STILL_NOT_AVAILABLE");
                        if (onComplete != null) onComplete.run();
                    }
                });
            });
            return;
        }
        checkPermissionAndRequest(activity, onComplete);
    }

    /** Non-blocking check. */
    public static boolean hasPermission() {
        return permissionGranted;
    }

    // endregion

    // ========================================================================
    // region privileged Bluetooth toggle
    // ========================================================================

    /**
     * Returns true if the UserService is bound and ready for commands.
     */
    public static boolean isServiceReady() {
        return serviceBound && remoteService != null
                && remoteService.asBinder().isBinderAlive();
    }

    /**
     * Bind the Shizuku UserService.  Must be called after permission is
     * granted.  Returns true if binding was initiated (or already bound).
     */
    public static boolean bindService(Context context) {
        if (!started || !binderAvailable || !permissionGranted) {
            Log.w(TAG, "SHIZUKU_BIND_PRECONDITIONS_NOT_MET");
            return false;
        }
        if (serviceBound && remoteService != null && remoteService.asBinder().isBinderAlive()) {
            Log.i(TAG, "SHIZUKU_SERVICE_ALREADY_BOUND");
            return true;
        }

        // Retire any previous binding.
        unbindService("re-binding");

        serviceDeathRecipient = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                Log.w(TAG, "SHIZUKU_SERVICE_DEATH");
                mainHandler.post(() -> unbindService("remote died"));
            }
        };

        serviceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(context, PrivilegedBluetoothService.class))
                .daemon(false)
                .processNameSuffix("bt_privileged")
                .version(1)
                .tag("bt_auto_" + UUID.randomUUID());

        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "SHIZUKU_SERVICE_CONNECTED");
                try {
                    service.linkToDeath(serviceDeathRecipient, 0);
                    remoteService = IPrivilegedBluetoothService.Stub.asInterface(service);
                    serviceBound = true;
                } catch (Exception e) {
                    Log.w(TAG, "SHIZUKU_SERVICE_LINK_FAILED type=" + e.getClass().getSimpleName());
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "SHIZUKU_SERVICE_DISCONNECTED");
                serviceBound = false;
                remoteService = null;
            }
        };

        io.execute(() -> {
            try {
                Shizuku.bindUserService(serviceArgs, serviceConnection);
            } catch (Exception e) {
                Log.w(TAG, "SHIZUKU_BIND_USER_SERVICE_FAILED type=" + e.getClass().getSimpleName());
                mainHandler.post(() -> {
                    serviceBound = false;
                    remoteService = null;
                });
            }
        });

        return true;
    }

    /**
     * Disable Bluetooth via Shizuku UserService.  The AIDL call is
     * submitted asynchronously — the caller should poll adapter state to
     * confirm the effect.
     *
     * @return true if the request was submitted, false if service not ready
     */
    public static boolean disableBluetooth() {
        return toggleBluetooth(false);
    }

    /**
     * Enable Bluetooth via Shizuku UserService.
     *
     * @return true if the request was submitted, false if service not ready
     */
    public static boolean enableBluetooth() {
        return toggleBluetooth(true);
    }

    // endregion

    // ========================================================================
    // region internal
    // ========================================================================

    private static boolean toggleBluetooth(boolean enabled) {
        if (!isServiceReady()) {
            Log.w(TAG, "SHIZUKU_TOGGLE_SERVICE_NOT_READY enabled=" + enabled);
            return false;
        }
        IPrivilegedBluetoothService svc = remoteService;
        if (svc == null) return false;

        io.execute(() -> {
            try {
                String wire = svc.setBluetoothEnabled(enabled);
                BluetoothCommandResult result = BluetoothCommandResult.decode(wire);
                Log.i(TAG, "SHIZUKU_TOGGLE enabled=" + enabled
                        + " status=" + result.status + " output=" + result.output);
            } catch (Exception e) {
                Log.w(TAG, "SHIZUKU_TOGGLE_EXCEPTION enabled=" + enabled
                        + " type=" + e.getClass().getSimpleName());
                mainHandler.post(() -> {
                    serviceBound = false;
                    remoteService = null;
                });
            }
        });
        return true;
    }

    private static void checkPermissionAsync() {
        io.execute(() -> {
            boolean avail = false;
            boolean grant = false;
            try {
                avail = Shizuku.pingBinder();
                grant = avail && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            } catch (Exception e) {
                Log.w(TAG, "SHIZUKU_PERMISSION_CHECK_FAILED type=" + e.getClass().getSimpleName());
            }
            boolean finalAvail = avail;
            boolean finalGrant = grant;
            mainHandler.post(() -> {
                if (!started) return;
                binderAvailable = finalAvail;
                permissionGranted = finalGrant;
                Log.i(TAG, "SHIZUKU_STATE avail=" + finalAvail + " grant=" + finalGrant);
            });
        });
    }

    private static void checkPermissionAndRequest(Activity activity, Runnable onComplete) {
        io.execute(() -> {
            boolean grant = false;
            boolean permanentlyDenied = false;
            try {
                grant = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                if (!grant) {
                    permanentlyDenied = !Shizuku.shouldShowRequestPermissionRationale();
                }
            } catch (Exception e) {
                Log.w(TAG, "SHIZUKU_PERMISSION_CHECK_FAILED");
            }
            boolean finalGrant = grant;
            boolean finalPermanentlyDenied = permanentlyDenied;
            mainHandler.post(() -> {
                if (!started) return;
                permissionGranted = finalGrant;
                if (finalGrant) {
                    Log.i(TAG, "SHIZUKU_PERMISSION_ALREADY_GRANTED");
                    if (onComplete != null) onComplete.run();
                } else if (finalPermanentlyDenied) {
                    Log.w(TAG, "SHIZUKU_PERMANENTLY_DENIED");
                    // Cannot show the dialog — user must allow in Shizuku app.
                    if (onComplete != null) onComplete.run();
                } else {
                    onPermissionComplete = onComplete;
                    pendingPermissionCode = (int) (SystemClock.elapsedRealtime() & 0xFFFF);
                    try {
                        Shizuku.requestPermission(pendingPermissionCode);
                        Log.i(TAG, "SHIZUKU_REQUEST_PERMISSION code=" + pendingPermissionCode);
                    } catch (Exception e) {
                        Log.w(TAG, "SHIZUKU_REQUEST_PERMISSION_FAILED");
                        pendingPermissionCode = -1;
                        onPermissionComplete = null;
                        if (onComplete != null) onComplete.run();
                    }
                }
            });
        });
    }

    private static boolean pingBinderFor(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                if (Shizuku.pingBinder()) return true;
            } catch (Exception ignored) {}
            try { Thread.sleep(100); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    private static void unbindService(String reason) {
        Log.i(TAG, "SHIZUKU_UNBIND_SERVICE reason=" + reason);
        serviceBound = false;
        IPrivilegedBluetoothService old = remoteService;
        remoteService = null;
        if (old != null && serviceDeathRecipient != null) {
            try { old.asBinder().unlinkToDeath(serviceDeathRecipient, 0); }
            catch (Exception ignored) {}
        }
        serviceDeathRecipient = null;

        Shizuku.UserServiceArgs oldArgs = serviceArgs;
        ServiceConnection oldConn = serviceConnection;
        serviceArgs = null;
        serviceConnection = null;
        if (oldArgs != null && oldConn != null) {
            io.execute(() -> {
                try { Shizuku.unbindUserService(oldArgs, oldConn, true); }
                catch (Exception ignored) {}
            });
        }
        if (old != null) {
            io.execute(() -> {
                try { old.destroy(); } catch (Exception ignored) {}
            });
        }
    }

    // endregion
}