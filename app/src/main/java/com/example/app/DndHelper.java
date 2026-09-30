package com.example.app;

import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * Helper untuk mengontrol mode Do Not Disturb (DND) HP.
 *
 * Membutuhkan izin ACCESS_NOTIFICATION_POLICY yang harus diaktifkan
 * manual oleh user:
 * Settings → Apps → Special App Access → Do Not Disturb Access → Myapk → Allow
 *
 * Mode DND:
 * - ON (total silence) : tidak ada suara, getar, atau notifikasi
 * - PRIORITY           : hanya notifikasi prioritas & telepon dari kontak
 * - OFF                : semua notifikasi normal kembali
 *
 * Jadwal DND:
 * Gunakan scheduleDnd() untuk menjadwalkan aktif/nonaktif otomatis
 * setiap hari pada jam tertentu via WorkManager.
 */
public class DndHelper {

    private static final String TAG = "DndHelper";

    // =========================================================
    // CEK IZIN
    // =========================================================

    public static boolean hasPermission(Context context) {
        NotificationManager nm = getNotificationManager(context);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    // =========================================================
    // AKTIFKAN DND (total silence)
    // =========================================================

    public static boolean setDndOn(Context context) {
        if (!hasPermission(context)) return false;
        try {
            getNotificationManager(context)
                    .setInterruptionFilter(
                            NotificationManager.INTERRUPTION_FILTER_NONE
                    );
            Log.d(TAG, "DND ON (total silence)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error setDndOn", e);
            return false;
        }
    }

    // =========================================================
    // DND PRIORITAS (kontak masih bisa telepon)
    // =========================================================

    public static boolean setDndPriority(Context context) {
        if (!hasPermission(context)) return false;
        try {
            getNotificationManager(context)
                    .setInterruptionFilter(
                            NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    );
            Log.d(TAG, "DND PRIORITY");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error setDndPriority", e);
            return false;
        }
    }

    // =========================================================
    // MATIKAN DND
    // =========================================================

    public static boolean setDndOff(Context context) {
        if (!hasPermission(context)) return false;
        try {
            getNotificationManager(context)
                    .setInterruptionFilter(
                            NotificationManager.INTERRUPTION_FILTER_ALL
                    );
            Log.d(TAG, "DND OFF");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error setDndOff", e);
            return false;
        }
    }

    // =========================================================
    // STATUS DND SAAT INI
    // =========================================================

    public static String getDndStatus(Context context) {

        if (!hasPermission(context)) {
            return "⚠️ Izin Do Not Disturb belum diberikan.\n\n" +
                   "Settings → Apps → Special App Access " +
                   "→ Do Not Disturb Access → Myapk → Allow";
        }

        NotificationManager nm = getNotificationManager(context);
        if (nm == null) return "❌ Gagal membaca status DND";

        int filter = nm.getCurrentInterruptionFilter();

        switch (filter) {
            case NotificationManager.INTERRUPTION_FILTER_NONE:
                return "🔇 *DND Aktif — Total Silence*\n" +
                       "Tidak ada suara, getar, atau notifikasi apapun.";

            case NotificationManager.INTERRUPTION_FILTER_PRIORITY:
                return "🔔 *DND Aktif — Priority Only*\n" +
                       "Hanya kontak & alarm yang bisa masuk.";

            case NotificationManager.INTERRUPTION_FILTER_ALARMS:
                return "⏰ *DND Aktif — Alarms Only*\n" +
                       "Hanya alarm yang berbunyi.";

            case NotificationManager.INTERRUPTION_FILTER_ALL:
                return "🔊 *DND Nonaktif*\n" +
                       "Semua notifikasi normal.";

            default:
                return "❓ Status DND tidak diketahui (filter=" + filter + ")";
        }
    }

    public static boolean isDndActive(Context context) {
        NotificationManager nm = getNotificationManager(context);
        if (nm == null) return false;
        int f = nm.getCurrentInterruptionFilter();
        return f == NotificationManager.INTERRUPTION_FILTER_NONE ||
               f == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
               f == NotificationManager.INTERRUPTION_FILTER_ALARMS;
    }

    // =========================================================
    // JADWAL DND (aktif dan nonaktif pada jam tertentu setiap hari)
    // =========================================================

    /**
     * Jadwalkan DND otomatis setiap hari.
     * startTime dan endTime dalam format "HH:mm" (contoh: "22:00")
     */
    public static void scheduleDnd(
            Context context, String startTime, String endTime) {

        scheduleOnce(context, "dnd_on",  startTime, "on");
        scheduleOnce(context, "dnd_off", endTime,   "off");

        Log.d(TAG, "DND dijadwalkan: ON=" + startTime + " OFF=" + endTime);
    }

    public static void cancelSchedule(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork("dnd_on");
        WorkManager.getInstance(context).cancelUniqueWork("dnd_off");
        Log.d(TAG, "Jadwal DND dibatalkan");
    }

    private static void scheduleOnce(
            Context context, String workName, String timeStr, String action) {

        String[] parts = timeStr.split(":");
        if (parts.length < 2) return;

        int hour;
        int minute;
        try {
            hour   = Integer.parseInt(parts[0].trim());
            minute = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid time: " + timeStr);
            return;
        }

        Calendar nextRun = Calendar.getInstance();
        nextRun.set(Calendar.HOUR_OF_DAY, hour);
        nextRun.set(Calendar.MINUTE,      minute);
        nextRun.set(Calendar.SECOND,      0);
        nextRun.set(Calendar.MILLISECOND, 0);

        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) {
            nextRun.add(Calendar.DAY_OF_YEAR, 1);
        }

        long delay = nextRun.getTimeInMillis() - System.currentTimeMillis();

        Data inputData = new Data.Builder()
                .putString("action", action)
                .putString("time",   timeStr)
                .build();

        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(
                DndScheduleWorker.class
        )
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .addTag(workName)
                .build();

        WorkManager.getInstance(context)
                .enqueueUniqueWork(
                        workName,
                        ExistingWorkPolicy.REPLACE,
                        work
                );

        Log.d(TAG, workName + " dijadwalkan dalam " +
                (delay / 1000 / 60) + " menit");
    }

    // =========================================================
    // UTILITAS
    // =========================================================

    private static NotificationManager getNotificationManager(Context context) {
        return (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
    }

    // =========================================================
    // INNER CLASS — DND SCHEDULE WORKER
    // =========================================================

    public static class DndScheduleWorker extends Worker {

        public DndScheduleWorker(
                Context context, WorkerParameters params) {
            super(context, params);
        }

        @Override
        public Result doWork() {

            Context ctx    = getApplicationContext();
            String  action = getInputData().getString("action");
            String  time   = getInputData().getString("time");

            Log.d(TAG, "DndScheduleWorker: action=" + action + " time=" + time);

            boolean ok = false;

            if ("on".equals(action)) {
                ok = DndHelper.setDndOn(ctx);
            } else if ("off".equals(action)) {
                ok = DndHelper.setDndOff(ctx);
            }

            if (ok) {
                // Beri tahu via GAS → Telegram
                String msg = "on".equals(action)
                        ? "🔇 DND otomatis diaktifkan (jadwal " + time + ")"
                        : "🔊 DND otomatis dinonaktifkan (jadwal " + time + ")";

                ApiHelper.sendDndResult("", true, msg);
            }

            // Jadwalkan ulang untuk besok pada jam yang sama
            String workName = "on".equals(action) ? "dnd_on" : "dnd_off";
            scheduleOnce(ctx, workName, time != null ? time : "00:00", action);

            return Result.success();
        }
    }
}
