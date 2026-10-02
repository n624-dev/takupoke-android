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

    @Test fun linkSearchShowsCategoryAndPunctuationQueryKeepsRegularList() {
        fun link(id: String, category: String, order: Int) = LinkItem(id, category, "架空リンク$id", "https://example.invalid/$id", "blue", true, order, false, 0, emptyList(), "fixture")
        val links = LinksPayload("v1", "fixture", listOf(LinkCategory("c1", "架空カテゴリ一", 0, listOf(link("A", "c1", 10))), LinkCategory("c2", "架空カテゴリ二", 1, listOf(link("B", "c2", 0)))))
        compose.setContent { MaterialTheme { LinksScreen(AppState(links = links), {}) {} } }
        compose.onNodeWithText("リンクを検索").performTextInput("!")
        compose.onNodeWithText("検索結果").assertDoesNotExist()
        compose.onNodeWithText("架空カテゴリ一").assertIsDisplayed()
        compose.onNodeWithText("リンクを検索").performTextReplacement("fixture")
        compose.onNodeWithText("検索結果").assertIsDisplayed()
        compose.onNodeWithText("架空カテゴリ一").assertIsDisplayed()
        val first = compose.onNodeWithText("架空リンクA").fetchSemanticsNode().boundsInRoot.top
        val second = compose.onNodeWithText("架空リンクB").fetchSemanticsNode().boundsInRoot.top
        assertTrue(first < second)
    }
    @Test fun homeFavoritesHaveSameEditMenuAsList() {
        val link = LinkItem("fixture", "category", "架空ホームリンク", "https://example.invalid/fixture", "blue", true, 0, false, 0, emptyList(), "fixture")
        val settings = mutableStateOf(Settings(favorites = setOf("fixture")))
        val payload = LinksPayload("v1", "fixture", listOf(LinkCategory("category", "架空カテゴリ", 0, listOf(link))))
        compose.setContent { MaterialTheme { HomeScreen(AppState(settings = settings.value, links = payload), {}, {}, {}, {}, { settings.value = it(settings.value) }) } }
        compose.onNodeWithText("架空ホームリンク").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText("お気に入りを解除").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(settings.value.favorites.isEmpty()) }
        compose.onNodeWithText("お気に入りに追加").assertIsDisplayed()
    }
    @Test fun blankChangePeriodIsShownAsUnrecordedWithoutInventingLimit() {
        val record = Change(today().toString(), "1_CN", "", "架空科目A", "架空科目B")
        val projection = ScheduleProjection(listOf(Analysis(MaterialKind.CHANGES, schoolYear(), changes = listOf(record))), emptyList(), null, null, true, true)
        compose.setContent { MaterialTheme { LessonDetailScreen(AppState(), today(), projection.changeDetail(record)) {} } }
        compose.onNodeWithText("時限: 記載なし").assertIsDisplayed()
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
