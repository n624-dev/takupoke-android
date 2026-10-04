package jp.n624.takupoke.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import jp.n624.takupoke.core.*
import java.io.File
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class RecoveryPromptAssetsTest {
    @Test fun unchangedStructureInstructionRetainsItsTaskVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = RecoveryModelManifest("synthetic", "1", "https://models.example.invalid/model", 1, "a".repeat(64), "liteRtLm", "29", 1, "CPU", "test-only", false)
        LiteRtRecoveryProvider(context, manifest, File(context.cacheDir, "absent-prompt-test-model"), { true }).use { provider ->
            val structure = provider.forStructureProposal()
            assertEquals("4", provider.metadata.promptVersion)
            assertEquals(provider.metadata.copy(promptVersion = "3"), structure.metadata)
            assertEquals(provider.id, structure.id)
            try {
                runBlocking { structure.recoverCell(RecoveryPromptCell("cell", emptyList(), emptyList(), emptyList(), 1, emptyList())) }
                fail("Structure adapter must not accept fieldExtraction")
            } catch (_: IllegalArgumentException) { }
            assertEquals("4", provider.metadata.promptVersion)
        }
    }
    @Test fun packagedFieldInstructionMatchesSharedRevision() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = RecoveryPromptAssets.fieldExtraction { context.assets.open(RecoveryPromptAssets.FIELD_ASSET) }
        assertEquals("4", RecoveryPromptAssets.VERSION)
        assertEquals(2939, text.toByteArray(Charsets.UTF_8).size)
        assertFalse(text.endsWith("\n"))
    }

    @Test fun modifiedOrOversizedInstructionIsRejectedAndStreamClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = context.assets.open(RecoveryPromptAssets.FIELD_ASSET).use { it.readBytes() }
        for (bytes in listOf(original + byteArrayOf(10), original.copyOf().also { it[0] = 0 }, ByteArray(4097))) {
            var closed = false
            val input = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
            try {
                RecoveryPromptAssets.fieldExtraction { input }
                fail("Modified instruction must not reach inference")
            } catch (_: IllegalArgumentException) { }
            assertTrue(closed)
        }
    }
}
