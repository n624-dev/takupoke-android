package jp.n624.takupoke.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.n624.takupoke.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual bundled Japanese OCR; every pixel and string is invented, with no model/PDF network request. */
@RunWith(AndroidJUnit4::class)
class RecoveryOcrNativeTest {
    @Test(timeout=90000) fun bundledJapaneseConfidenceAndBorderBlankProof():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue((context.applicationContext as OfflineApplication).offlineTransportInjected)
        val file=File(context.cacheDir,"invented-ocr-${java.util.UUID.randomUUID()}.pdf")
        val bitmap=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val rule=Paint().apply { color=Color.BLACK;strokeWidth=1f;style=Paint.Style.STROKE;isAntiAlias=false }
            canvas.drawRect(80f,80f,520f,320f,rule);canvas.drawLine(80f,200f,520f,200f,rule)
            val text=Paint().apply { color=Color.BLACK;textSize=40f;isAntiAlias=true }
            canvas.drawText("架空科目",200f,155f,text)
            val pdf=PdfDocument()
            try {
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(600,400,1).create())
                page.canvas.drawBitmap(bitmap,0f,0f,null);pdf.finishPage(page)
                file.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            val page=PdfRecoveryOcr.read(file,true).single()
            assertTrue("Bundled Japanese OCR must see the invented text",page.sources.joinToString("") { it.text }.contains("架空科目"))
            assertTrue(page.confidenceScores.isNotEmpty())
            assertTrue("Separate lower cell must have measured blank proof",page.verifiedBlankBoxes.any { it.y>=395 && it.height>=230 })
            val quality=RecoveryOcrQuality.complete(true,page.confidenceScores)
            // If this script/runtime exposes no usable confidence, preserve
            // text and fail safely; do not lower the acceptance threshold.
            if(!quality)assertEquals(RecoveryInputState.PARTIAL,page.inputState)
            else assertEquals(RecoveryInputState.COMPLETE,page.inputState)
            val report=com.google.gson.Gson().toJson(mapOf("script" to "Japanese","texts" to page.sources.map { it.text },"confidenceScores" to page.confidenceScores.map { if(it.isFinite())it else it.toString() },"confidenceSupported" to page.confidenceScores.any { it>0 },"inputState" to page.inputState.name,"rules" to page.lines.size,"verifiedBlankCells" to page.verifiedBlankBoxes.size,"syntheticOnly" to true))
            println("TAKUPOKE_OCR_REPORT $report")
            android.util.Log.i("TakupokeOcrEvaluation",report)
        } finally { bitmap.recycle();file.delete() }
    }
}
