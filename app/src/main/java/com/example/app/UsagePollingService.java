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
 * Service polling untuk request statistik penggunaan app on-demand.
 *
 * Saat user mengetik /usage today, /usage week, atau /appnow
 * di Telegram, GAS membuat request di sheet UsageRequests.
 * Service ini mendeteksinya, menjalankan UsageStatsHelper,
 * dan mengirim hasilnya kembali ke Telegram.
 *
 * Wajib di AndroidManifest.xml:
 *   <service android:name=".UsagePollingService" android:exported="false"/>
 */
public class UsagePollingService extends Service {

    private static final String TAG             = "UsagePollingService";
    private static final String CHANNEL_ID      = "usage_polling";
    private static final int    NOTIFICATION_ID = 7000;

    private static final long POLL_INTERVAL_MS  = 10_000L;

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
                JSONObject result = ApiHelper.getUsageRequest();

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

                String requestId = request.optString("id",     "");
                String period    = request.optString("period", "today");

                if (requestId.isEmpty()) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                Log.d(TAG, "Usage request: " + requestId + " period=" + period);

                isProcessing = true;
                handleUsageRequest(requestId, period);

            } catch (Exception e) {
                Log.e(TAG, "Error polling usage", e);
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

    private void handleUsageRequest(String requestId, String period) {

        final Context ctx = getApplicationContext();

        new Thread(() -> {

            try {

                if (!UsageStatsHelper.hasPermission(ctx)) {
                    ApiHelper.sendUsageReport(
                            requestId, period,
                            "⚠️ Izin Usage Access belum diberikan.\n\n" +
                            "Buka Settings → Apps → Special App Access " +
                            "→ Usage Access → Myapk → Allow"
                    );
                    isProcessing = false;
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                switch (period.toLowerCase()) {

                    case "today": {
                        List<UsageStatsHelper.AppUsage> list =
                                UsageStatsHelper.getTodayUsage(ctx);

                        String report = UsageStatsHelper.formatReport(
                                list, "📊 *Penggunaan App Hari Ini*"
                        );

                        ApiHelper.sendUsageReport(requestId, period, report);
                        break;
                    }

                    case "week": {
                        List<UsageStatsHelper.AppUsage> list =
                                UsageStatsHelper.getWeekUsage(ctx);

                        String report = UsageStatsHelper.formatReport(
                                list, "📊 *Penggunaan App 7 Hari Terakhir*"
                        );

                        ApiHelper.sendUsageReport(requestId, period, report);
                        break;
                    }

                    case "appnow": {
                        String current = UsageStatsHelper.getCurrentApp(ctx);
                        ApiHelper.sendUsageReport(
                                requestId, period,
                                "📱 *App yang sedang aktif:*\n\n" + current
                        );
                        break;
                    }

                    default:
                        ApiHelper.sendUsageReport(
                                requestId, period,
                                "❌ Period tidak dikenal: " + period
                        );
                        break;
                }

            } catch (Exception e) {
                Log.e(TAG, "Error handling usage request", e);
                ApiHelper.sendUsageReport(
                        requestId, period,
                        "❌ Gagal mengambil data penggunaan: " + e.getMessage()
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
                        .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    public static void start(Context context) {
        Intent intent = new Intent(context, UsagePollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, UsagePollingService.class));
    }
}
