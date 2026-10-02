package jp.n624.takupoke.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

/** Independent behavioral cases from takupoke-ios bbd2bd7 and its documented contracts. */
class IosParityTest {
    private val day = LocalDate.parse("2026-10-01")
    private val cls = "1_CN"
    private val normal = Analysis(MaterialKind.TIMETABLE, 2026, 2, (1..3).map { Lesson(cls, 4, it, Names("架空科目A", "架空教員A", "架空教室A")) }, classes = listOf(cls))
    private fun projection(a: List<Analysis> = listOf(normal), e: List<Event> = emptyList(), t: TimesPayload? = null) = ScheduleProjection(a, if (e.isEmpty()) emptyList() else listOf(EventsPayload("v1", 2026, "a".repeat(64), events = e)), null, t, true, false)
    @Test fun defaultColorIsAbsentAndExistingBlueRemainsExplicit() {
        assertNull(Settings().color)
        assertFalse(json.encodeToString(Settings.serializer(), Settings()).contains("\"color\""))
        assertEquals(0, json.decodeFromString<Settings>("{\"color\":0}").color)
        assertEquals(6, json.decodeFromString<Settings>("{\"color\":6}").color)
        assertNull(json.decodeFromString<Settings>("{}").color)
    }
    @Test fun gridAcceptsOnlyOrderedConsecutivePeriodsButDetailKeepsOtherRows() {
        fun c(period: String) = Change(day.toString(), cls, period, "架空科目A", "架空科目B")
        assertEquals(listOf(1, 2), c("１，２").gridPeriods())
        assertEquals(listOf(1, 2), c("1～2").gridPeriods())
        listOf("1,3", "2,1", "1,1", "1-2", "1・2", "1~1", "不明").forEach { assertTrue(c(it).gridPeriods().isEmpty(), it) }
        assertEquals(listOf(1, 3), c("1,3").periods())
        val unsupported = c("1,3")
        val p = projection(listOf(normal, Analysis(MaterialKind.CHANGES, 2026, changes = listOf(unsupported))))
        assertEquals("架空科目A", p.slots(day, cls)[0].lessons.single().names.subject)
        assertEquals(listOf(unsupported), p.filteredChanges(setOf(cls), "全件", day, Schedule.monday(day)))
        assertEquals("08:50〜09:35・10:30〜11:15", p.changeDetail(unsupported).time)
        assertEquals("09:35〜10:20・08:50〜09:35", p.changeDetail(unsupported.copy(period = "2,1")).time)
    }
    @Test fun cancellationMakeupAndEmptyChangesKeepKindsAndOriginals() {
        val cancel = Change(day.toString(), cls, "1,2", "", "", note = "休講")
        val makeup = cancel.copy(after = "架空科目B", note = "補講")
        val misleading = makeup.copy(after = "架空科目C", note = "補講の相談")
        val p = projection(listOf(normal, Analysis(MaterialKind.CHANGES, 2026, changes = listOf(cancel, makeup, misleading))))
        val slot = p.slots(day, cls)[0]
        assertEquals("補講", slot.type); assertEquals("架空科目B", slot.lessons.single().names.subject)
        assertEquals("架空科目A", slot.originals.single().names.subject)
        assertEquals(3, slot.changes.size)
        assertEquals("架空科目A", p.changeBefore(cancel).subject)
        val onlyCancellation = projection(listOf(normal, Analysis(MaterialKind.CHANGES, 2026, changes = listOf(cancel))))
        assertEquals("休講", onlyCancellation.blocks(day, cls)[0].slot.type)
        assertEquals(2, onlyCancellation.blocks(day, cls)[0].slot.endPeriod)
        assertFalse(onlyCancellation.inProgress(day, onlyCancellation.blocks(day, cls)[0].slot, Instant.parse("2026-10-01T00:00:00Z")))
        val blank = projection(listOf(normal, Analysis(MaterialKind.CHANGES, 2026, changes = listOf(cancel.copy(note = "要確認")))))
        assertEquals("変更", blank.slots(day, cls)[0].type); assertEquals("", blank.slots(day, cls)[0].lessons.single().names.subject)
    }
    @Test fun blocksMergeNeighborsAndKeepParallelLessonsInSeparateLanes() {
        val alternate = normal.lessons[0].copy(names = Names("架空科目B"))
        val p = projection(listOf(normal.copy(lessons = normal.lessons + alternate)))
        val blocks = p.blocks(day, cls)
        assertEquals(2, blocks.size); assertEquals(listOf(0, 1), blocks.map { it.lane })
        assertEquals(3, blocks[0].slot.endPeriod); assertEquals("08:50〜11:15", blocks[0].slot.time)
        assertEquals(4, p.analyses.single().lessons.size)
        assertTrue(p.inProgress(day, blocks[0].slot, Instant.parse("2026-09-30T23:50:00Z")))
        assertFalse(p.inProgress(day, blocks[0].slot, Instant.parse("2026-10-01T02:15:00Z")))
    }
    @Test fun unknownSemesterNeverApplies() {
        val p = projection(listOf(normal.copy(term = 0)))
        assertTrue(p.slots(day, cls).all { it.lessons.isEmpty() })
        assertTrue(p.missing(day, cls).single().contains("学期"))
    }
    @Test fun overlappingSpecialTimesCannotUseNormalOrApiFallback() {
        val exam = Analysis(MaterialKind.EXAM, 2026, lessons = listOf(Lesson(cls, 4, 1, Names("架空試験A"), date = day.toString(), time = "09:00〜10:00")), dates = listOf(day.toString()), classes = listOf(cls))
        val returned = exam.copy(kind = MaterialKind.RETURN, lessons = exam.lessons.map { it.copy(time = "11:00〜12:00") })
        val periods = Schedule.normalTimes.mapIndexed { i, range -> PeriodTime(i + 1, range.substringBefore('〜'), range.substringAfter('〜')) }
        val api = TimesPayload(1, listOf(DayTimes(day.toString(), periods)))
        val p = projection(listOf(normal, exam, returned), t = api)
        assertEquals(2, p.slots(day, cls)[0].lessons.size); assertNull(p.slots(day, cls)[0].time)
        assertNull(p.slots(day, cls)[1].time)
        val missingPdf = projection(listOf(normal), listOf(Event(day.toString(), day.toString(), "架空試験日", "テスト")), api)
        assertNull(missingPdf.slots(day, cls)[0].time); assertTrue(missingPdf.missing(day, cls).single().contains("未解析"))
    }
    @Test fun weekendRulesMemoAndFullDayCards() {
        val monday = Schedule.monday(day)
        val saturday = monday.plusDays(5); val sunday = monday.plusDays(6)
        fun e(date: LocalDate, title: String, tag: String) = Event(date.toString(), date.toString(), title, tag)
        val p = projection(e = listOf(e(saturday, "架空メモ", "行事メモ"), e(sunday, "架空補講日", "補講日"), e(day, "架空休業", "授業なし"), e(day, "架空メモ", "行事メモ")))
        assertFalse(saturday in p.displayedDays(monday, listOf(cls))); assertTrue(sunday in p.displayedDays(monday, listOf(cls)))
        assertEquals("架空休業", p.fullDayTitle(day, listOf(cls))); assertTrue(p.headerTitles(day, listOf(cls)).isEmpty())
        assertEquals("架空補講日", p.fullDayTitle(sunday, listOf(cls)))
        assertEquals(monday.plusWeeks(1), Schedule.week(saturday)); assertEquals(monday, Schedule.monday(saturday))
    }
    @Test fun navigationStaysContiguousAtSemesterBoundary() {
        val p = projection()
        val b = p.weekBounds(day, listOf(cls))
        assertEquals(LocalDate.parse("2026-09-28"), b.first)
        assertFalse(LocalDate.parse("2026-09-21") in b)
        assertEquals(LocalDate.parse("2027-03-29"), b.last)
        val nextWeekChange = Change("2027-04-05", cls, "1", "", "架空科目B")
        val laterChange = nextWeekChange.copy(date = "2027-04-19")
        val extended = projection(listOf(normal, Analysis(MaterialKind.CHANGES, 2026, changes = listOf(nextWeekChange, laterChange)))).weekBounds(day, listOf(cls))
        assertEquals(LocalDate.parse("2027-04-05"), extended.last)
    }
    @Test fun notificationCountsCanonicalOriginalSlotsAndIgnoresDuplicatesAndOrder() {
        val c = Change(day.toString(), cls, "1～2", "架空科目A", "架空科目B")
        assertEquals(1, Schedule.changedSlots(emptyList(), listOf(c), cls, day))
        assertEquals(0, Schedule.changedSlots(listOf(c), listOf(c.copy(period = "1,2", raw = "架空レイアウト変更"), c), cls, day))
        assertEquals(2, Schedule.changedSlots(listOf(c), listOf(c.copy(period = "1,3")), cls, day)) // two period strings differ: removal + addition
    }
    @Test fun kanaDisplayAndMappingsKeepSourceAndAmbiguity() {
        assertEquals("架空ゴ教室１２", displayKana("架空ｺﾞ教室１２"))
        assertEquals("架空ゴ", displayMetadata("（架空ｺﾞ）"))
        val m = Mapping(listOf(MappingRule("留 架空科目A", "架空留学生科目A", internationalStudent = true), MappingRule("架空科目A", "架空通常科目A", listOf(cls))), emptyList(), emptyList())
        assertFalse(m.international("架空科目A", cls)); assertTrue(m.international("架空科目A", "2_CN"))
        val old = Names("未登録", subjectFull = "架空保存名")
        assertEquals(old, m.apply(old, cls))
    }
    @Test fun recommendationsHaveStableIosTieBreaksAndHideBothKinds() {
        fun link(id: String, label: String, order: Int, recommended: Int = 0, visible: Boolean = true) = LinkItem(id, "fixture", label, "https://example.invalid", "blue", visible, order, true, recommended, emptyList(), label)
        val links = LinksPayload("v1", "sha256-" + "a".repeat(64), listOf(LinkCategory("fixture", "架空カテゴリ", 0, listOf(link("b", "架空リンクB", 1), link("c", "架空リンクC", 0), link("a", "架空リンクA", 1), link("hidden", "架空リンクD", 0, visible = false)))))
        assertEquals(listOf("c", "a", "b"), recommendedLinks(links, Settings()).map { it.id })
        assertEquals(listOf("c", "b"), recommendedLinks(links, Settings(hidden = setOf("a"))).map { it.id })
    }
}
