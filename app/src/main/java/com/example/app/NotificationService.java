package com.example.app;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationDebug";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();

        Log.d(TAG, "NotificationListener connected");

        enqueueSync();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        String packageName = safePackageName(sbn.getPackageName());
        Bundle extras = notification.extras;

        long postTime = sbn.getPostTime();

        Log.d(TAG, "==============================");
        Log.d(TAG, "NOTIFICATION POSTED / UPDATED");
        Log.d(TAG, "packageName = " + packageName);
        Log.d(TAG, "key        = " + sbn.getKey());
        Log.d(TAG, "postTime   = " + postTime);
        Log.d(TAG, "id         = " + sbn.getId());
        Log.d(TAG, "tag        = " + sbn.getTag());

        String title = "Tanpa Judul";
        String message = "Tanpa Isi";

        if (extras != null) {

            // TITLE
            CharSequence titleValue =
                    extras.getCharSequence(Notification.EXTRA_TITLE);

            if (titleValue != null) {
                title = titleValue.toString();
            }

            Log.d(TAG, "EXTRA_TITLE = " + title);

            // EXTRA_TEXT
            CharSequence textValue =
                    extras.getCharSequence(Notification.EXTRA_TEXT);

            Log.d(TAG, "EXTRA_TEXT = " +
                    (textValue == null ? "NULL" : textValue.toString()));

            // EXTRA_BIG_TEXT
            CharSequence bigTextValue =
                    extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

            Log.d(TAG, "EXTRA_BIG_TEXT = " +
                    (bigTextValue == null ? "NULL" : bigTextValue.toString()));

            // EXTRA_TEXT_LINES
            CharSequence[] lines =
                    extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);

            if (lines == null) {
                Log.d(TAG, "EXTRA_TEXT_LINES = NULL");
            } else {
                Log.d(TAG, "EXTRA_TEXT_LINES count = " + lines.length);

                for (int i = 0; i < lines.length; i++) {
                    Log.d(TAG, "LINE[" + i + "] = " +
                            (lines[i] == null ? "NULL" : lines[i].toString()));
                }
            }

            // Untuk sementara kita tetap menggunakan mekanisme lama
            // supaya perilaku APK tidak berubah selama diagnostic.

            if (bigTextValue != null && bigTextValue.length() > 0) {
                message = bigTextValue.toString();

            } else if (textValue != null && textValue.length() > 0) {
                message = textValue.toString();

            } else if (lines != null && lines.length > 0) {

                StringBuilder builder = new StringBuilder();

                for (CharSequence line : lines) {
                    if (line == null) continue;

                    if (builder.length() > 0) {
                        builder.append('\n');
                    }

                    builder.append(line);
                }

                if (builder.length() > 0) {
                    message = builder.toString();
                }
            }
        }

        Log.d(TAG, "FINAL TITLE   = " + title);
        Log.d(TAG, "FINAL MESSAGE = " + message);
        Log.d(TAG, "==============================");

        NotificationDatabase db =
                new NotificationDatabase(getApplicationContext());

        try {
            String eventKey = sbn.getKey() + "|" + postTime;

            db.insert(
                    postTime > 0 ? postTime : System.currentTimeMillis(),
                    packageName,
                    title,
                    message,
                    eventKey
            );

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
