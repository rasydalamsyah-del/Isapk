package com.example.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.app.utils.PermissionHelper;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int RUNTIME_PERMISSION_CODE = 100;
    private static final int DEVICE_ADMIN_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Minta Izin Runtime
        findViewById(R.id.btnRequestRuntime).setOnClickListener(v -> requestRuntimePermissions());

        // 2. Aksesibilitas
        findViewById(R.id.btnAccessibility).setOnClickListener(v -> 
            PermissionHelper.openAccessibilitySettings(this));

        // 3. Akses Notifikasi
        findViewById(R.id.btnNotificationListener).setOnClickListener(v -> 
            PermissionHelper.openNotificationListenerSettings(this));

        // 4. Akses Penggunaan
        findViewById(R.id.btnUsageStats).setOnClickListener(v -> 
            PermissionHelper.openUsageStatsSettings(this));

        // 5. Tampil di Atas Aplikasi Lain
        findViewById(R.id.btnOverlay).setOnClickListener(v -> 
            PermissionHelper.openOverlaySettings(this));

        // 6. Akses Semua File
        findViewById(R.id.btnManageStorage).setOnClickListener(v -> 
            PermissionHelper.openManageStorageSettings(this));

        // 7. Admin Perangkat
        findViewById(R.id.btnDeviceAdmin).setOnClickListener(v -> 
            PermissionHelper.requestDeviceAdmin(this, DEVICE_ADMIN_CODE));

        // 8. Ubah Pengaturan Sistem
        findViewById(R.id.btnWriteSettings).setOnClickListener(v -> 
            PermissionHelper.openWriteSettings(this));

        // 9. Abaikan Optimasi Baterai
        findViewById(R.id.btnIgnoreBattery).setOnClickListener(v -> 
            PermissionHelper.requestIgnoreBatteryOptimizations(this));
    }

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

        // Android 12+ Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }

        // Android 13+ Notification & Media
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, 
                    listPermissionsNeeded.toArray(new String[0]), RUNTIME_PERMISSION_CODE);
        }
    }
}
