package com.example.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Small private SQLite queue. It is app-private storage and needs no new storage permission. */
public class NotificationDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "notification_queue.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "notifications";

    public NotificationDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "timestamp INTEGER NOT NULL," +
                "package_name TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "message TEXT NOT NULL," +
                "status INTEGER NOT NULL DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX idx_notifications_status ON " + TABLE + "(status, id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // No schema migrations yet; keep the existing queue intact for future versions.
    }

    public synchronized long insert(long timestamp, String packageName, String title, String message) {
        ContentValues values = new ContentValues();
        values.put("timestamp", timestamp);
        values.put("package_name", packageName);
        values.put("title", title == null ? "Tanpa Judul" : title);
        values.put("message", message == null ? "Tanpa Isi" : message);
        values.put("status", 0);
        return getWritableDatabase().insert(TABLE, null, values);
    }

    public synchronized List<NotificationRecord> getPending(int limit) {
        List<NotificationRecord> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE,
                new String[]{"id", "timestamp", "package_name", "title", "message"},
                "status = 0",
                null,
                null,
                null,
                "id ASC",
                String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                result.add(new NotificationRecord(
                        cursor.getLong(0),
                        cursor.getLong(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getString(4)));
            }
        }
        return result;
    }

    public synchronized void markSent(long id) {
        ContentValues values = new ContentValues();
        values.put("status", 1);
        getWritableDatabase().update(TABLE, values, "id = ?", new String[]{String.valueOf(id)});
    }

    public static class NotificationRecord {
        public final long id;
        public final long timestamp;
        public final String packageName;
        public final String title;
        public final String message;

        public NotificationRecord(long id, long timestamp, String packageName,
                                  String title, String message) {
            this.id = id;
            this.timestamp = timestamp;
            this.packageName = packageName;
            this.title = title;
            this.message = message;
        }
    }
}
