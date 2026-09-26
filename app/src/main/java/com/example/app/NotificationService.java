package com.example.app;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Receives Android notifications and persists them before attempting sync. */
public class NotificationService extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        // If notifications accumulated while the device was offline, WorkManager
        // will wait for a connected network and then drain the local queue.
        enqueueSync();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        final String packageName = safePackageName(sbn.getPackageName());
        final Bundle extras = notification.extras;

        String title = "Tanpa Judul";
        String message = "Tanpa Isi";

        if (extras != null) {
            CharSequence titleValue = extras.getCharSequence(Notification.EXTRA_TITLE);
            if (titleValue != null && titleValue.length() > 0) {
                title = titleValue.toString();
            }

            CharSequence textValue = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (textValue == null) {
                textValue = extras.getCharSequence(Notification.EXTRA_TEXT);
            }

            if (textValue != null && textValue.length() > 0) {
                message = textValue.toString();
            } else {
                CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null && lines.length > 0) {
                    StringBuilder builder = new StringBuilder();
                    for (CharSequence line : lines) {
                        if (line == null) continue;
                        if (builder.length() > 0) builder.append('\n');
                        builder.append(line);
                    }
                    if (builder.length() > 0) message = builder.toString();
                }
            }
        }

        long timestamp = sbn.getPostTime() > 0
                ? sbn.getPostTime()
                : System.currentTimeMillis();

        NotificationDatabase db = new NotificationDatabase(getApplicationContext());
        try {
            // Every posted notification is queued first. No network call happens
            // inside the listener, so an offline device does not lose the record.
            String eventKey = sbn.getKey() + "|" + timestamp;
            db.insert(timestamp, packageName, title, message, eventKey);
        } finally {
            db.close();
        }

        enqueueSync();
    }

    private void enqueueSync() {
        SyncWorker.enqueue(getApplicationContext());
    }

    private String safePackageName(String packageName) {
        return (packageName == null || packageName.trim().isEmpty())
                ? "Unknown_App"
                : packageName;
    }
}
