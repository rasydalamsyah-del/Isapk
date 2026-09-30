package com.example.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Helper untuk download dan install APK dari URL.
 *
 * Alur:
 * 1. Download APK → cache dir
 * 2. Laporkan progress tiap 25%
 * 3. Trigger install via FileProvider + ACTION_VIEW
 *    (sistem menampilkan dialog "Instal?" — user tap Install)
 *
 * Untuk install diam-diam (tanpa dialog):
 * Accessible via AccessibilityService yang auto-klik tombol Install.
 *
 * Wajib di AndroidManifest.xml:
 *   <provider
 *       android:name="androidx.core.content.FileProvider"
 *       android:authorities="${applicationId}.provider"
 *       android:exported="false"
 *       android:grantUriPermissions="true">
 *       <meta-data
 *           android:name="android.support.FILE_PROVIDER_PATHS"
 *           android:resource="@xml/provider_paths"/>
 *   </provider>
 *
 * Wajib di res/xml/provider_paths.xml:
 *   <paths>
 *       <cache-path name="cache" path="."/>
 *   </paths>
 */
public class UpdateHelper {

    private static final String TAG = "UpdateHelper";

    // Timeout download (3 menit untuk APK besar)
    private static final int DOWNLOAD_TIMEOUT_MS = 180_000;

    // =========================================================
    // CALLBACK INTERFACE
    // =========================================================

    public interface ProgressCallback {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onComplete(File apkFile);
        void onError(String message);
    }

    // =========================================================
    // CEK VERSI APLIKASI
    // =========================================================

    public static String getCurrentVersion(Context context) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);

            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? pi.getLongVersionCode()
                    : (long) pi.versionCode;

            return pi.versionName + " (build " + versionCode + ")";

        } catch (PackageManager.NameNotFoundException e) {
            return "Tidak diketahui";
        }
    }

    // =========================================================
    // DOWNLOAD APK
    // =========================================================

    public static void downloadApk(
            String apkUrl,
            File outputFile,
            ProgressCallback callback) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(apkUrl);

            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int responseCode = conn.getResponseCode();

            if (responseCode != HttpURLConnection.HTTP_OK) {
                callback.onError("Server error: HTTP " + responseCode);
                return;
            }

            long fileSize = conn.getContentLengthLong();

            Log.d(TAG, "Download dimulai: " + apkUrl +
                    " size=" + fileSize + " bytes");

            // Hapus file lama kalau ada
            if (outputFile.exists()) {
                outputFile.delete();
            }

            try (
                InputStream  input  = conn.getInputStream();
                OutputStream output = new FileOutputStream(outputFile)
            ) {
                byte[] buffer       = new byte[8192];
                long   downloaded   = 0;
                int    lastReported = -1;
                int    count;

                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    downloaded += count;

                    if (fileSize > 0) {
                        int percent = (int) ((downloaded * 100L) / fileSize);

                        // Laporkan setiap kelipatan 25%
                        int milestone = (percent / 25) * 25;
                        if (milestone > lastReported && milestone > 0) {
                            lastReported = milestone;
                            callback.onProgress(milestone, downloaded, fileSize);
                        }
                    }
                }

                output.flush();
            }

            // Verifikasi file hasil download
            if (!outputFile.exists() || outputFile.length() == 0) {
                callback.onError("File kosong setelah download");
                return;
            }

            Log.d(TAG, "Download selesai: " + outputFile.length() + " bytes");

            callback.onProgress(100,
                    outputFile.length(), outputFile.length());
            callback.onComplete(outputFile);

        } catch (Exception e) {
            Log.e(TAG, "Error download APK", e);
            callback.onError("Error download: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // TRIGGER INSTALL (dialog sistem muncul, user tap Install)
    // =========================================================

    public static boolean triggerInstall(Context context, File apkFile) {

        if (!apkFile.exists() || apkFile.length() == 0) {
            Log.e(TAG, "APK file tidak valid: " + apkFile.getAbsolutePath());
            return false;
        }

        try {
            Uri apkUri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7+ butuh FileProvider
                apkUri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".provider",
                        apkFile
                );
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(
                    apkUri,
                    "application/vnd.android.package-archive"
            );
            installIntent.setFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_ACTIVITY_NEW_TASK
            );

            context.startActivity(installIntent);

            Log.d(TAG, "Installer triggered: " + apkFile.getName());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error trigger install", e);
            return false;
        }
    }

    // =========================================================
    // UKURAN FILE (human-readable)
    // =========================================================

    public static String formatSize(long bytes) {
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
