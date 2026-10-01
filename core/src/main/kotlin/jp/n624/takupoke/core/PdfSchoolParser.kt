package jp.n624.takupoke.core

import java.time.LocalDate
import kotlin.math.abs

object PdfSchoolParser {
    fun parse(pages: List<Page>, kind: MaterialKind): Analysis {
        require(kind != MaterialKind.CHANGES)
        if (pages.size != when (kind) { MaterialKind.EXAM -> 6; else -> 1 }) fail("ページ数")
        pages.forEach { p -> require(p.width in 1.0..5000.0 && p.height in 1.0..5000.0 && p.glyphs.size in 1..100000 && p.lines.size <= 100000)
            require(p.glyphs.all { listOf(it.x, it.y, it.width, it.height).all(Double::isFinite) && it.text.toByteArray().size <= 64 }) }
        val heading = Grid.rows(pages.first().glyphs.filter { it.cy < pages.first().height / 4 }).joinToString("") { row -> key(row.joinToString("") { it.text }) }
        val year = Regex("令和([0-9]{1,2})年度").find(heading)?.groupValues?.get(1)?.toInt()?.plus(2018) ?: fail("年度")
        if (kind == MaterialKind.TIMETABLE) {
            if (heading.contains("前期") == heading.contains("後期")) fail("学期")
            val term = if (heading.contains("前期")) 1 else 2
            val lessons = ordinary(pages.single())
            return Analysis(kind, year, term, lessons, classes = lessons.map { it.className }.distinct().sorted())
        }
        if (!heading.contains("試験") || heading.contains("返却") != (kind == MaterialKind.RETURN)) fail("資料の種類")
        val lessons = mutableListOf<Lesson>(); val classes = mutableSetOf<String>(); var dates: List<String>? = null; var firstTimes: Times? = null
        pages.forEachIndexed { index, page ->
            interrupted()
            val pageHeading = key(Grid.rows(page.glyphs.filter { it.cy < page.height / 4 }).joinToString("") { row -> row.joinToString("") { it.text } })
            if (!pageHeading.contains("令和${year - 2018}年度") || !pageHeading.contains("試験") || pageHeading.contains("返却") != (kind == MaterialKind.RETURN)) fail("年度・種類")
            val times = times(page, if (kind == MaterialKind.EXAM) 6 else 8)
            if (firstTimes != null && firstTimes != times) fail("ページ間の授業時刻")
            firstTimes = times
            val parsed = if (kind == MaterialKind.EXAM) exam(page, year, index + 1, times) else returned(page, year, times)
            if (parsed.dates != parsed.dates.sorted()) fail("日付順")
            if (dates != null && dates != parsed.dates.sorted()) fail("ページ間の日付")
            if (parsed.classes.any { it in classes }) fail("クラスの重複")
            dates = parsed.dates.sorted(); classes += parsed.classes; lessons += parsed.lessons
            require(lessons.size <= 20000)
        }
        if (classes.size != 17 || lessons.isEmpty()) fail("クラス数・授業数")
        val clocks = dates.orEmpty().mapIndexed { index, date ->
            val values = if (kind == MaterialKind.RETURN && index > 0) Schedule.normalTimes.mapIndexed { i, value -> i + 1 to value }.toMap() else firstTimes!!.single
            DayTimes(date, values.entries.sortedBy { it.key }.map { (period, value) -> PeriodTime(period, value.substringBefore('〜'), value.substringAfter('〜')) })
        }
        return Analysis(kind, year, lessons = lessons, dates = dates.orEmpty(), classes = classes.sorted(), specialTimes = clocks)
    }
    private fun header(page: Page, sequence: String, count: Int): List<Glyph> {
        val rows = Grid.rows(page.glyphs.filter { it.cy < page.height / 4 }).filter { key(it.joinToString("") { g -> g.text }) == sequence.repeat(count) }
        if (rows.size != 1 || rows.single().size != sequence.length * count) fail("時限見出し")
        return rows.single()
    }
    private fun ordinary(page: Page): List<Lesson> {
        val grid = Grid(page); val hs = header(page, "12345678", 5)
        val first = grid.box(hs[0].cx, hs[0].cy); val cls = grid.box(first.left - 2, first.bottom + 20)
        val bottom = page.lines.filter { it.vertical && abs(it.x1 - cls.right) < .3 }.maxOfOrNull { it.y2 } ?: fail("クラス罫線")
        val rows = Grid.rows(page.glyphs.filter { it.cx > cls.left && it.cx < cls.right && it.cy > first.bottom && it.cy < bottom })
        val lessons = mutableListOf<Lesson>(); val classes = mutableSetOf<String>()
        rows.forEach { row ->
            interrupted(); val label = key(row.joinToString("") { it.text }); if (!label.matches(Regex("[1-9]|[A-Z]{2,8}"))) fail("クラス")
            val y = row.map { it.cy }.average(); var box = grid.box(cls.cx, y)
            val grade = key(grid.text(grid.box(cls.left - 2, y)).joinToString("")); if (!grade.matches(Regex("[1-9]|AI"))) fail("学年")
            val name = canonicalClass("${grade}_$label"); if (!classes.add(name)) fail("クラス重複")
            box = box.copy(top = maxOf(box.top, grid.box(hs[0].cx, y).top))
            hs.forEachIndexed { col, h -> grid.subdivisions(h.cx, box).forEach { cell ->
                val text = grid.text(cell); if (text.isNotEmpty()) {
                    if (text.size > 3) fail("授業欄の行数")
                    val f = text + List(3 - text.size) { "" }; val parts = f.map { it.replace('･', '・').split('・') }
                    val parallel = text.size == 3 && parts.all { it.size == 2 }
                    if (parts[0].size > 1 && parts[1].size > 1 && !parallel) fail("並記授業")
                    repeat(if (parallel) 2 else 1) { v -> val fields = if (parallel) parts.map { it[v] } else f
                        if (fields[0].isEmpty()) fail("科目の空欄")
                        lessons += Lesson(name, col / 8 + 1, col % 8 + 1, Names(fields[0], fields[1], collapseMarks(fields[2])), text.joinToString("\n"))
                        require(lessons.size <= 20000)
                    }
                }
            } }
        }
        if (classes.isEmpty() || lessons.isEmpty()) fail("授業欄")
        return lessons
    }
    private data class Parsed(val dates: List<String>, val classes: List<String>, val lessons: List<Lesson>)
    private data class Times(val single: Map<Int, String>, val consecutive: Map<String, String>)
    private fun times(page: Page, count: Int): Times {
        val single = linkedMapOf<Int, String>(); val consecutive = linkedMapOf<String, String>()
        Grid.rows(page.glyphs).forEach { row -> val text = key(row.joinToString("") { it.text })
            Regex("([1-8])時限目([0-9]{1,2}:[0-9]{2})[~〜]([0-9]{1,2}:[0-9]{2})").findAll(text).forEach { m ->
                val p = m.groupValues[1].toInt(); if (p > count || p in single) fail("授業時刻")
                single[p] = clockRange(m.groupValues[2], m.groupValues[3])
            }
            Regex("([1-8])[・･]([1-8])時限連続([0-9]{1,2}:[0-9]{2})[~〜]([0-9]{1,2}:[0-9]{2})").findAll(text).forEach { m ->
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val k = "$a-$b"
                if (a >= b || b > count || k in consecutive) fail("連続授業時刻")
                consecutive[k] = clockRange(m.groupValues[3], m.groupValues[4])
            }
        }
        if (single.size != count) fail("授業時刻の件数")
        return Times(single, consecutive)
    }
    private fun clockRange(a: String, b: String): String {
        fun clock(v: String): String { val parts = v.split(':').map(String::toInt); require(parts[0] in 0..23 && parts[1] in 0..59); return "%02d:%02d".format(parts[0], parts[1]) }
        val start = clock(a); val end = clock(b); require(start < end); return "$start〜$end"
    }
    private fun date(text: String, year: Int, slash: Boolean): String? {
        val m = Regex(if (slash) "^([0-9]{1,2})/([0-9]{1,2})$" else "^([0-9]{1,2})月([0-9]{1,2})日").find(text) ?: return null
        val month = m.groupValues[1].toInt(); return LocalDate.of(year + if (month < 4) 1 else 0, month, m.groupValues[2].toInt()).toString()
    }
    private fun specialCell(page: Page, box: Box, date: String, name: String, period: Int, xs: List<Double>, times: Times): List<Lesson> {
        val text = Grid(page).text(box); if (text.isEmpty()) return emptyList()
        if (text.size > 8) fail("特別時間割の行数")
        val covered = xs.indices.filter { xs[it] > box.left + .5 && xs[it] < box.right - .5 }.map { it + 1 }
        if (period !in covered) fail("結合時限")
        val a = covered.first(); val b = covered.last()
        val time = if (a == b) times.single[a] else times.consecutive["$a-$b"] ?: times.single[a]?.substringBefore('〜')?.let { start -> times.single[b]?.substringAfter('〜')?.let { "$start〜$it" } }
        return listOf(Lesson(name, LocalDate.parse(date).dayOfWeek.value, period, Names(text[0], text.getOrElse(1) { "" }, text.getOrElse(2) { "" }), text.joinToString("\n"), date, a, b, time))
    }
    private fun exam(page: Page, year: Int, pageNumber: Int, times: Times): Parsed {
        val columns = if (pageNumber == 6) 2 else 3; val hs = header(page, "123456", columns); val y = hs[0].cy
        val labels = Grid.runs(page.glyphs.filter { it.cy < y - 3 && it.cy > y - page.height / 10 }).filter { it.first.matches(Regex(if (pageNumber == 6) "[12]年" else "[1-5]-(?:[1-3]|[A-Z]{2})")) }.sortedBy { it.second.cx }
        if (labels.size != columns) fail("試験クラス見出し")
        val names = labels.map { if (pageNumber == 6) "AI_${it.first.take(1)}" else it.first.replace('-', '_') }
        val days = Grid.runs(page.glyphs.filter { it.cy > y + 5 && it.cy < page.height * .7 && it.cx < hs[0].cx }).mapNotNull { r -> date(r.first, year, false)?.let { r.second to it } }.sortedBy { it.first.cy }
        if (days.size != 5 || days.map { it.second }.distinct().size != 5) fail("試験日")
        val grid = Grid(page); val lessons = mutableListOf<Lesson>()
        days.forEach { (r, day) -> val row = grid.box(r.cx, r.cy)
            names.forEachIndexed { col, name -> val xs = (0..5).map { hs[col * 6 + it].cx }
                xs.forEachIndexed { i, x -> grid.subdivisions(x, row).forEach { box -> lessons += specialCell(page, box, day, name, i + 1, xs, times) } }
            }
        }
        return Parsed(days.map { it.second }, names, lessons)
    }
    private fun returned(page: Page, year: Int, times: Times): Parsed {
        val hs = header(page, "12345678", 5); val y = hs[0].cy; val step = hs[1].cx - hs[0].cx; if (step <= 5) fail("返却時限")
        val days = Grid.runs(page.glyphs.filter { it.cy < y && it.cy > y - page.height / 20 }).mapNotNull { r -> date(r.first, year, true)?.let { r.second to it } }.sortedBy { it.first.cx }
        if (days.size != 5 || days.map { it.second }.distinct().size != 5) fail("返却日")
        val note = key(Grid.rows(page.glyphs).joinToString("") { it.joinToString("") { g -> g.text } }).replace('〜', '~')
        val first = LocalDate.parse(days[0].second); val start = LocalDate.parse(days[1].second); val end = LocalDate.parse(days[4].second)
        if (start.monthValue != end.monthValue || !note.contains("${first.monthValue}月${first.dayOfMonth}日の時間割は以下のとおり") || !note.contains("${start.monthValue}月${start.dayOfMonth}日~${end.dayOfMonth}日は通常の授業日どおりの授業時間")) fail("返却時刻の注記")
        val grid = Grid(page)
        val classRight = minOf(hs[0].cx - step * .15, grid.box(hs[0].cx, y).left - .3)
        val runs = Grid.runs(page.glyphs.filter { it.cx < classRight && it.cy > y + 5 && it.cy < page.height * .7 })
        val grades = runs.filter { it.second.cx < hs[0].cx - step * .8 && it.first.matches(Regex("[1-5]|AI")) }
        val labels = runs.filter { it.second.cx >= hs[0].cx - step * .8 && it.first.matches(Regex("[1-3]|CN|ES|IT")) }
        if (grades.size != 6 || labels.size != 17) fail("返却クラス")
        val lessons = mutableListOf<Lesson>(); val names = mutableListOf<String>()
        labels.forEach { (label, r) -> val grade = grades.minBy { abs(it.second.cy - r.cy) }; if (abs(grade.second.cy - r.cy) >= step * 2.5) fail("返却学年")
            val name = "${grade.first}_$label"; if (name in names) fail("返却クラス重複"); names += name
            val row = grid.box(r.cx, r.cy)
            days.forEachIndexed { dayIndex, (_, day) -> val xs = (0..7).map { hs[dayIndex * 8 + it].cx }; val dayTimes = if (dayIndex == 0) times else Times(Schedule.normalTimes.mapIndexed { i, t -> i + 1 to t }.toMap(), emptyMap())
                xs.forEachIndexed { i, x -> grid.subdivisions(x, row).forEach { box -> lessons += specialCell(page, box, day, name, i + 1, xs, dayTimes) } }
            }
        }
        return Parsed(days.map { it.second }, names, lessons)
    }
    fun collapseMarks(text: String): String = text.replace(Regex("([\uFF66-\uFF9D])([\uFF9E\uFF9F])\\2+"), "$1$2")
}
