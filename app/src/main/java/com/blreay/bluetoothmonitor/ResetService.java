package com.blreay.bluetoothmonitor;

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
import android.os.ResultReceiver;
import android.util.Log;

/** Private, non-restarting foreground owner of exactly one bounded reset session. */
public final class ResetService extends Service {
    static final String EXTRA_READY_RECEIVER = "com.blreay.bluetoothmonitor.READY_RECEIVER";
    static final int RESULT_READY = 1;
    static final int RESULT_FAILED = 2;
    private static final String TAG = "BluetoothMonitor";
    private static final String CHANNEL_ID = "bluetooth_reset";
    private static final int NOTIFICATION_ID = 1;
    private static final long WAKE_LOCK_TIMEOUT_MS = 120000;
    // Everything touching this flag runs on the application main thread.
    private static boolean active;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AndroidBluetoothSession session;
    private PowerManager.WakeLock wakeLock;
    private boolean started;
    private boolean stopping;

    static boolean isActive() { return active; }

    @Override public void onCreate() {
        super.onCreate();
        active = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ResultReceiver ready = readyReceiver(intent);
        if (stopping) {
            acknowledge(ready, RESULT_FAILED);
            return START_NOT_STICKY;
        }
        if (started) {
            Log.i(TAG, "DUPLICATE_SERVICE_START_IGNORED");
            acknowledge(ready, RESULT_READY);
            return START_NOT_STICKY;
        }
        started = true;
        try {
            NotificationManager notifications = getSystemService(NotificationManager.class);
            if (notifications == null) throw new IllegalStateException("NotificationManager unavailable");
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_channel_description));
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            notifications.createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_bluetooth_band)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.notification_running))
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();
            if (Build.VERSION.SDK_INT >= 29) {
                // Keep FINE-gated BLE scans foreground-eligible after TriggerActivity finishes.
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null) throw new IllegalStateException("PowerManager unavailable");
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BluetoothMonitor:Reset");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            session = new AndroidBluetoothSession(this, mainHandler, this::onSessionFinished);
            // Ready means the service owns foreground status/resources. Start on the next
            // main-loop turn so even immediate controller completion cannot lose the ack.
            acknowledge(ready, RESULT_READY);
            mainHandler.post(() -> {
                if (!stopping && session != null) session.start();
            });
        } catch (RuntimeException error) {
            Log.e(TAG, "FOREGROUND_SESSION_START_FAILED", error);
            acknowledge(ready, RESULT_FAILED);
            shutdown();
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private ResultReceiver readyReceiver(Intent intent) {
        if (intent == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra(EXTRA_READY_RECEIVER, ResultReceiver.class);
            }
            return intent.getParcelableExtra(EXTRA_READY_RECEIVER);
        } catch (RuntimeException error) {
            Log.w(TAG, "INVALID_READY_RECEIVER", error);
            return null;
        }
    }

    private void acknowledge(ResultReceiver ready, int result) {
        if (ready == null) return;
        try {
            ready.send(result, null);
        } catch (RuntimeException error) {
            Log.w(TAG, "READY_CALLBACK_FAILED", error);
        }
    }

    private void onSessionFinished(String outcome) {
        Log.i(TAG, "SESSION_FINISHED outcome=" + outcome);
        shutdown();
    }

    private void shutdown() {
        if (stopping) return;
        stopping = true;
        cleanup();
        stopSelf();
    }

    private void cleanup() {
        mainHandler.removeCallbacksAndMessages(null);
        if (session != null) {
            session.close();
            session = null;
        }
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "WAKE_LOCK_CLEANUP_FAILED", error);
            }
            wakeLock = null;
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException error) {
            Log.w(TAG, "FOREGROUND_CLEANUP_FAILED", error);
        }
    }

    @Override public void onDestroy() {
        if (!stopping) {
            // Do not enable immediately here: onDestroy cannot keep a three-second
            // restoration handshake alive. Force-stop/process death can leave BT off.
            Log.w(TAG, "UNEXPECTED_SERVICE_DESTRUCTION_RESTORATION_NOT_GUARANTEED");
            stopping = true;
        }
        cleanup();
        active = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
