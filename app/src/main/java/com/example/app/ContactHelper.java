package com.example.app;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper untuk manajemen kontak HP.
 *
 * Operasi:
 * - searchContacts()  — cari kontak berdasarkan nama/nomor
 * - addContact()      — tambah kontak baru
 * - deleteContact()   — hapus kontak berdasarkan nama
 * - exportContactsVcf() — ekspor semua kontak ke format VCF
 * - getContactCount() — total kontak
 */
public class ContactHelper {

    private static final String TAG = "ContactHelper";

    // =========================================================
    // INNER CLASS — HASIL KONTAK
    // =========================================================

    public static class Contact {
        public String id;
        public String name;
        public String phone;
        public String email;

        public String formatted() {
            StringBuilder sb = new StringBuilder();
            sb.append("👤 *").append(name).append("*\n");
            if (phone != null && !phone.isEmpty()) {
                sb.append("   📞 ").append(phone).append("\n");
            }
            if (email != null && !email.isEmpty()) {
                sb.append("   📧 ").append(email).append("\n");
            }
            return sb.toString().trim();
        }
    }

    // =========================================================
    // CARI KONTAK
    // =========================================================

    public static List<Contact> searchContacts(
            Context context, String query) {

        List<Contact> results = new ArrayList<>();

        if (query == null || query.trim().isEmpty()) return results;

        ContentResolver cr = context.getContentResolver();

        String selection =
                ContactsContract.Contacts.DISPLAY_NAME +
                " LIKE ? COLLATE NOCASE";

        String[] args = new String[]{"%" + query.trim() + "%"};

        String[] projection = {
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
        };

        try (Cursor cursor = cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection, selection, args,
                ContactsContract.Contacts.DISPLAY_NAME + " ASC"
        )) {
            if (cursor == null) return results;

            while (cursor.moveToNext() && results.size() < 10) {

                String contactId   = cursor.getString(0);
                String displayName = cursor.getString(1);
                int    hasPhone    = cursor.getInt(2);

                Contact c = new Contact();
                c.id   = contactId;
                c.name = displayName != null ? displayName : "";

                if (hasPhone > 0) {
                    c.phone = getFirstPhone(cr, contactId);
                }
                c.email = getFirstEmail(cr, contactId);

                results.add(c);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error searchContacts", e);
        }

        return results;
    }

    // =========================================================
    // TAMBAH KONTAK
    // =========================================================

    public static boolean addContact(
            Context context, String name, String phone, String email) {

        if (name == null || name.trim().isEmpty()) return false;

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        // Raw contact baru (tanpa akun khusus = lokal)
        ops.add(ContentProviderOperation
                .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build());

        // Nama
        ops.add(ContentProviderOperation
                .newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(
                        ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        name.trim())
                .build());

        // Nomor telepon
        if (phone != null && !phone.trim().isEmpty()) {
            ops.add(ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(
                            ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER,
                            phone.trim())
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build());
        }

        // Email
        if (email != null && !email.trim().isEmpty()) {
            ops.add(ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(
                            ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS,
                            email.trim())
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE,
                            ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                    .build());
        }

        try {
            context.getContentResolver().applyBatch(
                    ContactsContract.AUTHORITY, ops);
            Log.d(TAG, "Contact added: " + name);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error addContact", e);
            return false;
        }
    }

    // =========================================================
    // HAPUS KONTAK
    // =========================================================

    /**
     * Hapus semua kontak yang namanya cocok (partial match).
     * Mengembalikan jumlah kontak yang berhasil dihapus.
     */
    public static int deleteContact(Context context, String name) {

        if (name == null || name.trim().isEmpty()) return 0;

        ContentResolver cr = context.getContentResolver();
        int deleted = 0;

        String[] projection = {ContactsContract.Contacts._ID};
        String   selection  =
                ContactsContract.Contacts.DISPLAY_NAME +
                " LIKE ? COLLATE NOCASE";
        String[] args = {"%" + name.trim() + "%"};

        try (Cursor cursor = cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection, selection, args, null
        )) {
            if (cursor == null) return 0;

            while (cursor.moveToNext()) {

                String id = cursor.getString(0);

                Uri deleteUri = Uri.withAppendedPath(
                        ContactsContract.Contacts.CONTENT_URI, id);

                int rows = cr.delete(deleteUri, null, null);

                if (rows > 0) deleted++;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleteContact", e);
        }

        return deleted;
    }

    // =========================================================
    // EKSPOR KE VCF
    // =========================================================

    public static String exportContactsVcf(Context context) {

        ContentResolver cr = context.getContentResolver();

        String[] projection = {
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
        };

        StringBuilder vcf = new StringBuilder();
        int count = 0;

        try (Cursor cursor = cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection, null, null,
                ContactsContract.Contacts.DISPLAY_NAME + " ASC"
        )) {
            if (cursor == null) return "";

            while (cursor.moveToNext()) {

                String contactId = cursor.getString(0);
                String name      = cursor.getString(1);
                int    hasPhone  = cursor.getInt(2);

                if (name == null || name.trim().isEmpty()) continue;

                vcf.append("BEGIN:VCARD\n");
                vcf.append("VERSION:3.0\n");
                vcf.append("FN:").append(name).append("\n");

                if (hasPhone > 0) {
                    String phone = getFirstPhone(cr, contactId);
                    if (phone != null && !phone.isEmpty()) {
                        vcf.append("TEL;TYPE=MOBILE:")
                           .append(phone).append("\n");
                    }
                }

                String email = getFirstEmail(cr, contactId);
                if (email != null && !email.isEmpty()) {
                    vcf.append("EMAIL:").append(email).append("\n");
                }

                vcf.append("END:VCARD\n\n");
                count++;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error exportContactsVcf", e);
        }

        Log.d(TAG, "Exported " + count + " contacts");
        return vcf.toString();
    }

    // =========================================================
    // JUMLAH KONTAK
    // =========================================================

    public static int getContactCount(Context context) {

        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts._ID},
                null, null, null
        )) {
            return cursor != null ? cursor.getCount() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // =========================================================
    // HELPER PRIVAT
    // =========================================================

    private static String getFirstPhone(
            ContentResolver cr, String contactId) {

        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };

        String selection =
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?";

        try (Cursor cursor = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, selection,
                new String[]{contactId}, null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getFirstPhone", e);
        }

        return null;
    }

    private static String getFirstEmail(
            ContentResolver cr, String contactId) {

        String[] projection = {
                ContactsContract.CommonDataKinds.Email.ADDRESS
        };

        String selection =
                ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?";

        try (Cursor cursor = cr.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                projection, selection,
                new String[]{contactId}, null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getFirstEmail", e);
        }

        return null;
    }
}
