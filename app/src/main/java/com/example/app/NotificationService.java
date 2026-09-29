package com.example.app;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/** Receives Android notifications, queues them locally, then triggers sync. */
public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationDebug";
    private final Set<String> processedEvents = new HashSet<>();

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "NotificationListener connected");
        DiagnosticLogger.log(this, "SERVICE_CONNECTED", "Notification listener connected");
        DiagnosticLogger.status(this, "SERVICE_CONNECTED");

        try {
            enqueueSync();
            DiagnosticLogger.log(this, "SYNC_ENQUEUED", "Listener connected");
        } catch (Exception e) {
            DiagnosticLogger.error(this, "enqueue on connect: " + e);
            DiagnosticLogger.log(this, "SYNC_ENQUEUE_ERROR", e.toString());
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        DiagnosticLogger.log(this, "NOTIFICATION_CALLBACK", "onNotificationPosted");
        DiagnosticLogger.status(this, "NOTIFICATION_CALLBACK");

        Notification notification = sbn.getNotification();
        if (notification == null) {
            DiagnosticLogger.log(this, "NOTIFICATION_NULL", "StatusBarNotification notification is null");
            DiagnosticLogger.error(this, "Notification object is null");
            return;
        }

        final String packageName = safePackageName(sbn.getPackageName());
        final Bundle extras = notification.extras;
        final long postTime = sbn.getPostTime();

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
            CharSequence titleValue = extras.getCharSequence(Notification.EXTRA_TITLE);
            if (titleValue != null && titleValue.length() > 0) {
                title = titleValue.toString();
            }

            CharSequence textValue = extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence bigTextValue = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);

            if (textValue != null && textValue.length() > 0) {
                message = textValue.toString();
            } else if (bigTextValue != null && bigTextValue.length() > 0) {
                message = bigTextValue.toString();
            } else if (lines != null && lines.length > 0) {
                StringBuilder builder = new StringBuilder();
                for (CharSequence line : lines) {
                    if (line == null) continue;
                    if (builder.length() > 0) builder.append('\n');
                    builder.append(line);
                }
                if (builder.length() > 0) message = builder.toString();
            }
        }

        long timestamp = postTime > 0 ? postTime : System.currentTimeMillis();
        String eventKey = sbn.getKey() + "|" + timestamp;

        DiagnosticLogger.log(
                this,
                "NOTIFICATION_PARSED",
                "package=" + packageName
                        + " title=" + DiagnosticLogger.truncate(title, 80)
                        + " message=" + DiagnosticLogger.truncate(message, 120)
        );

        synchronized (processedEvents) {
            if (processedEvents.contains(eventKey)) {
                Log.d(TAG, "Duplicate event ignored: " + eventKey);
                DiagnosticLogger.log(this, "MEMORY_DUPLICATE", "eventKey already processed");
                return;
            }
            processedEvents.add(eventKey);
            if (processedEvents.size() > 500) {
                processedEvents.clear();
                processedEvents.add(eventKey);
            }
        }

        NotificationDatabase db = new NotificationDatabase(getApplicationContext());
        try {
            if (db.isRecentDuplicate(timestamp, packageName, title, message)) {
                Log.d(TAG, "Recent duplicate ignored: " + packageName);
                DiagnosticLogger.log(this, "DB_RECENT_DUPLICATE", "package=" + packageName);
                DiagnosticLogger.status(this, "DUPLICATE_IGNORED");
                return;
            }

            long insertedId = db.insert(
                    timestamp,
                    packageName,
                    title,
                    message,
                    eventKey
            );

            if (insertedId == -1L) {
                Log.e(TAG, "Notification database insert failed/ignored");
                DiagnosticLogger.log(this, "DB_INSERT_FAILED", "eventKey conflict or insert returned -1");
                DiagnosticLogger.error(this, "Database insert returned -1");
                DiagnosticLogger.status(this, "DB_INSERT_FAILED");
                return;
            }

            Log.d(TAG, "Notification queued for sync. id=" + insertedId);
            DiagnosticLogger.log(this, "DB_INSERT_OK", "id=" + insertedId + " package=" + packageName);
            DiagnosticLogger.status(this, "QUEUED");
        } catch (Exception e) {
            DiagnosticLogger.log(this, "DB_ERROR", e.toString());
            DiagnosticLogger.error(this, "Database: " + e);
            DiagnosticLogger.status(this, "DB_ERROR");
            Log.e(TAG, "Database error", e);
            return;
        } finally {
            db.close();
        }

        try {
            enqueueSync();
            DiagnosticLogger.log(this, "SYNC_ENQUEUED", "notification id queued");
            DiagnosticLogger.status(this, "SYNC_ENQUEUED");
        } catch (Exception e) {
            DiagnosticLogger.log(this, "SYNC_ENQUEUE_ERROR", e.toString());
            DiagnosticLogger.error(this, "WorkManager enqueue: " + e);
            DiagnosticLogger.status(this, "SYNC_ENQUEUE_ERROR");
            Log.e(TAG, "Unable to enqueue sync", e);
        }

        Log.d(TAG, "==============================");
    }

    private void enqueueSync() {
        SyncWorker.enqueue(getApplicationContext());
    }

    private String safePackageName(String packageName) {
        return packageName == null || packageName.trim().isEmpty()
                ? "Unknown_App"
                : packageName;
    }
}
