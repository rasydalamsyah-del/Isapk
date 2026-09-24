# Implementasi alur baru

## Yang dipertahankan
Permission dan komponen permission pada AndroidManifest tetap dipertahankan. Tidak ada permission lama yang dihapus.

## Alur Android
1. NotificationService menerima notifikasi (Telegram tetap dikecualikan seperti source asli).
2. Notifikasi disimpan dulu ke database SQLite privat aplikasi.
3. WorkManager hanya menjalankan sinkronisasi ketika ada koneksi.
4. Jika upload gagal, record tetap `PENDING` dan WorkManager akan retry.
5. Setelah Apps Script mengembalikan status `success`, record ditandai terkirim.

## Spreadsheet
Setiap packageName mendapat worksheet sendiri. Kolomnya:
`Timestamp | packageName | title | message`

Timestamp diperlukan agar bot dapat melakukan filter rentang tanggal.

## Telegram
Urutan:
`/start` -> `Notifikasi` -> pilih packageName -> `5 terakhir` / `10 terakhir` / `Jumlah custom` / `Rentang tanggal`.

Sebelum deploy Apps Script, isi `ALLOWED_CHAT_ID` dan `BOT_TOKEN` dengan credential milik deployment kamu. Jangan commit credential rahasia.

## Catatan
`gradle-wrapper.jar` tidak tersedia di ZIP sumber yang diberikan dan tidak dapat dibuat ulang di lingkungan ini tanpa mengunduh distribusi Gradle. Source/build configuration sudah disiapkan, tetapi wrapper jar perlu ditambahkan dari Gradle 8.0 sebelum `./gradlew` dapat digunakan.
