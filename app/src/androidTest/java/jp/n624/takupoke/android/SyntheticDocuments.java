package jp.n624.takupoke.android;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;

/** Test APK only. Only the generated fixture is exposed, and only read access is supported. */
public final class SyntheticDocuments extends DocumentsProvider {
    public static final String AUTHORITY = "jp.n624.takupoke.android.test.documents";
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(new String[] { DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS });
        cursor.addRow(new Object[] { "synthetic", "changes", "Synthetic test files", 0 });
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        if (!"changes".equals(id)) throw new FileNotFoundException();
        String[] columns = projection != null ? projection : new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS };
        File file = new File(getContext().getCacheDir(), "synthetic.xlsx");
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row[i] = "changes"; break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row[i] = "synthetic.xlsx"; break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE: row[i] = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; break;
                case DocumentsContract.Document.COLUMN_SIZE: row[i] = file.length(); break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED: row[i] = file.lastModified(); break;
                case DocumentsContract.Document.COLUMN_FLAGS: row[i] = 0; break;
                default: row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) throws FileNotFoundException { return queryDocument("changes", projection); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!"changes".equals(id) || !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(new File(getContext().getCacheDir(), "synthetic.xlsx"), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
