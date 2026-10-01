package jp.n624.takupoke.core

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable data class Settings(val primaryClass: String = "", val additionalClass: String = "", val international: Boolean = false, val color: Int = 0, val inAppBrowser: Boolean = true, val favorites: Set<String> = emptySet(), val hidden: Set<String> = emptySet(), val linkColors: Map<String, String> = emptyMap(), val changeNotifications: Boolean = false, val examNotifications: Boolean = false, val setupComplete: Boolean = false)
data class Slot(val period: Int, val lessons: List<Lesson>, val changes: List<Change>, val type: String, val time: String?)
object Schedule {
    val classes = listOf("1_1", "1_2", "1_3") + (1..5).flatMap { y -> listOf("CN", "ES", "IT").map { "${y}_$it" } } + listOf("AI_1", "AI_2")
    val normalTimes = listOf("08:50〜09:35", "09:35〜10:20", "10:30〜11:15", "11:15〜12:00", "12:50〜13:35", "13:35〜14:20", "14:30〜15:15", "15:15〜16:00")
    fun compatible(a: String, b: String): Boolean = (a in listOf("1_1", "1_2", "1_3") && b in listOf("1_CN", "1_ES", "1_IT")) || (b in listOf("1_1", "1_2", "1_3") && a in listOf("1_CN", "1_ES", "1_IT"))
    fun week(day: LocalDate): LocalDate = if (day.dayOfWeek.value >= 6) day.plusDays((8 - day.dayOfWeek.value).toLong()) else day.minusDays((day.dayOfWeek.value - 1).toLong())
    fun events(day: LocalDate, payloads: List<EventsPayload>): List<Event> = payloads.flatMap { it.events }.filter { day >= LocalDate.parse(it.startDate) && day <= LocalDate.parse(it.endDate) }
    fun slots(day: LocalDate, cls: String, analyses: List<Analysis>, payloads: List<EventsPayload>, mapping: Mapping?, times: TimesPayload?, includeChanges: Boolean, international: Boolean): List<Slot> {
        val events = events(day, payloads)
        val tags = events.map { it.tag }
        val override = events.filter { it.tag == "曜日振替" }.map { "月火水木金".indexOf(it.title[0]) + 1 }.distinct().singleOrNull()
        val special = analyses.filter { it.kind in listOf(MaterialKind.EXAM, MaterialKind.RETURN) && cls in it.classes && day.toString() in it.dates }
        val suppress = tags.any { it in setOf("授業なし", "行事（授業なし）", "補講日", "テスト", "テスト返却") } || special.isNotEmpty()
        val ordinary = analyses.filter { it.kind == MaterialKind.TIMETABLE && day >= LocalDate.of(it.schoolYear, if (it.term == 1) 4 else 10, 1) && day < (if (it.term == 1) LocalDate.of(it.schoolYear, 10, 1) else LocalDate.of(it.schoolYear + 1, 4, 1)) }
        fun visible(subject: String): Boolean = international || !(mapping?.international(subject, cls) ?: normalized(subject).startsWith("留"))
        return (1..8).map { period ->
            val changes = if (includeChanges) analyses.flatMap { it.changes }.filter { it.date == day.toString() && it.className == cls && period in it.periods() && visible(it.before) && visible(it.after) } else emptyList()
            val effective = changes.lastOrNull { it.note.contains("補講") } ?: changes.lastOrNull()
            val base = if (suppress) emptyList() else ordinary.flatMap { it.lessons }.filter { it.className == cls && it.weekday == (override ?: day.dayOfWeek.value) && it.period == period && visible(it.names.subject) }
            val selected = special.flatMap { it.lessons }.filter { it.className == cls && it.date == day.toString() && it.period == period && visible(it.names.subject) }
            val updated = effective?.let { c -> val inline = mapping?.separate(c.after, cls, schoolYear(day)) ?: Names(c.after)
                val names = if ((c.teacher.isNotEmpty() && inline.teacher.isNotEmpty() && c.teacher != inline.teacher) || (c.room.isNotEmpty() && inline.room.isNotEmpty() && c.room != inline.room)) Names(c.after, c.teacher, c.room) else inline.copy(teacher = c.teacher.ifEmpty { inline.teacher }, room = c.room.ifEmpty { inline.room })
                listOf(Lesson(cls, day.dayOfWeek.value, period, names.copy(subject = names.subject.ifEmpty { "休講" }), c.raw, day.toString()))
            }
            val lessons = (updated ?: (selected + base)).map { it.copy(names = mapping?.apply(it.names, cls, schoolYear(day)) ?: it.names) }
            val specialTime = selected.mapNotNull { it.time }.distinct().singleOrNull()
            val hasUnresolvedSpecialTime = selected.isNotEmpty() && specialTime == null
            val normalAllowed = selected.isEmpty() && "テスト" !in tags && "テスト返却" !in tags
            val time = specialTime ?: if (hasUnresolvedSpecialTime) null else times?.days?.firstOrNull { it.date == day.toString() }?.periods?.firstOrNull { it.period == period }?.let { "${it.start}〜${it.end}" } ?: if (normalAllowed) normalTimes[period - 1] else null
            Slot(period, lessons, changes, if (changes.isNotEmpty()) "変更" else if (selected.isNotEmpty()) (if (special.any { it.kind == MaterialKind.EXAM }) "試験" else "返却") else "通常", time)
        }
    }
    fun changedSlots(before: List<Change>?, after: List<Change>, cls: String, day: LocalDate): Int {
        if (before == null || cls.isEmpty()) return 0
        fun grouped(values: List<Change>) = values.filter { it.className == cls && LocalDate.parse(it.date) >= day }.flatMap { c -> c.periods().map { "${c.date}:$it" to c } }.groupBy({ it.first }, { it.second }).mapValues { (_, rows) -> rows.map { it.copy(raw = "") }.sortedBy { json.encodeToString(Change.serializer(), it) } }
        val old = grouped(before); val new = grouped(after); return (old.keys + new.keys).count { old[it] != new[it] }
    }
}
