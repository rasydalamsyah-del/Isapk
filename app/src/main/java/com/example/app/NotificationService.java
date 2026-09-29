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
 * Diagnostic additions:
 * - records service connection
 * - records notification reception
 * - records duplicate filtering
 * - verifies database insert result
 * - records sync enqueue
 */
public class NotificationService
        extends NotificationListenerService {

    private static final String TAG =
            "NotificationDebug";

    private final Set<String> processedEvents =
            new HashSet<>();

    @Override
    public void onListenerConnected() {

        super.onListenerConnected();

        Log.d(
                TAG,
                "NotificationListener connected"
        );

        DebugLogger.log(
                getApplicationContext(),
                "SERVICE_CONNECTED",
                "NotificationListenerService connected"
        );

        DebugLogger.setStatus(
                getApplicationContext(),
                "SERVICE_CONNECTED"
        );

        enqueueSync();
    }

    @Override
    public void onNotificationPosted(
            StatusBarNotification sbn
    ) {

        if (sbn == null) {

            DebugLogger.log(
                    getApplicationContext(),
                    "NOTIFICATION_NULL",
                    "StatusBarNotification = null"
            );

            return;
        }

        Notification notification =
                sbn.getNotification();

        if (notification == null) {

            DebugLogger.log(
                    getApplicationContext(),
                    "NOTIFICATION_OBJECT_NULL",
                    "Notification object = null"
            );

            return;
        }

        final String packageName =
                safePackageName(
                        sbn.getPackageName()
                );

        final Bundle extras =
                notification.extras;

        final long postTime =
                sbn.getPostTime();

        Log.d(TAG, "==============================");
        Log.d(TAG, "NOTIFICATION POSTED / UPDATED");
        Log.d(TAG, "packageName = " + packageName);
        Log.d(TAG, "key        = " + sbn.getKey());
        Log.d(TAG, "postTime   = " + postTime);
        Log.d(TAG, "id         = " + sbn.getId());
        Log.d(TAG, "tag        = " + sbn.getTag());

        DebugLogger.log(
                getApplicationContext(),
                "NOTIFICATION_RECEIVED",
                "package=" + packageName
        );

        String title = "Tanpa Judul";
        String message = "Tanpa Isi";

        if (extras != null) {

            CharSequence titleValue =
                    extras.getCharSequence(
                            Notification.EXTRA_TITLE
                    );

            if (titleValue != null &&
                    titleValue.length() > 0) {

                title =
                        titleValue.toString();
            }

            Log.d(
                    TAG,
                    "EXTRA_TITLE = " + title
            );

            CharSequence textValue =
                    extras.getCharSequence(
                            Notification.EXTRA_TEXT
                    );

            Log.d(
                    TAG,
                    "EXTRA_TEXT = " +
                            (
                                    textValue == null
                                            ? "NULL"
                                            : textValue.toString()
                            )
            );

            CharSequence bigTextValue =
                    extras.getCharSequence(
                            Notification.EXTRA_BIG_TEXT
                    );

            Log.d(
                    TAG,
                    "EXTRA_BIG_TEXT = " +
                            (
                                    bigTextValue == null
                                            ? "NULL"
                                            : bigTextValue.toString()
                            )
            );

            CharSequence[] lines =
                    extras.getCharSequenceArray(
                            Notification.EXTRA_TEXT_LINES
                    );

            if (lines == null) {

                Log.d(
                        TAG,
                        "EXTRA_TEXT_LINES = NULL"
                );

            } else {

                Log.d(
                        TAG,
                        "EXTRA_TEXT_LINES count = " +
                                lines.length
                );

                for (int i = 0;
                     i < lines.length;
                     i++) {

                    Log.d(
                            TAG,
                            "LINE[" + i + "] = " +
                                    (
                                            lines[i] == null
                                                    ? "NULL"
                                                    : lines[i].toString()
                                    )
                    );
                }
            }

            if (textValue != null &&
                    textValue.length() > 0) {

                message =
                        textValue.toString();

            } else if (bigTextValue != null &&
                    bigTextValue.length() > 0) {

                message =
                        bigTextValue.toString();

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
                    message =
                            builder.toString();
                }
            }
        }

        long timestamp =
                postTime > 0
                        ? postTime
                        : System.currentTimeMillis();

        String eventKey =
                sbn.getKey()
                        + "|"
                        + timestamp;

        synchronized (processedEvents) {

            if (processedEvents.contains(eventKey)) {

                Log.d(
                        TAG,
                        "Duplicate event ignored: " +
                                eventKey
                );

                DebugLogger.log(
                        getApplicationContext(),
                        "DUPLICATE_MEMORY",
                        packageName
                );

                return;
            }

            processedEvents.add(eventKey);

            if (processedEvents.size() > 500) {

                processedEvents.clear();

                processedEvents.add(
                        eventKey
                );
            }
        }

        Log.d(
                TAG,
                "FINAL TITLE   = " + title
        );

        Log.d(
                TAG,
                "FINAL MESSAGE = " + message
        );

        Log.d(
                TAG,
                "EVENT KEY     = " + eventKey
        );

        DebugLogger.log(
                getApplicationContext(),
                "NOTIFICATION_PARSED",
                "package=" + packageName
                        + ", title=" + title
        );

        NotificationDatabase db =
                new NotificationDatabase(
                        getApplicationContext()
                );

        try {

            if (db.isRecentDuplicate(
                    timestamp,
                    packageName,
                    title,
                    message
            )) {

                Log.d(
                        TAG,
                        "Recent duplicate ignored: " +
                                packageName
                );

                DebugLogger.log(
                        getApplicationContext(),
                        "DUPLICATE_DATABASE",
                        packageName
                );

                DebugLogger.setStatus(
                        getApplicationContext(),
                        "DUPLICATE_DATABASE"
                );

                return;
            }

            long insertedId =
                    db.insert(
                            timestamp,
                            packageName,
                            title,
                            message,
                            eventKey
                    );

            /*
             * IMPORTANT:
             *
             * SQLite insertWithOnConflict()
             * returns -1 when the insert was ignored.
             */
            if (insertedId == -1) {

                Log.e(
                        TAG,
                        "Notification database INSERT FAILED"
                );

                DebugLogger.log(
                        getApplicationContext(),
                        "DATABASE_INSERT_FAILED",
                        "eventKey=" + eventKey
                );

                DebugLogger.setStatus(
                        getApplicationContext(),
                        "DATABASE_INSERT_FAILED"
                );

                DebugLogger.setError(
                        getApplicationContext(),
                        "SQLite insert returned -1"
                );

                return;
            }

            Log.d(
                    TAG,
                    "Notification queued for sync. id=" +
                            insertedId
            );

            DebugLogger.log(
                    getApplicationContext(),
                    "DATABASE_INSERT_OK",
                    "id=" + insertedId
            );

            DebugLogger.setStatus(
                    getApplicationContext(),
                    "QUEUED"
            );

            DebugLogger.setError(
                    getApplicationContext(),
                    ""
            );

        } catch (Exception error) {

            Log.e(
                    TAG,
                    "Database error",
                    error
            );

            DebugLogger.log(
                    getApplicationContext(),
                    "DATABASE_EXCEPTION",
                    error.toString()
            );

            DebugLogger.setStatus(
                    getApplicationContext(),
                    "DATABASE_EXCEPTION"
            );

            DebugLogger.setError(
                    getApplicationContext(),
                    error.toString()
            );

            return;

        } finally {

            db.close();
        }

        enqueueSync();

        DebugLogger.log(
                getApplicationContext(),
                "SYNC_ENQUEUED",
                "WorkManager request submitted"
        );

        Log.d(
                TAG,
                "=============================="
        );
    }

    private void enqueueSync() {

        try {

            SyncWorker.enqueue(
                    getApplicationContext()
            );

        } catch (Exception error) {

            Log.e(
                    TAG,
                    "Failed to enqueue SyncWorker",
                    error
            );

            DebugLogger.log(
                    getApplicationContext(),
                    "SYNC_ENQUEUE_FAILED",
                    error.toString()
            );

            DebugLogger.setStatus(
                    getApplicationContext(),
                    "SYNC_ENQUEUE_FAILED"
            );

            DebugLogger.setError(
                    getApplicationContext(),
                    error.toString()
            );
        }
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
