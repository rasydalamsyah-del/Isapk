package com.example.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Persistent on-device diagnostic logger.
 *
 * It is intentionally independent from Logcat so diagnostics can be inspected
 * directly on the phone even when ADB/USB is unavailable.
 */
public final class DiagnosticLogger {

    private static final String PREF_NAME = "myapk_diagnostic";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_STATUS = "last_status";
    private static final String KEY_ERROR = "last_error";
    private static final int MAX_EVENTS = 120;
    private static final String SEP = "\n---\n";

    private DiagnosticLogger() {}

    public static synchronized void log(Context context, String event, String detail) {
        if (context == null) return;

        SharedPreferences prefs = prefs(context);
        String safeEvent = event == null ? "UNKNOWN_EVENT" : event;
        String safeDetail = detail == null ? "" : detail;

        String line = now() + " | " + safeEvent;
        if (!safeDetail.trim().isEmpty()) {
            line += " | " + truncate(safeDetail.replace('\n', ' '), 240);
        }

        String current = prefs.getString(KEY_EVENTS, "");
        List<String> events = new ArrayList<>();
        if (!TextUtils.isEmpty(current)) {
            String[] parts = current.split(SEP, -1);
            for (String part : parts) {
                if (!TextUtils.isEmpty(part.trim())) {
                    events.add(part);
                }
            }
        }

        events.add(line);
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }

        prefs.edit()
                .putString(KEY_EVENTS, TextUtils.join(SEP, events))
                .apply();
    }

    public static synchronized void status(Context context, String status) {
        if (context == null) return;
        prefs(context).edit()
                .putString(KEY_STATUS, status == null ? "" : status)
                .apply();
    }

    public static synchronized void error(Context context, String error) {
        if (context == null) return;
        prefs(context).edit()
                .putString(KEY_ERROR, error == null ? "" : truncate(error, 500))
                .apply();
    }

    public static synchronized String getStatus(Context context) {
        return prefs(context).getString(KEY_STATUS, "BELUM_ADA_STATUS");
    }

    public static synchronized String getError(Context context) {
        return prefs(context).getString(KEY_ERROR, "Tidak ada");
    }

    public static synchronized List<String> getEvents(Context context) {
        String current = prefs(context).getString(KEY_EVENTS, "");
        List<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(current)) return result;

        String[] parts = current.split(SEP, -1);
        for (String part : parts) {
            if (!TextUtils.isEmpty(part.trim())) {
                result.add(part);
            }
        }
        return result;
    }

    public static synchronized void clear(Context context) {
        if (context == null) return;
        prefs(context).edit()
                .remove(KEY_EVENTS)
                .remove(KEY_STATUS)
                .remove(KEY_ERROR)
                .apply();
    }

    public static String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static String now() {
        return new SimpleDateFormat(
                "HH:mm:ss.SSS",
                Locale.getDefault()
        ).format(new Date());
    }
}
