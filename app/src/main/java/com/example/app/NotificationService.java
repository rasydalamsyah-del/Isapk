package com.example.app;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashSet;
import java.util.Set;

/**
 * Receives Android notifications and persists them before attempting sync.
 *
 * The service keeps the existing notification fields:
 * packageName, title, message and timestamp.
 *
 * A short duplicate check prevents old conversation entries from being
 * inserted again when messaging apps rebuild/update their notification.
 */
public class NotificationService extends NotificationListenerService {

    /*
     * Prevents duplicate callbacks during the same process lifetime.
     */
    private final Set<String> processedEvents = new HashSet<>();

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();

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

        String title = "Tanpa Judul";
        String message = "Tanpa Isi";

        /*
         * Read title.
         */
        if (extras != null) {
            CharSequence titleValue =
                    extras.getCharSequence(Notification.EXTRA_TITLE);

            if (titleValue != null &&
                    titleValue.length() > 0) {
                title = titleValue.toString();
            }

            /*
             * Prefer the normal notification text.
             *
             * We do not immediately treat EXTRA_TEXT_LINES as a
             * separate set of messages because messaging apps can use
             * those lines to represent the entire current summary.
             */
            CharSequence textValue =
                    extras.getCharSequence(Notification.EXTRA_TEXT);

            CharSequence bigTextValue =
                    extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

            if (textValue != null &&
                    textValue.length() > 0) {

                message = textValue.toString();

            } else if (bigTextValue != null &&
                    bigTextValue.length() > 0) {

                message = bigTextValue.toString();

            } else {

                CharSequence[] lines =
                        extras.getCharSequenceArray(
                                Notification.EXTRA_TEXT_LINES
                        );

                if (lines != null && lines.length > 0) {

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
        }

        long timestamp =
                sbn.getPostTime() > 0
                        ? sbn.getPostTime()
                        : System.currentTimeMillis();

        /*
         * Android notification identity.
         *
         * Same notification callback with the same key and timestamp
         * should never be inserted twice.
         */
        String eventKey =
                sbn.getKey() + "|" + timestamp;

        /*
         * Fast in-memory duplicate protection.
         */
        synchronized (processedEvents) {
            if (processedEvents.contains(eventKey)) {
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

        NotificationDatabase db =
                new NotificationDatabase(
                        getApplicationContext()
                );

        try {

            /*
             * IMPORTANT:
             *
             * Do not deduplicate by message forever.
             *
             * We only reject an identical package/title/message that
             * appeared very recently. This handles notification
             * rebuilding such as:
             *
             * Bund Ayang -> Keren
             *
             * followed shortly by Telegram/WhatsApp rebuilding its
             * notification and exposing Bund Ayang -> Keren again.
             *
             * A later message with the same text is still allowed.
             */
            if (db.isRecentDuplicate(
                    timestamp,
                    packageName,
                    title,
                    message
            )) {
                return;
            }

            /*
             * Queue first.
             *
             * No network operation happens here.
             * This keeps offline notifications safe.
             */
            db.insert(
                    timestamp,
                    packageName,
                    title,
                    message,
                    eventKey
            );

        } finally {
            db.close();
        }

        /*
         * Let WorkManager/SyncWorker handle network delivery.
         */
        enqueueSync();
    }

    private void enqueueSync() {
        SyncWorker.enqueue(
                getApplicationContext()
        );
    }

    private String safePackageName(
            String packageName
    ) {
        return (
                packageName == null ||
                        packageName.trim().isEmpty()
        )
                ? "Unknown_App"
                : packageName;
    }
}
