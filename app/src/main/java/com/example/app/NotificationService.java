package com.example.app;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Receives Android notifications and persists them before attempting sync.
 *
 * Combines:
 * - notification diagnostics/logging
 * - in-memory duplicate protection
 * - database recent-duplicate protection
 * - local queue before network synchronization
 */
public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationDebug";

    /*
     * Prevents duplicate callbacks during the same process lifetime.
     */
    private final Set<String> processedEvents = new HashSet<>();

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();

        Log.d(TAG, "NotificationListener connected");

        // Drain anything that accumulated while offline.
        enqueueSync();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        final String packageName =
                safePackageName(sbn.getPackageName());

        final Bundle extras = notification.extras;

        final long postTime = sbn.getPostTime();

        /*
         * Diagnostic information.
         */
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

            /*
             * TITLE
             */
            CharSequence titleValue =
                    extras.getCharSequence(Notification.EXTRA_TITLE);

            if (titleValue != null &&
                    titleValue.length() > 0) {
                title = titleValue.toString();
            }

            Log.d(TAG, "EXTRA_TITLE = " + title);

            /*
             * EXTRA_TEXT
             */
            CharSequence textValue =
                    extras.getCharSequence(Notification.EXTRA_TEXT);

            Log.d(TAG, "EXTRA_TEXT = " +
                    (textValue == null
                            ? "NULL"
                            : textValue.toString()));

            /*
             * EXTRA_BIG_TEXT
             */
            CharSequence bigTextValue =
                    extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

            Log.d(TAG, "EXTRA_BIG_TEXT = " +
                    (bigTextValue == null
                            ? "NULL"
                            : bigTextValue.toString()));

            /*
             * EXTRA_TEXT_LINES
             */
            CharSequence[] lines =
                    extras.getCharSequenceArray(
                            Notification.EXTRA_TEXT_LINES);

            if (lines == null) {

                Log.d(TAG, "EXTRA_TEXT_LINES = NULL");

            } else {

                Log.d(TAG,
                        "EXTRA_TEXT_LINES count = "
                                + lines.length);

                for (int i = 0; i < lines.length; i++) {

                    Log.d(TAG,
                            "LINE[" + i + "] = " +
                                    (lines[i] == null
                                            ? "NULL"
                                            : lines[i].toString()));
                }
            }

            /*
             * Prefer normal notification text.
             * Fall back to BIG_TEXT and then TEXT_LINES.
             */
            if (textValue != null &&
                    textValue.length() > 0) {

                message = textValue.toString();

            } else if (bigTextValue != null &&
                    bigTextValue.length() > 0) {

                message = bigTextValue.toString();

            } else if (lines != null &&
                    lines.length > 0) {

                StringBuilder builder =
                        new StringBuilder();

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

        /*
         * Use notification post time when available.
         */
        long timestamp =
                postTime > 0
                        ? postTime
                        : System.currentTimeMillis();

        /*
         * Android notification identity.
         *
         * Same notification key + same timestamp represents
         * the same notification event.
         */
        String eventKey =
                sbn.getKey() + "|" + timestamp;

        /*
         * Fast in-memory duplicate protection.
         */
        synchronized (processedEvents) {

            if (processedEvents.contains(eventKey)) {
                Log.d(TAG,
                        "Duplicate event ignored: "
                                + eventKey);
                return;
            }

            processedEvents.add(eventKey);

            /*
             * Prevent unlimited memory growth.
             * The database remains the permanent duplicate protection.
             */
            if (processedEvents.size() > 500) {
                processedEvents.clear();
                processedEvents.add(eventKey);
            }
        }

        /*
         * Diagnostic final values.
         */
        Log.d(TAG, "FINAL TITLE   = " + title);
        Log.d(TAG, "FINAL MESSAGE = " + message);
        Log.d(TAG, "EVENT KEY     = " + eventKey);

        NotificationDatabase db =
                new NotificationDatabase(
                        getApplicationContext());

        try {

            /*
             * Do not deduplicate by message forever.
             *
             * The database only rejects a matching notification
             * that appeared recently.
             *
             * This allows a later notification containing the same
             * text to be stored normally.
             */
            if (db.isRecentDuplicate(
                    timestamp,
                    packageName,
                    title,
                    message)) {

                Log.d(TAG,
                        "Recent duplicate ignored: "
                                + packageName);

                return;
            }

            /*
             * Queue first.
             *
             * No network operation happens here.
             * SyncWorker handles network delivery later.
             */
            db.insert(
                    timestamp,
                    packageName,
                    title,
                    message,
                    eventKey
            );

            Log.d(TAG,
                    "Notification queued for sync");

        } finally {

            db.close();
        }

        /*
         * Let WorkManager / SyncWorker handle network delivery.
         */
        enqueueSync();

        Log.d(TAG, "==============================");
    }

    private void enqueueSync() {
        SyncWorker.enqueue(
                getApplicationContext());
    }

    private String safePackageName(
            String packageName) {

        return (
                packageName == null ||
                        packageName.trim().isEmpty()
        )
                ? "Unknown_App"
                : packageName;
    }
}
