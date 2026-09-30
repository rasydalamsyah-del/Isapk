package com.example.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager periodic worker — cek event kalender yang akan
 * dimulai dalam 30 menit ke depan dan kirim reminder ke Telegram.
 *
 * Jalan setiap 15 menit (interval minimum WorkManager).
 * Menggunakan SharedPreferences untuk tracking event yang sudah
 * diingatkan, supaya tidak kirim reminder dua kali.
 *
 * Daftarkan dari MainActivity:
 *   CalendarReminderWorker.schedule(context);
 */
public class CalendarReminderWorker extends Worker {

    private static final String TAG        = "CalendarReminderWorker";
    private static final String PREFS_NAME = "calendar_reminder_prefs";
    private static final String PREFS_KEY  = "reminded_event_ids";

    // Kirim reminder X menit sebelum event
    private static final int REMINDER_MINUTES_AHEAD = 30;

    public CalendarReminderWorker(
            @NonNull Context context,
            @NonNull WorkerParameters params) {
        super(context, params);
    }

    // =========================================================
    // DOWORK
    // =========================================================

    @NonNull
    @Override
    public Result doWork() {

        Context ctx = getApplicationContext();

        Log.d(TAG, "Checking upcoming events...");

        try {
            List<CalendarHelper.CalEvent> upcoming =
                    CalendarHelper.getUpcomingEvents(ctx, REMINDER_MINUTES_AHEAD);

            if (upcoming.isEmpty()) {
                return Result.success();
            }

            Set<String> alreadyReminded = getRemindedIds(ctx);
            Set<String> newReminded     = new HashSet<>(alreadyReminded);

            for (CalendarHelper.CalEvent event : upcoming) {

                String eventKey = String.valueOf(event.id) + "_" +
                                  String.valueOf(event.startMs);

                if (alreadyReminded.contains(eventKey)) continue;

                // Hitung berapa menit lagi
                long minutesLeft =
                        (event.startMs - System.currentTimeMillis()) / 60_000;

                if (minutesLeft < 0) continue;

                String message =
                        "⏰ *Pengingat Kalender*\n\n" +
                        event.formatted() + "\n\n" +
                        "⏱️ Dimulai dalam *" + minutesLeft + " menit*";

                ApiHelper.sendCalendarResult("", message);

                newReminded.add(eventKey);
                Log.d(TAG, "Reminder sent for: " + event.title);
            }

            saveRemindedIds(ctx, newReminded);

            // Bersihkan ID lama (event lebih dari 2 hari yang lalu)
            cleanOldReminders(ctx);

        } catch (Exception e) {
            Log.e(TAG, "Error checking reminders", e);
        }

        return Result.success();
    }

    // =========================================================
    // SHARED PREFERENCES
    // =========================================================

    private Set<String> getRemindedIds(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> ids = prefs.getStringSet(PREFS_KEY, null);
        return ids != null ? new HashSet<>(ids) : new HashSet<>();
    }

    private void saveRemindedIds(Context context, Set<String> ids) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(PREFS_KEY, ids)
                .apply();
    }

    private void cleanOldReminders(Context context) {
        // Hapus ID event yang sudah lebih dari 2 hari lalu
        // (eventKey = "id_startMs" — parse startMs untuk cek)
        Set<String> ids     = getRemindedIds(context);
        Set<String> cleaned = new HashSet<>();
        long        cutoff  = System.currentTimeMillis()
                              - (2L * 24 * 60 * 60 * 1000);

        for (String key : ids) {
            try {
                String[] parts  = key.split("_");
                long     startMs = Long.parseLong(parts[1]);
                if (startMs > cutoff) {
                    cleaned.add(key);
                }
            } catch (Exception ignored) {
                // ID format lama / tidak valid → skip
            }
        }

        saveRemindedIds(context, cleaned);
    }

    // =========================================================
    // JADWALKAN (PERIODIC SETIAP 15 MENIT)
    // =========================================================

    public static void schedule(Context context) {

        PeriodicWorkRequest work =
                new PeriodicWorkRequest.Builder(
                        CalendarReminderWorker.class,
                        15, TimeUnit.MINUTES
                )
                .addTag("calendar_reminder")
                .build();

        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                        "calendar_reminder",
                        ExistingPeriodicWorkPolicy.KEEP,
                        work
                );

        Log.d(TAG, "Calendar reminder scheduled (every 15 min)");
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context)
                .cancelUniqueWork("calendar_reminder");
    }
}
