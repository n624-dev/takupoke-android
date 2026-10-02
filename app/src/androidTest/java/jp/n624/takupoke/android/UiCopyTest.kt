package jp.n624.takupoke.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import jp.n624.takupoke.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI wording is the existing iOS wording, not Android-specific onboarding copy. */
class UiCopyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeUsesTodaySectionAndClassActionWithoutInstructions() {
        var selected = 0
        compose.setContent { MaterialTheme { HomeScreen(AppState(), {}, { selected++ }, {}, {}) } }
        compose.onNodeWithText("今日の予定").assertIsDisplayed()
        compose.onNodeWithText("時間割を見る").assertIsDisplayed()
        compose.onNodeWithText("週間時間割を見る").assertDoesNotExist()
        compose.onNodeWithText("設定からクラスと時間割ファイルを選択してください。").assertDoesNotExist()
        compose.onNodeWithText("クラスを選択").performClick()
        assertEquals(1, selected)
    }

    @Test fun linksUseAcquisitionActionAndHiddenLinkWording() {
        var acquired = 0
        compose.setContent { MaterialTheme { LinksScreen(AppState(), { acquired++ }, {}) } }
        compose.onNodeWithText("一覧はまだ取得されていません。").assertIsDisplayed()
        compose.onNodeWithText("リンク一覧を取得").performClick()
        assertEquals(1, acquired)
        compose.onNodeWithText("設定の「リンク・名称・授業時刻」から学校アカウントで取得してください。").assertDoesNotExist()
        compose.onNodeWithText("非表示のリンク").performClick()
        compose.onNodeWithText("非表示のリンクはありません。").assertIsDisplayed()
        compose.onNodeWithText("再表示").assertDoesNotExist()
    }

    @Test fun classLabelsAndSelectionMatchExistingWording() {
        val settings = mutableStateOf(Settings())
        compose.setContent { MaterialTheme { Column { ClassSettings(settings.value) { settings.value = it(settings.value) } } } }
        compose.onNodeWithText("クラスを選択").assertIsDisplayed()
        compose.onNodeWithText("追加クラス（1年生のみ・任意）").assertIsDisplayed()
        compose.onNodeWithText("追加なし").assertIsDisplayed()
        compose.onNodeWithText("留学生向けの授業も表示").assertIsDisplayed()
        compose.onNodeWithText("留学生向け授業を表示").assertDoesNotExist()
        compose.onNodeWithText("クラスを選択").performClick()
        compose.onNodeWithText("1-1").performClick()
        compose.runOnIdle { assertEquals("1_1", settings.value.primaryClass) }
    }

    @Test fun settingsKeepClassControlsOnTheirOwnScreen() {
        val settings = mutableStateOf(Settings())
        var destination = ""
        compose.setContent { MaterialTheme { SettingsScreen(AppState(settings = settings.value), { destination = it }) { settings.value = it(settings.value) } } }
        compose.onNodeWithText("未選択").performClick()
        assertEquals("classes", destination)
        compose.onNodeWithText("留学生向けの授業も表示").assertDoesNotExist()
        compose.onNodeWithText("リンクの開き方").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("アプリ内で開く").performClick()
        compose.onNodeWithText("デフォルトのブラウザ").performClick()
        compose.runOnIdle { assertEquals(false, settings.value.inAppBrowser) }
        compose.onNodeWithText("デフォルトのブラウザ").assertIsDisplayed()
        compose.onNodeWithText("リンクをアプリ内で開く").assertDoesNotExist()
    }

    @Test fun timetableUsesWeekAndModeWording() {
        compose.setContent { MaterialTheme { TimetableScreen(AppState(), {}, select = {}) } }
        compose.onNodeWithText("表示クラス").assertIsDisplayed()
        compose.onNodeWithText("週の時間割").assertIsDisplayed()
        compose.onNodeWithText("前週").assertIsDisplayed()
        compose.onNodeWithText("翌週").assertIsDisplayed()
        compose.onNodeWithText("変更込み").assertIsSelected()
        compose.onNodeWithText("通常").performClick().assertIsSelected()
        compose.onNodeWithText("前の週").assertDoesNotExist()
        compose.onNodeWithText("次の週").assertDoesNotExist()
        compose.onNodeWithText("変更を反映").assertDoesNotExist()
    }

    @Test fun linkMenuWordingDoesNotBreakFavoritesColorsOrRestoring() {
        val link = LinkItem("fixture", "fixture-category", "架空リンクA", "https://example.invalid/fixture", "blue", true, 0, false, 0, emptyList(), "架空リンクA")
        val payload = LinksPayload("v1", "sha256-" + "a".repeat(64), listOf(LinkCategory("fixture-category", "架空カテゴリーA", 0, listOf(link))))
        val settings = mutableStateOf(Settings())
        compose.setContent { MaterialTheme { LinksScreen(AppState(links = payload, settings = settings.value), {}) { settings.value = it(settings.value) } } }
        compose.onNodeWithText("架空リンクA").performTouchInput { longClick() }
        compose.onNodeWithText("お気に入りに追加").performClick()
        compose.onNodeWithText("お気に入りを解除").assertIsDisplayed()
        compose.onNodeWithText("デフォルトのブラウザで開く").assertIsDisplayed()
        compose.onNodeWithText("色を変更").assertIsDisplayed()
        compose.onNodeWithText("スカイ").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("sky", settings.value.linkColors[link.id]) }
        compose.onNodeWithText("既定色に戻す").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(emptyMap<String, String>(), settings.value.linkColors) }
        compose.onNodeWithText("非表示").performScrollTo().performClick()
        compose.onNodeWithText("表示できるリンクがありません。").assertIsDisplayed()
        compose.onNodeWithText("非表示のリンク").performClick()
        compose.onNodeWithText("再表示").performClick()
        compose.runOnIdle { assertEquals(emptySet<String>(), settings.value.hidden) }
    }
}
