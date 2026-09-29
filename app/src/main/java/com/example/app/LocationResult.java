package com.example.app;

import java.util.Locale;

/**
 * Hasil satu kali pengambilan lokasi.
 *
 * Tidak menyimpan data lokasi secara persisten.
 */
public final class LocationResult {

    private final double latitude;
    private final double longitude;
    private final float accuracy;
    private final long timestamp;

    public LocationResult(
            double latitude,
            double longitude,
            float accuracy,
            long timestamp) {

        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
        this.timestamp = timestamp;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public float getAccuracy() {
        return accuracy;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String toPayloadString() {
        return String.format(
                Locale.US,
                "%.7f,%.7f (±%.1fm)",
                latitude,
                longitude,
                accuracy
        );
    }
}
