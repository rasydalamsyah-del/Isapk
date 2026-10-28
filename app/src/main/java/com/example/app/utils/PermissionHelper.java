package com.example.app.utils;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.app.MyDeviceAdminReceiver;

public class PermissionHelper {

    public static void openAccessibilitySettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        context.startActivity(intent);
    }

    public static void openNotificationListenerSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        context.startActivity(intent);
    }

    public static void openUsageStatsSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        context.startActivity(intent);
    }

    public static void openOverlaySettings(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        }
    }

    public static void openManageStorageSettings(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        }
    }

    public static void requestDeviceAdmin(Activity activity, int requestCode) {
        ComponentName componentName = new ComponentName(activity, MyDeviceAdminReceiver.class);
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Membutuhkan akses admin untuk fitur keamanan.");
        activity.startActivityForResult(intent, requestCode);
    }

    public static void openWriteSettings(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        }
    }

    public static void requestIgnoreBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        }
    }

    /**
     * Minta izin ACCESS_BACKGROUND_LOCATION.
     * Android 10+ (API 29) mengharuskan izin ini diminta TERPISAH
     * dari izin lokasi foreground (FINE/COARSE), dan hanya setelah
     * izin foreground sudah diberikan.
     */
    public static void requestBackgroundLocation(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Cek apakah foreground location sudah diberikan
            boolean hasFine = ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean hasCoarse = ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

            if (hasFine || hasCoarse) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        requestCode);
            }
        }
    }

    /**
     * Cek apakah izin lokasi background sudah diberikan.
     */
    public static boolean hasBackgroundLocationPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        // Di bawah Android 10, lokasi foreground sudah mencakup background
        return true;
    }

    /**
     * Buka pengaturan exact alarm untuk Android 12+ (API 31).
     */
    public static void openExactAlarmSettings(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        }
    }

    /**
     * Cek apakah exact alarm sudah diizinkan.
     */
    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            return alarmManager != null && alarmManager.canScheduleExactAlarms();
        }
        // Di bawah Android 12, exact alarm selalu diizinkan
        return true;
    }
}
