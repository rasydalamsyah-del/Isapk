package com.example.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

/**
 * Pengambil lokasi satu kali.
 *
 * Urutan:
 * 1. GPS/provider FINE bila tersedia.
 * 2. NETWORK provider sebagai fallback.
 *
 * Tidak melakukan tracking berkala dan tidak meminta background location.
 */
public final class LocationHelper {

    public interface Callback {
        void onSuccess(LocationResult result);
        void onError(String message);
    }

    private static final long TIMEOUT_MS = 20000L;

    private LocationHelper() {}

    public static void getSingleLocation(
            Context context,
            Callback callback) {

        if (context == null || callback == null) return;

        Context app = context.getApplicationContext();

        boolean fine =
                ContextCompat.checkSelfPermission(
                        app,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        boolean coarse =
                ContextCompat.checkSelfPermission(
                        app,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        if (!fine && !coarse) {
            callback.onError("Permission lokasi belum diberikan.");
            return;
        }

        LocationManager manager =
                (LocationManager) app.getSystemService(
                        Context.LOCATION_SERVICE
                );

        if (manager == null) {
            callback.onError("LocationManager tidak tersedia.");
            return;
        }

        final Handler handler =
                new Handler(Looper.getMainLooper());

        final boolean[] finished = {false};

        final LocationListener listener =
                new LocationListener() {

                    private void success(Location location) {
                        if (finished[0] || location == null) return;

                        finished[0] = true;

                        try {
                            manager.removeUpdates(this);
                        } catch (SecurityException ignored) {
                        }

                        handler.removeCallbacksAndMessages(null);

                        callback.onSuccess(
                                new LocationResult(
                                        location.getLatitude(),
                                        location.getLongitude(),
                                        location.hasAccuracy()
                                                ? location.getAccuracy()
                                                : Float.NaN,
                                        location.getTime()
                                )
                        );
                    }

                    @Override
                    public void onLocationChanged(Location location) {
                        success(location);
                    }

                    @Override
                    public void onProviderDisabled(String provider) {
                        // Biarkan provider lain mencoba.
                    }

                    @Override
                    public void onProviderEnabled(String provider) {
                    }

                    @Override
                    public void onStatusChanged(
                            String provider,
                            int status,
                            Bundle extras) {
                    }
                };

        try {
            boolean requested = false;

            if (fine) {
                requested = requestProvider(
                        manager,
                        LocationManager.GPS_PROVIDER,
                        listener
                );
            }

            if (!requested) {
                requested = requestProvider(
                        manager,
                        LocationManager.NETWORK_PROVIDER,
                        listener
                );
            }

            if (!requested) {
                callback.onError(
                        "Tidak ada provider lokasi yang tersedia."
                );
                return;
            }

            Location last = null;

            if (fine && isEnabled(manager, LocationManager.GPS_PROVIDER)) {
                last = manager.getLastKnownLocation(
                        LocationManager.GPS_PROVIDER
                );
            }

            if (last == null &&
                    isEnabled(manager, LocationManager.NETWORK_PROVIDER)) {
                last = manager.getLastKnownLocation(
                        LocationManager.NETWORK_PROVIDER
                );
            }

            if (last != null) {
                listener.onLocationChanged(last);
            }

            handler.postDelayed(() -> {
                if (finished[0]) return;

                finished[0] = true;

                try {
                    manager.removeUpdates(listener);
                } catch (SecurityException ignored) {
                }

                callback.onError(
                        "Pengambilan lokasi timeout."
                );
            }, TIMEOUT_MS);

        } catch (SecurityException e) {
            callback.onError(
                    "Permission lokasi tidak tersedia: " + e.getMessage()
            );
        } catch (Exception e) {
            callback.onError(
                    "Gagal mengambil lokasi: " + e.getMessage()
            );
        }
    }

    private static boolean requestProvider(
            LocationManager manager,
            String provider,
            LocationListener listener) {

        if (!isEnabled(manager, provider)) return false;

        try {
            manager.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
            );
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static boolean isEnabled(
            LocationManager manager,
            String provider) {
        try {
            return manager.isProviderEnabled(provider);
        } catch (Exception ignored) {
            return false;
        }
    }
}
