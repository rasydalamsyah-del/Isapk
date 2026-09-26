package com.example.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Local SQLite queue for notification events.
 *
 * The database keeps pending/sent records and also provides
 * a short duplicate window for notification updates.
 */
public class NotificationDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "notification_queue.db";
    private static final int DB_VERSION = 3;
    private static final String TABLE = "notifications";

    /*
     * Short window used only for notification-update duplication.
     *
     * Example:
     * 14:00 Bund Ayang -> Keren
     * 14:00:15 Telegram updates the notification and sends
     *         Bund Ayang -> Keren again
     *
     * The second one is treated as an update.
     *
     * A genuinely repeated message after a longer period
     * is still allowed.
     */
    private static final long DUPLICATE_WINDOW_MS = 2 * 60 * 1000L;

    public NotificationDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE " + TABLE + " (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "timestamp INTEGER NOT NULL," +
                        "package_name TEXT NOT NULL," +
                        "title TEXT NOT NULL," +
                        "message TEXT NOT NULL," +
                        "event_key TEXT," +
                        "status INTEGER NOT NULL DEFAULT 0" +
                        ")"
        );

        db.execSQL(
                "CREATE INDEX idx_notifications_status " +
                        "ON " + TABLE + "(status, id)"
        );

        db.execSQL(
                "CREATE UNIQUE INDEX idx_notifications_event_key " +
                        "ON " + TABLE + "(event_key) " +
                        "WHERE event_key IS NOT NULL"
        );

        db.execSQL(
                "CREATE INDEX idx_notifications_content_time " +
                        "ON " + TABLE +
                        "(package_name, title, message, timestamp)"
        );
    }

    @Override
    public void onUpgrade(
            SQLiteDatabase db,
            int oldVersion,
            int newVersion
    ) {
        if (oldVersion < 2) {
            db.execSQL(
                    "ALTER TABLE " + TABLE +
                            " ADD COLUMN event_key TEXT"
            );

            db.execSQL(
                    "CREATE UNIQUE INDEX idx_notifications_event_key " +
                            "ON " + TABLE + "(event_key) " +
                            "WHERE event_key IS NOT NULL"
            );
        }

        if (oldVersion < 3) {
            db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                            "idx_notifications_content_time " +
                            "ON " + TABLE +
                            "(package_name, title, message, timestamp)"
            );
        }
    }

    /**
     * Returns true when the same package/title/message was already
     * stored very recently.
     *
     * This is specifically for notification update duplication.
     */
    public synchronized boolean isRecentDuplicate(
            long timestamp,
            String packageName,
            String title,
            String message
    ) {
        if (packageName == null) packageName = "";
        if (title == null) title = "";
        if (message == null) message = "";

        long lowerBound =
                timestamp - DUPLICATE_WINDOW_MS;

        Cursor cursor = null;

        try {
            cursor = getReadableDatabase().query(
                    TABLE,
                    new String[]{"id"},
                    "package_name = ? " +
                            "AND title = ? " +
                            "AND message = ? " +
                            "AND timestamp >= ? " +
                            "AND timestamp <= ?",
                    new String[]{
                            packageName,
                            title,
                            message,
                            String.valueOf(lowerBound),
                            String.valueOf(timestamp)
                    },
                    null,
                    null,
                    "timestamp DESC",
                    "1"
            );

            return cursor.moveToFirst();

        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Inserts a notification unless the exact Android eventKey
     * already exists.
     */
    public synchronized long insert(
            long timestamp,
            String packageName,
            String title,
            String message,
            String eventKey
    ) {
        ContentValues values = new ContentValues();

        values.put("timestamp", timestamp);
        values.put(
                "package_name",
                packageName == null ? "Unknown_App" : packageName
        );
        values.put(
                "title",
                title == null ? "Tanpa Judul" : title
        );
        values.put(
                "message",
                message == null ? "Tanpa Isi" : message
        );
        values.put("event_key", eventKey);
        values.put("status", 0);

        return getWritableDatabase().insertWithOnConflict(
                TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
        );
    }

    public synchronized List<NotificationRecord> getPending(int limit) {
        List<NotificationRecord> result = new ArrayList<>();

        try (Cursor cursor = getReadableDatabase().query(
                TABLE,
                new String[]{
                        "id",
                        "timestamp",
                        "package_name",
                        "title",
                        "message"
                },
                "status = 0",
                null,
                null,
                null,
                "id ASC",
                String.valueOf(limit)
        )) {
            while (cursor.moveToNext()) {
                result.add(
                        new NotificationRecord(
                                cursor.getLong(0),
                                cursor.getLong(1),
                                cursor.getString(2),
                                cursor.getString(3),
                                cursor.getString(4)
                        )
                );
            }
        }

        return result;
    }

    public synchronized void markSent(long id) {
        ContentValues values = new ContentValues();
        values.put("status", 1);

        getWritableDatabase().update(
                TABLE,
                values,
                "id = ?",
                new String[]{String.valueOf(id)}
        );
    }

    public static class NotificationRecord {

        public final long id;
        public final long timestamp;
        public final String packageName;
        public final String title;
        public final String message;

        public NotificationRecord(
                long id,
                long timestamp,
                String packageName,
                String title,
                String message
        ) {
            this.id = id;
            this.timestamp = timestamp;
            this.packageName = packageName;
            this.title = title;
            this.message = message;
        }
    }
}
