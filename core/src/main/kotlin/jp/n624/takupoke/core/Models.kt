package jp.n624.takupoke.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId

val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
val schoolZone: ZoneId = ZoneId.of("Asia/Tokyo")
fun today(): LocalDate = LocalDate.now(schoolZone)
fun schoolYear(day: LocalDate = today()): Int = day.year - if (day.monthValue < 4) 1 else 0
fun retentionPeriod(day: LocalDate = today()): String = "${schoolYear(day)}-${if (day.monthValue in 4..9) 1 else 2}"
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).replace("\r\n", "\n").replace('\r', '\n').replace(Regex("[\\t \\u00a0]+"), " ").replace(Regex("\\n+"), "\n").trim()
fun key(text: String): String = normalized(text).replace(Regex("\\s+"), "").uppercase()
fun canonicalClass(name: String): String = Regex("^([1-9])_AI$").matchEntire(name)?.let { "AI_${it.groupValues[1]}" } ?: name

@Serializable enum class MaterialKind(val title: String, val extension: String) {
    TIMETABLE("通常時間割", "pdf"), CHANGES("時間割変更", "xlsx"), EXAM("試験時間割", "pdf"), RETURN("試験返却時間割", "pdf")
}
@Serializable data class Names(val subject: String, val teacher: String = "", val room: String = "", val subjectFull: String = "", val teacherFull: String = "", val roomFull: String = "")
@Serializable data class Lesson(val className: String, val weekday: Int, val period: Int, val names: Names, val sourceText: String = "", val date: String? = null, val spanStart: Int = period, val spanEnd: Int = period, val time: String? = null)
const val PARSER_VERSION = 2
@Serializable data class Analysis(val kind: MaterialKind, val schoolYear: Int, val term: Int = 0, val lessons: List<Lesson> = emptyList(), val changes: List<Change> = emptyList(), val dates: List<String> = emptyList(), val classes: List<String> = emptyList(), val parserVersion: Int = PARSER_VERSION, val specialTimes: List<DayTimes> = emptyList())
@Serializable data class Change(val date: String, val className: String, val period: String, val before: String, val after: String, val teacher: String = "", val room: String = "", val note: String = "", val raw: String = "") {
    fun periods(): List<Int> {
        val value = key(period).removeSuffix("時限").removeSuffix("限目").removeSuffix("限")
        if (value.matches(Regex("[1-8]"))) return listOf(value.toInt())
        if (value.matches(Regex("[1-8](?:[,、・][1-8])+"))) return value.split(Regex("[,、・]")).map(String::toInt).distinct()
        val range = Regex("([1-8])[-~〜～]([1-8])").matchEntire(value) ?: return emptyList()
        val a = range.groupValues[1].toInt(); val b = range.groupValues[2].toInt()
        return if (a <= b) (a..b).toList() else emptyList()
    }
}
@Serializable data class Event(val startDate: String, val endDate: String, val title: String, val tag: String)
@Serializable data class EventsPayload(val version: String, val schoolYear: Int, val sourcePdfSha256: String, val sourcePdfETag: String? = null, val events: List<Event>) {
    fun validate(year: Int): EventsPayload {
        require(version == "v1" && schoolYear == year && sourcePdfSha256.matches(Regex("[a-f0-9]{64}")))
        require(events.isNotEmpty() && events.size <= 2000)
        val tags = setOf("授業なし", "曜日振替", "補講日", "行事（授業なし）", "行事（授業あり）", "行事", "行事（時間割変更）", "テスト", "テスト返却", "行事メモ")
        events.forEach { e -> val start = LocalDate.parse(e.startDate); val end = LocalDate.parse(e.endDate)
            require(start >= LocalDate.of(year, 4, 1) && end <= LocalDate.of(year + 1, 3, 31) && end >= start)
            require(e.tag in tags && e.title.isNotBlank() && e.title.length <= 200)
            if (e.tag == "曜日振替") require(e.title.matches(Regex("[月火水木金]曜日授業")))
        }
        return this
    }
}
@Serializable data class LinkItem(val id: String, val categoryId: String, val label: String, val href: String, val color: String, val visible: Boolean, val sortOrder: Int, val recommended: Boolean, val recommendationOrder: Int, val searchAliases: List<String>, val searchTerms: String)
@Serializable data class LinkCategory(val id: String, val label: String, val sortOrder: Int, val buttons: List<LinkItem>)
@Serializable data class LinksPayload(val version: String, val linksVersion: String, val categories: List<LinkCategory>) {
    fun validate(): LinksPayload {
        require(version == "v1" && linksVersion.matches(Regex("sha256-[a-f0-9]{64}")))
        require(categories.isNotEmpty() && categories.size <= 100 && categories.map { it.id }.distinct().size == categories.size)
        val items = categories.flatMap { it.buttons }; require(items.size <= 800 && items.map { it.id }.distinct().size == items.size)
        categories.forEach { c -> require(c.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && c.label.length in 1..80)
            c.buttons.forEach { b ->
                require(b.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && b.categoryId == c.id && b.label.length in 1..80)
                require(validLink(b.href) && b.color in linkColorNames && b.searchAliases.size <= 20 && b.searchAliases.all { it.length in 1..80 } && b.searchTerms.length in 1..5000)
            }
        }
        return this
    }
}
val linkColorNames = listOf("sky", "blue", "emerald", "green", "amber", "yellow", "orange", "rose", "red", "indigo", "purple", "pink", "teal", "slate", "gray")
fun validLink(value: String): Boolean = runCatching {
    require(value.length <= 2048 && value.none { it.isWhitespace() || it.isISOControl() || it == '\\' })
    val uri = java.net.URI(value)
    uri.scheme == "jrshikoku" || (uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
}.getOrDefault(false)
@Serializable data class PeriodTime(val period: Int, val start: String, val end: String)
@Serializable data class DayTimes(val date: String, val periods: List<PeriodTime>)
@Serializable data class TimesPayload(val schemaVersion: Int, val days: List<DayTimes>) {
    fun validate(): TimesPayload {
        require(schemaVersion == 1 && days.size <= 400 && days.map { it.date }.distinct().size == days.size)
        days.forEach { d -> require(LocalDate.parse(d.date).toString() == d.date && d.periods.size == 8)
            var previous = "00:00"
            d.periods.forEachIndexed { i, p -> require(p.period == i + 1 && p.start.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) && p.end.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) && p.start >= previous && p.end > p.start); previous = p.end }
        }
        return this
    }
}
