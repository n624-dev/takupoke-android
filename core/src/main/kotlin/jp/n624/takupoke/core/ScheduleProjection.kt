package jp.n624.takupoke.core

import java.time.Instant
import java.time.LocalDate
import java.text.Normalizer

/** Display projections share the iOS rules; saved source strings stay intact. */
fun displayKana(value: String): String = Regex("[\uFF61-\uFF9F]+").replace(value) { Normalizer.normalize(it.value, Normalizer.Form.NFKC) }
fun displayContinuous(value: String): String = displayKana(value).replace("\r", "").replace("\n", "")
fun displayMetadata(value: String): String = displayContinuous(value.replace(Regex("[()（）]"), "").replace(Regex("\\s{2,}"), " ").trim())
fun displayClass(value: String): String = displayKana(canonicalClass(value)).replace('_', '-')

private val halfwidthKanaTable = (0xFF61..0xFF9F).map { it.toChar().toString() }.flatMap { a ->
    listOf(a) + listOf("ﾞ", "ﾟ").map { a + it }
}.associateBy { Normalizer.normalize(it, Normalizer.Form.NFKC) }
fun halfwidthKana(value: String): String = value.map { halfwidthKanaTable[it.toString()] ?: it.toString() }.joinToString("")

fun visibleLinks(payload: LinksPayload?, settings: Settings): List<LinkItem> = payload?.categories.orEmpty().sortedBy { it.sortOrder }.flatMap { it.buttons.sortedBy { b -> b.sortOrder } }.filter { it.visible && it.id !in settings.hidden }
fun recommendedLinks(payload: LinksPayload?, settings: Settings): List<LinkItem> {
    val collator = java.text.Collator.getInstance(java.util.Locale.JAPANESE)
    return visibleLinks(payload, settings).filter { it.recommended }.sortedWith(compareBy<LinkItem> { it.recommendationOrder }.thenBy { it.sortOrder }.thenComparator { a, b -> collator.compare(a.label, b.label) })
}

data class PositionedSlot(val slot: Slot, val lane: Int)
data class WeekBounds(val first: LocalDate, val last: LocalDate) {
    operator fun contains(week: LocalDate) = week >= first && week <= last
}

class ScheduleProjection(val analyses: List<Analysis>, val events: List<EventsPayload>, val mapping: Mapping?, val times: TimesPayload?, val includesChanges: Boolean, val international: Boolean) {
    fun eventsLoaded(day: LocalDate): Boolean = events.any { it.schoolYear == schoolYear(day) }
    fun weekEventsLoaded(monday: LocalDate): Boolean = (0..6).all { eventsLoaded(monday.plusDays(it.toLong())) }

    fun slots(day: LocalDate, cls: String) = Schedule.slots(day, cls, analyses, events, mapping, times, includesChanges, international)

    fun blocks(day: LocalDate, cls: String): List<PositionedSlot> {
        val source = slots(day, cls)
        val result = mutableListOf<Slot>()
        source.forEach { slot ->
            slot.lessons.forEach { lesson ->
                val effective = slot.changes.lastOrNull { it.type == "補講" } ?: slot.changes.lastOrNull()
                val previous = result.indexOfLast { old -> old.endPeriod == slot.period - 1 &&
                    if (effective != null) (old.changes.lastOrNull { it.type == "補講" } ?: old.changes.lastOrNull()) == effective
                    else old.changes.isEmpty() && old.lessons.single().let { it.kind == lesson.kind && it.names == lesson.names } }
                if (previous < 0) result += slot.copy(lessons = listOf(lesson))
                else {
                    val old = result[previous]
                    val first = source[old.period - 1].time; val last = slot.time
                    result[previous] = old.copy(endPeriod = slot.period, time = if (first != null && last != null) first.substringBefore('〜') + "〜" + last.substringAfter('〜') else null,
                        originals = (old.originals + slot.originals).distinct(), changes = (old.changes + slot.changes).distinct())
                }
            }
        }
        val laneEnds = mutableListOf<Int>()
        return result.sortedBy { it.period }.map { slot ->
            val lane = laneEnds.indexOfFirst { it < slot.period }.let { if (it < 0) laneEnds.size else it }
            if (lane == laneEnds.size) laneEnds += slot.endPeriod else laneEnds[lane] = slot.endPeriod
            PositionedSlot(slot, lane)
        }
    }

    fun displayedDays(monday: LocalDate, classes: List<String>): List<LocalDate> = (0..6).map { monday.plusDays(it.toLong()) }.filter { day ->
        day.dayOfWeek.value <= 5 || Schedule.events(day, events).any { it.tag in setOf("行事（授業なし）", "補講日") } || classes.any { cls -> blocks(day, cls).isNotEmpty() }
    }

    fun fullDayTitle(day: LocalDate, classes: List<String>): String? {
        val labels = Schedule.events(day, events).filter { it.tag in setOf("授業なし", "行事（授業なし）", "補講日") }.map { it.title.trim() }.distinct().sorted()
        return labels.takeIf { it.isNotEmpty() && classes.all { cls -> blocks(day, cls).isEmpty() } }?.joinToString("・")
    }

    fun headerTitles(day: LocalDate, classes: List<String>): List<String> {
        val fullDay = fullDayTitle(day, classes)
        return Schedule.events(day, events).filter { it.tag != "行事メモ" && (fullDay == null || it.tag !in setOf("授業なし", "行事（授業なし）", "補講日")) }.map { it.title }.distinct()
    }

    fun missing(day: LocalDate, cls: String): List<String> {
        val tags = Schedule.events(day, events).map { it.tag }
        val specials = analyses.filter { it.kind in listOf(MaterialKind.EXAM, MaterialKind.RETURN) && cls in it.classes && day.toString() in it.dates }
        return buildList {
            listOf(MaterialKind.EXAM to "テスト", MaterialKind.RETURN to "テスト返却").forEach { (kind, tag) ->
                if (tag in tags && specials.none { it.kind == kind }) add("${kind.title}：未公開または未解析です")
            }
            if (specials.isEmpty() && tags.none { it in setOf("授業なし", "行事（授業なし）", "補講日", "テスト", "テスト返却") }) {
                val normal = analyses.firstOrNull { it.kind == MaterialKind.TIMETABLE }
                when {
                    normal == null -> add("通常時間割の解析結果がありません。")
                    normal.term !in 1..2 -> add("通常時間割の学期を確認できません。再解析してください。")
                    retentionPeriod(day) != "${normal.schoolYear}-${normal.term}" -> add("今日に適用できる通常時間割がありません。")
                    normal.lessons.none { it.className == cls } -> add("このクラスの通常時間割がありません。")
                }
            }
        }
    }

    fun inProgress(day: LocalDate, slot: Slot, now: Instant): Boolean {
        if (slot.type == "休講" || day != now.atZone(schoolZone).toLocalDate()) return false
        val range = slot.time?.split('〜')?.takeIf { it.size == 2 } ?: return false
        val current = now.atZone(schoolZone).toLocalTime()
        return runCatching { current >= java.time.LocalTime.parse(range[0]) && current < java.time.LocalTime.parse(range[1]) }.getOrDefault(false)
    }

    fun weekBounds(reference: LocalDate, classes: List<String>): WeekBounds {
        val firstHalf = reference.monthValue in 4..9
        val start = LocalDate.of(schoolYear(reference), if (firstHalf) 4 else 10, 1)
        val end = start.plusMonths(6).minusDays(1)
        var last = maxOf(Schedule.monday(end), Schedule.week(reference))
        fun hasData(week: LocalDate) = classes.isNotEmpty() && (0..6).any { offset ->
            val day = week.plusDays(offset.toLong())
            Schedule.events(day, events).isNotEmpty() || classes.any { cls -> slots(day, cls).any { it.lessons.isNotEmpty() || it.originals.isNotEmpty() || it.changes.isNotEmpty() } }
        }
        while (last.year < 9998 && hasData(last.plusWeeks(1))) last = last.plusWeeks(1)
        return WeekBounds(Schedule.monday(start), last)
    }

    fun filteredChanges(classes: Set<String>, range: String, day: LocalDate, week: LocalDate): List<Change> = analyses.flatMap { it.changes }.filter {
        canonicalClass(it.className) in classes && shouldDisplay(it) && (range == "全件" || if (range == "この週") LocalDate.parse(it.date) >= week && LocalDate.parse(it.date) < week.plusWeeks(1) else LocalDate.parse(it.date) >= day)
    }.sortedWith(compareBy<Change> { it.date }.thenBy { it.period }.thenBy { canonicalClass(it.className) })

    private fun shouldDisplay(change: Change): Boolean {
        if (international) return true
        val cls = canonicalClass(change.className)
        val year = schoolYear(LocalDate.parse(change.date))
        return listOf(change.before, change.after).none { subject -> normalized(subject).startsWith("留") ||
            mapping?.international(mapping.separate(subject, cls, year).subject, cls) == true }
    }

    fun changeBefore(change: Change): Names {
        val cls = canonicalClass(change.className); val day = LocalDate.parse(change.date)
        val source = change.before.ifEmpty {
            Schedule.slots(day, cls, analyses, events, mapping, times, false, true).filter { it.period in change.gridPeriods() }
                .flatMap { it.originals }.filter { it.kind == MaterialKind.TIMETABLE || it.kind == null }.map { it.names.subject }.distinct().joinToString("・")
        }
        val names = mapping?.separate(source, cls, schoolYear(day)) ?: Names(source)
        return mapping?.apply(names, cls, schoolYear(day)) ?: names
    }

    fun changeDetail(change: Change): Slot {
        val day = LocalDate.parse(change.date); val cls = canonicalClass(change.className)
        val source = slots(day, cls)
        val values = change.periods().map { source[it - 1] }
        val inline = mapping?.separate(change.after, cls, schoolYear(day)) ?: Names(change.after)
        val conflict = change.teacher.isNotEmpty() && inline.teacher.isNotEmpty() && change.teacher != inline.teacher || change.room.isNotEmpty() && inline.room.isNotEmpty() && change.room != inline.room
        val names = if (conflict) Names(change.after, change.teacher, change.room) else inline.copy(teacher = change.teacher.ifEmpty { inline.teacher }, room = change.room.ifEmpty { inline.room })
        val ranges = mutableListOf<List<Slot>>()
        values.forEach { slot -> if (ranges.lastOrNull()?.last()?.period == slot.period - 1) ranges[ranges.lastIndex] = ranges.last() + slot else ranges += listOf(slot) }
        val clocks = ranges.map { group -> if (group.first().time == null || group.last().time == null) null else group.first().time!!.substringBefore('〜') + "〜" + group.last().time!!.substringAfter('〜') }
        return Slot(change.periods().firstOrNull() ?: 0, listOf(Lesson(cls, day.dayOfWeek.value, change.periods().firstOrNull() ?: 0, mapping?.apply(names, cls, schoolYear(day)) ?: names, change.raw, change.date)), listOf(change) + analyses.flatMap { it.changes }.filter { it != change && it.date == change.date && canonicalClass(it.className) == cls && it.gridPeriods().any { p -> p in change.gridPeriods() } }, change.type,
            clocks.takeIf { it.isNotEmpty() && it.all { t -> t != null } }?.joinToString("・"), values.filter { it.period in change.gridPeriods() }.flatMap { it.originals }, change.periods().lastOrNull() ?: 0, selectedChange = change, timeRanges = clocks.map { it ?: "未確認" })
    }
}
