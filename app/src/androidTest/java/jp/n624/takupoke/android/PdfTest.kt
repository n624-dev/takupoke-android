package jp.n624.takupoke.android

import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import jp.n624.takupoke.core.ParseFailure
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class PdfTest {
    @Test fun actualPdfEngineReadsUnicodeTextAndVectorGrid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File.createTempFile("synthetic-", ".pdf", context.cacheDir)
        try {
            PDDocument().use { doc -> val page = PDPage(PDRectangle(200f, 200f)); doc.addPage(page)
                val fontFile = File("/system/fonts/Roboto-Regular.ttf").takeIf { it.isFile } ?: File("/system/fonts/NotoSans-Regular.ttf")
                val font = PDType0Font.load(doc, fontFile)
                PDPageContentStream(doc, page).use { stream -> stream.addRect(10f, 10f, 180f, 180f); stream.stroke(); stream.beginText(); stream.setFont(font, 12f); stream.newLineAtOffset(30f, 150f); stream.showText("Synthetic ABC"); stream.endText() }
                doc.save(f)
            }
            val page = PdfReader.readPages(f).single()
            assertEquals("Synthetic ABC", page.glyphs.joinToString("") { it.text }); assertEquals(4, page.lines.size)
            assertTrue(page.glyphs.all { it.width > 0 && it.height > 0 && it.y in 0.0..200.0 })
        } finally { f.delete() }
    }
    @Test fun missingUnicodeMapFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext; val f = File.createTempFile("synthetic-", ".pdf", context.cacheDir)
        try {
            PDDocument().use { doc -> val page = PDPage(); doc.addPage(page); PDPageContentStream(doc, page).use { stream -> stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f); stream.newLineAtOffset(20f, 100f); stream.showText("Unsupported font"); stream.endText() }; doc.save(f) }
            try { PdfReader.readPages(f); fail("Missing ToUnicode accepted") } catch (_: ParseFailure) { }
        } finally { f.delete() }
    }
}
