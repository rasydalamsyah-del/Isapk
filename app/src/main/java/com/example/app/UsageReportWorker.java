package com.example.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager Worker untuk laporan penggunaan app otomatis.
 *
 * Jadwal:
 * - Setiap hari pukul 23:00 → laporan penggunaan hari ini
 * - Setiap Minggu pukul 22:00 → laporan penggunaan 7 hari
 *
 * Setelah berjalan, Worker menjadwalkan ulang dirinya sendiri
 * untuk waktu berikutnya (siklus otomatis tanpa perlu AlarmManager).
 *
 * Cara daftarkan pertama kali dari MainActivity:
 *   UsageReportWorker.scheduleDailyReport(context);
 *   UsageReportWorker.scheduleWeeklyReport(context);
 */
public class UsageReportWorker extends Worker {

    private static final String TAG  = "UsageReportWorker";
    private static final String KEY_TYPE = "report_type";
    public  static final String TYPE_DAILY  = "daily";
    public  static final String TYPE_WEEKLY = "weekly";

    public UsageReportWorker(
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

        Context ctx  = getApplicationContext();
        String  type = getInputData().getString(KEY_TYPE);

        Log.d(TAG, "Menjalankan laporan: " + type);

        try {

            if (!UsageStatsHelper.hasPermission(ctx)) {
                Log.w(TAG, "Izin Usage Access belum diberikan");
                reschedule(ctx, type);
                return Result.success();
            }

            if (TYPE_DAILY.equals(type)) {

                List<UsageStatsHelper.AppUsage> list =
                        UsageStatsHelper.getTodayUsage(ctx);

                String report = UsageStatsHelper.formatReport(
                        list, "📊 *Laporan Harian Penggunaan App*"
                );

                ApiHelper.sendAutoUsageReport(report, TYPE_DAILY);

                Log.d(TAG, "Laporan harian terkirim");

            } else if (TYPE_WEEKLY.equals(type)) {

                List<UsageStatsHelper.AppUsage> list =
                        UsageStatsHelper.getWeekUsage(ctx);

                String report = UsageStatsHelper.formatReport(
                        list, "📊 *Laporan Mingguan Penggunaan App*"
                );

                ApiHelper.sendAutoUsageReport(report, TYPE_WEEKLY);

                Log.d(TAG, "Laporan mingguan terkirim");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error menjalankan laporan usage", e);
        }

        // Jadwalkan ulang untuk waktu berikutnya
        reschedule(ctx, type);

        return Result.success();
    }

    // =========================================================
    // JADWALKAN ULANG
    // =========================================================

    private void reschedule(Context context, String type) {

        if (TYPE_DAILY.equals(type)) {
            scheduleDailyReport(context);
        } else if (TYPE_WEEKLY.equals(type)) {
            scheduleWeeklyReport(context);
        }
    }

    // =========================================================
    // JADWAL LAPORAN HARIAN (23:00)
    // =========================================================

    public static void scheduleDailyReport(Context context) {

        Calendar nextRun = Calendar.getInstance();
        nextRun.set(Calendar.HOUR_OF_DAY, 23);
        nextRun.set(Calendar.MINUTE, 0);
        nextRun.set(Calendar.SECOND, 0);
        nextRun.set(Calendar.MILLISECOND, 0);

        // Kalau sudah lewat 23:00 hari ini, jadwalkan untuk besok
        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) {
            nextRun.add(Calendar.DAY_OF_YEAR, 1);
        }

        long delay = nextRun.getTimeInMillis() - System.currentTimeMillis();

        Data inputData = new Data.Builder()
                .putString(KEY_TYPE, TYPE_DAILY)
                .build();

        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(
                UsageReportWorker.class
        )
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .addTag("usage_daily_report")
                .build();

        WorkManager.getInstance(context)
                .enqueueUniqueWork(
                        "usage_daily_report",
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        work
                );

        Log.d(TAG, "Laporan harian dijadwalkan dalam " +
                (delay / 1000 / 60) + " menit");
    }

    // =========================================================
    // JADWAL LAPORAN MINGGUAN (Minggu 22:00)
    // =========================================================

    public static void scheduleWeeklyReport(Context context) {

        Calendar nextRun = Calendar.getInstance();
        nextRun.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        nextRun.set(Calendar.HOUR_OF_DAY, 22);
        nextRun.set(Calendar.MINUTE, 0);
        nextRun.set(Calendar.SECOND, 0);
        nextRun.set(Calendar.MILLISECOND, 0);

        // Kalau sudah lewat, jadwalkan Minggu depan
        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) {
            nextRun.add(Calendar.WEEK_OF_YEAR, 1);
        }

        long delay = nextRun.getTimeInMillis() - System.currentTimeMillis();

        Data inputData = new Data.Builder()
                .putString(KEY_TYPE, TYPE_WEEKLY)
                .build();

        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(
                UsageReportWorker.class
        )
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .addTag("usage_weekly_report")
                .build();

        WorkManager.getInstance(context)
                .enqueueUniqueWork(
                        "usage_weekly_report",
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        work
                );

        Log.d(TAG, "Laporan mingguan dijadwalkan dalam " +
                (delay / 1000 / 60 / 60) + " jam");
    }
}
