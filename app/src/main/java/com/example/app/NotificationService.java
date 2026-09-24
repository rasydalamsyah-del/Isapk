package com.example.app;

import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

/** Receives Android notifications and persists them before attempting sync. */
public class NotificationService extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        enqueueSync();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;

        String packageName = sbn.getPackageName();

        // Preserve the existing behavior: do not collect Telegram notifications.
        if ("org.telegram.messenger".equals(packageName)
                || "org.telegram.plus".equals(packageName)) {
            return;
        }

        Bundle extras = sbn.getNotification().extras;
        String title = extras != null
                ? extras.getString("android.title", "Tanpa Judul")
                : "Tanpa Judul";
        CharSequence textChar = extras != null
                ? extras.getCharSequence("android.text")
                : null;
        String text = (textChar != null) ? textChar.toString() : "Tanpa Isi";

        long timestamp = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();

        NotificationDatabase db = new NotificationDatabase(getApplicationContext());
        db.insert(timestamp, packageName, title, text);

        enqueueSync();
    }

    private void enqueueSync() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(getApplicationContext())
                .enqueueUniqueWork(
                        SyncWorker.UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        request);
    }
}
