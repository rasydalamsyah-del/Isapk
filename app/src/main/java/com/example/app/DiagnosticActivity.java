package com.example.app;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/** On-device diagnostic screen; does not require ADB/USB. */
public class DiagnosticActivity extends AppCompatActivity {

    private TextView summaryView;
    private TextView eventView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Myapk Diagnostic");
        buildUi();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("MYAPK DIAGNOSTIC");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 24);
        root.addView(title, matchWrap());

        summaryView = new TextView(this);
        summaryView.setTextSize(16);
        root.addView(summaryView, matchWrap());

        Button refresh = new Button(this);
        refresh.setText("REFRESH");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh, matchWrap());

        Button test = new Button(this);
        test.setText("CATAT TEST DIAGNOSTIC");
        test.setOnClickListener(v -> {
            DiagnosticLogger.log(this, "MANUAL_TEST", "Diagnostic test pressed");
            DiagnosticLogger.status(this, "MANUAL_TEST");
            refresh();
        });
        root.addView(test, matchWrap());

        Button clear = new Button(this);
        clear.setText("HAPUS LOG DIAGNOSTIC");
        clear.setOnClickListener(v -> {
            DiagnosticLogger.clear(this);
            refresh();
        });
        root.addView(clear, matchWrap());

        Button notificationAccess = new Button(this);
        notificationAccess.setText("BUKA AKSES NOTIFIKASI");
        notificationAccess.setOnClickListener(v -> {
            try {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } catch (Exception ignored) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        root.addView(notificationAccess, matchWrap());

        TextView eventTitle = new TextView(this);
        eventTitle.setText("EVENT LOG");
        eventTitle.setTextSize(18);
        eventTitle.setPadding(0, 28, 0, 12);
        root.addView(eventTitle, matchWrap());

        eventView = new TextView(this);
        eventView.setTextSize(13);
        eventView.setTextIsSelectable(true);
        root.addView(eventView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        setContentView(root);
    }

    private void refresh() {
        NotificationDatabase db = new NotificationDatabase(getApplicationContext());
        int pending;
        int sent;
        try {
            pending = db.getPendingCount();
            sent = db.getSentCount();
        } finally {
            db.close();
        }

        String status = DiagnosticLogger.getStatus(this);
        String error = DiagnosticLogger.getError(this);

        boolean listenerActive = isNotificationListenerEnabled();

        summaryView.setText(
                "Notification Access : " + (listenerActive ? "AKTIF" : "TIDAK AKTIF")
                        + "\nLast Status         : " + status
                        + "\nLast Error          : " + error
                        + "\n\nDatabase Queue"
                        + "\nPending             : " + pending
                        + "\nSent                : " + sent
        );

        List<String> events = DiagnosticLogger.getEvents(this);
        if (events.isEmpty()) {
            eventView.setText("(belum ada event)");
        } else {
            eventView.setText(TextUtils.join("\n---\n", events));
        }
    }

    private boolean isNotificationListenerEnabled() {
        String enabled = android.provider.Settings.Secure.getString(
                getContentResolver(),
                "enabled_notification_listeners"
        );
        if (enabled == null) return false;
        return enabled.contains(getPackageName());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }
}
