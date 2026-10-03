package jp.n624.takupoke.core

import kotlin.math.abs

data class Glyph(val text: String, val x: Double, val y: Double, val width: Double, val height: Double, val order: Int, val sourceLine: Int? = null) {
    val cx get() = x + width / 2; val cy get() = y + height / 2
}
data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val horizontal get() = abs(y1 - y2) < .3 && x2 - x1 > 1
    val vertical get() = abs(x1 - x2) < .3 && y2 - y1 > 1
}
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val cx get() = (left + right) / 2; val cy get() = (top + bottom) / 2
}
data class Page(val width: Double, val height: Double, val glyphs: List<Glyph>, val lines: List<Line>)
class ParseFailure(val code: String, val stage: String, val page: Int? = null, val classRow: Int? = null, val day: Int? = null, val period: Int? = null) : IllegalArgumentException(
    (page?.let { "${it}ページ目：" } ?: "") + "確認箇所：$stage（$code）。" +
        (classRow?.let { "対象：表の上から${it}番目のクラス" + (day?.let { d -> "・${"月火水木金土日".getOrNull(d - 1) ?: '?'}曜" } ?: "") + (period?.let { p -> "${p}限" } ?: "") + "。" } ?: "") + "推測せず解析を停止しました。前回の正常な解析結果は保持しています。") {
    fun located(page: Int? = this.page, classRow: Int? = this.classRow, day: Int? = this.day, period: Int? = this.period) = ParseFailure(code, stage, page, classRow, day, period)
}
fun fail(stage: String, code: String = when (stage) {
    "ページの回転" -> "P02"; "年度", "年度・種類" -> "P03"; "ページ数", "学期", "資料の種類" -> "P04"; "時限見出し", "返却時限" -> "P05"
    "罫線・列", "クラス罫線" -> "P06"; "罫線・行" -> "P07"; "罫線・セル" -> "P08"
    "試験日", "返却日", "日付順", "ページ間の日付" -> "P10"; "Form XObject", "画像を含むPDF", "未対応の描画", "クリッピング", "曲線の描画" -> "P12"
    "文字行の対応", "文字順の重複" -> "P13"; "クラス", "試験クラス見出し", "返却クラス" -> "P14"; "学年", "返却学年" -> "P15"
    "クラス重複", "クラスの重複", "返却クラス重複" -> "P16"; "授業欄の行数", "特別時間割の行数", "授業欄" -> "P17"; "並記授業" -> "P18"; "科目の空欄" -> "P19"
    "文字列断片の位置と読み順" -> "P20"; "文字列断片が属する行" -> "P21"; else -> "P01"
}): Nothing = throw ParseFailure(code, stage)
internal class PdfGeometryLimit : IllegalArgumentException("PDF geometry comparison limit exceeded")
/** Shared by every query in one strict parse, including role-band calibration. */
internal class PdfGeometryWork(private val limit:Long=20_000_000) {
    var comparisons=0L; private set
    init { interrupted() }
    fun step() {
        comparisons++
        if(comparisons%128==0L)interrupted()
        if(comparisons>limit)throw PdfGeometryLimit()
    }
}
class Grid(val page: Page, private val check:()->Unit = ::interrupted) {
    private val byCy by lazy { page.glyphs.withIndex().sortedWith { a,b -> check();a.value.cy.compareTo(b.value.cy) } }
    /** The strict containment predicate is unchanged; only the candidate lookup is indexed. */
    internal fun glyphsIn(box:Box):List<Glyph> {
        val indexed=byCy
        fun boundary(value:Double,upper:Boolean):Int {
            var low=0;var high=indexed.size
            while(low<high) { check();val middle=(low+high)/2
                if(indexed[middle].value.cy<value || upper && indexed[middle].value.cy==value)low=middle+1 else high=middle
            }
            return low
        }
        val start=boundary(box.top+.3,true);val end=boundary(box.bottom-.3,false)
        val found=mutableListOf<IndexedValue<Glyph>>()
        for(i in start until end) { check();val entry=indexed[i];val g=entry.value;if(g.cx>box.left+.3 && g.cx<box.right-.3)found+=entry }
        // Filtering used to preserve page order. Restore it before downstream
        // stable sorts, including sourceLine-less glyphs with equal order values.
        return found.sortedWith { a,b -> check();a.index.compareTo(b.index) }.map { check();it.value }
    }
    fun box(x: Double, y: Double): Box {
        val vs = page.lines.filter { check();it.vertical && it.y1 - .8 <= y && y <= it.y2 + .8 }
        val hs = page.lines.filter { check();it.horizontal && it.x1 - .8 <= x && x <= it.x2 + .8 }
        return Box(vs.filter { check();it.x1 < x - .5 }.maxOfOrNull { it.x1 } ?: fail("罫線・列"),
            hs.filter { check();it.y1 < y - .5 }.maxOfOrNull { it.y1 } ?: fail("罫線・行"),
            vs.filter { check();it.x1 > x + .5 }.minOfOrNull { it.x1 } ?: fail("罫線・列"),
            hs.filter { check();it.y1 > y + .5 }.minOfOrNull { it.y1 } ?: fail("罫線・行"))
    }
    fun text(box: Box, combineFragments: Boolean = true): List<String> {
        val glyphs = glyphsIn(box)
        require(glyphs.sumOf { check();it.text.toByteArray().size } <= 4096)
        if (glyphs.any { check();it.sourceLine != null }) {
            if (glyphs.any { check();(it.sourceLine ?: -1) < 0 || it.order < 0 } || glyphs.map { it.order }.distinct().size != glyphs.size) fail("文字行の対応")
            val rows = glyphs.groupBy { it.sourceLine }.values.map { it.sortedBy(Glyph::order) }
            fun center(row: List<Glyph>): Double = row.map { it.cy }.sorted()[row.size / 2]
            if (!combineFragments) return rows.sortedWith(compareBy<List<Glyph>> { center(it) }.thenBy { it.first().order }).map { it.joinToString("") { g -> g.text }.trim() }.filter(String::isNotEmpty)
            data class Fragment(val glyphs: List<Glyph>) {
                val left = this.glyphs.minOf { it.x }; val right = this.glyphs.maxOf { it.x + it.width }
                val top = this.glyphs.minOf { it.y }; val bottom = this.glyphs.maxOf { it.y + it.height }
                fun precedes(next: Fragment): Boolean = right <= next.left + .1 || left < next.left && right < next.right &&
                    this.glyphs.last().order < next.glyphs.first().order && this.glyphs.last().x < next.glyphs.first().x &&
                    this.glyphs.last().x + this.glyphs.last().width < next.glyphs.first().x + next.glyphs.first().width
            }
            val bands = mutableListOf<MutableList<Fragment>>()
            rows.map(::Fragment).sortedWith(compareBy<Fragment> { it.top }.thenBy { it.left }).forEach { fragment ->
                val aligned = bands.filter { band -> check();band.all { check();abs(it.top - fragment.top) <= .35 && abs(it.bottom - fragment.bottom) <= .35 } }
                if (aligned.size > 1) fail("文字列断片が属する行")
                if (aligned.isEmpty()) bands += mutableListOf(fragment) else {
                    val band = aligned.single()
                    if (band.any { check();if (it.left < fragment.left) !it.precedes(fragment) else !fragment.precedes(it) }) fail("文字列断片の位置と読み順")
                    band += fragment
                }
            }
            return bands.map { band -> band.sortedBy { it.left }.flatMap { it.glyphs }.joinToString("") { it.text }.trim() }.filter(String::isNotEmpty)
        }
        val bands = mutableListOf<MutableList<Glyph>>()
        glyphs.sortedWith { a,b -> check();a.order.compareTo(b.order) }.forEach { g -> check()
            val aligned = bands.filter { band -> check();band.all { check();abs(it.y - g.y) <= .35 && abs(it.y + it.height - g.y - g.height) <= .35 } }
            if (aligned.size > 1) fail("文字行の対応")
            if (aligned.isEmpty()) bands += mutableListOf(g) else {
                val band = aligned.single(); val prior = band.last()
                if (g.x < prior.x || g.x + g.width <= prior.x + prior.width) fail("文字順の重複")
                band += g
            }
        }
        return bands.sortedBy { it.minOf(Glyph::y) }.map { it.joinToString("") { g -> g.text }.trim() }.filter(String::isNotEmpty)
    }
    fun subdivisions(x: Double, row: Box): List<Box> {
        val cuts = page.lines.filter { check();it.horizontal && it.x1 - .5 <= x && x <= it.x2 + .5 && it.y1 > row.top + 1 && it.y1 < row.bottom - 1 }.map { kotlin.math.round(it.y1 * 100) / 100 }.distinct().sorted()
        return (listOf(row.top) + cuts + row.bottom).zipWithNext().filter { it.second - it.first >= 2 }.map { box(x, (it.first + it.second) / 2) }.distinct()
    }
    companion object {
        fun rows(glyphs: List<Glyph>, check:()->Unit = ::interrupted): List<List<Glyph>> {
            val rows = mutableListOf<MutableList<Glyph>>()
            glyphs.sortedWith { a,b -> check();a.cy.compareTo(b.cy) }.forEach { g -> check(); if (rows.isNotEmpty() && abs(rows.last().first().cy - g.cy) <= 2) rows.last() += g else rows += mutableListOf(g) }
            return rows.map { it.sortedWith { a,b -> check();a.cx.compareTo(b.cx) } }
        }
        fun runs(glyphs: List<Glyph>): List<Pair<String, Box>> = rows(glyphs).flatMap { row ->
            val chunks = mutableListOf<MutableList<Glyph>>()
            row.forEach { g -> val last = chunks.lastOrNull()?.lastOrNull()
                if (last == null || g.x - last.x - last.width > maxOf(2.0, minOf(last.height, g.height) * .55)) chunks += mutableListOf(g) else chunks.last() += g
            }
            chunks.map { gs -> key(gs.joinToString("") { it.text }) to Box(gs.minOf { it.x }, gs.minOf { it.y }, gs.maxOf { it.x + it.width }, gs.maxOf { it.y + it.height }) }.filter { it.first.isNotEmpty() }
        }
    }
}
