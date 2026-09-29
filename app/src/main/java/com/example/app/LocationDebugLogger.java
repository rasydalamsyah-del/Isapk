package com.example.app;

import android.content.Context;
import android.util.Log;

/**
 * Logger lokasi terpisah dari DebugLogger notifikasi/kamera.
 *
 * Detail terakhir disimpan hanya untuk diagnostic lokal.
 */
public final class LocationDebugLogger {

    private static final String TAG = "LocationDebug";
    private static final String PREFS = "myapk_location_debug";
    private static final String LAST_STATUS = "last_status";
    private static final String LAST_ERROR = "last_error";

    private LocationDebugLogger() {}

    public static void log(
            Context context,
            String event,
            String detail) {

        if (context == null) return;

        String safeEvent = event == null ? "" : event;
        String safeDetail = detail == null ? "" : detail;

        Log.d(TAG, safeEvent + ": " + safeDetail);

        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(LAST_STATUS, safeEvent)
                .putString(LAST_ERROR,
                        isError(safeEvent) ? safeDetail : "Tidak ada")
                .apply();
    }

    public static String getLastStatus(Context context) {
        if (context == null) return "UNKNOWN";
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(LAST_STATUS, "UNKNOWN");
    }

    public static String getLastError(Context context) {
        if (context == null) return "Tidak ada";
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(LAST_ERROR, "Tidak ada");
    }

    private static boolean isError(String event) {
        return event != null &&
                (event.contains("FAILED")
                        || event.contains("ERROR")
                        || event.contains("REJECTED"));
    }
}
