package jp.n624.takupoke.core

import kotlin.math.abs

data class Glyph(val text: String, val x: Double, val y: Double, val width: Double, val height: Double, val order: Int) {
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
class ParseFailure(val code: String, val stage: String) : IllegalArgumentException("$code: $stage")
fun fail(stage: String): Nothing = throw ParseFailure("P01", stage)
class Grid(val page: Page) {
    fun box(x: Double, y: Double): Box {
        val vs = page.lines.filter { it.vertical && it.y1 - .8 <= y && y <= it.y2 + .8 }
        val hs = page.lines.filter { it.horizontal && it.x1 - .8 <= x && x <= it.x2 + .8 }
        return Box(vs.filter { it.x1 < x - .5 }.maxOfOrNull { it.x1 } ?: fail("罫線・列"),
            hs.filter { it.y1 < y - .5 }.maxOfOrNull { it.y1 } ?: fail("罫線・行"),
            vs.filter { it.x1 > x + .5 }.minOfOrNull { it.x1 } ?: fail("罫線・列"),
            hs.filter { it.y1 > y + .5 }.minOfOrNull { it.y1 } ?: fail("罫線・行"))
    }
    fun text(box: Box): List<String> {
        val glyphs = page.glyphs.filter { it.cx > box.left + .3 && it.cx < box.right - .3 && it.cy > box.top + .3 && it.cy < box.bottom - .3 }
        require(glyphs.sumOf { it.text.toByteArray().size } <= 4096)
        val bands = mutableListOf<MutableList<Glyph>>()
        glyphs.sortedBy { it.order }.forEach { g ->
            val aligned = bands.filter { band -> band.all { abs(it.y - g.y) <= .35 && abs(it.y + it.height - g.y - g.height) <= .35 } }
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
        val cuts = page.lines.filter { it.horizontal && it.x1 - .5 <= x && x <= it.x2 + .5 && it.y1 > row.top + 1 && it.y1 < row.bottom - 1 }.map { kotlin.math.round(it.y1 * 100) / 100 }.distinct().sorted()
        return (listOf(row.top) + cuts + row.bottom).zipWithNext().filter { it.second - it.first >= 2 }.map { box(x, (it.first + it.second) / 2) }.distinct()
    }
    companion object {
        fun rows(glyphs: List<Glyph>): List<List<Glyph>> {
            val rows = mutableListOf<MutableList<Glyph>>()
            glyphs.sortedBy { it.cy }.forEach { g -> if (rows.isNotEmpty() && abs(rows.last().first().cy - g.cy) <= 2) rows.last() += g else rows += mutableListOf(g) }
            return rows.map { it.sortedBy(Glyph::cx) }
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
