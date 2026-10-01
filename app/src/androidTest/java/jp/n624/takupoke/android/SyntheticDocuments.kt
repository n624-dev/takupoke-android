package jp.n624.takupoke.android

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Test APK only. No real school documents or accounts. */
class SyntheticDocuments : DocumentsProvider() {
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS)).apply { addRow(arrayOf("synthetic", "changes", "Synthetic test files", 0)) }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        require(documentId == "changes")
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
        val f = java.io.File(context!!.cacheDir, "synthetic.xlsx").also { if (!it.exists()) it.writeBytes(syntheticXlsx("理科")) }
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) { DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "changes"; DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "synthetic.xlsx"; DocumentsContract.Document.COLUMN_MIME_TYPE -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; DocumentsContract.Document.COLUMN_SIZE -> f.length(); DocumentsContract.Document.COLUMN_LAST_MODIFIED -> f.lastModified(); DocumentsContract.Document.COLUMN_FLAGS -> 0; else -> null } }.toTypedArray()) }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = queryDocument("changes", projection)
    override fun openDocument(documentId: String, mode: String, signal: android.os.CancellationSignal?): ParcelFileDescriptor { require(documentId == "changes" && mode == "r"); return ParcelFileDescriptor.open(java.io.File(context!!.cacheDir, "synthetic.xlsx"), ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "replaceSynthetic") { java.io.File(context!!.cacheDir, "synthetic.xlsx").writeBytes(requireNotNull(extras?.getByteArray("bytes"))); context!!.contentResolver.notifyChange(DocumentsContract.buildDocumentUri(AUTHORITY, "changes"), null); return Bundle() }
        return super.call(method, arg, extras)
    }
    companion object { const val AUTHORITY = "jp.n624.takupoke.android.test.documents" }
}
internal fun syntheticXlsx(after: String): ByteArray {
    val headers = listOf("学 年", "学科・クラス", "月日", "時限", "変更前", "変更後")
    val values = listOf("1", "CN", "10/1", "1", "数学", after)
    val rows = listOf(headers, values).mapIndexed { r, items -> "<row r=\"${r + 1}\">" + items.mapIndexed { c, text -> "<c r=\"${'A' + c}${r + 1}\" t=\"inlineStr\"><is><t>$text</t></is></c>" }.joinToString("") + "</row>" }.joinToString("")
    val files = mapOf("xl/workbook.xml" to "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"時間割変更\" r:id=\"r1\"/></sheets></workbook>", "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/></Relationships>", "xl/worksheets/sheet1.xml" to "<worksheet><sheetData>$rows</sheetData></worksheet>")
    val out = ByteArrayOutputStream(); ZipOutputStream(out).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }; return out.toByteArray()
}
