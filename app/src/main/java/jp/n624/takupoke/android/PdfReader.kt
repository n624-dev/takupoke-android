package jp.n624.takupoke.android

import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import jp.n624.takupoke.core.*
import java.io.File

object PdfReader {
    fun parse(file: File, kind: MaterialKind): Analysis {
        return PdfSchoolParser.parse(readPages(file), kind)
    }
    internal fun readPages(file: File): List<Page> {
        require(file.length() in 1..50L * 1024 * 1024)
        val scratch = File(file.parentFile, "scratch").also { require(it.isDirectory || it.mkdirs()) }
        val memory = MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 64L * 1024 * 1024).setTempDir(scratch)
        PDDocument.load(file, memory).use { document ->
            require(!document.isEncrypted && document.numberOfPages in 1..12)
            return document.pages.mapIndexed { index, page -> try { Engine(page).read() } catch (e: ParseFailure) { throw e.located(page = index + 1) } }
        }
    }
    private class Engine(private val source: PDPage) : PDFGraphicsStreamEngine(source) {
        private val glyphs = mutableListOf<Glyph>(); private val lines = mutableListOf<Line>(); private val pending = mutableListOf<Pair<PointF, PointF>>()
        private var current = PointF(); private var start = PointF(); private var operations = 0
        private var sourceLine = 0
        private val crop = source.cropBox
        private fun point(x: Float, y: Float): PointF {
            val a = x - crop.lowerLeftX; val b = y - crop.lowerLeftY
            return when (((source.rotation % 360) + 360) % 360) {
                0 -> PointF(a, crop.height - b)
                90 -> PointF(b, a)
                180 -> PointF(crop.width - a, b)
                270 -> PointF(crop.height - b, crop.width - a)
                else -> fail("ページの回転")
            }
        }
        fun read(): Page {
            processPage(source)
            val rotated = source.rotation % 180 != 0
            return Page((if (rotated) crop.height else crop.width).toDouble(), (if (rotated) crop.width else crop.height).toDouble(), glyphs, lines)
        }
        override fun processOperator(operator: com.tom_roush.pdfbox.contentstream.operator.Operator, operands: MutableList<com.tom_roush.pdfbox.cos.COSBase>) {
            interrupted(); require(++operations <= 500000)
            // These operators explicitly start/move a text line or change its coordinate system.
            // Font-size changes and TJ kerning continue the same source line.
            if (operator.name in setOf("Q", "cm", "BT", "Tm", "Td", "TD", "T*", "Ts", "'", "\"")) sourceLine++
            super.processOperator(operator, operands)
        }
        override fun showGlyph(matrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
            interrupted()
            if (font.isVertical || font.cosObject.getDictionaryObject(COSName.TO_UNICODE) == null || font.fontDescriptor == null) fail("未対応のPDFフォント")
            if (graphicsState.textState.renderingMode.isClip || !graphicsState.textState.renderingMode.isFill && !graphicsState.textState.renderingMode.isStroke) fail("不可視文字")
            val text = font.toUnicode(code) ?: fail("文字コード")
            require(text.toByteArray().size <= 64 && glyphs.size < 100000)
            if (text.isEmpty()) return
            val desc = font.fontDescriptor
            val ascent = desc.ascent.takeIf { it > 0 } ?: desc.fontBoundingBox.upperRightY
            val descent = desc.descent.takeIf { it <= 0 } ?: desc.fontBoundingBox.lowerLeftY
            val positions = listOf(matrix.transformPoint(0f, descent / 1000), matrix.transformPoint(displacement.x, descent / 1000), matrix.transformPoint(0f, ascent / 1000), matrix.transformPoint(displacement.x, ascent / 1000)).map { point(it.x, it.y) }
            val x = positions.minOf { it.x }.toDouble(); val y = positions.minOf { it.y }.toDouble()
            val width = positions.maxOf { it.x } - x; val height = positions.maxOf { it.y } - y
            glyphs += Glyph(text, x, y, width, height, glyphs.size, sourceLine)
        }
        override fun showForm(form: PDFormXObject) { fail("Form XObject") }
        override fun drawImage(image: PDImage) { fail("画像を含むPDF") }
        override fun shadingFill(name: COSName) { fail("未対応の描画") }
        override fun clip(rule: Path.FillType) { fail("クリッピング") }
        override fun moveTo(x: Float, y: Float) { current = point(x, y); start = current }
        override fun lineTo(x: Float, y: Float) { val next = point(x, y); require(pending.size < 100000); pending += current to next; current = next }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { fail("曲線の描画") }
        override fun getCurrentPoint(): PointF = current
        override fun closePath() { pending += current to start; current = start }
        override fun endPath() { pending.clear() }
        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
            val ps = listOf(p0, p1, p2, p3, p0).map { point(it.x, it.y) }
            ps.zipWithNext().forEach { pending += it }; current = ps[0]; start = ps[0]
        }
        override fun strokePath() {
            pending.forEach { (a, b) ->
                val line = Line(minOf(a.x, b.x).toDouble(), minOf(a.y, b.y).toDouble(), maxOf(a.x, b.x).toDouble(), maxOf(a.y, b.y).toDouble())
                if (line.horizontal || line.vertical) { require(lines.size < 100000); lines += line }
            }
            pending.clear()
        }
        override fun fillPath(rule: Path.FillType) { pending.clear() }
        override fun fillAndStrokePath(rule: Path.FillType) { strokePath() }
    }
}
