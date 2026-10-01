package jp.n624.takupoke.core

import java.time.LocalDate
import kotlin.math.floor

class WeekdayWarning(val rows: List<Int>) : IllegalArgumentException("曜日の計算結果を確認できません。行: ${rows.joinToString()}")
object XlsxParser {
    fun parse(bytes: ByteArray, year: Int, preview: Boolean = false): Analysis {
        require(year in 1900..9998)
        val files = Archives.read(bytes)
        require(files.keys.none { it.contains("externalLinks") || it.endsWith("vbaProject.bin") })
        fun xml(name: String) = SafeXml.parse(requireNotNull(files[name]))
        val workbook = xml("xl/workbook.xml")
        require(workbook.descendants("workbookPr").none { it.attrs["date1904"] in listOf("1", "true") })
        val sheet = workbook.descendants("sheet").single { it.attrs["name"] == "時間割変更" }
        val rels = xml("xl/_rels/workbook.xml.rels").descendants("Relationship")
        require(rels.none { it.attrs["TargetMode"] == "External" })
        val target = requireNotNull(rels.single { it.attrs["Id"] == sheet.attrs["id"] }.attrs["Target"])
        val path = if (target.startsWith("/xl/")) target.drop(1) else "xl/$target"
        require(!path.contains(".."))
        val strings = files["xl/sharedStrings.xml"]?.let { SafeXml.parse(it).descendants("si").map(::richText) }.orEmpty()
        require(strings.size <= 50000 && strings.all { it.toByteArray().size <= 4096 })
        val worksheet = xml(path); val rows = linkedMapOf<Int, MutableMap<Int, Pair<String, Boolean>>>(); var cellCount = 0
        worksheet.descendants("row").forEach { row ->
            val number = requireNotNull(row.attrs["r"]).toInt(); require(number in 1..10000 && number !in rows)
            val cells = linkedMapOf<Int, Pair<String, Boolean>>()
            row.children.filter { it.name == "c" }.forEach { cell ->
                require(++cellCount <= 100000)
                val ref = Regex("([A-Z]+)([0-9]+)").matchEntire(requireNotNull(cell.attrs["r"])) ?: error("セル位置を確認できません")
                require(ref.groupValues[2].toInt() == number)
                val col = ref.groupValues[1].fold(0) { a, c -> a * 26 + c.code - 64 } - 1
                require(col in 0..127 && col !in cells && cell.children.count { it.name == "v" } <= 1 && cell.children.count { it.name == "f" } <= 1)
                val raw = cell.child("v")?.text.orEmpty()
                val value = when (cell.attrs["t"]) {
                    "s" -> strings[raw.toInt()]
                    "inlineStr" -> richText(requireNotNull(cell.child("is")))
                    "b" -> when (raw) { "1" -> "TRUE"; "0" -> "FALSE"; else -> error("セル型を確認できません") }
                    "e" -> error("セルにエラーがあります")
                    null, "n", "str" -> raw
                    else -> error("未対応のセル型です")
                }
                require(value.toByteArray().size <= 4096); cells[col] = normalized(value) to (cell.child("f") != null)
            }
            rows[number] = cells
        }
        val headerEntry = rows.entries.firstOrNull { e -> setOf("学年", "学科・クラス", "月日").all { wanted -> e.value.values.any { key(it.first) == wanted } } } ?: error("見出しを確認できません")
        val width = headerEntry.value.keys.max() + 1
        val headers = (0 until width).map { headerEntry.value[it]?.first.orEmpty() }
        require(headers.filter(String::isNotEmpty).distinct().size == headers.count(String::isNotEmpty) && headerEntry.value.values.none { it.second })
        worksheet.descendants("mergeCell").forEach { m -> val nums = Regex("[0-9]+").findAll(m.attrs["ref"].orEmpty()).map { it.value.toInt() }.toList(); require(nums.isNotEmpty() && nums.max() < headerEntry.key) }
        fun index(vararg aliases: String): Int = headers.indexOfFirst { key(it) in aliases.map(::key) }
        val gi = index("学年"); val ci = index("学科・クラス"); val di = index("月日")
        val pi = index("時限", "校時", "時間", "限"); require(pi >= 0)
        val bi = index("変更前", "変更前科目", "旧科目", "変更元"); val ai = index("変更後", "変更後科目", "新科目", "変更先")
        val ti = index("教員", "担当", "担当教員", "担任", "教官"); val ri = index("教室", "場所"); val ni = index("備考", "連絡", "メモ", "その他")
        val ki = index("変更内容", "変更種別", "種別"); val si = index("科目(担当教員)", "科目・担当教員", "科目")
        val data = rows.filterKeys { it > headerEntry.key }.filterValues { it.values.any { p -> p.first.isNotEmpty() } }
        val known = mutableMapOf<String, MutableSet<String>>()
        data.values.forEach { row -> grades(row[gi]?.first.orEmpty()).forEach { grade -> classes(row[ci]?.first.orEmpty()).filter { it != "全" && it != "AI" }.forEach { known.getOrPut(grade) { sortedSetOf() } += it } } }
        val warnings = mutableListOf<Int>(); val changes = mutableListOf<Change>()
        data.forEach { (number, row) ->
            interrupted(); require(row.filterKeys { it >= width }.values.none { it.first.isNotEmpty() })
            fun value(i: Int): String = row[i]?.first.orEmpty().let { if (it.matches(Regex("-?[0-9]+\\.0+"))) it.substringBefore('.') else it }
            val date = date(value(di), year)
            row.filterValues { it.second }.forEach { (i, pair) -> require(key(headers[i]) in setOf("曜日", "曜"))
                val weekday = "月火水木金土日"[date.dayOfWeek.value - 1].toString()
                if (key(pair.first).removeSuffix("曜日").removeSuffix("曜").removePrefix("(").removeSuffix(")") != weekday) warnings += number
            }
            var before = value(bi); var after = value(ai); val type = value(ki); val note = value(ni).ifEmpty { type }
            if (before.isEmpty() && after.isEmpty()) { if (type == "休講") before = value(si) else after = value(si) }
            val period = value(pi).removeSuffix("時限").removeSuffix("限目").removeSuffix("限"); require(period.isNotBlank())
            val raw = headers.indices.filter { value(it).isNotEmpty() }.joinToString(" | ") { "${headers[it]}:${value(it)}" }
            grades(value(gi)).forEach { grade -> classes(value(ci)).forEach { cls ->
                val targets = if (cls == "全") known[grade]?.toList().orEmpty().also { require(it.isNotEmpty()) } else listOf(cls)
                targets.forEach { c ->
                    require(grade.matches(Regex("[1-9]|AI")) && c.matches(Regex("[A-Z0-9]{1,12}")))
                    changes += Change(date.toString(), canonicalClass("${grade}_$c"), period, before, after, value(ti), value(ri), note, raw)
                    require(changes.size <= 20000)
                }
            } }
        }
        require(changes.isNotEmpty() && changes.sumOf { it.raw.toByteArray().size } <= 16 * 1024 * 1024)
        if (warnings.isNotEmpty() && !preview) throw WeekdayWarning(warnings.distinct())
        return Analysis(MaterialKind.CHANGES, year, changes = changes, classes = changes.map { it.className }.distinct().sorted())
    }
    private fun richText(node: XmlNode): String = node.children.filter { it.name != "rPh" }.joinToString("") { if (it.name == "t") it.text else richText(it) }
    private fun grades(value: String): List<String> {
        val v = key(value); val range = Regex("([1-9])[-~〜～]([1-9])").matchEntire(v)
        return if (range == null) listOf(v) else { val a = range.groupValues[1].toInt(); val b = range.groupValues[2].toInt(); (if (a <= b) a..b else a downTo b).map(Int::toString) }
    }
    private fun classes(value: String) = key(value).split(Regex("[,、]"))
    fun date(value: String, year: Int): LocalDate {
        val v = normalized(value)
        v.toDoubleOrNull()?.let { require(it.isFinite() && it > 0 && it < 3000000); return LocalDate.of(1899, 12, 30).plusDays(floor(it).toLong()) }
        val full = Regex("([0-9]{4})[年/.-]([0-9]{1,2})[月/.-]([0-9]{1,2})日?").matchEntire(v)
        if (full != null) return LocalDate.of(full.groupValues[1].toInt(), full.groupValues[2].toInt(), full.groupValues[3].toInt())
        val short = Regex("([0-9]{1,2})[月/.-]([0-9]{1,2})日?").matchEntire(v) ?: error("日付を確認できません")
        val month = short.groupValues[1].toInt(); return LocalDate.of(year + if (month < 4) 1 else 0, month, short.groupValues[2].toInt())
    }
}
