package jp.n624.takupoke.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import jp.n624.takupoke.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Compose/PDF viewer and repository/SQLite adoption; the provider/model I/O is offline. */
class RecoveryScreenTest {
    @get:Rule val compose=createComposeRule()
    private fun mount(services:OfflineRecoveryServices=OfflineRecoveryServices()):OfflineRecoverySeed {
        val seed=OfflineRecoverySeed(InstrumentationRegistry.getInstrumentation().targetContext,services)
        runBlocking { seed.install() }
        compose.setContent { TakupokeUi(seed.repository,{error("Unexpected document picker")},{error("Unexpected auth")},{error("Unexpected notification request")}) }
        compose.onNodeWithText("設定").performClick();compose.onNodeWithText("時間割ファイル").performClick();compose.onNodeWithText("詳細を見る").performScrollTo().performClick()
        return seed
    }
    @Test fun pendingRunPreviewOriginalPdfAndExplicitAdoptionPreserveFormalUntilCommit() {
        val seed=mount()
        try {
            compose.onNodeWithText("端末内で復旧する").performScrollTo().performClick()
            compose.waitUntil(15000) { seed.repository.state.value.recoveryPreviews.isNotEmpty() && !seed.repository.state.value.busy }
            assertEquals(seed.oldAnalysis,seed.database.records().single().analysis)
            compose.onNodeWithText("閉じる").performClick()
            compose.onNodeWithText("復旧結果をプレビュー").performScrollTo().performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("復旧結果のプレビュー（未採用）"))
            compose.onNodeWithText("復旧結果のプレビュー（未採用）").assertIsDisplayed()
            compose.onNodeWithText("元PDFを確認").performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("保存済みPDF 1ページ").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1/1").assertIsDisplayed();compose.onNodeWithText("閉じる").performClick()
            compose.onNodeWithText("この復旧結果を使用").performScrollTo().performClick()
            assertEquals(seed.oldAnalysis,seed.database.records().single().analysis)
            compose.onNodeWithText("確認した結果を採用").performClick()
            compose.waitUntil(10000) { seed.database.records().single().recoveryJob?.state==RecoveryJobState.ADOPTED && !seed.repository.state.value.busy }
            val saved=seed.database.records().single();assertEquals(saved.digest,saved.parsedDigest);assertEquals("架空復旧科目",saved.analysis!!.lessons.single().names.subject);assertNotNull(saved.recoveryMetadata);assertNotNull(saved.recoveryAcceptance)
            assertEquals(0,seed.services.providerCalls)
        } finally { compose.runOnIdle { seed.stop() } }
    }
    @Test fun cancelPendingRecoveryKeepsPreviousFormalAndHasNoPreview() {
        val service=OfflineRecoveryServices().apply { holdPreparation=true };val seed=mount(service)
        try {
            compose.onNodeWithText("端末内で復旧する").performScrollTo().performClick()
            compose.waitUntil(10000) { service.preparationEntered.isCompleted }
            compose.onNodeWithText("中止").performClick()
            compose.waitUntil(10000) { !seed.repository.state.value.busy }
            assertEquals(seed.oldAnalysis,seed.database.records().single().analysis);assertTrue(seed.repository.state.value.recoveryPreviews.isEmpty());assertEquals(RecoveryJobState.PENDING,seed.database.records().single().recoveryJob?.state)
        } finally { compose.runOnIdle { seed.stop() } }
    }
    @Test fun modelDownloadPreparationAndDeletionStayOffline() {
        val service=OfflineRecoveryServices(hasModel=false);val seed=mount(service)
        try {
            compose.onNodeWithText("必要なAIモデルを準備").performScrollTo().performClick()
            compose.onNodeWithText("端末内AIモデルをダウンロード").assertIsDisplayed()
            compose.onNodeWithText("ダウンロードして準備").performClick()
            compose.waitUntil(10000) { !seed.repository.state.value.busy && seed.repository.state.value.recoveryModel.installed!=null }
            assertEquals(1,service.downloads)
            compose.onNodeWithText("追加モデルを削除").performScrollTo().performClick()
            compose.waitUntil(10000) { !seed.repository.state.value.busy && seed.repository.state.value.recoveryModel.installed==null }
            assertEquals(1,service.deletions)
        } finally { compose.runOnIdle { seed.stop() } }
    }
    @Test fun unprovidedModelHasNoDownloadAction() {
        val seed=mount(OfflineRecoveryServices(offer=false,hasModel=false))
        try { compose.onNodeWithText("この版では検証済みの追加モデルをまだ配信していません。原文から確定できる復旧は利用できます。").performScrollTo().assertIsDisplayed();compose.onNodeWithText("必要なAIモデルを準備").assertDoesNotExist() }
        finally { compose.runOnIdle { seed.stop() } }
    }
    @Test fun retainedModelCanBeDeletedAfterAllSchoolMaterialsAreRemoved() {
        val seed=mount()
        try {
            seed.database.clearSchool(retentionPeriod())
            runBlocking { seed.repository.activate(false) }
            compose.waitUntil(10000) { seed.repository.state.value.materials.isEmpty() }
            compose.onNodeWithText("追加モデルを削除").performScrollTo().performClick()
            compose.waitUntil(10000) { !seed.repository.state.value.busy && seed.repository.state.value.recoveryModel.installed==null }
            assertEquals(1,seed.services.deletions)
        } finally { compose.runOnIdle { seed.stop() } }
    }
    @Test fun cancellingOfflineModelPreparationRetainsPriorModelSelection() {
        val service=OfflineRecoveryServices(hasModel=false).apply { holdDownload=true };val seed=mount(service)
        try {
            compose.onNodeWithText("必要なAIモデルを準備").performScrollTo().performClick();compose.onNodeWithText("ダウンロードして準備").performClick()
            compose.waitUntil(10000) { service.downloadEntered.isCompleted };compose.onNodeWithText("中止").performClick();compose.waitUntil(10000) { !seed.repository.state.value.busy }
            assertNull(seed.repository.state.value.recoveryModel.installed);assertFalse(seed.repository.state.value.recoveryModel.downloading);assertEquals(seed.oldAnalysis,seed.database.records().single().analysis)
        } finally { compose.runOnIdle { seed.stop() } }
    }
}
