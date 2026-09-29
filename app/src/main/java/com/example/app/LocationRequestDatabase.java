package com.example.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Penyimpanan request lokasi terakhir.
 *
 * Hanya menyimpan metadata request, bukan koordinat.
 */
public final class LocationRequestDatabase {

    private static final String PREFS = "myapk_location_request";
    private static final String REQUEST_ID = "request_id";
    private static final String CHAT_ID = "chat_id";
    private static final String STATUS = "status";

    private LocationRequestDatabase() {}

    public static void saveRequest(
            Context context,
            String requestId,
            String chatId) {

        prefs(context).edit()
                .putString(REQUEST_ID, requestId == null ? "" : requestId)
                .putString(CHAT_ID, chatId == null ? "" : chatId)
                .putString(STATUS, "OFFERED")
                .apply();
    }

    public static String getRequestId(Context context) {
        return prefs(context).getString(REQUEST_ID, "");
    }

    public static void setStatus(Context context, String status) {
        prefs(context).edit()
                .putString(STATUS, status == null ? "" : status)
                .apply();
    }

    public static String getStatus(Context context) {
        return prefs(context).getString(STATUS, "");
    }

    public static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
