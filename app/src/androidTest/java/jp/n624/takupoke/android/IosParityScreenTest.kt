package jp.n624.takupoke.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import jp.n624.takupoke.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class IosParityScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun colorResetPreservesExistingBlueAndRemovesOverride() {
        val settings = mutableStateOf(Settings(color = 0))
        compose.setContent { MaterialTheme { SettingsScreen(AppState(settings = settings.value), {}) { settings.value = it(settings.value) } } }
        compose.onNodeWithText("青").performScrollTo().performClick()
        compose.onNodeWithText("デフォルト").performClick()
        compose.runOnIdle { assertNull(settings.value.color) }
        compose.onNodeWithText("デフォルト").assertIsDisplayed()
        compose.onNodeWithText("デフォルト").performClick()
        compose.onNodeWithText("紫").performClick()
        compose.runOnIdle { assertEquals(6, settings.value.color) }
    }

    @Test fun homeShowsMergedLessonsSeparatelyForEachClassAndMissingStates() {
        val day = today()
        val normal = Analysis(MaterialKind.TIMETABLE, schoolYear(), if (day.monthValue in 4..9) 1 else 2,
            listOf("1_1", "1_CN").flatMap { cls -> (1..2).map { Lesson(cls, day.dayOfWeek.value, it, Names("架空科目A", "架空教員A", "架空教室A")) } })
        var selected: Slot? = null
        compose.setContent { MaterialTheme { HomeScreen(AppState(settings = Settings(primaryClass = "1_1", additionalClass = "1_CN"), materials = listOf(MaterialRecord(MaterialKind.TIMETABLE, "content://example.invalid", "fixture.pdf", "digest", 1, 1, analysis = normal))), {}, {}, { selected = it.second }, {}) } }
        compose.onNodeWithText("1-1").assertIsDisplayed()
        compose.onNodeWithText("1-CN").assertIsDisplayed()
        compose.onNodeWithText("時間割変更の解析結果がありません。").assertIsDisplayed()
        compose.onAllNodesWithText("架空科目A")[0].performClick()
        compose.runOnIdle { assertEquals(1, selected!!.period); assertEquals(2, selected!!.endPeriod); assertEquals("08:50〜10:20", selected!!.time); assertEquals("1_1", selected!!.lessons.single().className) }
    }

    @Test fun unsupportedChangeRemainsInListAndOpensRawPeriodDetail() {
        val day = today()
        val record = Change(day.toString(), "1_CN", "1,3", "架空科目A", "架空科目B")
        val settings = mutableStateOf(Settings(primaryClass = "1_CN"))
        val state = AppState(settings = settings.value, materials = listOf(MaterialRecord(MaterialKind.CHANGES, "content://example.invalid", "fixture.xlsx", "digest", 1, 1, analysis = Analysis(MaterialKind.CHANGES, schoolYear(), changes = listOf(record)))))
        var selected: Slot? = null
        compose.setContent { MaterialTheme { TimetableScreen(state.copy(settings = settings.value), {}, { settings.value = it(settings.value) }) { selected = it.second } } }
        // The chips have a horizontal scroll parent. Scroll the outer page first.
        compose.onNodeWithText("変更一覧の対象クラス").performScrollTo()
        compose.onNodeWithText("全件").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("全件", settings.value.changeRange) }
        compose.onNodeWithText("架空科目A → 架空科目B").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("1,3", selected!!.changes.single().period) }
        compose.onNodeWithText("通常").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(settings.value.includesChanges) }
    }

    @Test fun todayRequestUsesContainingWeekendWeekAndKeepsNormalMode() {
        val settings = mutableStateOf(Settings(includesChanges = false))
        compose.setContent { MaterialTheme { TimetableScreen(AppState(settings = settings.value), {}, { settings.value = it(settings.value) }, "fixture-request") {} } }
        compose.onNodeWithText("通常").assertIsSelected()
        val monday = Schedule.monday(today()); val sunday = monday.plusDays(6)
        compose.onNodeWithText("${monday.monthValue}/${monday.dayOfMonth}〜${sunday.monthValue}/${sunday.dayOfMonth}").assertIsDisplayed()
    }

    @Test fun helpHasFiveSeparateTopics() {
        compose.setContent { MaterialTheme { HelpScreen() } }
        compose.onNodeWithText("困ったとき").performClick()
        compose.onNodeWithText("使い方に戻る").assertIsDisplayed()
        compose.onNodeWithText("更新と通知").assertDoesNotExist()
        compose.onNodeWithText("使い方に戻る").performClick()
        compose.onNodeWithText("更新と通知").assertIsDisplayed()
    }
}
