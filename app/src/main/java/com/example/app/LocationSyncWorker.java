package com.example.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

/**
 * Mengirim satu hasil lokasi melalui route LOCATION.
 *
 * Tidak melakukan pengambilan lokasi sendiri.
 * Koordinat sudah diperoleh setelah pengguna mengonfirmasi.
 */
public class LocationSyncWorker extends Worker {

    private static final String KEY_REQUEST_ID = "requestId";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";
    private static final String KEY_ACCURACY = "accuracy";
    private static final String KEY_TIMESTAMP = "timestamp";

    public LocationSyncWorker(
            @NonNull Context context,
            @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {

        String requestId =
                getInputData().getString(KEY_REQUEST_ID);

        double latitude =
                getInputData().getDouble(KEY_LATITUDE, Double.NaN);

        double longitude =
                getInputData().getDouble(KEY_LONGITUDE, Double.NaN);

        float accuracy =
                getInputData().getFloat(KEY_ACCURACY, Float.NaN);

        long timestamp =
                getInputData().getLong(KEY_TIMESTAMP, 0L);

        if (requestId == null || requestId.trim().isEmpty()
                || Double.isNaN(latitude)
                || Double.isNaN(longitude)
                || timestamp <= 0L) {

            LocationDebugLogger.log(
                    getApplicationContext(),
                    "LOCATION_INVALID",
                    "Data lokasi tidak lengkap."
            );

            return Result.failure();
        }

        try {
            JSONObject result =
                    ApiHelper.sendLocationResult(
                            requestId,
                            latitude,
                            longitude,
                            accuracy,
                            timestamp
                    );

            if (result != null
                    && "success".equalsIgnoreCase(
                            result.optString("status", "")
                    )) {

                LocationRequestDatabase.setStatus(
                        getApplicationContext(),
                        "SENT"
                );

                LocationDebugLogger.log(
                        getApplicationContext(),
                        "LOCATION_SENT",
                        "requestId=" + requestId
                );

                return Result.success();
            }

            LocationDebugLogger.log(
                    getApplicationContext(),
                    "LOCATION_REJECTED",
                    result == null
                            ? "Response kosong"
                            : result.toString()
            );

            return Result.retry();

        } catch (Exception e) {

            LocationDebugLogger.log(
                    getApplicationContext(),
                    "LOCATION_ERROR",
                    e.toString()
            );

            return Result.retry();
        }
    }

    public static androidx.work.Data buildInput(
            String requestId,
            LocationResult result) {

        return new androidx.work.Data.Builder()
                .putString(KEY_REQUEST_ID, requestId)
                .putDouble(KEY_LATITUDE, result.getLatitude())
                .putDouble(KEY_LONGITUDE, result.getLongitude())
                .putFloat(KEY_ACCURACY, result.getAccuracy())
                .putLong(KEY_TIMESTAMP, result.getTimestamp())
                .build();
    }
}
