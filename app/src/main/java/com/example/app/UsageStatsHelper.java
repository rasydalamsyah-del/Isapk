package com.example.app;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper untuk membaca statistik penggunaan aplikasi.
 *
 * Membutuhkan izin PACKAGE_USAGE_STATS yang harus diaktifkan
 * secara manual oleh user di:
 * Settings → Apps → Special App Access → Usage Access → Myapk → Allow
 *
 * Metode utama:
 * - hasPermission()       — cek izin sudah diberikan atau belum
 * - getTodayUsage()       — statistik penggunaan hari ini
 * - getWeekUsage()        — statistik 7 hari terakhir
 * - getCurrentApp()       — nama app yang sedang aktif di foreground
 * - formatReport()        — format statistik jadi teks Telegram
 */
public class UsageStatsHelper {

    private static final String TAG = "UsageStatsHelper";

    // Tampilkan app dengan minimum 1 menit penggunaan
    private static final long MIN_USAGE_MS = 60_000L;

    // Maksimum app yang ditampilkan di laporan
    private static final int MAX_APPS_IN_REPORT = 15;

    // =========================================================
    // INNER CLASS
    // =========================================================

    public static class AppUsage {
        public String packageName;
        public String appName;
        public long   totalTimeMs;
        public String formattedTime;
    }

    // =========================================================
    // CEK IZIN
    // =========================================================

    public static boolean hasPermission(Context context) {

        AppOpsManager ops =
                (AppOpsManager) context.getSystemService(
                        Context.APP_OPS_SERVICE
                );

        if (ops == null) return false;

        int mode = ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.getPackageName()
        );

        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // =========================================================
    // STATISTIK HARI INI
    // =========================================================

    public static List<AppUsage> getTodayUsage(Context context) {

        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        return getUsageStats(context, start.getTimeInMillis(),
                System.currentTimeMillis());
    }

    // =========================================================
    // STATISTIK 7 HARI TERAKHIR
    // =========================================================

    public static List<AppUsage> getWeekUsage(Context context) {

        long endTime   = System.currentTimeMillis();
        long startTime = endTime - (7L * 24 * 60 * 60 * 1000);

        return getUsageStats(context, startTime, endTime);
    }

    // =========================================================
    // QUERY USAGE STATS
    // =========================================================

    private static List<AppUsage> getUsageStats(
            Context context, long startTime, long endTime) {

        UsageStatsManager manager =
                (UsageStatsManager) context.getSystemService(
                        Context.USAGE_STATS_SERVICE
                );

        if (manager == null) return new ArrayList<>();

        List<UsageStats> statsList = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startTime, endTime
        );

        if (statsList == null || statsList.isEmpty()) return new ArrayList<>();

        // Agregasi per package (bisa ada beberapa entry untuk satu package)
        Map<String, Long> aggregated = new HashMap<>();

        for (UsageStats stats : statsList) {
            String pkg      = stats.getPackageName();
            long   existing = aggregated.containsKey(pkg)
                    ? aggregated.get(pkg) : 0L;
            aggregated.put(pkg, existing + stats.getTotalTimeInForeground());
        }

        // Konversi ke AppUsage, filter paket sistem & < MIN_USAGE
        PackageManager pm = context.getPackageManager();
        List<AppUsage> result = new ArrayList<>();

        for (Map.Entry<String, Long> entry : aggregated.entrySet()) {

            if (entry.getValue() < MIN_USAGE_MS) continue;

            String pkg = entry.getKey();

            // Skip paket milik sistem sendiri
            if (isSystemPackage(pm, pkg) &&
                    !isWhitelistedSystemApp(pkg)) continue;

            AppUsage usage    = new AppUsage();
            usage.packageName = pkg;
            usage.totalTimeMs = entry.getValue();
            usage.appName     = getAppLabel(context, pkg);
            usage.formattedTime = formatDuration(entry.getValue());

            result.add(usage);
        }

        // Urutkan berdasarkan waktu pemakaian (terbanyak di atas)
        result.sort((a, b) ->
                Long.compare(b.totalTimeMs, a.totalTimeMs));

        // Potong jika terlalu banyak
        if (result.size() > MAX_APPS_IN_REPORT) {
            result = result.subList(0, MAX_APPS_IN_REPORT);
        }

        return result;
    }

    // =========================================================
    // APP YANG SEDANG AKTIF (FOREGROUND)
    // =========================================================

    public static String getCurrentApp(Context context) {

        UsageStatsManager manager =
                (UsageStatsManager) context.getSystemService(
                        Context.USAGE_STATS_SERVICE
                );

        if (manager == null) return "Tidak diketahui";

        long endTime   = System.currentTimeMillis();
        long startTime = endTime - (1000 * 60 * 2); // 2 menit ke belakang

        UsageEvents events = manager.queryEvents(startTime, endTime);

        if (events == null) return "Tidak diketahui";

        UsageEvents.Event lastEvent = null;
        UsageEvents.Event event     = new UsageEvents.Event();

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() ==
                    UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (lastEvent == null ||
                        event.getTimeStamp() > lastEvent.getTimeStamp()) {
                    // Buat salinan karena objek event dipakai ulang
                    lastEvent = new UsageEvents.Event();
                    lastEvent.mPackage = event.getPackageName();
                }
            }
        }

        if (lastEvent == null) return "Tidak ada aktivitas";

        return getAppLabel(context, lastEvent.getPackageName())
                + " (" + lastEvent.getPackageName() + ")";
    }

    // =========================================================
    // FORMAT LAPORAN
    // =========================================================

    public static String formatReport(
            List<AppUsage> list, String title) {

        if (list.isEmpty()) {
            return title + "\n\nTidak ada data penggunaan aplikasi.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n");

        for (int i = 0; i < list.size(); i++) {
            AppUsage u = list.get(i);
            sb.append(i + 1)
              .append(". ")
              .append(u.appName)
              .append(" — ")
              .append(u.formattedTime)
              .append("\n");
        }

        return sb.toString().trim();
    }

    // =========================================================
    // UTILITAS
    // =========================================================

    public static String getAppLabel(Context context, String packageName) {

        try {
            PackageManager pm   = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            String label = (String) pm.getApplicationLabel(info);
            return label != null ? label : packageName;
        } catch (Exception e) {
            return packageName;
        }
    }

    public static String formatDuration(long ms) {

        long totalSeconds = ms / 1000;
        long hours        = totalSeconds / 3600;
        long minutes      = (totalSeconds % 3600) / 60;
        long seconds      = totalSeconds % 60;

        if (hours > 0) {
            return hours + "j " + minutes + "m";
        } else if (minutes > 0) {
            return minutes + "m " + seconds + "d";
        } else {
            return seconds + "d";
        }
    }

    private static boolean isSystemPackage(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isWhitelistedSystemApp(String pkg) {
        // Beberapa app bawaan yang berguna untuk dilaporkan
        return pkg.equals("com.android.chrome")         ||
               pkg.equals("com.google.android.youtube") ||
               pkg.equals("com.google.android.gm")      ||
               pkg.equals("com.google.android.apps.maps");
    }
}
