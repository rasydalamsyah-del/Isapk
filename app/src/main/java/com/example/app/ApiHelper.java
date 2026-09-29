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
 * API helper untuk:
 *
 * 1. NOTIFIKASI
 *    Android -> Cloudflare Worker -> Google Apps Script
 *
 * 2. KAMERA
 *    Android -> Google Apps Script
 *
 * PENTING:
 * - Jalur notifikasi dan kamera sengaja dipisahkan.
 * - Jangan mengubah GAS_URL untuk kebutuhan Worker.
 * - Kamera tetap menggunakan GAS_URL.
 */
public class ApiHelper {

    private static final String TAG = "ApiHelper";

    // =========================================================
    // NOTIFICATION ROUTE
    // Android -> Cloudflare Worker -> Apps Script
    // =========================================================

    private static final String WORKER_URL =
            "https://telegram-notification-bot.nadimmakarim641.workers.dev";

    // =========================================================
    // CAMERA ROUTE
    // Android -> Apps Script
    //
    // JANGAN diganti menjadi WORKER_URL.
    // Kamera yang sudah berhasil tetap menggunakan URL ini.
    // =========================================================

    private static final String GAS_URL =
            "https://script.google.com/macros/s/AKfycbw5HSVm2lQm4nVr-xZArwS8tbYL9fYYs7EVV4MbhxwY03jdHbI5_r0K6ZDk5QiIRfIZ8A/exec";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private ApiHelper() {
    }


    // =========================================================
    // NOTIFICATION
    // =========================================================

    /**
     * Mengirim satu notifikasi:
     *
     * Android
     *   -> Cloudflare Worker
     *   -> Google Apps Script
     *
     * Worker sudah diuji dan mengembalikan:
     *
     * worker: OK
     * appsScriptStatus: 200
     * appsScriptResponse: OK
     *
     * Fungsi ini TIDAK menggunakan GAS_URL secara langsung.
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
            conn.setInstanceFollowRedirects(false);
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

            InputStream responseStream;
            if (responseCode >= 200 && responseCode < 300) {
                responseStream = conn.getInputStream();
            } else {
                responseStream = conn.getErrorStream();
            }

            String responseBody = readResponse(responseStream);

            Log.d(
                    TAG,
                    "Notification Worker response: "
                            + responseCode
                            + " body="
                            + responseBody
            );

            if (responseCode >= 300 && responseCode < 400) {
                String location = conn.getHeaderField("Location");

                String detail =
                        "HTTP=" + responseCode
                                + ", redirect=" + (location == null ? "" : location);

                logDiagnostic(
                        context,
                        "API_REDIRECT_REJECTED",
                        detail
                );

                Log.e(TAG, "Notification redirect rejected: " + detail);
                return false;
            }

            if (responseCode < 200 || responseCode >= 300) {
                String detail =
                        "HTTP=" + responseCode
                                + ", body=" + limitForLog(responseBody);

                logDiagnostic(
                        context,
                        "API_HTTP_FAILED",
                        detail
                );

                Log.e(TAG, "Notification HTTP failed: " + detail);
                return false;
            }

            if (responseBody == null || responseBody.trim().isEmpty()) {
                logDiagnostic(
                        context,
                        "API_RESPONSE_REJECTED",
                        "HTTP=2xx tetapi response Worker kosong"
                );
                return false;
            }

            String trimmed = responseBody.trim();

            // Untuk route notifikasi, Worker seharusnya mengembalikan JSON.
            // Jangan lagi menganggap HTTP 200 atau plain "OK" sebagai sukses.
            JSONObject workerResponse;
            try {
                workerResponse = new JSONObject(trimmed);
            } catch (Exception parseError) {
                String detail =
                        "HTTP=" + responseCode
                                + ", invalid Worker JSON=" + limitForLog(trimmed);

                logDiagnostic(
                        context,
                        "API_RESPONSE_REJECTED",
                        detail
                );

                Log.e(TAG, "Invalid Worker JSON: " + detail, parseError);
                return false;
            }

            String worker = workerResponse.optString("worker", "");
            int appsScriptStatus =
                    workerResponse.optInt("appsScriptStatus", -1);
            String appsScriptResponse =
                    workerResponse.optString("appsScriptResponse", "");

            logDiagnostic(
                    context,
                    "API_RESPONSE",
                    "HTTP=" + responseCode
                            + ", worker=" + worker
                            + ", appsScriptStatus=" + appsScriptStatus
                            + ", appsScriptResponse="
                            + limitForLog(appsScriptResponse)
            );

            if (!"OK".equalsIgnoreCase(worker)) {
                String detail =
                        "Worker status bukan OK: "
                                + limitForLog(workerResponse.toString());

                logDiagnostic(
                        context,
                        "API_WORKER_FAILED",
                        detail
                );

                Log.e(TAG, detail);
                return false;
            }

            if (appsScriptStatus < 200 || appsScriptStatus >= 300) {
                String detail =
                        "Apps Script HTTP=" + appsScriptStatus
                                + ", response="
                                + limitForLog(appsScriptResponse);

                logDiagnostic(
                        context,
                        "API_APPS_SCRIPT_HTTP_FAILED",
                        detail
                );

                Log.e(TAG, detail);
                return false;
            }

            // Apps Script dapat mengembalikan dua bentuk response sukses:
            //
            // 1. JSON:
            //    {"status":"success","action":"notification",...}
            //
            // 2. Plain text:
            //    OK
            //
            // HTTP 200 saja TIDAK cukup karena doPost() dapat mengembalikan
            // {"status":"error",...} dengan HTTP 200.
            String appsScriptTrimmed =
                    appsScriptResponse == null
                            ? ""
                            : appsScriptResponse.trim();

            // Untuk jalur notification, plain "OK" TIDAK dianggap cukup.
            // Code.gs notification seharusnya mengembalikan JSON:
            // {"status":"success","action":"notification",...}
            //
            // Ini sengaja ketat agar Worker yang hanya meneruskan "OK"
            // tidak menyebabkan row queue di-mark SENT sebelum kita
            // membuktikan bahwa Apps Script menerima action notification.
            if ("OK".equalsIgnoreCase(appsScriptTrimmed)) {
                String detail =
                        "Apps Script mengembalikan plain OK; "
                                + "notification membutuhkan JSON status=success.";

                logDiagnostic(
                        context,
                        "API_APPS_SCRIPT_REJECTED",
                        detail
                );

                Log.e(TAG, detail);
                return false;
            }

            try {
                JSONObject appsScriptJson =
                        new JSONObject(appsScriptTrimmed);

                String status =
                        appsScriptJson.optString("status", "");
                String action =
                        appsScriptJson.optString("action", "");

                if (
                        "success".equalsIgnoreCase(status)
                                && (action.isEmpty()
                                || "notification".equalsIgnoreCase(action))
                ) {
                    logDiagnostic(
                            context,
                            "API_ACCEPTED",
                            "Apps Script status=success, action="
                                    + (action.isEmpty() ? "(none)" : action)
                    );
                    return true;
                }

                String detail =
                        "Apps Script application status=" + status
                                + ", action=" + action
                                + ", response="
                                + limitForLog(appsScriptResponse);

                logDiagnostic(
                        context,
                        "API_APPS_SCRIPT_REJECTED",
                        detail
                );

                Log.e(TAG, detail);
                return false;

            } catch (Exception parseError) {
                String detail =
                        "Apps Script response bukan JSON sukses atau OK: "
                                + limitForLog(appsScriptResponse);

                logDiagnostic(
                        context,
                        "API_APPS_SCRIPT_REJECTED",
                        detail
                );

                Log.e(TAG, detail, parseError);
                return false;
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Error sending notification to Worker",
                    e
            );

            logDiagnostic(
                    context,
                    "API_EXCEPTION",
                    e.toString()
            );

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

            // Simpan detail kegagalan di Last Error juga.
            // Ini membuat penyebab API failure tetap terlihat
            // walaupun area Event Log tidak tampil di layar.
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
     * Cek satu pending camera request dari Apps Script.
     *
     * JALUR:
     *
     * Android -> Apps Script
     *
     * Tidak menggunakan Cloudflare Worker.
     */
    public static JSONObject getPendingCameraRequest() {

        HttpURLConnection conn = null;

        try {

            URL url =
                    new URL(
                            GAS_URL +
                            "?action=getCameraRequest"
                    );


            conn =
                    (HttpURLConnection)
                            url.openConnection();


            conn.setRequestMethod(
                    "GET"
            );

            conn.setInstanceFollowRedirects(
                    true
            );

            conn.setRequestProperty(
                    "Accept",
                    "application/json, text/plain, */*"
            );

            conn.setConnectTimeout(
                    CONNECT_TIMEOUT_MS
            );

            conn.setReadTimeout(
                    READ_TIMEOUT_MS
            );


            int responseCode =
                    conn.getResponseCode();


            InputStream responseStream;

            if (
                    responseCode >= 200 &&
                    responseCode < 400
            ) {
                responseStream =
                        conn.getInputStream();
            } else {
                responseStream =
                        conn.getErrorStream();
            }


            String responseBody =
                    readResponse(
                            responseStream
                    );


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


            return new JSONObject(
                    responseBody
            );


        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Error checking camera request",
                    e
            );

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
     * JALUR:
     *
     * Android -> Apps Script
     */
    public static boolean updateCameraRequestStatus(
            String requestId,
            String status) {

        if (
                requestId == null ||
                requestId.trim().isEmpty()
        ) {
            return false;
        }


        if (
                status == null ||
                status.trim().isEmpty()
        ) {
            return false;
        }


        HttpURLConnection conn = null;

        try {

            URL url =
                    new URL(GAS_URL);


            conn =
                    (HttpURLConnection)
                            url.openConnection();


            conn.setRequestMethod(
                    "POST"
            );

            conn.setInstanceFollowRedirects(
                    false
            );

            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );

            conn.setRequestProperty(
                    "Accept",
                    "text/plain, application/json, */*"
            );

            conn.setConnectTimeout(
                    CONNECT_TIMEOUT_MS
            );

            conn.setReadTimeout(
                    READ_TIMEOUT_MS
            );

            conn.setDoOutput(
                    true
            );


            JSONObject json =
                    new JSONObject();


            json.put(
                    "action",
                    "cameraRequestStatus"
            );

            json.put(
                    "requestId",
                    requestId
            );

            json.put(
                    "status",
                    status
            );


            byte[] body =
                    json.toString()
                            .getBytes(
                                    StandardCharsets.UTF_8
                            );


            try (
                    OutputStream os =
                            conn.getOutputStream()
            ) {

                os.write(body);
                os.flush();
            }


            int responseCode =
                    conn.getResponseCode();


            InputStream responseStream;

            if (
                    responseCode >= 200 &&
                    responseCode < 400
            ) {
                responseStream =
                        conn.getInputStream();
            } else {
                responseStream =
                        conn.getErrorStream();
            }


            String responseBody =
                    readResponse(
                            responseStream
                    );


            Log.d(
                    TAG,
                    "Camera status response: "
                            + responseCode
                            + " body="
                            + responseBody
            );


            if (
                    responseCode >= 200 &&
                    responseCode < 300
            ) {
                return true;
            }


            if (
                    responseCode >= 300 &&
                    responseCode < 400
            ) {

                String location =
                        conn.getHeaderField(
                                "Location"
                        );

                return location != null &&
                        !location.trim().isEmpty();
            }


            return false;


        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Error updating camera request status",
                    e
            );

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
     * Upload satu foto yang secara eksplisit telah diambil
     * setelah pengguna menekan "Ambil Foto".
     *
     * JALUR:
     *
     * Android -> Apps Script
     *
     * Tidak menggunakan Worker.
     */
    public static boolean uploadCapturedPhoto(
            String requestId,
            String camera,
            java.io.File photoFile) {

        if (
                requestId == null ||
                requestId.trim().isEmpty()
        ) {
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
                    FileInputStream input =
                            new FileInputStream(
                                    photoFile
                            );

                    ByteArrayOutputStream output =
                            new ByteArrayOutputStream()
            ) {

                byte[] buffer =
                        new byte[8192];

                int count;

                while (
                        (count =
                                input.read(buffer))
                                != -1
                ) {

                    output.write(
                            buffer,
                            0,
                            count
                    );
                }

                photoBytes =
                        output.toByteArray();
            }


            // =================================================
            // BASE64
            // =================================================

            String encodedPhoto =
                    Base64.encodeToString(
                            photoBytes,
                            Base64.NO_WRAP
                    );


            // =================================================
            // APPS SCRIPT
            // =================================================

            URL url =
                    new URL(GAS_URL);


            conn =
                    (HttpURLConnection)
                            url.openConnection();


            conn.setRequestMethod(
                    "POST"
            );

            conn.setInstanceFollowRedirects(
                    false
            );

            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
            );

            conn.setRequestProperty(
                    "Accept",
                    "text/plain, application/json, */*"
            );

            conn.setConnectTimeout(
                    CONNECT_TIMEOUT_MS
            );

            conn.setReadTimeout(
                    READ_TIMEOUT_MS
            );

            conn.setDoOutput(
                    true
            );


            JSONObject json =
                    new JSONObject();


            json.put(
                    "action",
                    "uploadCameraPhoto"
            );

            json.put(
                    "requestId",
                    requestId
            );

            json.put(
                    "camera",
                    camera == null
                            ? ""
                            : camera
            );

            json.put(
                    "fileName",
                    photoFile.getName()
            );

            json.put(
                    "mimeType",
                    "image/jpeg"
            );

            json.put(
                    "photoBase64",
                    encodedPhoto
            );


            byte[] body =
                    json.toString()
                            .getBytes(
                                    StandardCharsets.UTF_8
                            );


            try (
                    OutputStream os =
                            conn.getOutputStream()
            ) {

                os.write(body);
                os.flush();
            }


            int responseCode =
                    conn.getResponseCode();


            InputStream responseStream;

            if (
                    responseCode >= 200 &&
                    responseCode < 400
            ) {
                responseStream =
                        conn.getInputStream();
            } else {
                responseStream =
                        conn.getErrorStream();
            }


            String responseBody =
                    readResponse(
                            responseStream
                    );


            Log.d(
                    TAG,
                    "Photo upload response: "
                            + responseCode
                            + " body="
                            + responseBody
            );


            // =================================================
            // SUCCESS
            // =================================================

            if (
                    responseCode >= 200 &&
                    responseCode < 300
            ) {

                if (
                        responseBody == null ||
                        responseBody.trim().isEmpty()
                ) {
                    return true;
                }


                try {

                    JSONObject response =
                            new JSONObject(
                                    responseBody
                            );


                    return "success".equalsIgnoreCase(
                            response.optString(
                                    "status",
                                    ""
                            )
                    );


                } catch (Exception ignored) {

                    return "OK".equalsIgnoreCase(
                            responseBody.trim()
                    );
                }
            }


            // =================================================
            // REDIRECT
            // =================================================

            if (
                    responseCode >= 300 &&
                    responseCode < 400
            ) {

                String location =
                        conn.getHeaderField(
                                "Location"
                        );


                return location != null &&
                        !location.trim().isEmpty();
            }


            return false;


        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Error uploading captured photo",
                    e
            );

            return false;


        } finally {

            if (conn != null) {
                conn.disconnect();
            }
        }
    }



    // =========================================================
    // LOCATION
    // Android -> Google Apps Script (request/status)
    // Android -> Cloudflare Worker -> Apps Script (result)
    // =========================================================

    public static JSONObject getPendingLocationRequest() {

        HttpURLConnection conn = null;

        try {
            URL url = new URL(
                    GAS_URL + "?action=getLocationRequest"
            );

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
                    responseCode >= 200 && responseCode < 400
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String body = readResponse(stream);

            if (responseCode < 200 || responseCode >= 300
                    || body == null || body.trim().isEmpty()) {
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

    public static boolean updateLocationRequestStatus(
            String requestId,
            String status) {

        if (requestId == null || requestId.trim().isEmpty()
                || status == null || status.trim().isEmpty()) {
            return false;
        }

        HttpURLConnection conn = null;

        try {
            URL url = new URL(GAS_URL);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
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
                    responseCode >= 200 && responseCode < 400
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String responseBody = readResponse(stream);

            if (responseCode >= 200 && responseCode < 300) {
                try {
                    JSONObject jsonResponse =
                            new JSONObject(responseBody);
                    return "success".equalsIgnoreCase(
                            jsonResponse.optString("status", "")
                    );
                } catch (Exception ignored) {
                    return false;
                }
            }

            return false;

        } catch (Exception e) {
            Log.e(TAG, "Error updating location request status", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Mengirim hasil lokasi melalui Worker.
     *
     * Worker route: action=location
     * Apps Script: doPost() -> handleLocationResult()
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
            conn.setInstanceFollowRedirects(false);
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
                    responseCode >= 200 && responseCode < 400
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

            JSONObject workerResponse =
                    new JSONObject(responseBody);

            if (!"OK".equalsIgnoreCase(
                    workerResponse.optString("worker", ""))) {
                return workerResponse;
            }

            int appsScriptStatus =
                    workerResponse.optInt(
                            "appsScriptStatus",
                            -1
                    );

            String appsScriptResponse =
                    workerResponse.optString(
                            "appsScriptResponse",
                            ""
                    ).trim();

            if (appsScriptStatus < 200 ||
                    appsScriptStatus >= 300) {
                return workerResponse;
            }

            JSONObject appsScriptJson =
                    new JSONObject(appsScriptResponse);

            if (!"success".equalsIgnoreCase(
                    appsScriptJson.optString("status", "")
            )) {
                return workerResponse;
            }

            return appsScriptJson;

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

            StringBuilder result =
                    new StringBuilder();

            String line;

            while (
                    (line = reader.readLine())
                            != null
            ) {

                result.append(line);
            }


            return result.toString();


        } catch (Exception e) {

            return "";
        }
    }
}
