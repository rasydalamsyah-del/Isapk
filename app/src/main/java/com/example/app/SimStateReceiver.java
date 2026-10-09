package com.example.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Memantau perubahan status kartu SIM secara real-time.
 *
 * Notifikasi dikirim ke Telegram saat:
 * - SIM dicabut (ABSENT)
 * - SIM dimasukkan (PRESENT / READY)
 * - SIM berganti (nomor atau operator berubah)
 *
 * Wajib di AndroidManifest.xml:
 *   <receiver android:name=".SimStateReceiver" android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.intent.action.SIM_STATE_CHANGED"/>
 *       </intent-filter>
 *   </receiver>
 */
public class SimStateReceiver extends BroadcastReceiver {

    private static final String TAG = "SimStateReceiver";
    private static final String ACTION_SIM_CARD_STATE_CHANGED = "android.telephony.action.SIM_CARD_STATE_CHANGED";
    private static final String EXTRA_SIM_STATE = "android.telephony.extra.SIM_STATE";

    // Key SharedPreferences untuk track state SIM sebelumnya
    private static final String PREFS_NAME    = "sim_monitor_prefs";
    private static final String PREFS_SIM_STATE = "last_sim_state";

    @Override
    public void onReceive(Context context, Intent intent) {

        if (!ACTION_SIM_CARD_STATE_CHANGED.equals(
                intent.getAction()) &&
            !"android.intent.action.SIM_STATE_CHANGED".equals(
                intent.getAction())) {
            return;
        }

        String simState = intent.getStringExtra(
                EXTRA_SIM_STATE
        );

        if (simState == null) {
            simState = intent.getStringExtra("ss");
        }

        if (simState == null) return;

        Log.d(TAG, "SIM state: " + simState);

        // Bandingkan dengan state sebelumnya
        String lastState = getLastSimState(context);

        if (simState.equals(lastState)) return; // tidak berubah

        saveLastSimState(context, simState);

        final String finalState = simState;
        final Context appCtx    = context.getApplicationContext();

        new Thread(() -> {

            String message = buildMessage(finalState);

            if (message != null) {
                ApiHelper.sendSimEvent(appCtx, finalState, message);
            }

        }).start();
    }

    // =========================================================
    // FORMAT PESAN
    // =========================================================

    private String buildMessage(String simState) {

        switch (simState) {

            case "ABSENT":
                return "⚠️ *SIM Dicabut!*\n\nKartu SIM tidak terdeteksi di HP.";

            case "PRESENT":
            case "READY":
                return "✅ *SIM Terpasang*\n\nKartu SIM berhasil terdeteksi dan siap digunakan.";

            case "LOCKED":
                return "🔒 *SIM Terkunci*\n\nKartu SIM terkunci — mungkin memerlukan PIN atau PUK.";

            case "CARD_IO_ERROR":
            case "CARD_RESTRICTED":
                return "❌ *SIM Error*\n\nTerjadi error pada kartu SIM (" + simState + ").";

            case "NOT_READY":
                // Biasanya muncul saat booting — skip untuk menghindari notifikasi palsu
                return null;

            default:
                return null;
        }
    }

    // =========================================================
    // SHARED PREFERENCES
    // =========================================================

    private String getLastSimState(Context context) {
        return context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREFS_SIM_STATE, "");
    }

    private void saveLastSimState(Context context, String state) {
        context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREFS_SIM_STATE, state)
                .apply();
    }
}
