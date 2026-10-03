package jp.n624.takupoke.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import jp.n624.takupoke.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Compose/PDF viewer and repository/SQLite adoption; the provider/model I/O is offline. */
class RecoveryScreenTest {
    @get:Rule val compose=createComposeRule()
    private fun mount(services:OfflineRecoveryServices=OfflineRecoveryServices(),kind:MaterialKind=MaterialKind.TIMETABLE):OfflineRecoverySeed {
        val seed=OfflineRecoverySeed(InstrumentationRegistry.getInstrumentation().targetContext,services,kind)
        runBlocking { seed.install() }
        compose.setContent { TakupokeUi(seed.repository,{error("Unexpected document picker")},{error("Unexpected auth")},{error("Unexpected notification request")}) }
        compose.onNodeWithText("設定").performClick();compose.onNodeWithText("時間割ファイル").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("詳細を見る"))
        compose.onNodeWithText("詳細を見る").performClick()
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
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("架空復旧科目"))
            offlineScreenshot(compose,"recovery-preview")
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("元PDFを確認"))
            compose.onNodeWithText("元PDFを確認").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("保存済みPDF 1ページ").fetchSemanticsNodes().isNotEmpty() }
            // PixelCopy waits for the Image draw, unlike semantics publication.
            val pixels=compose.onNodeWithContentDescription("保存済みPDF 1ページ").captureToImage().toPixelMap()
            val paperHeight=pixels.width/2 // The fixture page is 600 by 300.
            val paperTop=(pixels.height-paperHeight)/2
            val center=pixels[pixels.width/2,pixels.height/2]
            assertTrue("The saved PDF has rendered its white page",center.red>.9f && center.green>.9f && center.blue>.9f)
            var sourceInk=0
            for(y in (paperTop+paperHeight/20)..(paperTop+paperHeight/5))for(x in (pixels.width/40)..(pixels.width*4/5)) {
                val color=pixels[x,y]
                if(color.red<.25f && color.green<.25f && color.blue<.25f)sourceInk++
            }
            assertTrue("The actual synthetic source text is drawn",sourceInk>20)
            try {
                compose.waitUntil(10000) { compose.onNodeWithText("1/1").isDisplayed() }
                compose.onNodeWithText("1/1").assertIsDisplayed()
            } catch (failure: Throwable) {
                val counter = compose.onNodeWithText("1/1").fetchSemanticsNode().boundsInRoot
                val image = compose.onNodeWithContentDescription("保存済みPDF 1ページ").fetchSemanticsNode().boundsInRoot
                throw AssertionError("Actual PDF controls must remain visible: counter=$counter, image=$image", failure)
            }
            repeat(3) { compose.onNodeWithContentDescription("PDFを拡大").performClick() }
            compose.onNodeWithText("400%").assertIsDisplayed()
            val viewport = compose.onNodeWithContentDescription("PDFの表示領域")
            val axes = viewport.fetchSemanticsNode().config
            assertTrue("The enlarged original can move horizontally", axes[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f)
            assertTrue("The enlarged original can move vertically", axes[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
            fun awaitPan(horizontal: Boolean) {
                try {
                    compose.waitUntil(10000) {
                        val moved = viewport.fetchSemanticsNode().config
                        moved[if (horizontal) SemanticsProperties.HorizontalScrollAxisRange else SemanticsProperties.VerticalScrollAxisRange].value() > 0f
                    }
                } catch (failure: Throwable) {
                    val node = viewport.fetchSemanticsNode()
                    val x = node.config[SemanticsProperties.HorizontalScrollAxisRange]
                    val y = node.config[SemanticsProperties.VerticalScrollAxisRange]
                    throw AssertionError("Actual PDF pan horizontal=$horizontal: x=${x.value()}/${x.maxValue()}, y=${y.value()}/${y.maxValue()}, viewport=${node.boundsInRoot}", failure)
                }
            }
            // Let the first real gesture settle before injecting the second.
            viewport.performTouchInput { swipeLeft() }; awaitPan(true)
            viewport.performTouchInput { swipeUp() }; awaitPan(false)
            compose.onNodeWithText("全体を表示").performClick()
            compose.onNodeWithText("100%").assertIsDisplayed()
            compose.waitUntil(10000) {
                val reset = viewport.fetchSemanticsNode().config
                reset[SemanticsProperties.HorizontalScrollAxisRange].value() == 0f && reset[SemanticsProperties.VerticalScrollAxisRange].value() == 0f
            }
            assertEquals(seed.oldAnalysis, seed.database.records().single().analysis)
            // Synchronize the actual raster after changing scale, before screenshot capture.
            compose.onNodeWithContentDescription("保存済みPDF 1ページ").captureToImage()
            offlineScreenshot(compose,"recovery-original");compose.onNodeWithText("閉じる").performClick()
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
            offlineScreenshot(compose,"recovery-models")
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
    @Test fun obsoleteStrictCacheIsRetriedAndFailedPdfRetainsPreviousFormal() {
        val seed=mount()
        try {
            val record=seed.database.records().single();val old=seed.oldAnalysis.copy(parserVersion=PARSER_VERSION-1)
            seed.database.save(record.copy(uri=android.net.Uri.fromFile(seed.repository.file(record)).toString(),parsedDigest=record.digest,analysis=old,failure=null,recoveryJob=null))
            runBlocking { seed.repository.refresh(sourcesOnly=true) }
            val saved=seed.database.records().single()
            assertEquals(old,saved.analysis);assertNotNull(saved.failure);assertEquals(RecoveryJobState.PENDING,saved.recoveryJob?.state)
            assertEquals(old,seed.repository.state.value.materials.single().analysis)
            assertFalse(seed.services.preparationEntered.isCompleted);assertEquals(0,seed.services.providerCalls)
        } finally { compose.runOnIdle { seed.stop() } }
    }
    @Test fun examClockPreviewAdoptionAndRestartStayOffline()=specialPreviewAdoptionAndRestart(MaterialKind.EXAM,"recovery-exam")
    @Test fun returnClockPreviewAdoptionAndRestartStayOffline()=specialPreviewAdoptionAndRestart(MaterialKind.RETURN,"recovery-return")
    @Test fun sourceUpdateClosesPreviousLessonDetailAndPreservesFormalResult() {
        val seed=mount()
        val source=java.io.File(seed.context.cacheDir,"recovery-dialog-source-${java.util.UUID.randomUUID()}.pdf")
        try {
            val original=seed.database.records().single()
            source.writeBytes(seed.repository.file(original).readBytes())
            seed.database.save(original.copy(uri=android.net.Uri.fromFile(source).toString()))
            runBlocking { seed.repository.activate(false) }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("架空の前回科目"))
            compose.onNodeWithText("架空の前回科目").performClick()
            compose.onNodeWithText("授業詳細").assertIsDisplayed()
            compose.onNodeWithText("科目: 架空の前回科目").assertIsDisplayed()
            android.graphics.pdf.PdfDocument().let { pdf ->
                try {
                    val page=pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600,300,1).create())
                    page.canvas.drawText("Entirely synthetic updated source",30f,40f,android.graphics.Paint().apply { textSize=12f })
                    pdf.finishPage(page)
                    source.outputStream().use { pdf.writeTo(it) }
                } finally { pdf.close() }
            }
            val updatedHash=sha256(source.readBytes())
            assertNotEquals(original.digest,updatedHash)
            runBlocking { seed.repository.refresh(sourcesOnly=true) }
            compose.waitUntil(10000) { seed.repository.state.value.materials.single().digest==updatedHash }
            compose.onNodeWithText("授業詳細").assertDoesNotExist()
            val saved=seed.database.records().single()
            assertEquals(updatedHash,saved.digest)
            assertEquals(original.parsedDigest,saved.parsedDigest)
            assertEquals(seed.oldAnalysis,saved.analysis)
            assertNotNull(saved.failure)
            assertEquals(RecoveryJobState.PENDING,saved.recoveryJob?.state)
            assertEquals(0,seed.services.providerCalls)
        } finally { compose.runOnIdle { seed.stop() };source.delete() }
    }
    private fun specialPreviewAdoptionAndRestart(kind:MaterialKind,fixture:String) {
        val document=specialRecoveryFixture(kind)
        val service=OfflineRecoveryServices().apply { preparedDocument=document }
        val seed=mount(service,kind)
        var restarted:AppRepository?=null
        try {
            compose.onNodeWithText("端末内で復旧する").performScrollTo().performClick()
            compose.waitUntil(15000) { seed.repository.state.value.recoveryPreviews.isNotEmpty() && !seed.repository.state.value.busy }
            assertEquals(seed.oldAnalysis,seed.database.records().single().analysis)
            compose.onNodeWithText("閉じる").performClick();compose.onNodeWithText("復旧結果をプレビュー").performScrollTo().performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("結合授業: 1〜2限"))
            compose.onAllNodesWithText("結合授業: 1〜2限")[0].assertIsDisplayed()
            compose.onAllNodesWithText("時刻: 08:00〜09:00")[0].assertIsDisplayed()
            offlineScreenshot(compose,fixture)
            val preview=seed.repository.state.value.recoveryPreviews.getValue(kind)
            assertEquals(5,preview.analysis.specialTimes.size);assertEquals(17,preview.analysis.classes.size)
            assertEquals(listOf(1,2),preview.analysis.lessons.map { it.period });assertEquals(2,preview.analysis.lessons.size)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("この復旧結果を使用"));compose.onNodeWithText("この復旧結果を使用").performClick()
            assertEquals(seed.oldAnalysis,seed.database.records().single().analysis)
            compose.onNodeWithText("確認した結果を採用").performClick()
            compose.waitUntil(10000) { seed.database.records().single().recoveryJob?.state==RecoveryJobState.ADOPTED && !seed.repository.state.value.busy }
            val saved=seed.database.records().single();assertEquals(preview.analysis,saved.analysis);assertNotNull(saved.recoveryAcceptance);assertEquals(0,service.providerCalls)
            compose.runOnIdle { seed.stop() }
            val reopened=AppRepository(seed.context,RejectNetwork,seed.database,seed.preferences,service);restarted=reopened
            runBlocking { reopened.foreground(true);reopened.activate(false) }
            assertEquals(saved.analysis,reopened.state.value.materials.single().analysis)
            assertEquals(saved.recoveryAcceptance,reopened.state.value.materials.single().recoveryAcceptance)
            assertTrue(reopened.state.value.recoveryPreviews.isEmpty())
        } finally { compose.runOnIdle { seed.stop();restarted?.foreground(false);restarted?.cancel();restarted?.stopObserving() } }
    }
}
