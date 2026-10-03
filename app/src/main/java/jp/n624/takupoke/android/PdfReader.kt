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
    fun parse(file: File, kind: MaterialKind, capture: RecoveryReadCapture? = null): Analysis {
        return PdfSchoolParser.parse(readPages(file, capture), kind)
    }
    internal fun readPages(file: File, capture: RecoveryReadCapture? = null): List<Page> {
        capture?.reset()
        require(file.length() in 1..50L * 1024 * 1024)
        val scratch = File(file.parentFile, "scratch").also { require(it.isDirectory || it.mkdirs()) }
        val memory = MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 64L * 1024 * 1024).setTempDir(scratch)
        PDDocument.load(file, memory).use { document ->
            require(!document.isEncrypted && document.numberOfPages in 1..12)
            capture?.begin(document.numberOfPages)
            val pages = document.pages.mapIndexed { index, page ->
                val engine = Engine(page)
                try { engine.read().also { capture?.record(index + 1, RecoveryInputState.COMPLETE, it) } }
                catch (e: Exception) {
                    capture?.record(index + 1, RecoveryInputState.PARTIAL, engine.snapshot())
                    if (e is ParseFailure) throw e.located(page = index + 1)
                    throw e
                }
            }
            capture?.finish(); return pages
        }
    }
    private class Engine(private val source: PDPage) : PDFGraphicsStreamEngine(source) {
        private val glyphs = mutableListOf<Glyph>(); private val lines = mutableListOf<Line>(); private val pending = mutableListOf<Pair<PointF, PointF>>()
        private val paintedStrokes = mutableListOf<Box>()
        private var paintComparisons=0
        private fun overlaps(a: Box, b: Box):Boolean {
            interrupted();require(++paintComparisons<=20000000) { "文字と描画の比較上限" }
            return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
        }
        private var current = PointF(); private var start = PointF(); private var operations = 0
        private var sourceLine = 0
        private val crop = source.cropBox
        private val viewportWidth get()=if(source.rotation%180!=0)crop.height.toDouble()else crop.width.toDouble()
        private val viewportHeight get()=if(source.rotation%180!=0)crop.width.toDouble()else crop.height.toDouble()
        private fun insideViewport(box:Box)=box.left>=0 && box.top>=0 && box.right<=viewportWidth && box.bottom<=viewportHeight
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
            if (glyphs.isEmpty()) throw ParseFailure("raster", "画像からの文字認識")
            return snapshot()
        }
        fun snapshot(): Page {
            val rotated = source.rotation % 180 != 0
            return Page((if (rotated) crop.height else crop.width).toDouble(), (if (rotated) crop.width else crop.height).toDouble(), glyphs, lines)
        }
        override fun processOperator(operator: com.tom_roush.pdfbox.contentstream.operator.Operator, operands: MutableList<com.tom_roush.pdfbox.cos.COSBase>) {
            interrupted(); require(++operations <= 500000)
            // These operators explicitly start/move a text line or change its coordinate system.
            // Font-size changes and TJ kerning continue the same source line.
            if (operator.name in setOf("Q", "cm", "BT", "Tm", "Td", "TD", "T*", "Ts", "'", "\"")) sourceLine++
            super.processOperator(operator, operands)
            if (operator.name == "gs" && (graphicsState.alphaConstant != 1.0 || graphicsState.nonStrokeAlphaConstant != 1.0 || graphicsState.softMask != null || graphicsState.blendMode != com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode.NORMAL)) fail("未対応の透明・合成描画")
        }
        override fun showGlyph(matrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
            interrupted()
            if (font.isVertical || font.cosObject.getDictionaryObject(COSName.TO_UNICODE) == null || font.fontDescriptor == null) fail("未対応のPDFフォント")
            if (graphicsState.textState.renderingMode.isClip || !graphicsState.textState.renderingMode.isFill && !graphicsState.textState.renderingMode.isStroke) fail("不可視文字")
            val mode=graphicsState.textState.renderingMode
            // Stroked glyph outlines can extend beyond the font advance/ascent
            // box and cover neighboring text or rules. Their paint is not proven.
            if(mode.isStroke)fail("未対応の描画")
            if(mode.isFill && graphicsState.nonStrokingColor.toRGB()!=0 || mode.isStroke && graphicsState.strokingColor.toRGB()!=0)fail("未対応の文字色")
            val text = font.toUnicode(code) ?: fail("文字コード")
            require(text.toByteArray().size <= 64 && glyphs.size < 100000)
            if (text.isEmpty()) return
            val desc = font.fontDescriptor
            val ascent = desc.ascent.takeIf { it > 0 } ?: desc.fontBoundingBox.upperRightY
            val descent = desc.descent.takeIf { it <= 0 } ?: desc.fontBoundingBox.lowerLeftY
            val positions = listOf(matrix.transformPoint(0f, descent / 1000), matrix.transformPoint(displacement.x, descent / 1000), matrix.transformPoint(0f, ascent / 1000), matrix.transformPoint(displacement.x, ascent / 1000)).map { point(it.x, it.y) }
            val x = positions.minOf { it.x }.toDouble(); val y = positions.minOf { it.y }.toDouble()
            val width = positions.maxOf { it.x } - x; val height = positions.maxOf { it.y } - y
            val glyphBox=Box(x,y,x+width,y+height)
            if(!insideViewport(glyphBox))fail("CropBox外の文字")
            if(paintedStrokes.any { overlaps(it,glyphBox) })fail("文字と描画の重なり")
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
            // Dash paths do not paint their entire geometric segment. In
            // particular [0 1000] with butt caps can paint no border at all.
            if(graphicsState.lineDashPattern.dashArray.isNotEmpty())fail("未対応の描画")
            if(graphicsState.strokingColor.toRGB()!=0)fail("未対応の罫線色")
            val transform=graphicsState.currentTransformationMatrix
            val a=transform.scaleX.toDouble();val b=transform.shearY.toDouble();val c=transform.shearX.toDouble();val d=transform.scaleY.toDouble()
            val firstNorm=a*a+b*b;val secondNorm=c*c+d*d
            if(!listOf(a,b,c,d).all(Double::isFinite) || firstNorm<=0 || firstNorm!=secondNorm || a*c+b*d!=0.0)fail("未対応の描画")
            if(!graphicsState.lineWidth.isFinite() || graphicsState.lineWidth<0)fail("未対応の描画")
            val scale=maxOf(kotlin.math.hypot(transform.scaleX.toDouble(),transform.shearY.toDouble()),kotlin.math.hypot(transform.shearX.toDouble(),transform.scaleY.toDouble()))
            val pad=maxOf(1.0,kotlin.math.abs(graphicsState.lineWidth.toDouble())*scale*.75)
            pending.forEach { (a, b) ->
                interrupted()
                // Exact display axes plus a similarity stroke CTM preserve
                // right-angle joins; near-axis acute miters can exceed this pad.
                if(a.x!=b.x && a.y!=b.y)fail("未対応の描画")
                val line = Line(minOf(a.x, b.x).toDouble(), minOf(a.y, b.y).toDouble(), maxOf(a.x, b.x).toDouble(), maxOf(a.y, b.y).toDouble())
                if(!insideViewport(Box(line.x1,line.y1,line.x2,line.y2)))fail("CropBox外の罫線")
                if(!line.horizontal && !line.vertical)fail("未対応の罫線方向")
                val paint=Box(line.x1-pad,line.y1-pad,line.x2+pad,line.y2+pad)
                if(glyphs.any { overlaps(paint,Box(it.x,it.y,it.x+it.width,it.y+it.height)) })fail("文字後の重なり描画")
                require(lines.size < 100000);lines+=line;paintedStrokes+=paint
            }
            pending.clear()
        }
        private fun checkFill() {
            // Only an initial white background is provably harmless. An opaque
            // fill after text/rules can hide captured content regardless of its alpha.
            if(glyphs.isNotEmpty() || lines.isNotEmpty() || graphicsState.nonStrokingColor.toRGB()!=0xffffff)fail("未対応の面描画")
        }
        override fun fillPath(rule: Path.FillType) { checkFill();pending.clear() }
        override fun fillAndStrokePath(rule: Path.FillType) { checkFill();strokePath() }
    }
}
