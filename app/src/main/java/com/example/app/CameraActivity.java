package com.example.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Visible camera screen.
 *
 * This activity deliberately requires a visible user-facing camera screen.
 * It is not a background/hidden camera service.
 */
public class CameraActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST = 200;

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private boolean useFrontCamera = true;
    private String requestId;
    private String requestedCamera = "front";
    private File lastCapturedPhoto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewView = findViewById(R.id.cameraPreview);
        Button btnFront = findViewById(R.id.btnFront);
        Button btnBack = findViewById(R.id.btnBack);
        Button btnCapture = findViewById(R.id.btnCapture);

        // Stage 4: receive the camera command from MainActivity.
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

        btnFront.setOnClickListener(v -> {
            requestedCamera = "front";
            useFrontCamera = true;
            startCamera();
        });

        btnBack.setOnClickListener(v -> {
            requestedCamera = "back";
            useFrontCamera = false;
            startCamera();
        });

        btnCapture.setOnClickListener(v -> takePhoto());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST
            );
        }
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                CameraSelector selector = useFrontCamera
                        ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : CameraSelector.DEFAULT_BACK_CAMERA;

                if (!cameraProvider.hasCamera(selector)) {
                    Toast.makeText(this,
                            "Kamera yang dipilih tidak tersedia",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        selector,
                        preview,
                        imageCapture
                );

            } catch (Exception e) {
                Toast.makeText(this,
                        "Gagal membuka kamera: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
            return;
        }

        File outputDir = new File(getCacheDir(), "camera");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Toast.makeText(this, "Gagal membuat penyimpanan sementara", Toast.LENGTH_SHORT).show();
            return;
        }

        String timestamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS",
                Locale.US
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
                            @NonNull ImageCapture.OutputFileResults outputFileResults) {
                        lastCapturedPhoto = photoFile;

                        Toast.makeText(
                                CameraActivity.this,
                                "Foto tersimpan: " + photoFile.getName(),
                                Toast.LENGTH_SHORT
                        ).show();

                        // Upload hanya setelah pengguna menekan "Ambil Foto".
                        uploadLastCapturedPhoto();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Toast.makeText(
                                CameraActivity.this,
                                "Gagal mengambil foto: " + exception.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(
                        this,
                        "Izin kamera diperlukan untuk fitur kamera",
                        Toast.LENGTH_LONG
                ).show();
                finish();
            }
        }
    }


    /**
     * Stage 6: explicitly upload the photo that was just captured.
     * Call this from an explicit user action (e.g. a future "Kirim Foto" button).
     */
    private void uploadLastCapturedPhoto() {
        if (lastCapturedPhoto == null || !lastCapturedPhoto.isFile()) {
            Toast.makeText(this, "Belum ada foto yang diambil.", Toast.LENGTH_SHORT).show();
            return;
        }

        final String currentRequestId = requestId;
        final String currentCamera = requestedCamera;
        final File photo = lastCapturedPhoto;

        if (currentRequestId == null || currentRequestId.trim().isEmpty()) {
            Toast.makeText(this, "ID permintaan kamera tidak valid.", Toast.LENGTH_LONG).show();
            return;
        }

        new Thread(() -> {
            boolean uploaded = ApiHelper.uploadCapturedPhoto(
                    currentRequestId,
                    currentCamera,
                    photo);

            ApiHelper.updateCameraRequestStatus(
                    currentRequestId,
                    uploaded ? "COMPLETED" : "UPLOAD_FAILED");

            runOnUiThread(() -> {
                Toast.makeText(
                        CameraActivity.this,
                        uploaded
                                ? "Foto berhasil dikirim."
                                : "Gagal mengirim foto.",
                        Toast.LENGTH_SHORT
                ).show();

                if (uploaded) {
                    // File berada di cache dan tidak diperlukan lagi setelah upload.
                    // Hapus hanya setelah backend menerima upload.
                    if (photo.exists() && !photo.delete()) {
                        photo.deleteOnExit();
                    }
                    lastCapturedPhoto = null;
                    finish();
                }
            });
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
