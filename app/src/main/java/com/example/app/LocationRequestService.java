package com.example.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Coordinator request lokasi.
 *
 * Service ini TIDAK mengambil lokasi dan TIDAK meminta permission.
 * Tugasnya hanya menampilkan pemberitahuan yang terlihat agar pengguna
 * membuka layar konfirmasi lokasi.
 */
public class LocationRequestService extends Service {

    public static final String ACTION_OFFER =
            "com.example.app.location.OFFER";

    public static final String EXTRA_REQUEST_ID =
            "requestId";

    private static final String CHANNEL_ID =
            "location_requests";

    private static final int NOTIFICATION_ID = 8201;

    public static void offer(
            Context context,
            String requestId,
            String chatId) {

        if (context == null || requestId == null
                || requestId.trim().isEmpty()) {
            return;
        }

        LocationRequestDatabase.saveRequest(
                context,
                requestId,
                chatId
        );

        Intent intent =
                new Intent(context, LocationRequestService.class);

        intent.setAction(ACTION_OFFER);
        intent.putExtra(EXTRA_REQUEST_ID, requestId);

        try {
            // Dipanggil dari MainActivity yang sedang aktif/terlihat.
            // Service ini hanya membuat notifikasi; tidak mengambil lokasi.
            context.startService(intent);
        } catch (Exception e) {
            showNotification(context, requestId);
        }
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {

        if (intent != null &&
                ACTION_OFFER.equals(intent.getAction())) {

            String requestId =
                    intent.getStringExtra(EXTRA_REQUEST_ID);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createChannel();
            }

            android.app.Notification notification =
                    buildNotification(requestId);

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.notify(
                        NOTIFICATION_ID,
                        notification
                );
            }

            stopSelf(startId);
        }

        return START_NOT_STICKY;
    }

    private android.app.Notification buildNotification(
            String requestId) {

        Intent open =
                new Intent(this, LocationRequestActivity.class);

        open.putExtra(
                EXTRA_REQUEST_ID,
                requestId
        );

        open.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        PendingIntent pending =
                PendingIntent.getActivity(
                        this,
                        8201,
                        open,
                        Build.VERSION.SDK_INT >= 23
                                ? PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                                : PendingIntent.FLAG_UPDATE_CURRENT
                );

        return new NotificationCompat.Builder(
                this,
                CHANNEL_ID
        )
        .setSmallIcon(
                android.R.drawable.ic_dialog_map
        )
        .setContentTitle(
                "Permintaan lokasi"
        )
        .setContentText(
                "Ketuk untuk melihat dan mengonfirmasi permintaan lokasi."
        )
        .setAutoCancel(true)
        .setContentIntent(pending)
        .build();
    }

    private void createChannel() {

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "Permintaan Lokasi",
                        NotificationManager.IMPORTANCE_HIGH
                );

        NotificationManager manager =
                getSystemService(NotificationManager.class);

        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private static void showNotification(
            Context context,
            String requestId) {

        // Android lama tidak membutuhkan foreground service.
        // Untuk API modern, kegagalan start dicatat; request tetap tersimpan.
        LocationDebugLogger.log(
                context,
                "LOCATION_SERVICE_FAILED",
                "Tidak dapat menjalankan service untuk requestId="
                        + requestId
        );
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
