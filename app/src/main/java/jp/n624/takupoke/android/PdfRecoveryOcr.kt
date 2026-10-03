package jp.n624.takupoke.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import jp.n624.takupoke.core.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Acquisition completeness does not certify that OCR found every visible character or that a cell is empty. */
data class RecoveryOcrPage(val page: Int, val width: Int, val height: Int, val sources: List<RecoverySource>, val inputState: RecoveryInputState, val lines: List<Line>, val verifiedBlankBoxes: List<RecoveryBox>, val confidenceScores: List<Float> = emptyList()) {
    fun layout() = RecoveryLayoutPage(page, Page(width.toDouble(), height.toDouble(), sources.mapIndexed { i,s -> Glyph(s.text,s.box.x,s.box.y,s.box.width,s.box.height,i) },lines),true,inputState==RecoveryInputState.COMPLETE,verifiedBlankBoxes)
}
object PdfRecoveryOcr {
    suspend fun read(file: File, foreground: Boolean, onlyPages: Set<Int>? = null): List<RecoveryOcrPage> = withContext(Dispatchers.IO) {
        val acquisitionJob=currentCoroutineContext().job
        require(foreground && file.length() in 1..50L * 1024 * 1024)
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()).use { recognizer ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount in 1..12)
                (0 until renderer.pageCount).filter { onlyPages == null || it+1 in onlyPages }.map { index ->
                    currentCoroutineContext().ensureActive()
                    val bitmap = renderer.openPage(index).use { page ->
                        require(page.width > 0 && page.height > 0)
                        val scale = minOf(2.0, 2048.0 / maxOf(page.width, page.height))
                        val image = Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1), (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        image.eraseColor(Color.WHITE)
                        try { page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); image }
                        catch (e: Exception) { image.recycle(); throw e }
                    }
                    val width = bitmap.width; val height = bitmap.height
                    // Retain only pixels in this local acquisition; they never leave the device.
                    val pixels = IntArray(width * height); bitmap.getPixels(pixels,0,width,0,0,width,height)
                    val result = withContext(NonCancellable) { suspendCancellableCoroutine<Text> { continuation ->
                        val task = try { acquisitionJob.ensureActive(); recognizer.process(InputImage.fromBitmap(bitmap, 0)) } catch (e: Exception) { bitmap.recycle(); throw e }
                        // Native OCR may complete after coroutine cancellation. Keep its bitmap alive until then.
                        task.addOnCompleteListener { bitmap.recycle() }
                        task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                        task.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                        task.addOnCanceledListener { continuation.cancel() }
                    } }
                    currentCoroutineContext().ensureActive()
                    val words = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                    // The bundled SDK exposes primitive float confidence. Zero
                    // (including an unavailable score), NaN and low confidence
                    // retain their original atoms but cannot certify completion.
                    val confidences=words.flatMap { word -> listOf(word.confidence)+word.symbols.map { it.confidence } }
                    val elements = words.flatMap { element -> if(element.symbols.isNotEmpty())element.symbols.map { it.text to it.boundingBox } else listOf(element.text to element.boundingBox) }
                    require(elements.size <= 100000)
                    val spans = elements.mapIndexed { order, element ->
                        val box = requireNotNull(element.second); require(element.first.length <= 4096)
                        RecoverySource("ocr-${index + 1}-$order", "unassigned", index + 1, element.first, RecoveryBox(box.left.toDouble(), box.top.toDouble(), box.width().toDouble(), box.height().toDouble()), fromOcr = true)
                    }
                    val geometry = runInterruptible { RecoveryRasterGeometry.analyze(width,height,pixels,spans.map { it.box }) }
                    RecoveryOcrPage(index + 1, width, height, spans, if(RecoveryOcrQuality.complete(geometry.complete,confidences))RecoveryInputState.COMPLETE else RecoveryInputState.PARTIAL,geometry.lines,geometry.blankBoxes,confidences)
                }
            } }
        }
    }
}
