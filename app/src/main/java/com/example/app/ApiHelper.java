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
    private static final String GAS_URL = "https://script.google.com/macros/s/AKfycbzQpRDbqgHoxZCrMgOOC1vkMHjEC6eewBDIhY8YY_bPLfwVRs1hYyDMcLukms4tgn7H/exec";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private ApiHelper() {}

    /**
     * Synchronously sends one record. The caller (SyncWorker) is responsible
     * for running this off the main thread and deciding when to retry.
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
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("timestamp", timestamp);
            json.put("packageName", packageName);
            json.put("title", title);
            json.put("message", messageText);

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readResponse(responseCode >= 200 && responseCode < 400
                    ? conn.getInputStream() : conn.getErrorStream());

            Log.d(TAG, "Response Code: " + responseCode + " body=" + responseBody);

            if (responseCode < 200 || responseCode >= 300) {
                return false;
            }

            // Apps Script returns JSON {status:"success"} for Android requests.
            if (responseBody != null && !responseBody.trim().isEmpty()) {
                try {
                    JSONObject response = new JSONObject(responseBody);
                    return "success".equalsIgnoreCase(response.optString("status"));
                } catch (Exception ignored) {
                    // Some Apps Script deployments may return plain text "OK".
                    return "OK".equalsIgnoreCase(responseBody.trim());
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error sending data to GAS", e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readResponse(InputStream stream) {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
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
