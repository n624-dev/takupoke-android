package jp.n624.takupoke.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.provider.DocumentsContract

/** Control endpoint belongs only to the test APK; it can access only its generated fixture. */
class SyntheticControl : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "replaceSynthetic")
        val bytes = requireNotNull(extras?.getByteArray("bytes")); require(bytes.size <= 1024 * 1024)
        val token = Binder.clearCallingIdentity()
        try {
            java.io.File(context!!.cacheDir, "synthetic.xlsx").writeBytes(bytes)
            val uri = DocumentsContract.buildDocumentUri(SyntheticDocuments.AUTHORITY, "changes")
            if (extras!!.getBoolean("grant")) context!!.grantUriPermission("jp.n624.takupoke.android", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            context!!.contentResolver.notifyChange(uri, null)
            return Bundle()
        } finally { Binder.restoreCallingIdentity(token) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    companion object { const val AUTHORITY = "jp.n624.takupoke.android.test.control" }
}
