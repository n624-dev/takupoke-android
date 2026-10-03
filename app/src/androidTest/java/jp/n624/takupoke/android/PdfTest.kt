package jp.n624.takupoke.android

import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import jp.n624.takupoke.core.ParseFailure
import jp.n624.takupoke.core.Grid
import jp.n624.takupoke.core.Box
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
    @Test fun actualTextOperatorsKeepMixedFontSizeOnOneSourceLine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("synthetic-", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(200f, 200f)); document.addPage(page)
                val fontFile = File("/system/fonts/Roboto-Regular.ttf").takeIf { it.isFile } ?: File("/system/fonts/NotoSans-Regular.ttf")
                val font = PDType0Font.load(document, fontFile)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText(); stream.setFont(font, 12f); stream.newLineAtOffset(20f, 150f); stream.showText("Synthetic")
                    stream.setFont(font, 8f); stream.showText("Small")
                    stream.setFont(font, 12f); stream.newLineAtOffset(0f, -25f); stream.showText("Teacher")
                    stream.newLineAtOffset(0f, -25f); stream.showText("Room"); stream.endText()
                }
                document.save(file)
            }
            val page = PdfReader.readPages(file).single()
            assertTrue(page.glyphs.all { it.sourceLine != null })
            assertEquals(listOf("SyntheticSmall", "Teacher", "Room"), Grid(page).text(Box(0.0, 0.0, 200.0, 200.0)))
            assertEquals(3, page.glyphs.map { it.sourceLine }.distinct().size)
        } finally { file.delete() }
    }
    @Test fun actualOverprintedFragmentsAreRejectedWithoutGuessingText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("synthetic-", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(200f, 200f)); document.addPage(page)
                val fontFile = File("/system/fonts/Roboto-Regular.ttf").takeIf { it.isFile } ?: File("/system/fonts/NotoSans-Regular.ttf")
                val font = PDType0Font.load(document, fontFile)
                PDPageContentStream(document, page).use { stream ->
                    repeat(2) { stream.beginText(); stream.setFont(font, 12f); stream.newLineAtOffset(20f, 150f); stream.showText("Synthetic"); stream.endText() }
                }
                document.save(file)
            }
            try { Grid(PdfReader.readPages(file).single()).text(Box(0.0, 0.0, 200.0, 200.0)); fail("Overprinted fragments accepted") }
            catch (error: ParseFailure) { assertEquals("P20", error.code) }
        } finally { file.delete() }
    }
    @Test fun transparentAndClippingTextCannotBecomeCompleteRecoveryInventory() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        for(mode in listOf("fill0","stroke0","fillHalf","clip")) {
            val file=File.createTempFile("synthetic-alpha-",".pdf",context.cacheDir)
            try {
                PDDocument().use { doc ->
                    val page=PDPage(PDRectangle(200f,200f));doc.addPage(page)
                    val fontFile=File("/system/fonts/Roboto-Regular.ttf").takeIf { it.isFile }?:File("/system/fonts/NotoSans-Regular.ttf")
                    val font=PDType0Font.load(doc,fontFile)
                    PDPageContentStream(doc,page).use { stream ->
                        stream.beginText();stream.setFont(font,12f);stream.newLineAtOffset(20f,170f);stream.showText("Visible");stream.endText()
                        if(mode!="clip")stream.setGraphicsStateParameters(com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply {
                            if(mode=="stroke0")strokingAlphaConstant=0f else nonStrokingAlphaConstant=if(mode=="fill0")0f else .5f
                        })
                        stream.beginText();stream.setFont(font,12f);stream.newLineAtOffset(20f,120f)
                        if(mode=="clip")stream.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER_CLIP)
                        if(mode=="stroke0")stream.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.STROKE)
                        stream.showText("Hidden");stream.endText()
                    };doc.save(file)
                }
                val capture=jp.n624.takupoke.core.RecoveryReadCapture()
                try { PdfReader.readPages(file,capture);fail("Unsupported alpha/clip marked complete: $mode") }catch(_:ParseFailure) {}
                val snapshot=capture;assertFalse(snapshot.complete)
                assertEquals(jp.n624.takupoke.core.RecoveryInputState.PARTIAL,snapshot.pages.single().state)
                assertEquals("Visible",requireNotNull(snapshot.pages.single().layout).glyphs.joinToString("") { it.text })
            } finally { file.delete() }
        }
    }
    @Test fun missingUnicodeMapFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext; val f = File.createTempFile("synthetic-", ".pdf", context.cacheDir)
        try {
            PDDocument().use { doc -> val page = PDPage(); doc.addPage(page); PDPageContentStream(doc, page).use { stream -> stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f); stream.newLineAtOffset(20f, 100f); stream.showText("Unsupported font"); stream.endText() }; doc.save(f) }
            try { PdfReader.readPages(f); fail("Missing ToUnicode accepted") } catch (_: ParseFailure) { }
        } finally { f.delete() }
    }
}
