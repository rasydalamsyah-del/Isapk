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

/**
 * Worker khusus untuk sinkronisasi NOTIFIKASI.
 *
 * JALUR:
 *
 * NotificationDatabase
 *        ↓
 * SyncWorker
 *        ↓
 * ApiHelper.sendNotificationToSheet()
 *        ↓
 * Cloudflare Worker
 *        ↓
 * Google Apps Script
 *
 * Worker ini TIDAK menangani kamera.
 */
public class SyncWorker extends Worker {

    private static final String TAG =
            "SyncWorker";

    public static final String UNIQUE_WORK_NAME =
            "notification_sync";

    private static final int BATCH_SIZE =
            25;


    public SyncWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams) {

        super(
                appContext,
                workerParams
        );
    }


    @NonNull
    @Override
    public Result doWork() {

        Log.d(
                TAG,
                "Notification sync started."
        );


        NotificationDatabase db =
                new NotificationDatabase(
                        getApplicationContext()
                );


        try {

            while (true) {

                // =============================================
                // WORK STOPPED
                // =============================================

                if (isStopped()) {

                    Log.w(
                            TAG,
                            "Worker stopped. Retry."
                    );

                    return Result.retry();
                }


                // =============================================
                // GET PENDING NOTIFICATIONS
                // =============================================

                List<NotificationDatabase.NotificationRecord>
                        pending =
                        db.getPending(
                                BATCH_SIZE
                        );


                if (
                        pending == null ||
                        pending.isEmpty()
                ) {

                    Log.d(
                            TAG,
                            "No pending notifications."
                    );

                    return Result.success();
                }


                Log.d(
                        TAG,
                        "Pending notifications: "
                                + pending.size()
                );


                // =============================================
                // SEND ONE BY ONE
                // =============================================

                for (
                        NotificationDatabase.NotificationRecord record
                                : pending
                ) {

                    if (isStopped()) {

                        Log.w(
                                TAG,
                                "Worker stopped during batch. Retry."
                        );

                        return Result.retry();
                    }


                    if (record == null) {

                        Log.w(
                                TAG,
                                "Null notification record. Skip."
                        );

                        continue;
                    }


                    Log.d(
                            TAG,
                            "Sending notification id="
                                    + record.id
                                    + " package="
                                    + record.packageName
                    );


                    // =========================================
                    // NOTIFICATION ROUTE
                    //
                    // ApiHelper akan menggunakan:
                    //
                    // Android
                    //   -> Cloudflare Worker
                    //   -> Apps Script
                    //
                    // =========================================

                    boolean sent =
                            ApiHelper.sendNotificationToSheet(
                                    record.timestamp,
                                    record.packageName,
                                    record.title,
                                    record.message
                            );


                    // =========================================
                    // FAILED
                    // =========================================

                    if (!sent) {

                        Log.e(
                                TAG,
                                "Notification send failed. "
                                        + "Record remains pending. "
                                        + "WorkManager will retry."
                        );

                        /*
                         * Jangan markSent().
                         *
                         * Record tetap pending sehingga
                         * WorkManager dapat mencoba kembali.
                         */

                        return Result.retry();
                    }


                    // =========================================
                    // SUCCESS
                    // =========================================

                    db.markSent(
                            record.id
                    );


                    Log.d(
                            TAG,
                            "Notification marked as sent. id="
                                    + record.id
                    );
                }
            }


        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Unexpected error in notification sync.",
                    e
            );

            /*
             * Database/API error:
             * jangan menghapus queue.
             */
            return Result.retry();


        } finally {

            db.close();

            Log.d(
                    TAG,
                    "Notification sync finished."
            );
        }
    }


    /**
     * Membuat unique WorkManager request
     * untuk sinkronisasi notifikasi.
     */
    public static void enqueue(
            Context context) {

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


        WorkManager
                .getInstance(
                        context.getApplicationContext()
                )
                .enqueueUniqueWork(
                        UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        request
                );
    }
}
