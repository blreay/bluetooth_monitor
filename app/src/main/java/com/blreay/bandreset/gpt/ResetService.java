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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.blreay.bandreset.gpt.shizuku.ShizukuManager;

/**
 * Private foreground service that owns exactly one bounded reset session. It runs
 * entirely in the background with a persistent low-importance notification; the
 * launching Activity finishes immediately after {@code startForegroundService}.
 * <p>
 * Each reset cycle posts a <em>new</em> notification with a unique id so they
 * accumulate — the user can count how many resets have run. Notifications are
 * never auto-cancelled; the user swipes them away manually.
 * <p>
 * The service is {@code START_NOT_STICKY} — Android will not restart it after
 * unexpected death.
 */
public final class ResetService extends Service {
    static final String TAG = "BandResetGPT";
    static final String CHANNEL_ID = "band_reset_gpt";
    private static final long WAKE_LOCK_TIMEOUT_MS = 120_000;
    private static final int NOTIFICATION_ID_BASE = 1000;
    // main-thread-only flag; onCreate / onDestroy are sequenced by the framework.
    private static boolean active;
    private static final AtomicInteger nextNotificationId = new AtomicInteger(NOTIFICATION_ID_BASE);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NotificationManager notifications;
    private Notification.Builder notificationBuilder;
    private AndroidBluetoothSession session;
    private PowerManager.WakeLock wakeLock;
    private boolean started;
    private boolean stopping;
    private int notificationId;
    private boolean shizukuActive;

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
            // Allocate a fresh notification id so this cycle stacks independently.
            notificationId = nextNotificationId.getAndIncrement();
            shizukuActive = ShizukuManager.isAvailable();

            // Build the notification for this cycle.
            int initialText = shizukuActive
                    ? R.string.notification_scanning_shizuku
                    : R.string.notification_scanning;
            notificationBuilder = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_bluetooth_band)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(initialText))
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false);

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
            startForeground(notificationId, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(notificationId, notification);
        }
    }

    private void acquireWakeLock() {
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null) throw new IllegalStateException("PowerManager unavailable");
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BandResetGPT:Reset:" + notificationId);
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    // endregion

    // region phase & completion

    private void onPhase(String phaseName) {
        int resId;
        switch (phaseName) {
            case "SCANNING":
                resId = shizukuActive ? R.string.notification_scanning_shizuku : R.string.notification_scanning;
                break;
            case "WAITING_OFF":
                resId = shizukuActive ? R.string.notification_waiting_off_shizuku : R.string.notification_waiting_off;
                break;
            case "OFF_DELAY":
                resId = shizukuActive ? R.string.notification_off_delay_shizuku : R.string.notification_off_delay;
                break;
            case "WAITING_ON":
                resId = shizukuActive ? R.string.notification_waiting_on_shizuku : R.string.notification_waiting_on;
                break;
            default:
                return; // NEW, FINISHED — no update
        }
        updateNotification(resId);
        Log.i(TAG, "PHASE " + phaseName + " nid=" + notificationId
                + (shizukuActive ? " [Shizuku]" : " [normal]"));
    }

    private void onSessionFinished(String outcome) {
        Log.i(TAG, "SESSION_FINISHED outcome=" + outcome + " nid=" + notificationId);
        int resId = shizukuActive
                ? R.string.notification_done_shizuku
                : R.string.notification_done;
        updateNotification(resId);
        shutdown();
    }

    private void updateNotification(int resId) {
        if (notificationBuilder == null) return;
        try {
            notificationBuilder.setContentText(getString(resId));
            // Strip ongoing flag once done so the user CAN swipe it away,
            // but never auto-cancel — the notification persists until manual removal.
            notificationBuilder.setOngoing(false);
            notifications.notify(notificationId, notificationBuilder.build());
        } catch (RuntimeException e) {
            Log.w(TAG, "NOTIFICATION_UPDATE_FAILED type=" + e.getClass().getSimpleName());
        }
    }

    // endregion

    // region cleanup

    private void shutdown() {
        if (stopping) return;
        stopping = true;
        // Immediately mark the notification as done — it stays visible until
        // the user swipes it away.  No delayed cleanup needed.
        cleanup();
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
            // DETACH keeps the notification visible after the service stops.
            stopForeground(STOP_FOREGROUND_DETACH);
        } catch (RuntimeException e) {
            Log.w(TAG, "FOREGROUND_CLEANUP_FAILED");
        }
        stopSelf();
    }

    // endregion
}