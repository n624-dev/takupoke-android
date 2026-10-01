package jp.n624.takupoke.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Rule
import org.junit.Test

class ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun startupAndNavigationRemainUsableOffline() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("あとで設定").fetchSemanticsNodes().isNotEmpty() }
        screenshot("00-setup")
        compose.onNodeWithText("あとで設定").performScrollTo().performClick()
        compose.onAllNodesWithText("ホーム")[0].assertIsDisplayed()
        screenshot("01-home")
        compose.onNodeWithText("一覧").performClick()
        compose.onNodeWithText("リンクを検索").assertIsDisplayed()
        screenshot("02-links")
        compose.onNodeWithText("時間割").performClick()
        compose.onNodeWithText("変更を反映").assertIsDisplayed()
        screenshot("03-timetable")
        compose.onNodeWithText("次の週").performClick()
        compose.onNodeWithText("設定").performClick()
        screenshot("04-settings")
        compose.onNodeWithText("時間割ファイル").performClick()
        compose.onNodeWithText("通常時間割").assertIsDisplayed()
        compose.onAllNodesWithText("未選択")[0].assertExists()
        screenshot("05-materials")
    }
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Draw the actual window, including the system bars; not a mockup or Compose preview.
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.filesDir, "screenshots").also { check(it.mkdirs() || it.isDirectory) }
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
