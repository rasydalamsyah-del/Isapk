package com.example.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Uploads pending local notifications whenever a network connection is available. */
public class SyncWorker extends Worker {

    private static final String TAG = "SyncWorker";
    public static final String UNIQUE_WORK_NAME = "notification_sync";
    private static final int BATCH_SIZE = 25;

    public SyncWorker(@NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        DiagnosticLogger.log(getApplicationContext(), "WORKER_STARTED", "SyncWorker doWork");
        DiagnosticLogger.status(getApplicationContext(), "WORKER_STARTED");
        Log.d(TAG, "Notification sync started.");

        NotificationDatabase db = new NotificationDatabase(getApplicationContext());
        try {
            while (true) {
                if (isStopped()) {
                    DiagnosticLogger.log(getApplicationContext(), "WORKER_STOPPED", "retry");
                    DiagnosticLogger.status(getApplicationContext(), "WORKER_STOPPED");
                    return Result.retry();
                }

                List<NotificationDatabase.NotificationRecord> pending = db.getPending(BATCH_SIZE);
                DiagnosticLogger.log(
                        getApplicationContext(),
                        "QUEUE_CHECK",
                        "pending=" + (pending == null ? 0 : pending.size())
                );

                if (pending == null || pending.isEmpty()) {
                    Log.d(TAG, "No pending notifications.");
                    DiagnosticLogger.log(getApplicationContext(), "QUEUE_EMPTY", "No pending notifications");
                    DiagnosticLogger.status(getApplicationContext(), "QUEUE_EMPTY");
                    return Result.success();
                }

                Log.d(TAG, "Pending notifications: " + pending.size());

                for (NotificationDatabase.NotificationRecord record : pending) {
                    if (isStopped()) {
                        DiagnosticLogger.log(getApplicationContext(), "WORKER_STOPPED", "during batch");
                        DiagnosticLogger.status(getApplicationContext(), "WORKER_STOPPED");
                        return Result.retry();
                    }

                    if (record == null) continue;

                    DiagnosticLogger.log(
                            getApplicationContext(),
                            "API_SEND_START",
                            "id=" + record.id + " package=" + record.packageName
                    );
                    DiagnosticLogger.status(getApplicationContext(), "API_SEND_START");

                    boolean sent = ApiHelper.sendNotificationToSheet(
                            record.timestamp,
                            record.packageName,
                            record.title,
                            record.message
                    );

                    if (!sent) {
                        Log.e(TAG, "Notification send failed. Record remains pending.");
                        DiagnosticLogger.log(
                                getApplicationContext(),
                                "API_SEND_FAILED",
                                "id=" + record.id
                        );
                        DiagnosticLogger.error(getApplicationContext(), "Notification API returned false");
                        DiagnosticLogger.status(getApplicationContext(), "API_SEND_FAILED");
                        return Result.retry();
                    }

                    DiagnosticLogger.log(
                            getApplicationContext(),
                            "API_SEND_SUCCESS",
                            "id=" + record.id
                    );
                    DiagnosticLogger.status(getApplicationContext(), "API_SEND_SUCCESS");

                    db.markSent(record.id);
                    DiagnosticLogger.log(
                            getApplicationContext(),
                            "DB_MARK_SENT",
                            "id=" + record.id
                    );
                    DiagnosticLogger.status(getApplicationContext(), "SENT");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in notification sync.", e);
            DiagnosticLogger.log(getApplicationContext(), "WORKER_EXCEPTION", e.toString());
            DiagnosticLogger.error(getApplicationContext(), "SyncWorker: " + e);
            DiagnosticLogger.status(getApplicationContext(), "WORKER_EXCEPTION");
            return Result.retry();
        } finally {
            db.close();
            Log.d(TAG, "Notification sync finished.");
            DiagnosticLogger.log(getApplicationContext(), "WORKER_FINISHED", "database closed");
        }
    }

    public static void enqueue(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        10,
                        TimeUnit.SECONDS
                )
                .build();

        try {
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(
                            UNIQUE_WORK_NAME,
                            // APPEND_OR_REPLACE prevents a new notification from
                            // being discarded while an earlier unique worker is running.
                            ExistingWorkPolicy.APPEND_OR_REPLACE,
                            request
                    );

            DiagnosticLogger.log(
                    context,
                    "WORK_ENQUEUE_OK",
                    "policy=APPEND_OR_REPLACE"
            );
        } catch (Exception e) {
            DiagnosticLogger.log(context, "WORK_ENQUEUE_ERROR", e.toString());
            DiagnosticLogger.error(context, "WorkManager: " + e);
            DiagnosticLogger.status(context, "WORK_ENQUEUE_ERROR");
            throw e;
        }
    }
}
