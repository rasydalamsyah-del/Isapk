package com.example.app;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class DebugActivity extends AppCompatActivity {

    private TextView statusText;
    private TextView queueText;
    private TextView logText;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        super.onCreate(savedInstanceState);

        setTitle("Myapk Diagnostic");

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL
        );

        root.setPadding(
                dp(16),
                dp(16),
                dp(16),
                dp(16)
        );

        statusText =
                createTextView(
                        16
                );

        queueText =
                createTextView(
                        16
                );

        logText =
                createTextView(
                        13
                );

        logText.setMovementMethod(
                ScrollingMovementMethod.getInstance()
        );

        Button refreshButton =
                new Button(this);

        refreshButton.setText(
                "Refresh"
        );

        refreshButton.setOnClickListener(
                v -> refresh()
        );

        Button testButton =
                new Button(this);

        testButton.setText(
                "Catat Test Diagnostic"
        );

        testButton.setOnClickListener(
                v -> {

                    DebugLogger.log(
                            getApplicationContext(),
                            "MANUAL_TEST",
                            "Diagnostic test pressed"
                    );

                    refresh();
                }
        );

        Button clearButton =
                new Button(this);

        clearButton.setText(
                "Hapus Log Diagnostic"
        );

        clearButton.setOnClickListener(
                v -> {

                    DebugLogger.clear(
                            getApplicationContext()
                    );

                    refresh();
                }
        );

        root.addView(
                statusText,
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                )
        );

        root.addView(
                queueText,
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                )
        );

        root.addView(
                refreshButton,
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                )
        );

        root.addView(
                testButton,
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                )
        );

        root.addView(
                clearButton,
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                )
        );

        TextView logTitle =
                createTextView(
                        17
                );

        logTitle.setText(
                "EVENT LOG"
        );

        root.addView(
                logTitle,
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                )
        );

        ScrollView scrollView =
                new ScrollView(this);

        scrollView.addView(
                logText,
                new ScrollView.LayoutParams(
                        -1,
                        -1
                )
        );

        LinearLayout.LayoutParams
                scrollParams =
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1f
                );

        root.addView(
                scrollView,
                scrollParams
        );

        setContentView(root);

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {

        Context context =
                getApplicationContext();

        NotificationDatabase db =
                new NotificationDatabase(
                        context
                );

        int pending = 0;
        int sent = 0;

        try {

            pending =
                    db.getPendingCount();

            sent =
                    db.getSentCount();

        } finally {

            db.close();
        }

        boolean listenerEnabled =
                isNotificationListenerEnabled();

        String status =
                DebugLogger.getStatus(
                        context
                );

        String error =
                DebugLogger.getError(
                        context
                );

        String lastEvent =
                DebugLogger.getLastEvent(
                        context
                );

        if (status == null ||
                status.trim().isEmpty()) {

            status = "BELUM ADA";
        }

        if (error == null ||
                error.trim().isEmpty()) {

            error = "Tidak ada";
        }

        statusText.setText(
                "MYAPK DIAGNOSTIC\n\n"
                        + "Notification Access : "
                        + (
                                listenerEnabled
                                        ? "AKTIF"
                                        : "TIDAK AKTIF"
                        )
                        + "\n"
                        + "Last Status         : "
                        + status
                        + "\n"
                        + "Last Error          : "
                        + error
                        + "\n"
                        + "Last Event          : "
                        + (lastEvent == null || lastEvent.trim().isEmpty()
                                ? "BELUM ADA"
                                : lastEvent)
        );

        queueText.setText(
                String.format(
                        Locale.getDefault(),
                        "\nDatabase Queue\n"
                                + "Pending : %d\n"
                                + "Sent    : %d\n",
                        pending,
                        sent
                )
        );

        String logs =
                DebugLogger.getLogs(
                        context
                );

        if (logs == null ||
                logs.trim().isEmpty()) {

            logs =
                    "Belum ada diagnostic event.";
        }

        logText.setText(
                logs
        );

        logText.post(
                () -> logText.scrollTo(
                        0,
                        logText.getBottom()
                )
        );
    }

    private boolean isNotificationListenerEnabled() {

        String enabledListeners =
                Settings.Secure.getString(
                        getContentResolver(),
                        "enabled_notification_listeners"
                );

        if (enabledListeners == null) {
            return false;
        }

        ComponentName component =
                new ComponentName(
                        this,
                        NotificationService.class
                );

        return enabledListeners.contains(
                component.flattenToString()
        );
    }

    private TextView createTextView(
            int size
    ) {

        TextView textView =
                new TextView(this);

        textView.setTextSize(
                size
        );

        textView.setPadding(
                0,
                dp(6),
                0,
                dp(6)
        );

        return textView;
    }

    private int dp(int value) {

        float density =
                getResources()
                        .getDisplayMetrics()
                        .density;

        return Math.round(
                value * density
        );
    }
}