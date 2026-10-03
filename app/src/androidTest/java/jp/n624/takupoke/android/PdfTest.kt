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
    @Test fun rendererInvisibleWhiteTextAndOpaqueCoverNeverHaveCompleteCapture() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        for(mode in listOf("whiteText","whiteFill","whiteImage","blackStroke","whiteStroke")) {
            val file=File.createTempFile("synthetic-hidden-",".pdf",context.cacheDir)
            try {
                PDDocument().use { doc ->
                    val page=PDPage(PDRectangle(200f,200f));doc.addPage(page)
                    val fontFile=File("/system/fonts/Roboto-Regular.ttf").takeIf { it.isFile }?:File("/system/fonts/NotoSans-Regular.ttf")
                    val font=PDType0Font.load(doc,fontFile)
                    PDPageContentStream(doc,page).use { stream ->
                        stream.beginText();stream.setFont(font,12f);stream.newLineAtOffset(20f,170f);stream.showText("Visible");stream.endText()
                        if(mode=="whiteText")stream.setNonStrokingColor(255,255,255)
                        stream.beginText();stream.setFont(font,12f);stream.newLineAtOffset(20f,120f);stream.showText("Hidden");stream.endText()
                        when(mode) {
                            "whiteFill" -> { stream.setNonStrokingColor(255,255,255);stream.addRect(15f,110f,150f,25f);stream.fill() }
                            "whiteImage" -> {
                                val image=android.graphics.Bitmap.createBitmap(150,25,android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                                try { stream.drawImage(com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc,image),15f,110f,150f,25f) }finally { image.recycle() }
                            }
                            "blackStroke","whiteStroke" -> { stream.setStrokingColor(if(mode=="blackStroke")0 else 255,if(mode=="blackStroke")0 else 255,if(mode=="blackStroke")0 else 255);stream.setLineWidth(25f);stream.moveTo(15f,122f);stream.lineTo(165f,122f);stream.stroke() }
                        }
                    };doc.save(file)
                }
                val bitmap=android.graphics.Bitmap.createBitmap(200,200,android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> android.graphics.pdf.PdfRenderer(fd).use { renderer -> renderer.openPage(0).use { it.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } } }
                    var visible=0;for(y in 20..45)for(x in 15..150)if((bitmap.getPixel(x,y) and 0xffffff)!=0xffffff)visible++
                    assertTrue("Prefix really is rendered",visible>10)
                    val expected=if(mode=="blackStroke")0 else 0xffffff
                    for(y in 68..87)for(x in 20..150)assertEquals("Hidden area has no distinct text: $mode",expected,bitmap.getPixel(x,y) and 0xffffff)
                } finally { bitmap.recycle() }
                val capture=jp.n624.takupoke.core.RecoveryReadCapture()
                try { PdfReader.readPages(file,capture);fail("Invisible content marked complete: $mode") }catch(_:ParseFailure) {}
                assertFalse(capture.complete);assertEquals(jp.n624.takupoke.core.RecoveryInputState.PARTIAL,capture.pages.single().state)
            } finally { file.delete() }
        }
    }
    @Test fun rendererCropOutsideGlyphsAndLinesCannotBecomeCompleteInventory() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        for(mode in listOf("within","outsideText","outsideLine")) {
            val file=File.createTempFile("synthetic-crop-",".pdf",context.cacheDir)
            try {
                PDDocument().use { doc ->
                    val page=PDPage(PDRectangle(200f,200f));page.cropBox=PDRectangle(0f,150f,200f,50f);doc.addPage(page)
                    val fontFile=File("/system/fonts/Roboto-Regular.ttf").takeIf { it.isFile }?:File("/system/fonts/NotoSans-Regular.ttf")
                    val font=PDType0Font.load(doc,fontFile)
                    PDPageContentStream(doc,page).use { stream ->
                        stream.beginText();stream.setFont(font,12f);stream.newLineAtOffset(20f,170f);stream.showText("Visible");stream.endText()
                        if(mode=="outsideText") { stream.beginText();stream.setFont(font,12f);stream.newLineAtOffset(20f,120f);stream.showText("Hidden");stream.endText() }
                        if(mode=="outsideLine") { stream.moveTo(20f,120f);stream.lineTo(180f,120f);stream.stroke() }
                    };doc.save(file)
                }
                android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> android.graphics.pdf.PdfRenderer(fd).use { renderer -> renderer.openPage(0).use { page ->
                    assertEquals(200,page.width);assertEquals(50,page.height)
                    val bitmap=android.graphics.Bitmap.createBitmap(page.width,page.height,android.graphics.Bitmap.Config.ARGB_8888)
                    try { bitmap.eraseColor(android.graphics.Color.WHITE);page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertTrue((0 until 50).any { y -> (0 until 200).any { x -> (bitmap.getPixel(x,y) and 0xffffff)!=0xffffff } })
                    }finally { bitmap.recycle() }
                } } }
                val capture=jp.n624.takupoke.core.RecoveryReadCapture()
                if(mode=="within") { assertEquals("Visible",PdfReader.readPages(file,capture).single().glyphs.joinToString("") { it.text });assertTrue(capture.complete) }
                else { try { PdfReader.readPages(file,capture);fail("Crop-hidden content marked complete") }catch(_:ParseFailure) {};assertFalse(capture.complete);assertEquals(jp.n624.takupoke.core.RecoveryInputState.PARTIAL,capture.pages.single().state) }
            }finally { file.delete() }
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
