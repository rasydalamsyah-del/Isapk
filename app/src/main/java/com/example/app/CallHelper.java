package com.example.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * Helper untuk melakukan panggilan telepon dari background service.
 *
 * Membutuhkan izin CALL_PHONE.
 *
 * Catatan: makeCall() menggunakan FLAG_ACTIVITY_NEW_TASK supaya
 * bisa dipanggil dari Service (bukan Activity).
 * Pada Android 10+ ini masih berfungsi dari Foreground Service.
 */
public class CallHelper {

    private static final String TAG = "CallHelper";

    // =========================================================
    // BUAT PANGGILAN
    // =========================================================

    public static boolean makeCall(Context context, String number) {

        if (number == null || number.trim().isEmpty()) return false;

        String cleanNumber = number.trim().replaceAll("\\s+", "");

        if (!SmsHelper.isValidNumber(cleanNumber)) {
            Log.w(TAG, "Nomor tidak valid: " + cleanNumber);
            return false;
        }

        try {
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:" + cleanNumber));
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(callIntent);

            Log.d(TAG, "Memanggil: " + cleanNumber);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error makeCall ke " + cleanNumber, e);
            return false;
        }
    }
}
