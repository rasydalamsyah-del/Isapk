package com.example.app;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sends queued notification records to the Google Apps Script endpoint. */
public class ApiHelper {

    private static final String TAG = "ApiHelper";

    // Replace only this URL with the CURRENT Apps Script Web App /exec URL.
    // Do not put the Telegram bot token here.
    private static final String GAS_URL = "https://script.google.com/macros/s/AKfycbw5HSVm2lQm4nVr-xZArwS8tbYL9fYYs7EVV4MbhxwY03jdHbI5_r0K6ZDk5QiIRfIZ8A/exec";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private ApiHelper() {}

    /**
     * Sends exactly one notification record.
     * Returns true only when the Apps Script endpoint has accepted the request.
     * A 2xx response is the normal success case. Apps Script Web Apps can also
     * answer a POST with 302 after executing it, so a 3xx response is accepted
     * as success as long as a Location header is present.
     */
    public static boolean sendNotificationToSheet(
            long timestamp,
            String packageName,
            String title,
            String messageText) {

        HttpURLConnection conn = null;
        try {
            URL url = new URL(GAS_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "text/plain, application/json, */*");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("timestamp", timestamp);
            json.put("packageName", packageName == null ? "Unknown_App" : packageName);
            json.put("title", title == null ? "Tanpa Judul" : title);
            json.put("message", messageText == null ? "Tanpa Isi" : messageText);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readResponse(
                    responseCode >= 200 && responseCode < 400
                            ? conn.getInputStream()
                            : conn.getErrorStream());

            Log.d(TAG, "Response Code: " + responseCode + " body=" + responseBody);

            if (responseCode >= 200 && responseCode < 300) {
                // Current Code.gs returns plain "OK". Also tolerate JSON success.
                if (responseBody == null || responseBody.trim().isEmpty()) return true;
                if ("OK".equalsIgnoreCase(responseBody.trim())) return true;
                try {
                    JSONObject response = new JSONObject(responseBody);
                    String status = response.optString("status", "");
                    return status.isEmpty() || "success".equalsIgnoreCase(status);
                } catch (Exception ignored) {
                    return true;
                }
            }

            // Google Apps Script Web Apps commonly return 302 to a googleusercontent
            // execution URL after processing a POST. We deliberately do not follow
            // it because following a 302 can change POST semantics. The request has
            // already reached the Apps Script endpoint, so treat this normal redirect
            // as accepted when a redirect target exists.
            if (responseCode >= 300 && responseCode < 400) {
                String location = conn.getHeaderField("Location");
                if (location != null && !location.trim().isEmpty()) {
                    Log.d(TAG, "Apps Script accepted request and returned redirect: " + location);
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error sending data to GAS", e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readResponse(InputStream stream) {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
