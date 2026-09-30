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
 * Service polling untuk request kontrol DND dari Telegram.
 *
 * Operasi yang ditangani:
 * - on       → aktifkan DND total silence
 * - off      → matikan DND
 * - priority → DND prioritas (kontak tetap bisa masuk)
 * - status   → baca status DND saat ini
 * - schedule → jadwalkan DND otomatis setiap hari
 * - cancel   → batalkan jadwal DND
 *
 * Wajib di AndroidManifest.xml:
 *   <service android:name=".DndPollingService" android:exported="false"/>
 */
public class DndPollingService extends Service {

    private static final String TAG             = "DndPollingService";
    private static final String CHANNEL_ID      = "dnd_polling";
    private static final int    NOTIFICATION_ID = 10000;

    private static final long POLL_INTERVAL_MS = 10_000L;

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
                JSONObject result = ApiHelper.getDndRequest();

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
                String action    = request.optString("action",    "");
                String params    = request.optString("params",    "");

                if (requestId.isEmpty() || action.isEmpty()) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                Log.d(TAG, "DND request: " + action + " params=" + params);

                isProcessing = true;
                handleDndRequest(requestId, action, params);

            } catch (Exception e) {
                Log.e(TAG, "Error polling DND", e);
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
    // HANDLE REQUEST
    // =========================================================

    private void handleDndRequest(
            String requestId, String action, String params) {

        final Context ctx = getApplicationContext();

        new Thread(() -> {

            boolean success = false;
            String  message;

            try {

                if (!DndHelper.hasPermission(ctx) &&
                        !action.equals("status")) {

                    ApiHelper.sendDndResult(requestId, false,
                            "⚠️ Izin Do Not Disturb belum diberikan.\n\n" +
                            "Settings → Apps → Special App Access " +
                            "→ Do Not Disturb Access → Myapk → Allow");

                    isProcessing = false;
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                switch (action.toLowerCase()) {

                    case "on": {
                        success = DndHelper.setDndOn(ctx);
                        message = success
                                ? "🔇 *DND diaktifkan*\nMode: Total Silence\n" +
                                  "Tidak ada suara, getar, atau notifikasi."
                                : "❌ Gagal mengaktifkan DND";
                        break;
                    }

                    case "off": {
                        success = DndHelper.setDndOff(ctx);
                        message = success
                                ? "🔊 *DND dinonaktifkan*\nSemua notifikasi kembali normal."
                                : "❌ Gagal menonaktifkan DND";
                        break;
                    }

                    case "priority": {
                        success = DndHelper.setDndPriority(ctx);
                        message = success
                                ? "🔔 *DND Priority diaktifkan*\n" +
                                  "Hanya kontak & alarm yang bisa masuk."
                                : "❌ Gagal mengaktifkan DND Priority";
                        break;
                    }

                    case "status": {
                        message = DndHelper.getDndStatus(ctx);
                        success = true;
                        break;
                    }

                    case "schedule": {
                        // params format: "22:00|06:00"
                        String[] times = params.split("\\|");
                        if (times.length == 2) {
                            DndHelper.scheduleDnd(
                                    ctx,
                                    times[0].trim(),
                                    times[1].trim()
                            );
                            success = true;
                            message = "⏰ *Jadwal DND disimpan*\n" +
                                      "🔇 Aktif: " + times[0].trim() + "\n" +
                                      "🔊 Nonaktif: " + times[1].trim() + "\n\n" +
                                      "Berjalan otomatis setiap hari.";
                        } else {
                            message = "❌ Format jadwal salah.\n" +
                                      "Contoh: /dnd 22:00 06:00";
                        }
                        break;
                    }

                    case "cancel": {
                        DndHelper.cancelSchedule(ctx);
                        success = true;
                        message = "✅ Jadwal DND dibatalkan.";
                        break;
                    }

                    default:
                        message = "❌ Aksi DND tidak dikenal: " + action;
                        break;
                }

            } catch (Exception e) {
                Log.e(TAG, "Error handling DND request", e);
                message = "❌ Error: " + e.getMessage();
            }

            ApiHelper.sendDndResult(requestId, success, message);

            isProcessing = false;
            scheduleNextPoll(POLL_INTERVAL_MS);

        }).start();
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
                        .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    public static void start(Context context) {
        Intent intent = new Intent(context, DndPollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, DndPollingService.class));
    }
}
