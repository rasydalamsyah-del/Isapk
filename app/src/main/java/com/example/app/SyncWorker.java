package com.example.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;
import java.util.concurrent.TimeUnit;

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

        try {
            // Drain the queue in small batches. The WorkManager network constraint
            // guarantees that this work is only started while CONNECTED.
            while (true) {
                if (isStopped()) return Result.retry();

                List<NotificationDatabase.NotificationRecord> pending =
                        db.getPending(BATCH_SIZE);

                if (pending.isEmpty()) {
                    return Result.success();
                }

                for (NotificationDatabase.NotificationRecord record : pending) {
                    if (isStopped()) return Result.retry();

                    boolean sent = ApiHelper.sendNotificationToSheet(
                            record.timestamp,
                            record.packageName,
                            record.title,
                            record.message);

                    if (!sent) {
                        // Do not mark it sent. WorkManager will retry using backoff.
                        return Result.retry();
                    }

                    db.markSent(record.id);
                }
            }
        } finally {
            db.close();
        }
    }

    /** Creates the unique sync request used by NotificationService. */
    public static void enqueue(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        10,
                        TimeUnit.SECONDS)
                .build();

        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(
                        UNIQUE_WORK_NAME,
                        androidx.work.ExistingWorkPolicy.KEEP,
                        request);
    }
}
