package com.example.app;

import android.content.Context;
import android.util.Log;

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

/**
 * Uploads pending local notifications whenever a network connection is available.
 *
 * Diagnostic version:
 * - records worker start
 * - records queue size
 * - records each API attempt
 * - records success/failure
 * - records markSent
 */
public class SyncWorker extends Worker {

    private static final String TAG =
            "SyncWorker";

    public static final String UNIQUE_WORK_NAME =
            "notification_sync";

    private static final int BATCH_SIZE = 25;

    public SyncWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams
    ) {
        super(
                appContext,
                workerParams
        );
    }

    @NonNull
    @Override
    public Result doWork() {

        Context context =
                getApplicationContext();

        NotificationDatabase db =
                new NotificationDatabase(
                        context
                );

        DebugLogger.log(
                context,
                "WORKER_STARTED",
                "SyncWorker started"
        );

        DebugLogger.setStatus(
                context,
                "WORKER_STARTED"
        );

        try {

            while (true) {

                if (isStopped()) {

                    DebugLogger.log(
                            context,
                            "WORKER_STOPPED",
                            "WorkManager stopped worker"
                    );

                    DebugLogger.setStatus(
                            context,
                            "WORKER_STOPPED"
                    );

                    return Result.retry();
                }

                List<NotificationDatabase.NotificationRecord>
                        pending =
                        db.getPending(
                                BATCH_SIZE
                        );

                int pendingCount =
                        db.getPendingCount();

                DebugLogger.log(
                        context,
                        "QUEUE_CHECK",
                        "batch=" + pending.size()
                                + ", pending=" +
                                pendingCount
                );

                if (pending.isEmpty()) {

                    DebugLogger.log(
                            context,
                            "QUEUE_EMPTY",
                            "No pending notifications"
                    );

                    DebugLogger.setStatus(
                            context,
                            "QUEUE_EMPTY"
                    );

                    return Result.success();
                }

                for (
                        NotificationDatabase.NotificationRecord record
                                : pending
                ) {

                    if (isStopped()) {

                        DebugLogger.log(
                                context,
                                "WORKER_STOPPED",
                                "Stopped before sending id=" +
                                        record.id
                        );

                        DebugLogger.setStatus(
                                context,
                                "WORKER_STOPPED"
                        );

                        return Result.retry();
                    }

                    DebugLogger.log(
                            context,
                            "API_SEND_START",
                            "id=" + record.id
                                    + ", package=" +
                                    record.packageName
                    );

                    DebugLogger.setStatus(
                            context,
                            "API_SENDING"
                    );

                    boolean sent;

                    try {

                        sent =
                                ApiHelper
                                        .sendNotificationToSheet(
                                                record.timestamp,
                                                record.packageName,
                                                record.title,
                                                record.message
                                        );

                    } catch (Exception error) {

                        Log.e(
                                TAG,
                                "ApiHelper exception",
                                error
                        );

                        DebugLogger.log(
                                context,
                                "API_EXCEPTION",
                                "id=" + record.id
                                        + ", " +
                                        error.toString()
                        );

                        DebugLogger.setStatus(
                                context,
                                "API_EXCEPTION"
                        );

                        DebugLogger.setError(
                                context,
                                error.toString()
                        );

                        return Result.retry();
                    }

                    if (!sent) {

                        Log.e(
                                TAG,
                                "Notification send failed. id=" +
                                        record.id
                        );

                        DebugLogger.log(
                                context,
                                "API_SEND_FAILED",
                                "id=" + record.id
                        );

                        DebugLogger.setStatus(
                                context,
                                "API_SEND_FAILED"
                        );

                        DebugLogger.setError(
                                context,
                                "ApiHelper returned false"
                        );

                        return Result.retry();
                    }

                    DebugLogger.log(
                            context,
                            "API_SEND_SUCCESS",
                            "id=" + record.id
                    );

                    DebugLogger.setStatus(
                            context,
                            "API_SUCCESS"
                    );

                    db.markSent(
                            record.id
                    );

                    DebugLogger.log(
                            context,
                            "DATABASE_MARK_SENT",
                            "id=" + record.id
                    );
                }
            }

        } finally {

            db.close();
        }
    }

    /**
     * Creates the unique sync request used by NotificationService.
     */
    public static void enqueue(
            Context context
    ) {

        Context appContext =
                context.getApplicationContext();

        Constraints constraints =
                new Constraints.Builder()
                        .setRequiredNetworkType(
                                NetworkType.CONNECTED
                        )
                        .build();

        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(
                        SyncWorker.class
                )
                        .setConstraints(
                                constraints
                        )
                        .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                10,
                                TimeUnit.SECONDS
                        )
                        .build();

        try {

            WorkManager.getInstance(
                            appContext
                    )
                    .enqueueUniqueWork(
                            UNIQUE_WORK_NAME,
                            androidx.work.ExistingWorkPolicy.KEEP,
                            request
                    );

            DebugLogger.log(
                    appContext,
                    "WORK_ENQUEUE_OK",
                    "Unique work submitted"
            );

        } catch (Exception error) {

            Log.e(
                    TAG,
                    "WorkManager enqueue failed",
                    error
            );

            DebugLogger.log(
                    appContext,
                    "WORK_ENQUEUE_FAILED",
                    error.toString()
            );

            DebugLogger.setStatus(
                    appContext,
                    "WORK_ENQUEUE_FAILED"
            );

            DebugLogger.setError(
                    appContext,
                    error.toString()
            );
        }
    }
}
