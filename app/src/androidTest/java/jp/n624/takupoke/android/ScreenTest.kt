package jp.n624.takupoke.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun startupAndNavigationRemainUsableOffline() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("あとで設定").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("データを取得").assertIsDisplayed()
        compose.onNodeWithText("たくポケへようこそ").assertDoesNotExist()
        screenshot("00-setup")
        compose.onNodeWithText("次へ").performClick()
        jp.n624.takupoke.core.MaterialKind.entries.forEach { kind ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(kind.title))
            compose.onNodeWithText(kind.title).assertIsDisplayed()
        }
        compose.onNodeWithText("次へ").performClick()
        compose.onNodeWithText("留学生向けの授業も表示").assertIsDisplayed()
        compose.onNodeWithText("はじめる").assertIsDisplayed()
        compose.onNodeWithText("戻る").performClick()
        compose.onNodeWithText("戻る").performClick()
        compose.onNodeWithText("あとで設定").performClick()
        compose.onAllNodesWithText("ホーム")[0].assertIsDisplayed()
        compose.onNodeWithText("たくポケ").assertIsDisplayed()
        compose.onNodeWithText("今日の予定").assertIsDisplayed()
        compose.onNodeWithText("時間割を見る").assertIsDisplayed()
        compose.onNodeWithText("設定からクラスと時間割ファイルを選択してください。").assertDoesNotExist()
        screenshot("01-home")
        compose.onNodeWithText("一覧").performClick()
        compose.onNodeWithText("リンクを検索").assertIsDisplayed()
        screenshot("02-links")
        compose.onNodeWithText("時間割").performClick()
        compose.onNodeWithText("通常").assertIsDisplayed()
        compose.onNodeWithText("変更込み").assertIsDisplayed()
        compose.onNodeWithText("前週").assertIsDisplayed()
        compose.onNodeWithText("翌週").assertIsDisplayed()
        screenshot("03-timetable")
        compose.onNodeWithText("翌週").performClick()
        compose.onNodeWithText("設定").performClick()
        screenshot("04-settings")
        compose.onNodeWithText("時間割ファイル").performClick()
        compose.onNodeWithText("通常時間割").assertIsDisplayed()
        compose.onAllNodesWithText("未選択")[0].assertExists()
        compose.onNodeWithText("未解析").assertDoesNotExist()
        screenshot("05-materials")
    }
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("takupokeScreenshots") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Draw the actual window, including the system bars; not a mockup or Compose preview.
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            val bytes = output.toByteArray(); check(bytes.size in 9..2 * 1024 * 1024)
            val filename = "$name.png"
            fun emit(record: JSONObject) { Log.i("TakupokeScreenshots", "TAKUPOKE_SCREENSHOT $record") }
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            emit(JSONObject().put("type", "begin").put("name", filename).put("size", bytes.size).put("sha256", sha256))
            val data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            for (offset in data.indices step 2048) emit(JSONObject().put("type", "chunk").put("name", filename).put("offset", offset).put("data", data.substring(offset, minOf(offset + 2048, data.length))))
            emit(JSONObject().put("type", "end").put("name", filename))
        } finally { bitmap.recycle() }
    }
}
