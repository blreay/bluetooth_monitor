package com.blreay.bandreset.gpt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import java.util.function.Consumer;

import com.blreay.bandreset.gpt.shizuku.BluetoothControlMode;
import com.blreay.bandreset.gpt.shizuku.ShizukuManager;

/**
 * Private foreground service that owns exactly one bounded reset session. It runs
 * entirely in the background with a persistent low-importance notification; the
 * launching Activity finishes immediately after {@code startForegroundService}.
 * <p>
 * The notification text updates at each phase transition. The service is
 * {@code START_NOT_STICKY} — Android will not restart it after unexpected death.
 */
public final class ResetService extends Service {
    static final String TAG = "BandResetGPT";
    static final String CHANNEL_ID = "band_reset_gpt";
    static final int NOTIFICATION_ID = 1;
    private static final long WAKE_LOCK_TIMEOUT_MS = 120_000;
    // main-thread-only flag; onCreate / onDestroy are sequenced by the framework.
    private static boolean active;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NotificationManager notifications;
    private Notification.Builder notificationBuilder;
    private AndroidBluetoothSession session;
    private PowerManager.WakeLock wakeLock;
    private boolean started;
    private boolean stopping;

    static boolean isActive() { return active; }

    // region lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        active = true;
        notifications = getSystemService(NotificationManager.class);
        if (notifications == null) {
            Log.e(TAG, "NOTIFICATION_MANAGER_UNAVAILABLE");
            stopSelf();
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        notifications.createNotificationChannel(channel);
        notificationBuilder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bluetooth_band)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_scanning))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (stopping) return START_NOT_STICKY;
        if (started) {
            Log.i(TAG, "DUPLICATE_SERVICE_START_IGNORED");
            return START_NOT_STICKY;
        }
        started = true;
        try {
            promoteForeground();
            acquireWakeLock();
            Consumer<String> phaseListener = this::onPhase;
            Consumer<String> completion = this::onSessionFinished;
            session = new AndroidBluetoothSession(this, mainHandler, completion, phaseListener);
            mainHandler.post(() -> {
                if (!stopping && session != null) session.start();
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "SESSION_START_FAILED type=" + e.getClass().getSimpleName());
            shutdown();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (!stopping) {
            Log.w(TAG, "UNEXPECTED_SERVICE_DESTRUCTION_RESTORATION_NOT_GUARANTEED");
            stopping = true;
        }
        cleanup();
        active = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // endregion

    // region foreground / wakelock

    private void promoteForeground() {
        Notification notification = notificationBuilder.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void acquireWakeLock() {
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null) throw new IllegalStateException("PowerManager unavailable");
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BandResetGPT:Reset");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    // endregion

    // region phase & completion

    private void onPhase(String phaseName) {
        boolean shizuku = isShizukuActive();
        int resId;
        switch (phaseName) {
            case "SCANNING":
                resId = shizuku ? R.string.notification_scanning_shizuku : R.string.notification_scanning;
                break;
            case "WAITING_OFF":
                resId = shizuku ? R.string.notification_waiting_off_shizuku : R.string.notification_waiting_off;
                break;
            case "OFF_DELAY":
                resId = shizuku ? R.string.notification_off_delay_shizuku : R.string.notification_off_delay;
                break;
            case "WAITING_ON":
                resId = shizuku ? R.string.notification_waiting_on_shizuku : R.string.notification_waiting_on;
                break;
            default:
                return; // NEW, FINISHED — no update
        }
        updateNotification(resId);
        Log.i(TAG, "PHASE " + phaseName + (shizuku ? " [Shizuku]" : " [normal]"));
    }

    private void onSessionFinished(String outcome) {
        Log.i(TAG, "SESSION_FINISHED outcome=" + outcome);
        boolean shizuku = isShizukuActive();
        int resId;
        if (isRestored(outcome)) {
            resId = shizuku ? R.string.notification_done_shizuku : R.string.notification_done;
        } else {
            resId = shizuku ? R.string.notification_done_shizuku : R.string.notification_done;
        }
        updateNotification(resId);
        shutdown();
    }

    private boolean isShizukuActive() {
        return ShizukuManager.isAvailable()
                && ShizukuManager.lastMode() == BluetoothControlMode.SHIZUKU;
    }

    private static boolean isRestored(String outcome) {
        return "RESET_COMPLETE".equals(outcome)
                || "RESTORED_EXTERNALLY".equals(outcome)
                || outcome.endsWith("_RESTORED");
    }

    private void updateNotification(int resId) {
        try {
            notificationBuilder.setContentText(getString(resId));
            notifications.notify(NOTIFICATION_ID, notificationBuilder.build());
        } catch (RuntimeException e) {
            Log.w(TAG, "NOTIFICATION_UPDATE_FAILED type=" + e.getClass().getSimpleName());
        }
    }

    // endregion

    // region cleanup

    private void shutdown() {
        if (stopping) return;
        stopping = true;
        // Briefly flip notification to "done" so the user sees completion.
        updateNotification(R.string.notification_done);
        mainHandler.postDelayed(this::cleanup, 1500);
    }

    private void cleanup() {
        mainHandler.removeCallbacksAndMessages(null);
        if (session != null) {
            AndroidBluetoothSession old = session;
            session = null;
            old.close();
        }
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); }
            catch (RuntimeException e) { Log.w(TAG, "WAKE_LOCK_CLEANUP_FAILED"); }
            wakeLock = null;
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException e) {
            Log.w(TAG, "FOREGROUND_CLEANUP_FAILED");
        }
        stopSelf();
    }

    // endregion
}