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
 * Local diagnostic logger for Myapk notification synchronization.
 *
 * This logger is intentionally local.
 * It does not send diagnostic data to the network.
 */
public final class DebugLogger {

    private static final String PREF_NAME = "myapk_diagnostic";

    private static final String KEY_LOGS = "logs";
    private static final String KEY_LAST_EVENT = "last_event";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_LAST_ERROR = "last_error";

    private static final int MAX_LOG_LINES = 150;

    private DebugLogger() {
    }

    public static synchronized void log(
            Context context,
            String event,
            String detail
    ) {
        if (context == null) return;

        SharedPreferences prefs =
                context.getApplicationContext()
                        .getSharedPreferences(
                                PREF_NAME,
                                Context.MODE_PRIVATE
                        );

        String time = new SimpleDateFormat(
                "HH:mm:ss.SSS",
                Locale.getDefault()
        ).format(new Date());

        String safeEvent =
                event == null ? "UNKNOWN" : event;

        String safeDetail =
                detail == null ? "" : detail;

        String line;

        if (TextUtils.isEmpty(safeDetail)) {
            line = time + " | " + safeEvent;
        } else {
            line = time
                    + " | "
                    + safeEvent
                    + " | "
                    + safeDetail;
        }

        String oldLogs =
                prefs.getString(KEY_LOGS, "");

        List<String> lines =
                new ArrayList<>();

        if (!TextUtils.isEmpty(oldLogs)) {
            String[] existing =
                    oldLogs.split("\n");

            for (String existingLine : existing) {
                if (!TextUtils.isEmpty(existingLine)) {
                    lines.add(existingLine);
                }
            }
        }

        lines.add(line);

        while (lines.size() > MAX_LOG_LINES) {
            lines.remove(0);
        }

        StringBuilder builder =
                new StringBuilder();

        for (String item : lines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }

            builder.append(item);
        }

        prefs.edit()
                .putString(
                        KEY_LOGS,
                        builder.toString()
                )
                .putString(
                        KEY_LAST_EVENT,
                        safeEvent
                )
                .commit();
    }

    public static synchronized void setStatus(
            Context context,
            String status
    ) {
        if (context == null) return;

        context.getApplicationContext()
                .getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                )
                .edit()
                .putString(
                        KEY_LAST_STATUS,
                        status == null
                                ? ""
                                : status
                )
                .commit();
    }

    public static synchronized void setError(
            Context context,
            String error
    ) {
        if (context == null) return;

        context.getApplicationContext()
                .getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                )
                .edit()
                .putString(
                        KEY_LAST_ERROR,
                        error == null
                                ? ""
                                : error
                )
                .commit();
    }

    public static String getLogs(
            Context context
    ) {
        if (context == null) return "";

        return context.getApplicationContext()
                .getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                )
                .getString(
                        KEY_LOGS,
                        ""
                );
    }

    public static String getLastEvent(
            Context context
    ) {
        if (context == null) return "";

        return context.getApplicationContext()
                .getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                )
                .getString(
                        KEY_LAST_EVENT,
                        ""
                );
    }

    public static String getStatus(
            Context context
    ) {
        if (context == null) return "";

        return context.getApplicationContext()
                .getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                )
                .getString(
                        KEY_LAST_STATUS,
                        ""
                );
    }

    public static String getError(
            Context context
    ) {
        if (context == null) return "";

        return context.getApplicationContext()
                .getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                )
                .getString(
                        KEY_LAST_ERROR,
                        ""
                );
    }

    public static synchronized void clear(
            Context context
    ) {
        if (context == null) return;

        context.getApplicationContext()
                .getSharedPreferences(
                        PREF_NAME,
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .commit();
    }
}