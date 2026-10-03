package jp.n624.takupoke.core

import java.time.LocalDate
import kotlin.math.floor

class WeekdayWarning(val rows: List<Int>) : IllegalArgumentException("曜日の計算結果を確認できません。行: ${rows.joinToString()}")
class XlsxFailure(val row: Int?, val reason: String) : IllegalArgumentException((row?.let { "${it}行目：" } ?: "") + reason + "前回の正常な解析結果は保持しています。")
object XlsxParser {
    private const val spreadsheet = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val relationships = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val documentRelationships = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private inline fun <T> checked(row: Int? = null, reason: String, block: () -> T): T = try { block() }
        catch (e: InterruptedException) { throw e } catch (e: XlsxFailure) { throw e } catch (e: WeekdayWarning) { throw e }
        catch (_: Exception) { throw XlsxFailure(row, reason) }

    fun parse(bytes: ByteArray, year: Int, preview: Boolean = false): Analysis {
        require(year in 1900..9998)
        return checked(reason = "XLSXの構造を読み取れません。") { Archives.withArchive(bytes) { archive -> parseArchive(archive, year, preview) } }
    }
    private fun parseArchive(archive: Archives.Archive, year: Int, preview: Boolean): Analysis {
        require(archive.names.none { it.contains("externalLinks") || it.endsWith("vbaProject.bin") })
        fun xml(name: String, root: String, namespace: String = spreadsheet) = checked(reason = "XMLの構造・名前空間を確認できません。") { SafeXml.parse(archive.read(name, 8 * 1024 * 1024), root, namespace) }
        val workbook = xml("xl/workbook.xml", "workbook")
        checked(reason = "1904年起点の日付・外部参照には対応していません。") {
            require(workbook.child("workbookPr")?.attrs?.get("date1904")?.let { it in listOf("0", "false") } != false)
            require(workbook.child("externalReferences") == null)
        }
        val sheet = checked(reason = "時間割変更シートを確認できません。") { requireNotNull(workbook.child("sheets")).children.single { it.namespace == spreadsheet && it.name == "sheet" && it.attrs["name"] == "時間割変更" } }
        val rels = xml("xl/_rels/workbook.xml.rels", "Relationships", relationships).children.filter { it.name == "Relationship" && it.namespace == relationships }
        val path = checked(reason = "シートの内部参照を確認できません。") {
            val id = requireNotNull(sheet.attrs["{$documentRelationships}id"])
            val selected = rels.single { it.attrs["Id"] == id }
            require(selected.attrs["Type"] == "$documentRelationships/worksheet" && selected.attrs.getOrDefault("TargetMode", "Internal") == "Internal")
            val target = requireNotNull(selected.attrs["Target"])
            require(target.none { it in ":%\\?#" })
            (if (target.startsWith('/')) target.drop(1) else "xl/$target").also { require(it.split('/').none { p -> p in listOf("..", ".", "") }) }
        }
        val strings = if ("xl/sharedStrings.xml" in archive.names) xml("xl/sharedStrings.xml", "sst").children.filter { it.name == "si" && it.namespace == spreadsheet }.map(::richText) else emptyList()
        require(strings.size <= 50000 && strings.all { it.toByteArray().size <= 4096 })
        val worksheet = xml(path, "worksheet"); val rows = linkedMapOf<Int, MutableMap<Int, Pair<String, Boolean>>>(); var cellCount = 0
        val sheetData = checked(reason = "シートの表領域を確認できません。") { requireNotNull(worksheet.child("sheetData")) }
        var totalCellBytes = 0L
        sheetData.children.filter { it.name == "row" && it.namespace == spreadsheet }.forEach { row ->
            checked(row.attrs["r"]?.toIntOrNull(), "セルの位置・型・文字数を確認できません。") {
            val number = requireNotNull(row.attrs["r"]).toInt(); require(number in 1..10000 && number > (rows.keys.lastOrNull() ?: 0))
            val cells = linkedMapOf<Int, Pair<String, Boolean>>()
            row.children.filter { it.name == "c" && it.namespace == spreadsheet }.forEach { cell ->
                require(++cellCount <= 100000)
                val ref = Regex("([A-Z]{1,2})([0-9]+)").matchEntire(requireNotNull(cell.attrs["r"])) ?: error("セル位置を確認できません")
                require(ref.groupValues[2].toInt() == number)
                val col = ref.groupValues[1].fold(0) { a, c -> a * 26 + c.code - 64 } - 1
                require(col in 0..127 && col !in cells && cell.children.count { it.name == "v" } <= 1 && cell.children.count { it.name == "f" } <= 1)
                val raw = cell.child("v")?.text.orEmpty()
                val value = when (cell.attrs["t"]) {
                    "s" -> strings[raw.toInt()]
                    "inlineStr" -> cell.child("is")?.let(::richText).orEmpty()
                    "b" -> when (raw) { "1" -> "TRUE"; "0" -> "FALSE"; else -> error("セル型を確認できません") }
                    "e" -> if (cell.child("f") != null) "" else error("セルにエラーがあります")
                    null, "n", "str", "d" -> raw
                    else -> if (cell.child("f") != null) "" else error("未対応のセル型です")
                }
                totalCellBytes += value.toByteArray().size; require(value.toByteArray().size <= 4096 && totalCellBytes <= 16L * 1024 * 1024); cells[col] = normalized(value) to (cell.child("f") != null)
            }
            rows[number] = cells
            }
        }
        val headerEntry = rows.entries.firstOrNull { e -> setOf("学 年", "学科・クラス", "月日").all { wanted -> e.value.values.any { normalized(it.first) == wanted && !it.second } } } ?: throw XlsxFailure(null, "必要な見出し（学 年・学科・クラス・月日）を確認できません。")
        val width = headerEntry.value.keys.max() + 1
        val headers = (0 until width).map { headerEntry.value[it]?.first.orEmpty() }
        checked(headerEntry.key, "見出しの重複・数式を確認できません。") { require(headers.filter(String::isNotEmpty).distinct().size == headers.count(String::isNotEmpty) && headerEntry.value.values.none { it.second }) }
        worksheet.descendants("mergeCell").forEach { m ->
            val nums = Regex("[0-9]+").findAll(m.attrs["ref"].orEmpty()).map { it.value.toInt() }.toList()
            checked(nums.maxOrNull(), "見出し以降の結合セルには対応していません。") { require(nums.isNotEmpty() && nums.max() < headerEntry.key) }
        }
        fun index(vararg aliases: String): Int = headers.indexOfFirst { key(it) in aliases.map(::key) }
        val gi = headers.indexOf("学 年"); val ci = index("学科・クラス"); val di = index("月日")
        val pi = index("時限", "校時", "時間", "限")
        val bi = index("変更前", "変更前科目", "旧科目", "変更元"); val ai = index("変更後", "変更後科目", "新科目", "変更先")
        val ti = index("教員", "担当", "担当教員", "担任", "教官"); val ri = index("教室", "場所"); val ni = index("備考", "連絡", "メモ", "その他")
        val ki = index("変更内容", "変更種別", "種別"); val si = index("科目(担当教員)", "科目・担当教員", "科目")
        val data = rows.filterKeys { it > headerEntry.key }.filterValues { it.values.any { p -> p.first.isNotEmpty() } }
        val known = mutableMapOf<String, MutableSet<String>>()
        data.values.forEach { row -> grades(row[gi]?.first.orEmpty()).forEach { grade -> classes(row[ci]?.first.orEmpty()).filter { it != "全" && it != "AI" }.forEach { known.getOrPut(grade) { sortedSetOf() } += it } } }
        val warnings = mutableListOf<Int>(); val changes = mutableListOf<Change>()
        data.forEach { (number, row) ->
            checked(number, "行の学年・クラス・時限・数式を確認できません。") {
            interrupted(); require(row.filterKeys { it >= width }.values.none { it.first.isNotEmpty() })
            fun value(i: Int): String = row[i]?.first.orEmpty().let { if (it.matches(Regex("-?[0-9]+\\.0+"))) it.substringBefore('.') else it }
            val date = checked(number, "月日の暦日・年度を確認できません。") { date(value(di), year) }
            row.filterValues { it.second }.forEach { (i, pair) -> checked(number, "曜日以外の数式には対応していません。") { require(key(headers[i]) in setOf("曜日", "曜")) }
                val weekday = "月火水木金土日"[date.dayOfWeek.value - 1].toString()
                if (key(pair.first).removeSuffix("曜日").removeSuffix("曜").removePrefix("(").removeSuffix(")") != weekday) warnings += number
            }
            var before = value(bi); var after = value(ai); val type = value(ki); val note = value(ni).ifEmpty { type }
            if (before.isEmpty() && after.isEmpty()) { if (type == "休講") before = value(si) else after = value(si) }
            val period = value(pi).removeSuffix("時限").removeSuffix("限目").removeSuffix("限")
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
        }
        checked(reason = "解析結果が空か、件数・文字数の上限を超えています。") { require(changes.isNotEmpty() && changes.sumOf { listOf(it.date, it.className, it.period, it.before, it.after, it.teacher, it.room, it.note, it.raw).sumOf { v -> v.toByteArray().size.toLong() } } <= 16L * 1024 * 1024) }
        if (warnings.isNotEmpty() && !preview) throw WeekdayWarning(warnings.distinct())
        return Analysis(MaterialKind.CHANGES, year, changes = changes, classes = changes.map { it.className }.distinct().sorted(), warningRows = if (preview) warnings.distinct() else emptyList())
    }
    private fun richText(node: XmlNode): String = node.children.filter { it.namespace == node.namespace && it.name != "rPh" }.joinToString("") { if (it.name == "t") it.text else richText(it) }
    private fun grades(value: String): List<String> {
        val v = key(value); val range = Regex("([1-9])[-~〜～]([1-9])").matchEntire(v)
        return if (range == null) listOf(v) else { val a = range.groupValues[1].toInt(); val b = range.groupValues[2].toInt(); (if (a <= b) a..b else a downTo b).map(Int::toString) }
    }
    private fun classes(value: String) = key(value).split(Regex("[,、]"))
    fun date(value: String, year: Int): LocalDate {
        val v = normalized(value).replace(Regex("\\s+"), "")
        v.toDoubleOrNull()?.let { require(it.isFinite() && it > 0 && it < 3000000); return LocalDate.of(1899, 12, 30).plusDays(floor(it).toLong()).also { d -> require(d.year in 1900..9999) } }
        val full = Regex("([0-9]{4})[年/.-]([0-9]{1,2})[月/.-]([0-9]{1,2})日?").matchEntire(v)
        if (full != null) return LocalDate.of(full.groupValues[1].toInt().also { require(it in 1900..9999) }, full.groupValues[2].toInt(), full.groupValues[3].toInt())
        val short = Regex("([0-9]{1,2})[月/.-]([0-9]{1,2})日?").matchEntire(v) ?: error("日付を確認できません")
        val month = short.groupValues[1].toInt(); return LocalDate.of(year + if (month < 4) 1 else 0, month, short.groupValues[2].toInt())
    }
}
