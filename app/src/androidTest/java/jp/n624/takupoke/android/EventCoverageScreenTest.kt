package jp.n624.takupoke.android

import android.widget.DatePicker
import android.widget.Button
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import jp.n624.takupoke.core.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class EventCoverageScreenTest {
    @get:Rule val compose = createComposeRule()
    private val warning = "学校行事は未取得です。"
    private val noLessons = "授業はありません。"
    private val cls = "1_CN"
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun payload(year: Int, excludedDay: LocalDate? = null): EventsPayload {
        val first = LocalDate.of(year, 5, 1)
        val day = if (first == excludedDay) first.plusDays(1) else first
        return EventsPayload("v1", year, "a".repeat(64), events = listOf(Event(day.toString(), day.toString(), "架空行事", "行事メモ"))).validate(year)
    }
    private fun state(day: LocalDate, normalWeekday: Int, changes: List<Change> = emptyList()) = AppState(
        settings = Settings(primaryClass = cls),
        materials = listOf(
            Analysis(MaterialKind.TIMETABLE, schoolYear(day), if (day.monthValue in 4..9) 1 else 2,
                lessons = listOf(Lesson(cls, normalWeekday, 1, Names("架空通常科目"))), classes = listOf(cls)),
            Analysis(MaterialKind.CHANGES, schoolYear(day), changes = changes)
        ).map { MaterialRecord(it.kind, "content://example.invalid/fixture", "fixture.${it.kind.extension}", "a".repeat(64), 1, 1, parsedDigest = "a".repeat(64), analysis = it) }
    )

    @Test fun homePastOrFutureOnlyCacheWarnsAndCannotClaimNoLessonsButLoadedYearCan() {
        val day = today(); val year = schoolYear(day)
        // A different weekday guarantees no lessons today, including on Sunday.
        val base = state(day, if (day.dayOfWeek.value == 1) 2 else 1)
        val current = mutableStateOf(base.copy(events = listOf(payload(year - 1))))
        compose.setContent { MaterialTheme { HomeScreen(current.value, {}, {}, {}, {}) } }
        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.onNodeWithText(noLessons).assertDoesNotExist()
        compose.runOnIdle { current.value = base.copy(events = listOf(payload(year + 1))) }
        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.onNodeWithText(noLessons).assertDoesNotExist()
        compose.runOnIdle { current.value = base.copy(events = listOf(payload(year, day))) }
        compose.onNodeWithText(warning).assertDoesNotExist()
        compose.onNodeWithText(noLessons).assertIsDisplayed()
        compose.onNodeWithText("架空行事").assertDoesNotExist()
    }

    @Test fun homeKeepsAvailableNormalAndMakeupLessonsVisibleAlongsideIncompleteEventsWarning() {
        val day = today(); val year = schoolYear(day)
        val makeup = Change(day.toString(), cls, "2", "", "架空補講科目", note = "補講")
        val current = state(day, day.dayOfWeek.value.coerceAtMost(5), listOf(makeup)).copy(events = listOf(payload(year - 1)))
        compose.setContent { MaterialTheme { HomeScreen(current, {}, {}, {}, {}) } }
        compose.onNodeWithText(warning).assertIsDisplayed()
        if (day.dayOfWeek.value <= 5) compose.onNodeWithText("架空通常科目").assertIsDisplayed()
        else compose.onNodeWithText("架空通常科目").assertDoesNotExist()
        compose.onNodeWithText("架空補講科目").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(noLessons).assertDoesNotExist()
    }

    @Test fun weekKeepsNormalLessonsVisibleAlongsideIncompleteEventsWarning() {
        val day = today(); val monday = Schedule.week(day)
        val current = state(monday, 1).copy(events = listOf(payload(schoolYear(monday) - 1)))
        compose.setContent { MaterialTheme { TimetableScreen(current, {}) {} } }
        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.onNodeWithText("架空通常科目").performScrollTo().assertIsDisplayed()
    }

    @Test fun weekDatePickerRequiresBothSchoolYearsAcrossMarchAprilBoundary() {
        val day = today()
        var april = LocalDate.of(day.year + 1, 4, 1)
        while (april.dayOfWeek.value == 1) april = april.plusYears(1)
        val boundary = Schedule.monday(april)
        // Fictional changes keep navigation bounds contiguous up to the selected week.
        val changes = generateSequence(Schedule.monday(day)) { it.plusWeeks(1) }.takeWhile { it <= boundary }
            .map { Change(it.toString(), cls, "2", "", "架空補講科目", note = "補講") }.toList()
        val base = state(day, 1, changes)
        val previousYear = schoolYear(boundary); val nextYear = schoolYear(april)
        val current = mutableStateOf(base.copy(events = listOf(payload(previousYear))))
        compose.setContent { MaterialTheme { TimetableScreen(current.value, {}) {} } }
        val initialMonday = Schedule.week(day); val initialSunday = initialMonday.plusDays(6)
        compose.onNodeWithText("${initialMonday.monthValue}/${initialMonday.dayOfMonth}〜${initialSunday.monthValue}/${initialSunday.dayOfMonth}").performClick()
        compose.runOnIdle {
            val picker = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<DatePicker>().single()
            picker.updateDate(boundary.year, boundary.monthValue - 1, boundary.dayOfMonth)
        }
        compose.runOnIdle {
            WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<Button>()
                .single { it.text.toString() == "この週へ移動" }.performClick()
        }
        val sunday = boundary.plusDays(6)
        compose.onNodeWithText("${boundary.monthValue}/${boundary.dayOfMonth}〜${sunday.monthValue}/${sunday.dayOfMonth}").assertIsDisplayed()
        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.runOnIdle { current.value = base.copy(events = listOf(payload(nextYear))) }
        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.runOnIdle { current.value = base.copy(events = listOf(payload(previousYear), payload(nextYear))) }
        compose.onNodeWithText(warning).assertDoesNotExist()
    }
}
