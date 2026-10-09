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
 * Service polling untuk request perubahan pengaturan sistem HP.
 *
 * Saat user mengirim /brightness 70, /volume 50, /screentimeout 60,
 * /fontsize large, atau /settings di Telegram:
 * → GAS membuat request di sheet SettingsRequests
 * → Service ini mendeteksi dan mengeksekusi perubahan via SettingsHelper
 * → Mengirim konfirmasi balik ke Telegram
 *
 * Wajib di AndroidManifest.xml:
 *   <service android:name=".SettingsPollingService" android:exported="false"/>
 */
public class SettingsPollingService extends Service {

    private static final String TAG             = "SettingsPollingService";
    private static final String CHANNEL_ID      = "settings_polling";
    private static final int    NOTIFICATION_ID = 8000;

    private static final long POLL_INTERVAL_MS = 10_000L;

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
                JSONObject result = ApiHelper.getSettingsRequest();

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

                String requestId    = request.optString("id",     "");
                String action       = request.optString("action", "");
                String params       = request.optString("params", "");

                if (requestId.isEmpty() || action.isEmpty()) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                Log.d(TAG, "Settings request: " + action + " params=" + params);

                isProcessing = true;
                handleSettingsRequest(requestId, action, params);

            } catch (Exception e) {
                Log.e(TAG, "Error polling settings", e);
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

    private void handleSettingsRequest(
            String requestId, String action, String params) {

        final Context ctx = getApplicationContext();

        new Thread(() -> {

            boolean success = false;
            String  message;

            try {

                if (!SettingsHelper.hasPermission(ctx)) {
                    ApiHelper.sendSettingsResult(requestId,
                            false,
                            "⚠️ Izin Modify System Settings belum diberikan.\n" +
                            "Settings → Apps → Special App Access " +
                            "→ Modify System Settings → Myapk → Allow");
                    isProcessing = false;
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                switch (action.toLowerCase()) {

                    case "brightness": {
                        int val = parsePercent(params, 50);
                        success = SettingsHelper.setBrightness(ctx, val);
                        message = success
                                ? "✅ Kecerahan diubah ke *" + val + "%*"
                                : "❌ Gagal mengubah kecerahan";
                        break;
                    }

                    case "volume":
                    case "volume_media": {
                        int val = parsePercent(params, 50);
                        success = SettingsHelper.setVolume(ctx, "media", val);
                        message = success
                                ? "✅ Volume media diubah ke *" + val + "%*"
                                : "❌ Gagal mengubah volume media";
                        break;
                    }

                    case "volume_ring": {
                        int val = parsePercent(params, 50);
                        success = SettingsHelper.setVolume(ctx, "ring", val);
                        message = success
                                ? "✅ Volume dering diubah ke *" + val + "%*"
                                : "❌ Gagal mengubah volume dering";
                        break;
                    }

                    case "volume_notif": {
                        int val = parsePercent(params, 50);
                        success = SettingsHelper.setVolume(ctx, "notif", val);
                        message = success
                                ? "✅ Volume notifikasi diubah ke *" + val + "%*"
                                : "❌ Gagal mengubah volume notifikasi";
                        break;
                    }

                    case "screentimeout": {
                        int val = parseInt(params, 60);
                        success = SettingsHelper.setScreenTimeout(ctx, val);
                        message = success
                                ? "✅ Screen timeout diubah ke *" + val + " detik*"
                                : "❌ Gagal mengubah screen timeout";
                        break;
                    }

                    case "fontsize": {
                        String size = params.trim().isEmpty() ? "normal" : params.trim();
                        success = SettingsHelper.setFontSize(ctx, size);
                        message = success
                                ? "✅ Ukuran font diubah ke *" + size + "*"
                                : "❌ Gagal mengubah ukuran font";
                        break;
                    }

                    case "status": {
                        message = SettingsHelper.getCurrentSettings(ctx);
                        success = true;
                        break;
                    }

                    case "info": {
                        // Kumpulkan info identitas HP
                        message = buildPhoneInfo(ctx);
                        success = true;
                        break;
                    }

                    default:
                        message = "❌ Aksi tidak dikenal: " + action;
                        break;
                }

            } catch (Exception e) {
                Log.e(TAG, "Error handling settings request", e);
                message = "❌ Error: " + e.getMessage();
            }

            ApiHelper.sendSettingsResult(requestId, success, message);

            isProcessing = false;
            scheduleNextPoll(POLL_INTERVAL_MS);

        }).start();
    }

    // =========================================================
    // INFO IDENTITAS HP
    // =========================================================

    private String buildPhoneInfo(Context context) {

        android.telephony.TelephonyManager tm =
                (android.telephony.TelephonyManager) context
                        .getSystemService(Context.TELEPHONY_SERVICE);

        StringBuilder sb = new StringBuilder();
        sb.append("📱 *Identitas HP*\n\n");

        // Nomor HP
        String number = "";
        try {
            if (tm != null) number = tm.getLine1Number();
        } catch (Exception ignored) {}
        sb.append("📞 Nomor: ")
          .append((number != null && !number.isEmpty()) ? number : "Tidak tersedia")
          .append("\n");

        // Operator
        String operator = "";
        try {
            if (tm != null) operator = tm.getNetworkOperatorName();
        } catch (Exception ignored) {}
        sb.append("📡 Operator: ")
          .append((operator != null && !operator.isEmpty()) ? operator : "Tidak tersedia")
          .append("\n");

        // IMEI / Android ID
        String deviceId = "";
        try {
            if (tm != null && android.os.Build.VERSION.SDK_INT
                    < android.os.Build.VERSION_CODES.Q) {
                deviceId = tm.getImei();
            }
        } catch (Exception ignored) {}

        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = android.provider.Settings.Secure.getString(
                    context.getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID
            );
            sb.append("🔑 Android ID: ").append(deviceId).append("\n");
        } else {
            sb.append("🔢 IMEI: ").append(deviceId).append("\n");
        }

        // Jenis jaringan
        String networkType = getNetworkType(tm);
        sb.append("📶 Jaringan: ").append(networkType).append("\n");

        // Status SIM
        String simState = getSimState(tm);
        sb.append("💳 SIM: ").append(simState).append("\n");

        // Model HP
        String model = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        sb.append("📲 Perangkat: ").append(model).append("\n");

        // Versi Android
        sb.append("🤖 Android: ").append(android.os.Build.VERSION.RELEASE)
          .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")");

        return sb.toString().trim();
    }

    private String getNetworkType(android.telephony.TelephonyManager tm) {
        if (tm == null) return "Tidak tersedia";
        try {
            int type = tm.getNetworkType();
            switch (type) {
                case android.telephony.TelephonyManager.NETWORK_TYPE_LTE:
                    return "4G LTE";
                case android.telephony.TelephonyManager.NETWORK_TYPE_NR:
                    return "5G";
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA:
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSPA:
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA:
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP:
                    return "3G HSPA";
                case android.telephony.TelephonyManager.NETWORK_TYPE_UMTS:
                    return "3G UMTS";
                case android.telephony.TelephonyManager.NETWORK_TYPE_EDGE:
                    return "2G EDGE";
                case android.telephony.TelephonyManager.NETWORK_TYPE_GPRS:
                    return "2G GPRS";
                default:
                    return "Unknown (" + type + ")";
            }
        } catch (Exception e) {
            return "Tidak tersedia";
        }
    }

    private String getSimState(android.telephony.TelephonyManager tm) {
        if (tm == null) return "Tidak tersedia";
        try {
            int state = tm.getSimState();
            switch (state) {
                case android.telephony.TelephonyManager.SIM_STATE_READY:
                    return "✅ Terpasang & Aktif";
                case android.telephony.TelephonyManager.SIM_STATE_ABSENT:
                    return "❌ Tidak ada SIM";
                // SIM_STATE_LOCKED tidak tersedia di public API, diabaikan
                case android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED:
                    return "🔒 Butuh PIN";
                case android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED:
                    return "🔒 Butuh PUK";
                default:
                    return "Unknown";
            }
        } catch (Exception e) {
            return "Tidak tersedia";
        }
    }

    // =========================================================
    // UTILITAS PARSE
    // =========================================================

    private int parsePercent(String s, int defaultVal) {
        try {
            int val = Integer.parseInt(s.trim());
            return Math.max(0, Math.min(100, val));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private int parseInt(String s, int defaultVal) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return defaultVal;
        }
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
                        .setSmallIcon(android.R.drawable.ic_menu_manage)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    public static void start(Context context) {
        Intent intent = new Intent(context, SettingsPollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SettingsPollingService.class));
    }
}
