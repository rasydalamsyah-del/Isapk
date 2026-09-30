package com.example.app;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * Helper untuk membaca dan mengubah pengaturan sistem HP.
 *
 * Membutuhkan izin WRITE_SETTINGS yang harus diaktifkan manual oleh user:
 * Settings → Apps → Special App Access → Modify System Settings → Myapk → Allow
 *
 * Pengaturan yang didukung:
 * - brightness  : kecerahan layar (0-100%)
 * - volume      : volume media, dering, notifikasi (0-100%)
 * - timeout     : screen off timeout (detik)
 * - fontsize    : ukuran teks sistem (small/normal/large/huge)
 * - status      : baca semua pengaturan saat ini
 */
public class SettingsHelper {

    private static final String TAG = "SettingsHelper";

    // =========================================================
    // CEK IZIN
    // =========================================================

    public static boolean hasPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.System.canWrite(context);
        }
        return true;
    }

    // =========================================================
    // BRIGHTNESS (0-100%)
    // =========================================================

    public static boolean setBrightness(Context context, int percent) {
        if (!hasPermission(context)) return false;

        int value = clamp(percent, 0, 100);
        // Konversi persen ke skala 0-255
        int brightness = (int) (value * 2.55f);

        try {
            ContentResolver cr = context.getContentResolver();
            // Matikan auto-brightness dulu supaya manual bisa berlaku
            Settings.System.putInt(cr,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);

            Settings.System.putInt(cr,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness);

            Log.d(TAG, "Brightness set to " + percent + "% (" + brightness + ")");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error setBrightness", e);
            return false;
        }
    }

    public static int getBrightness(Context context) {
        try {
            int raw = Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            return (int) (raw / 2.55f);
        } catch (Exception e) {
            return -1;
        }
    }

    // =========================================================
    // VOLUME (0-100%)
    // =========================================================

    public static boolean setVolume(
            Context context, String type, int percent) {

        int value = clamp(percent, 0, 100);

        AudioManager am = (AudioManager) context
                .getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;

        int streamType;
        switch (type.toLowerCase()) {
            case "ring":
                streamType = AudioManager.STREAM_RING;
                break;
            case "notif":
            case "notification":
                streamType = AudioManager.STREAM_NOTIFICATION;
                break;
            case "alarm":
                streamType = AudioManager.STREAM_ALARM;
                break;
            default:
                streamType = AudioManager.STREAM_MUSIC;
                break;
        }

        int max    = am.getStreamMaxVolume(streamType);
        int target = (int) ((value / 100.0f) * max);

        try {
            am.setStreamVolume(streamType, target, 0);
            Log.d(TAG, "Volume " + type + " set to " + percent + "% (" + target + "/" + max + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error setVolume " + type, e);
            return false;
        }
    }

    public static int getVolume(Context context, String type) {
        AudioManager am = (AudioManager) context
                .getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return -1;

        int streamType;
        switch (type.toLowerCase()) {
            case "ring":        streamType = AudioManager.STREAM_RING;         break;
            case "notif":       streamType = AudioManager.STREAM_NOTIFICATION; break;
            case "alarm":       streamType = AudioManager.STREAM_ALARM;        break;
            default:            streamType = AudioManager.STREAM_MUSIC;        break;
        }

        int current = am.getStreamVolume(streamType);
        int max     = am.getStreamMaxVolume(streamType);
        return max > 0 ? (int) ((current * 100.0f) / max) : 0;
    }

    // =========================================================
    // SCREEN TIMEOUT (detik)
    // =========================================================

    public static boolean setScreenTimeout(Context context, int seconds) {
        if (!hasPermission(context)) return false;

        int ms = clamp(seconds, 15, 1800) * 1000;

        try {
            Settings.System.putInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    ms);
            Log.d(TAG, "Screen timeout set to " + seconds + "s");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error setScreenTimeout", e);
            return false;
        }
    }

    public static int getScreenTimeout(Context context) {
        try {
            return Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 30000) / 1000;
        } catch (Exception e) {
            return -1;
        }
    }

    // =========================================================
    // FONT SIZE
    // =========================================================

    public static boolean setFontSize(Context context, String size) {
        if (!hasPermission(context)) return false;

        float scale;
        switch (size.toLowerCase()) {
            case "small":  scale = 0.85f; break;
            case "large":  scale = 1.15f; break;
            case "huge":   scale = 1.30f; break;
            default:       scale = 1.00f; break; // normal
        }

        try {
            Settings.System.putFloat(
                    context.getContentResolver(),
                    Settings.System.FONT_SCALE,
                    scale);
            Log.d(TAG, "Font size set to " + size + " (" + scale + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error setFontSize", e);
            return false;
        }
    }

    public static String getFontSize(Context context) {
        try {
            float scale = Settings.System.getFloat(
                    context.getContentResolver(),
                    Settings.System.FONT_SCALE, 1.0f);
            if (scale <= 0.86f) return "small";
            if (scale >= 1.29f) return "huge";
            if (scale >= 1.14f) return "large";
            return "normal";
        } catch (Exception e) {
            return "normal";
        }
    }

    // =========================================================
    // BACA SEMUA PENGATURAN SAAT INI
    // =========================================================

    public static String getCurrentSettings(Context context) {

        StringBuilder sb = new StringBuilder();

        sb.append("⚙️ *Status Pengaturan HP*\n\n");

        if (!hasPermission(context)) {
            sb.append("⚠️ Izin Modify System Settings belum diberikan.\n\n");
            sb.append("Settings → Apps → Special App Access → Modify System Settings → Myapk → Allow");
            return sb.toString();
        }

        sb.append("☀️ Kecerahan: ")
          .append(getBrightness(context)).append("%\n");

        sb.append("🔊 Volume Media: ")
          .append(getVolume(context, "media")).append("%\n");

        sb.append("📳 Volume Dering: ")
          .append(getVolume(context, "ring")).append("%\n");

        sb.append("🔔 Volume Notifikasi: ")
          .append(getVolume(context, "notif")).append("%\n");

        sb.append("⏱️ Screen Off Timeout: ")
          .append(getScreenTimeout(context)).append(" detik\n");

        sb.append("🔤 Ukuran Font: ")
          .append(getFontSize(context)).append("\n");

        return sb.toString().trim();
    }

    // =========================================================
    // UTILITAS
    // =========================================================

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
