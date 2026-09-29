package com.example.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

/**
 * Layar konfirmasi lokasi.
 *
 * Lokasi hanya diambil setelah pengguna menekan "Izinkan".
 */
public class LocationRequestActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_CODE = 2201;

    private String requestId = "";
    private boolean waitingPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestId = getIntent().getStringExtra("requestId");
        if (requestId == null) requestId = "";

        buildUi();
    }

    private void buildUi() {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("Permintaan Lokasi");
        title.setTextSize(24f);

        TextView message = new TextView(this);
        message.setText(
                "Bot meminta satu kali lokasi perangkat.\n\n" +
                "Lokasi hanya akan diambil setelah kamu menekan " +
                "\"Izinkan\" dan Android memberikan permission lokasi."
        );
        message.setTextSize(17f);
        message.setPadding(0, 24, 0, 32);

        Button deny = new Button(this);
        deny.setText("Tolak");

        Button allow = new Button(this);
        allow.setText("Izinkan");

        root.addView(title);
        root.addView(message);
        root.addView(deny);
        root.addView(allow);

        setContentView(root);

        deny.setOnClickListener(v -> {
            LocationRequestDatabase.setStatus(this, "CANCELLED");
            new Thread(() ->
                    ApiHelper.updateLocationRequestStatus(
                            requestId,
                            "CANCELLED"
                    )
            ).start();
            finish();
        });

        allow.setOnClickListener(v -> beginLocation());

        if (requestId.isEmpty()) {
            allow.setEnabled(false);
        }
    }

    private void beginLocation() {

        boolean fine =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        boolean coarse =
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        if (!fine && !coarse) {
            waitingPermission = true;

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_CODE
            );

            return;
        }

        startLocation();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode != LOCATION_PERMISSION_CODE) return;

        boolean granted = false;

        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }

        if (granted && waitingPermission) {
            waitingPermission = false;
            startLocation();
        } else {
            LocationRequestDatabase.setStatus(
                    this,
                    "CANCELLED"
            );

            new Thread(() ->
                    ApiHelper.updateLocationRequestStatus(
                            requestId,
                            "CANCELLED"
                    )
            ).start();

            finish();
        }
    }

    private void startLocation() {

        LocationRequestDatabase.setStatus(
                this,
                "RECEIVED"
        );

        new Thread(() ->
                ApiHelper.updateLocationRequestStatus(
                        requestId,
                        "RECEIVED"
                )
        ).start();

        LocationDebugLogger.log(
                this,
                "LOCATION_READING",
                "requestId=" + requestId
        );

        LocationHelper.getSingleLocation(
                this,
                new LocationHelper.Callback() {

                    @Override
                    public void onSuccess(
                            LocationResult result) {

                        LocationDebugLogger.log(
                                LocationRequestActivity.this,
                                "LOCATION_ACQUIRED",
                                result.toPayloadString()
                        );

                        OneTimeWorkRequest work =
                                new OneTimeWorkRequest.Builder(
                                        LocationSyncWorker.class
                                )
                                .setInputData(
                                        LocationSyncWorker.buildInput(
                                                requestId,
                                                result
                                        )
                                )
                                .build();

                        WorkManager.getInstance(
                                LocationRequestActivity.this
                        ).enqueue(work);

                        LocationRequestDatabase.setStatus(
                                LocationRequestActivity.this,
                                "QUEUED"
                        );

                        runOnUiThread(
                                () -> finish()
                        );
                    }

                    @Override
                    public void onError(String message) {

                        LocationDebugLogger.log(
                                LocationRequestActivity.this,
                                "LOCATION_FAILED",
                                message
                        );

                        LocationRequestDatabase.setStatus(
                                LocationRequestActivity.this,
                                "FAILED"
                        );

                        new Thread(() ->
                                ApiHelper.updateLocationRequestStatus(
                                        requestId,
                                        "FAILED"
                                )
                        ).start();

                        runOnUiThread(
                                () -> finish()
                        );
                    }
                }
        );
    }
}
