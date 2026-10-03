package jp.n624.takupoke.core

import java.nio.file.Files
import kotlin.test.*

class RecoveryModelStoreTest {
    private fun content(version: String) = "synthetic-model-$version".toByteArray()
    private fun manifest(version: String) = RecoveryModelManifest("synthetic", version, "https://models.example.invalid/model", content(version).size.toLong(), sha256(content(version)), "liteRtLm", "29", 1, "CPU", "test-only", true)
    @Test fun badHashKeepsPreviousAndCleansStaging() {
        val root = Files.createTempDirectory("takupoke-model-test-").toFile()
        try { val store = RecoveryModelStore(root); val first = store.install(manifest("1"), "liteRtLm", Long.MAX_VALUE, true, { content("1").inputStream() }, {}, {})
            val pointer = root.resolve("active.liteRtLm.json").readText()
            assertFailsWith<IllegalArgumentException> { store.install(manifest("2").copy(sha256 = "0".repeat(64)), "liteRtLm", Long.MAX_VALUE, true, { content("2").inputStream() }, {}, {}) }
            assertTrue(first.exists()); assertEquals(pointer, root.resolve("active.liteRtLm.json").readText()); assertTrue(root.listFiles()!!.none { it.name.startsWith("staging-") })
        } finally { root.deleteRecursively() }
    }
    @Test fun failedSmokeKeepsPrevious() {
        val root = Files.createTempDirectory("takupoke-model-test-").toFile()
        try { val store = RecoveryModelStore(root); val first = store.install(manifest("1"), "liteRtLm", Long.MAX_VALUE, true, { content("1").inputStream() }, {}, {})
            assertFailsWith<IllegalStateException> { store.install(manifest("2"), "liteRtLm", Long.MAX_VALUE, true, { content("2").inputStream() }, { error("synthetic") }, {}) }; assertTrue(first.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun validUpdateSwitchesAndRemovesOldThenSupportsDelete() {
        val root = Files.createTempDirectory("takupoke-model-test-").toFile()
        try { val store = RecoveryModelStore(root); val first = store.install(manifest("1"), "liteRtLm", Long.MAX_VALUE, true, { content("1").inputStream() }, {}, {})
            val next = store.install(manifest("2"), "liteRtLm", Long.MAX_VALUE, true, { content("2").inputStream() }, {}, {}); assertTrue(next.exists()); assertFalse(first.exists())
            store.delete("liteRtLm"); assertFalse(next.exists()); assertFalse(root.resolve("active.liteRtLm.json").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun unvalidatedOrLowMemoryNeverDownload() {
        val root = Files.createTempDirectory("takupoke-model-test-").toFile()
        try { val store = RecoveryModelStore(root); var called = false
            assertFailsWith<IllegalArgumentException> { store.install(manifest("1").copy(validated = false), "liteRtLm", Long.MAX_VALUE, true, { called = true; content("1").inputStream() }, {}, {}) }
            assertFailsWith<IllegalArgumentException> { store.install(manifest("1"), "liteRtLm", 0, true, { called = true; content("1").inputStream() }, {}, {}) }; assertFalse(called)
        } finally { root.deleteRecursively() }
    }
    @Test fun sameBytesVersionChangeRemovesOldAndCleanupRetainsActiveAndLeasedModels() {
        val root = Files.createTempDirectory("takupoke-model-test-").toFile()
        try {
            val store = RecoveryModelStore(root); val first = store.install(manifest("1"), "liteRtLm", Long.MAX_VALUE, true, { content("1").inputStream() }, {}, {})
            val next = store.install(manifest("1").copy(version = "2"), "liteRtLm", Long.MAX_VALUE, true, { content("1").inputStream() }, {}, {})
            assertFalse(first.exists()); assertTrue(next.exists())
            val abandoned = root.resolve("staging-${java.util.UUID.randomUUID()}").apply { writeText("synthetic") }; val other = root.resolve("unrelated.txt").apply { writeText("synthetic") }
            val leased = root.resolve("liteRtLm-synthetic-99-${"a".repeat(64)}.model").apply { writeText("synthetic") }
            store.cleanupAbandonedFiles(setOf(leased)); assertFalse(abandoned.exists()); assertTrue(next.exists()); assertTrue(other.exists()); assertTrue(leased.exists())
            store.delete("liteRtLm"); assertFalse(next.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationAfterMoveKeepsPreviousPointerAndRemovesUnadoptedTarget() {
        val root = Files.createTempDirectory("takupoke-model-test-").toFile()
        try {
            val store = RecoveryModelStore(root); val first = store.install(manifest("1"), "liteRtLm", Long.MAX_VALUE, true, { content("1").inputStream() }, {}, {})
            val pointer = root.resolve("active.liteRtLm.json").readText(); var prepared = false; var after = 0
            assertFailsWith<InterruptedException> { store.install(manifest("2"), "liteRtLm", Long.MAX_VALUE, true, { content("2").inputStream() }, { prepared = true }, { if (prepared && ++after == 2) throw InterruptedException() }) }
            assertTrue(first.exists()); assertEquals(pointer, root.resolve("active.liteRtLm.json").readText()); assertEquals(1, root.listFiles()!!.count { it.name.endsWith(".model") })
        } finally { root.deleteRecursively() }
    }

}
