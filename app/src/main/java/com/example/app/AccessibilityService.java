package com.example.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;

public class AccessibilityService extends android.accessibilityservice.AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Logika untuk menangkap peristiwa layar (misal: perpindahan window, klik, teks)
    }

    @Override
    public void onInterrupt() {
        // Dipanggil saat sistem menginterupsi layanan
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Konfigurasi tambahan saat service aktif
    }
}
