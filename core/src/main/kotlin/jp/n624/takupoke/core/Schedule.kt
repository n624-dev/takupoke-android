package jp.n624.takupoke.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import java.time.LocalDate

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class Settings(val primaryClass: String = "", val additionalClass: String = "", val international: Boolean = false, @EncodeDefault(EncodeDefault.Mode.NEVER) val color: Int? = null, val inAppBrowser: Boolean = true, val favorites: Set<String> = emptySet(), val hidden: Set<String> = emptySet(), val linkColors: Map<String, String> = emptyMap(), val changeNotifications: Boolean = false, val examNotifications: Boolean = false, val setupComplete: Boolean = false, val notificationsSetupComplete: Boolean = false, val includesChanges: Boolean = true, val changeRange: String = "今日以降", val changeClasses: Set<String> = emptySet(), val useTimetableClasses: Boolean = true, val timetableFilter: String = "", val changesFilter: String = "", val timetableWeekdayFilter: Int = 0, val defaultSchoolYear: String = "") {
    fun notificationChoice(mode: String, allowed: Boolean): Settings = when (mode) {
        "changes" -> copy(changeNotifications = allowed || changeNotifications, notificationsSetupComplete = true)
        "exam" -> copy(examNotifications = allowed || examNotifications, notificationsSetupComplete = true)
        "setup" -> if (notificationsSetupComplete) this else copy(changeNotifications = allowed, examNotifications = allowed, notificationsSetupComplete = true)
        else -> error("Unknown notification choice")
    }
}
data class Slot(val period: Int, val lessons: List<Lesson>, val changes: List<Change>, val type: String, val time: String?, val originals: List<Lesson> = emptyList(), val endPeriod: Int = period, val selectedChange: Change? = null, val timeRanges: List<String> = emptyList())
object Schedule {
    val classes = listOf("1_1", "1_2", "1_3") + (1..5).flatMap { y -> listOf("CN", "ES", "IT").map { "${y}_$it" } } + listOf("AI_1", "AI_2")
    val normalTimes = listOf("08:50〜09:35", "09:35〜10:20", "10:30〜11:15", "11:15〜12:00", "12:50〜13:35", "13:35〜14:20", "14:30〜15:15", "15:15〜16:00")
    fun compatible(a: String, b: String): Boolean = (a in listOf("1_1", "1_2", "1_3") && b in listOf("1_CN", "1_ES", "1_IT")) || (b in listOf("1_1", "1_2", "1_3") && a in listOf("1_CN", "1_ES", "1_IT"))
    fun monday(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())
    fun week(day: LocalDate): LocalDate = if (day.dayOfWeek.value >= 6) monday(day).plusWeeks(1) else monday(day)
    fun events(day: LocalDate, payloads: List<EventsPayload>): List<Event> = payloads.flatMap { it.events }.filter { day >= LocalDate.parse(it.startDate) && day <= LocalDate.parse(it.endDate) }
    fun slots(day: LocalDate, cls: String, analyses: List<Analysis>, payloads: List<EventsPayload>, mapping: Mapping?, times: TimesPayload?, includeChanges: Boolean, international: Boolean): List<Slot> {
        val events = events(day, payloads)
        val tags = events.map { it.tag }
        val override = events.filter { it.tag == "曜日振替" }.map { "月火水木金".indexOf(it.title.trim()[0]) + 1 }.distinct().singleOrNull()
        val special = analyses.filter { it.kind in listOf(MaterialKind.EXAM, MaterialKind.RETURN) && cls in it.classes && day.toString() in it.dates }
        val suppress = tags.any { it in setOf("授業なし", "行事（授業なし）", "補講日", "テスト", "テスト返却") } || special.isNotEmpty()
        val ordinary = analyses.filter { it.kind == MaterialKind.TIMETABLE && it.term in 1..2 && day >= LocalDate.of(it.schoolYear, if (it.term == 1) 4 else 10, 1) && day < (if (it.term == 1) LocalDate.of(it.schoolYear, 10, 1) else LocalDate.of(it.schoolYear + 1, 4, 1)) }
        fun visible(subject: String): Boolean = international || !(normalized(subject).startsWith("留") || mapping?.international(mapping.separate(subject, cls, schoolYear(day)).subject, cls) == true)
        return (1..8).map { period ->
            val changes = if (includeChanges) analyses.flatMap { it.changes }.filter { it.date == day.toString() && canonicalClass(it.className) == cls && period in it.gridPeriods() } else emptyList()
            val visibleChanges = changes.filter { visible(it.before) && visible(it.after) }
            val effective = visibleChanges.lastOrNull { it.type == "補講" } ?: visibleChanges.lastOrNull()
            val base = if (suppress) emptyList() else ordinary.flatMap { it.lessons }.filter { it.className == cls && it.weekday == (override ?: day.dayOfWeek.value) && it.period == period && visible(it.names.subject) }
            val selected = special.flatMap { analysis -> analysis.lessons.map { it.copy(kind = analysis.kind) } }.filter { it.className == cls && it.date == day.toString() && it.period == period && visible(it.names.subject) }
            val updated = effective?.let { c -> val inline = mapping?.separate(c.after, cls, schoolYear(day)) ?: Names(c.after)
                val names = if ((c.teacher.isNotEmpty() && inline.teacher.isNotEmpty() && c.teacher != inline.teacher) || (c.room.isNotEmpty() && inline.room.isNotEmpty() && c.room != inline.room)) Names(c.after, c.teacher, c.room) else inline.copy(teacher = c.teacher.ifEmpty { inline.teacher }, room = c.room.ifEmpty { inline.room })
                listOf(Lesson(cls, day.dayOfWeek.value, period, names, c.raw, day.toString()))
            }
            val lessons = (updated ?: if (changes.isEmpty()) (selected + base) else emptyList()).map { it.copy(names = mapping?.apply(it.names, cls, if (effective != null) schoolYear(day) else null) ?: it.names) }
            // Ambiguous/missing PDF times must never fall back to a normal/API time.
            val selectedTimes = selected.map { it.time }
            val specialTime = if (selected.isNotEmpty()) selectedTimes.takeIf { it.all { t -> t != null } }?.distinct()?.singleOrNull() else {
                val clocks = special.map { analysis -> analysis.specialTimes.firstOrNull { it.date == day.toString() }?.periods?.firstOrNull { it.period == period }?.let { "${it.start}〜${it.end}" } }
                clocks.takeIf { it.isNotEmpty() && it.all { t -> t != null } }?.distinct()?.singleOrNull()
            }
            val normalAllowed = special.isEmpty() && "テスト" !in tags && "テスト返却" !in tags
            val time = if (normalAllowed) times?.days?.firstOrNull { it.date == day.toString() }?.periods?.firstOrNull { it.period == period }?.let { "${it.start}〜${it.end}" } ?: normalTimes[period - 1] else specialTime
            val originals = (selected + base).map { it.copy(names = mapping?.apply(it.names, cls) ?: it.names) }
            Slot(period, lessons, visibleChanges, effective?.type ?: if (selected.isNotEmpty()) (if (special.any { it.kind == MaterialKind.EXAM }) "試験" else "返却") else "通常", time, originals, selectedChange = effective)

        }
    }
    fun changedSlots(before: List<Change>?, after: List<Change>, cls: String, day: LocalDate): Int {
        if (before == null || cls.isEmpty()) return 0
        // iOS counts one original date/class/period string, including unsupported grid notation.
        fun grouped(values: List<Change>) = values.filter { canonicalClass(it.className) == cls && LocalDate.parse(it.date) >= day }
            .groupBy { "${it.date}:${it.periods().takeIf { p -> p.isNotEmpty() }?.joinToString(",") ?: it.period}" }.mapValues { (_, rows) -> rows.map { it.copy(raw = "", className = canonicalClass(it.className), period = it.periods().takeIf { p -> p.isNotEmpty() }?.joinToString(",") ?: it.period) }.toSet() }
        val old = grouped(before); val new = grouped(after); return (old.keys + new.keys).count { old[it] != new[it] }

    }
}
