package jp.n624.takupoke.android

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun syntheticXlsx(after: String, date: String = "10/1"): ByteArray {
    val headers = listOf("学 年", "学科・クラス", "月日", "時限", "変更前", "変更後")
    val values = listOf("1", "CN", date, "1", "数学", after)
    val rows = listOf(headers, values).mapIndexed { r, items -> "<row r=\"${r + 1}\">" + items.mapIndexed { c, text -> "<c r=\"${'A' + c}${r + 1}\" t=\"inlineStr\"><is><t>$text</t></is></c>" }.joinToString("") + "</row>" }.joinToString("")
    val files = mapOf("xl/workbook.xml" to "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"時間割変更\" r:id=\"r1\"/></sheets></workbook>", "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"r1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>", "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$rows</sheetData></worksheet>")
    val out = ByteArrayOutputStream(); ZipOutputStream(out).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }; return out.toByteArray()
}
