package com.example.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.ArrayList;

/**
 * Helper untuk mengirim SMS dari HP.
 *
 * Membutuhkan izin SEND_SMS yang harus diberikan runtime.
 *
 * Fitur:
 * - sendSms()        — kirim SMS ke satu nomor
 * - sendSmsToMany()  — kirim SMS yang sama ke banyak nomor
 *
 * SMS panjang (> 160 karakter) otomatis dipecah jadi multipart.
 */
public class SmsHelper {

    private static final String TAG = "SmsHelper";

    // =========================================================
    // KIRIM SMS KE SATU NOMOR
    // =========================================================

    public static boolean sendSms(
            Context context, String number, String message) {

        if (number == null || number.trim().isEmpty()) return false;
        if (message == null || message.trim().isEmpty()) return false;

        String cleanNumber = number.trim().replaceAll("\\s+", "");

        try {
            SmsManager smsManager = SmsManager.getDefault();

            // Pecah kalau pesan terlalu panjang
            ArrayList<String> parts = smsManager.divideMessage(message);

            if (parts.size() == 1) {
                smsManager.sendTextMessage(
                        cleanNumber, null, message, null, null
                );
            } else {
                smsManager.sendMultipartTextMessage(
                        cleanNumber, null, parts, null, null
                );
            }

            Log.d(TAG, "SMS terkirim ke " + cleanNumber +
                    " (" + parts.size() + " part)");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error sendSms ke " + cleanNumber, e);
            return false;
        }
    }

    // =========================================================
    // KIRIM SMS KE BANYAK NOMOR (BLAST)
    // =========================================================

    /**
     * Kirim SMS yang sama ke banyak nomor.
     * numbers: array nomor yang sudah divalidasi
     * Mengembalikan jumlah SMS yang berhasil terkirim.
     */
    public static int sendSmsToMany(
            Context context, String[] numbers, String message) {

        if (numbers == null || numbers.length == 0) return 0;
        if (message == null || message.trim().isEmpty()) return 0;

        int sent = 0;

        for (String number : numbers) {
            if (number == null || number.trim().isEmpty()) continue;

            boolean ok = sendSms(context, number.trim(), message);
            if (ok) sent++;

            // Jeda singkat antar pengiriman untuk menghindari throttling
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}
        }

        Log.d(TAG, "SMS blast: " + sent + "/" + numbers.length + " berhasil");
        return sent;
    }

    // =========================================================
    // VALIDASI NOMOR (minimal)
    // =========================================================

    public static boolean isValidNumber(String number) {
        if (number == null) return false;
        String clean = number.trim().replaceAll("[\\s\\-()]", "");
        // Minimal 8 digit, maksimal 15 digit, bisa diawali +
        return clean.matches("^\\+?\\d{8,15}$");
    }
}
