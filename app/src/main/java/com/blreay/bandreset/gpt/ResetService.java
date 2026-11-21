package com.blreay.bandreset.gpt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Private, non-restarting foreground owner of exactly one bounded reset session. */
public final class ResetService extends Service {
    static final String EXTRA_RESULT_RECEIVER = "com.blreay.bandreset.gpt.RESULT_RECEIVER";
    static final String EXTRA_OUTCOME = "com.blreay.bandreset.gpt.OUTCOME";
    static final int RESULT_READY = 1;
    static final int RESULT_FAILED = 2;
    static final int RESULT_TERMINAL = 3;
    private static final String TAG = "BandResetGPT";
    private static final String CHANNEL_ID = "band_reset_gpt";
    private static final int NOTIFICATION_ID = 1;
    private static final long WAKE_LOCK_TIMEOUT_MS = 120000;
    // Everything touching this flag runs on the application main thread.
    private static boolean active;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ResultReceiver> receivers = new ArrayList<>();
    private AndroidBluetoothSession session;
    private PowerManager.WakeLock wakeLock;
    private boolean started;
    private boolean stopping;
    private String terminalOutcome;

    static boolean isActive() { return active; }

    @Override public void onCreate() {
        super.onCreate();
        active = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ResultReceiver receiver = resultReceiver(intent);
        if (stopping) {
            acknowledge(receiver, RESULT_TERMINAL,
                    terminalOutcome == null ? "SERVICE_STOPPING" : terminalOutcome);
            return START_NOT_STICKY;
        }
        if (receiver != null && !receivers.contains(receiver)) receivers.add(receiver);
        if (started) {
            Log.i(TAG, "DUPLICATE_SERVICE_START_IGNORED");
            acknowledge(receiver, RESULT_READY, null);
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
                // Keep FINE-gated scans eligible even if the transparent Activity is destroyed.
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null) throw new IllegalStateException("PowerManager unavailable");
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BandResetGPT:Reset");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            session = new AndroidBluetoothSession(this, mainHandler, this::onSessionFinished);
            acknowledge(receiver, RESULT_READY, null);
            mainHandler.post(() -> {
                if (!stopping && session != null) session.start();
            });
        } catch (RuntimeException error) {
            Log.e(TAG, "FOREGROUND_SESSION_START_FAILED type=" + error.getClass().getSimpleName());
            sendTerminal("SERVICE_START_FAILED");
            shutdown();
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private ResultReceiver resultReceiver(Intent intent) {
        if (intent == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
            }
            return intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
        } catch (RuntimeException error) {
            Log.w(TAG, "INVALID_RESULT_RECEIVER type=" + error.getClass().getSimpleName());
            return null;
        }
    }

    private void acknowledge(ResultReceiver receiver, int result, String outcome) {
        if (receiver == null) return;
        try {
            Bundle data = new Bundle();
            if (outcome != null) data.putString(EXTRA_OUTCOME, outcome);
            receiver.send(result, data);
        } catch (RuntimeException error) {
            Log.w(TAG, "RESULT_CALLBACK_FAILED type=" + error.getClass().getSimpleName());
        }
    }

    private void sendTerminal(String outcome) {
        if (terminalOutcome != null) return;
        terminalOutcome = outcome;
        for (ResultReceiver receiver : receivers) acknowledge(receiver, RESULT_TERMINAL, outcome);
        receivers.clear();
    }

    private void onSessionFinished(String outcome) {
        Log.i(TAG, "SESSION_FINISHED outcome=" + outcome);
        sendTerminal(outcome);
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
            AndroidBluetoothSession old = session;
            session = null; // Completion may reenter shutdown.
            old.close();
        }
        sendTerminal("SERVICE_CLEANUP");
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "WAKE_LOCK_CLEANUP_FAILED type=" + error.getClass().getSimpleName());
            }
            wakeLock = null;
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException error) {
            Log.w(TAG, "FOREGROUND_CLEANUP_FAILED type=" + error.getClass().getSimpleName());
        }
    }

    @Override public void onDestroy() {
        if (!stopping) {
            // Destruction cannot await the recovery delay; force-stop/death can leave BT off.
            Log.w(TAG, "UNEXPECTED_SERVICE_DESTRUCTION_RESTORATION_NOT_GUARANTEED");
            stopping = true;
        }
        cleanup();
        active = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
