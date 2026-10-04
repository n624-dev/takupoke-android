package jp.n624.takupoke.core

import java.time.LocalDate
import kotlin.test.*

class EventCoverageTest {
    // Valid annual data with no events on the days being projected.
    private fun payload(year: Int) = EventsPayload("v1", year, "a".repeat(64), events = listOf(
        Event("$year-05-01", "$year-05-01", "架空行事", "行事メモ")
    )).validate(year)
    private fun projection(vararg years: Int) = ScheduleProjection(emptyList(), years.map(::payload), null, null, true, false)

    @Test fun loadedCoverageUsesTheRequestedSchoolYearRatherThanAnyCachedPayload() {
        val day = LocalDate.parse("2026-10-01")
        assertFalse(projection().eventsLoaded(day))
        assertFalse(projection(2025).eventsLoaded(day))
        assertFalse(projection(2027).eventsLoaded(day))
        assertTrue(projection(2026).eventsLoaded(day))
        assertTrue(Schedule.events(day, listOf(payload(2026))).isEmpty())
        assertTrue(projection(2025, 2026, 2027).eventsLoaded(day))
        assertTrue(projection(2026).eventsLoaded(LocalDate.parse("2027-03-31")))
        assertFalse(projection(2026).eventsLoaded(LocalDate.parse("2027-04-01")))
    }

    @Test fun aBoundaryWeekRequiresBothYearsEvenWhenTheNewYearOnlyAppearsOnSunday() {
        val monday = LocalDate.parse("2029-03-26")
        assertEquals(LocalDate.parse("2029-04-01"), monday.plusDays(6))
        assertFalse(projection(2028).weekEventsLoaded(monday))
        assertFalse(projection(2029).weekEventsLoaded(monday))
        assertFalse(projection(2027, 2030).weekEventsLoaded(monday))
        assertTrue(projection(2028, 2029).weekEventsLoaded(monday))
        assertTrue(projection(2028).weekEventsLoaded(monday.minusWeeks(1)))
        assertTrue(projection(2029).weekEventsLoaded(monday.plusWeeks(1)))
    }

    @Test fun incompleteEventsDoNotHideNormalLessonsOrAppliedChanges() {
        val day = LocalDate.parse("2026-10-01")
        val normal = Analysis(MaterialKind.TIMETABLE, 2026, 2, listOf(Lesson("1_CN", 4, 1, Names("架空通常科目"))))
        val change = Analysis(MaterialKind.CHANGES, 2026, changes = listOf(Change(day.toString(), "1_CN", "2", "", "架空補講科目", note = "補講")))
        val p = ScheduleProjection(listOf(normal, change), listOf(payload(2025)), null, null, true, false)
        assertFalse(p.eventsLoaded(day))
        assertEquals(listOf("架空通常科目", "架空補講科目"), p.blocks(day, "1_CN").flatMap { it.slot.lessons }.map { it.names.subject })
        assertTrue(p.missing(day, "1_CN").isEmpty())
    }
}
