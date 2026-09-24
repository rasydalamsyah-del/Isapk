package com.example.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;

/** Uploads pending local notifications whenever a network connection is available. */
public class SyncWorker extends Worker {

    public static final String UNIQUE_WORK_NAME = "notification_sync";
    private static final int BATCH_SIZE = 25;

    public SyncWorker(@NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        NotificationDatabase db = new NotificationDatabase(getApplicationContext());
        List<NotificationDatabase.NotificationRecord> pending = db.getPending(BATCH_SIZE);

        if (pending.isEmpty()) {
            return Result.success();
        }

        for (NotificationDatabase.NotificationRecord record : pending) {
            boolean sent = ApiHelper.sendNotificationToSheet(
                    record.timestamp,
                    record.packageName,
                    record.title,
                    record.message);

            if (!sent) {
                // Keep the record locally. WorkManager will retry with backoff.
                return Result.retry();
            }

            db.markSent(record.id);
        }

        // More records may remain. Queue another pass; the network constraint remains active.
        if (!db.getPending(1).isEmpty()) {
            return Result.retry();
        }
        return Result.success();
    }
}
