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
