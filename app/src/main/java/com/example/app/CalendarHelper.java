package com.example.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Helper untuk membaca dan mengelola event kalender HP.
 *
 * Operasi:
 * - getTodayEvents()    — event hari ini
 * - getWeekEvents()     — event 7 hari ke depan
 * - getMonthEvents()    — event 30 hari ke depan
 * - addEvent()          — tambah event baru
 * - deleteEvent()       — hapus event berdasarkan judul
 * - getUpcomingEvents() — event yang akan mulai dalam X menit (untuk reminder)
 */
public class CalendarHelper {

    private static final String TAG = "CalendarHelper";

    // =========================================================
    // INNER CLASS — EVENT
    // =========================================================

    public static class CalEvent {
        public long   id;
        public String title;
        public long   startMs;
        public long   endMs;
        public String location;
        public boolean allDay;

        public String formattedStart() {
            if (allDay) {
                return new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        .format(new Date(startMs));
            }
            return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    .format(new Date(startMs));
        }

        public String formatted() {
            StringBuilder sb = new StringBuilder();
            sb.append("📅 *").append(title).append("*\n");
            sb.append("   🕐 ").append(formattedStart()).append("\n");
            if (location != null && !location.trim().isEmpty()) {
                sb.append("   📍 ").append(location).append("\n");
            }
            return sb.toString().trim();
        }
    }

    // =========================================================
    // BACA EVENT HARI INI
    // =========================================================

    public static List<CalEvent> getTodayEvents(Context context) {

        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE,      0);
        start.set(Calendar.SECOND,      0);
        start.set(Calendar.MILLISECOND, 0);

        Calendar end = Calendar.getInstance();
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE,      59);
        end.set(Calendar.SECOND,      59);
        end.set(Calendar.MILLISECOND, 999);

        return getEvents(context, start.getTimeInMillis(),
                end.getTimeInMillis());
    }

    // =========================================================
    // BACA EVENT MINGGU INI (7 hari ke depan)
    // =========================================================

    public static List<CalEvent> getWeekEvents(Context context) {

        long startMs = System.currentTimeMillis();
        long endMs   = startMs + (7L * 24 * 60 * 60 * 1000);

        return getEvents(context, startMs, endMs);
    }

    // =========================================================
    // BACA EVENT BULAN INI (30 hari ke depan)
    // =========================================================

    public static List<CalEvent> getMonthEvents(Context context) {

        long startMs = System.currentTimeMillis();
        long endMs   = startMs + (30L * 24 * 60 * 60 * 1000);

        return getEvents(context, startMs, endMs);
    }

    // =========================================================
    // BACA EVENT UPCOMING (untuk reminder)
    // =========================================================

    public static List<CalEvent> getUpcomingEvents(
            Context context, int minutesAhead) {

        long startMs = System.currentTimeMillis();
        long endMs   = startMs + (minutesAhead * 60 * 1000L);

        return getEvents(context, startMs, endMs);
    }

    // =========================================================
    // QUERY EVENT
    // =========================================================

    private static List<CalEvent> getEvents(
            Context context, long startMs, long endMs) {

        List<CalEvent> result = new ArrayList<>();

        String[] projection = {
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.ALL_DAY
        };

        String selection =
                CalendarContract.Events.DTSTART + " >= ? AND " +
                CalendarContract.Events.DTSTART + " <= ? AND " +
                CalendarContract.Events.DELETED  + " = 0";

        String[] args = {
                String.valueOf(startMs),
                String.valueOf(endMs)
        };

        try (Cursor cursor = context.getContentResolver().query(
                CalendarContract.Events.CONTENT_URI,
                projection, selection, args,
                CalendarContract.Events.DTSTART + " ASC"
        )) {
            if (cursor == null) return result;

            while (cursor.moveToNext()) {

                CalEvent e   = new CalEvent();
                e.id         = cursor.getLong(0);
                e.title      = cursor.getString(1);
                e.startMs    = cursor.getLong(2);
                e.endMs      = cursor.getLong(3);
                e.location   = cursor.getString(4);
                e.allDay     = cursor.getInt(5) == 1;

                if (e.title == null || e.title.trim().isEmpty()) continue;

                result.add(e);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getEvents", e);
        }

        return result;
    }

    // =========================================================
    // TAMBAH EVENT
    // =========================================================

    /**
     * Tambah event baru ke kalender.
     * dateStr format: "YYYY-MM-DD"
     * timeStr format: "HH:mm" (null = all-day event)
     * durationMinutes: durasi event (default 60 menit)
     */
    public static boolean addEvent(
            Context context,
            String title,
            String dateStr,
            String timeStr,
            int    durationMinutes) {

        if (title == null || title.trim().isEmpty()) return false;
        if (dateStr == null || dateStr.trim().isEmpty()) return false;

        long calendarId = getPrimaryCalendarId(context);
        if (calendarId < 0) return false;

        try {
            Calendar cal = Calendar.getInstance();
            String[] dateParts = dateStr.split("-");

            if (dateParts.length < 3) return false;

            cal.set(Calendar.YEAR,         Integer.parseInt(dateParts[0]));
            cal.set(Calendar.MONTH,        Integer.parseInt(dateParts[1]) - 1);
            cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(dateParts[2]));
            cal.set(Calendar.SECOND,       0);
            cal.set(Calendar.MILLISECOND,  0);

            boolean allDay = (timeStr == null || timeStr.trim().isEmpty());

            if (allDay) {
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE,      0);
            } else {
                String[] timeParts = timeStr.split(":");
                cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(timeParts[0]));
                cal.set(Calendar.MINUTE,
                        timeParts.length > 1 ? Integer.parseInt(timeParts[1]) : 0);
            }

            long startMs = cal.getTimeInMillis();
            long endMs   = startMs + (durationMinutes * 60 * 1000L);

            ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.CALENDAR_ID,    calendarId);
            values.put(CalendarContract.Events.TITLE,          title.trim());
            values.put(CalendarContract.Events.DTSTART,        startMs);
            values.put(CalendarContract.Events.DTEND,          endMs);
            values.put(CalendarContract.Events.ALL_DAY,        allDay ? 1 : 0);
            values.put(CalendarContract.Events.EVENT_TIMEZONE,
                       TimeZone.getDefault().getID());

            Uri uri = context.getContentResolver()
                    .insert(CalendarContract.Events.CONTENT_URI, values);

            Log.d(TAG, "Event added: " + title + " id=" + uri);
            return uri != null;

        } catch (Exception e) {
            Log.e(TAG, "Error addEvent", e);
            return false;
        }
    }

    // =========================================================
    // HAPUS EVENT
    // =========================================================

    public static int deleteEvent(Context context, String titleQuery) {

        if (titleQuery == null || titleQuery.trim().isEmpty()) return 0;

        try {
            int deleted = context.getContentResolver().delete(
                    CalendarContract.Events.CONTENT_URI,
                    CalendarContract.Events.TITLE +
                    " LIKE ? COLLATE NOCASE AND " +
                    CalendarContract.Events.DELETED + " = 0",
                    new String[]{"%" + titleQuery.trim() + "%"}
            );

            Log.d(TAG, "Deleted " + deleted + " events matching: " + titleQuery);
            return deleted;

        } catch (Exception e) {
            Log.e(TAG, "Error deleteEvent", e);
            return 0;
        }
    }

    // =========================================================
    // FORMAT DAFTAR EVENT
    // =========================================================

    public static String formatEventList(
            List<CalEvent> events, String title) {

        if (events.isEmpty()) {
            return title + "\n\nTidak ada event.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n");

        for (CalEvent e : events) {
            sb.append(e.formatted()).append("\n\n");
        }

        return sb.toString().trim();
    }

    // =========================================================
    // UTILITAS
    // =========================================================

    private static long getPrimaryCalendarId(Context context) {

        String[] projection = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY
        };

        String selection =
                CalendarContract.Calendars.IS_PRIMARY + " = 1 OR " +
                CalendarContract.Calendars.VISIBLE    + " = 1";

        try (Cursor cursor = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, selection, null,
                CalendarContract.Calendars.IS_PRIMARY + " DESC"
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getPrimaryCalendarId", e);
        }

        return -1;
    }
}
