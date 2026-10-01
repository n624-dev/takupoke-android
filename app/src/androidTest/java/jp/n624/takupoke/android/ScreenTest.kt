package jp.n624.takupoke.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun startupAndNavigationRemainUsableOffline() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("あとで設定").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("あとで設定").performScrollTo().performClick()
        compose.onAllNodesWithText("ホーム")[0].assertIsDisplayed()
        compose.onNodeWithText("一覧").performClick()
        compose.onNodeWithText("リンクを検索").assertIsDisplayed()
        compose.onNodeWithText("時間割").performClick()
        compose.onNodeWithText("変更を反映").assertIsDisplayed()
        compose.onNodeWithText("次の週").performClick()
        compose.onNodeWithText("設定").performClick()
        compose.onNodeWithText("時間割ファイル").performClick()
        compose.onNodeWithText("通常時間割").assertIsDisplayed()
        compose.onAllNodesWithText("未選択")[0].assertExists()
    }
}
