package jp.n624.takupoke.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test APK only. Java keeps the standalone provider independent of the target APK's Kotlin runtime. */
public final class SyntheticControl extends ContentProvider {
    public static final String AUTHORITY = "jp.n624.takupoke.android.test.control";
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"replaceSynthetic".equals(method) || extras == null) throw new IllegalArgumentException();
        byte[] bytes = extras.getByteArray("bytes");
        if (bytes == null || bytes.length > 1024 * 1024) throw new IllegalArgumentException();
        long token = Binder.clearCallingIdentity();
        try {
            try (FileOutputStream out = new FileOutputStream(new File(getContext().getCacheDir(), "synthetic.xlsx"))) { out.write(bytes); }
            Uri uri = DocumentsContract.buildDocumentUri(SyntheticDocuments.AUTHORITY, "changes");
            if (extras.getBoolean("grant")) getContext().grantUriPermission("jp.n624.takupoke.android", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            getContext().getContentResolver().notifyChange(uri, null);
            return new Bundle();
        } catch (IOException e) { throw new IllegalStateException(e); }
        finally { Binder.restoreCallingIdentity(token); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
