package jp.n624.takupoke.android

import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.n624.takupoke.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assert.fail
import org.junit.runner.RunWith
import java.io.File
import okhttp3.RequestBody

@RunWith(AndroidJUnit4::class)
class PlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun isolated(): android.content.Context = object : ContextWrapper(context) {
        private val directory = File(context.cacheDir, "test-${java.util.UUID.randomUUID()}").also { it.mkdirs() }
        override fun getNoBackupFilesDir(): File = directory
    }
    @Test fun eventsConditionalResponsesPreserveLastValidPayloadAndOlderYears() = runBlocking {
        val c = isolated(); val db = Database(c)
        val event = EventsPayload("v1", 2020, "a".repeat(64), events = listOf(Event("2020-10-01", "2020-10-01", "合成公開行事", "行事")))
        var response = HttpResult(200, json.encodeToString(EventsPayload.serializer(), event).toByteArray(), mapOf("etag" to "\"synthetic\""))
        var sentEtag: String? = null
        val fake = object : Transport { override fun request(url: String, headers: Map<String, String>, body: RequestBody?, maxBytes: Int, head: Boolean): HttpResult { require(url == Endpoints.API + "/events?schoolYear=2020"); sentEtag = headers["If-None-Match"]; return response } }
        val repository = AppRepository(c, fake, db, MemorySettings()); repository.fetchEvents(2020)
        assertEquals(2020, repository.state.value.events.single().schoolYear)
        response = HttpResult(304, byteArrayOf(), mapOf("etag" to "W/\"synthetic\"")); repository.fetchEvents(2020); assertEquals("\"synthetic\"", sentEtag)
        response = response.copy(headers = mapOf("etag" to "\"wrong\""))
        try { repository.fetchEvents(2020); fail("Mismatched 304 accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(event, repository.state.value.events.single())
        response = HttpResult(200, "invalid".toByteArray(), mapOf("etag" to "\"new\""))
        try { repository.fetchEvents(2020); fail("Invalid payload accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(event, repository.state.value.events.single()); assertEquals("\"synthetic\"", db.value("events-etag:2020"))
    }
    @Test fun retentionDeletionFailureIsFailClosedAndRetryable() = runBlocking {
        val c = isolated(); val db = Database(c); db.put("period", "2000-1"); db.put("mapping", "synthetic private data")
        val root = File(c.noBackupFilesDir, "school/materials").also { it.mkdirs() }; File(root, "owned-synthetic.txt").writeText("synthetic")
        assertTrue(root.setWritable(false, false))
        try {
            val repository = AppRepository(c, RejectNetwork, db, MemorySettings())
            try { repository.activate(false); fail("Deletion failure accepted") } catch (_: IllegalArgumentException) { }
            assertFalse(repository.state.value.ready); assertTrue(repository.state.value.retentionFailure); assertNull(repository.state.value.mapping); assertEquals("2000-1", db.value("period"))
            root.setWritable(true, true); repository.activate(false); assertTrue(repository.state.value.ready); assertNull(db.value("mapping")); assertEquals(retentionPeriod(), db.value("period"))
        } finally { root.setWritable(true, true) }
    }
    @Test fun retentionDropsPrivateDataButKeepsPublicEventsAndSettings() = runBlocking {
        val c = isolated(); val db = Database(c); val settings = MemorySettings(Settings(primaryClass = "1_CN", favorites = setOf("example")))
        db.put("period", "2000-1"); db.put("mapping", "private"); db.put("notification:old", "old")
        val events = EventsPayload("v1", schoolYear(), "a".repeat(64), events = listOf(Event("${schoolYear()}-10-01", "${schoolYear()}-10-01", "合成行事", "行事")))
        db.put("events:${schoolYear()}", json.encodeToString(EventsPayload.serializer(), events)); db.put("events-etag:${schoolYear()}", "\"test\"")
        val root = File(c.noBackupFilesDir, "school/materials").also { it.mkdirs() }; File(root, "old.txt").writeText("synthetic")
        val repository = AppRepository(c, RejectNetwork, db, settings); repository.activate(false)
        assertNull(db.value("mapping")); assertNull(db.value("notification:old")); assertEquals(retentionPeriod(), db.value("period")); assertTrue(root.listFiles()!!.isEmpty())
        assertEquals(1, repository.state.value.events.size); assertEquals("1_CN", settings.data.value.primaryClass); assertEquals(setOf("example"), settings.data.value.favorites)
    }
    @Test fun safPersistsReadPermissionUpdatesDigestAndPreservesLastSuccessfulAnalysis() = runBlocking {
        val c = isolated(); val db = Database(c); val repository = AppRepository(c, RejectNetwork, db, MemorySettings())
        val uri = DocumentsContract.buildDocumentUri(SyntheticDocuments.AUTHORITY, "changes")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        instrumentation.context.contentResolver.call(SyntheticDocuments.AUTHORITY, "replaceSynthetic", null, Bundle().apply { putByteArray("bytes", syntheticXlsx("理科")) })
        instrumentation.context.grantUriPermission(context.packageName, uri, flags)
        repository.select(MaterialKind.CHANGES, uri, flags)
        val initial = db.records().single(); assertEquals("理科", initial.analysis!!.changes.single().after); assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        instrumentation.context.contentResolver.call(SyntheticDocuments.AUTHORITY, "replaceSynthetic", null, Bundle().apply { putByteArray("bytes", syntheticXlsx("英語")) })
        repository.refresh(true)
        val changed = db.records().single(); assertNotEquals(initial.digest, changed.digest); assertEquals("英語", changed.analysis!!.changes.single().after)
        instrumentation.context.contentResolver.call(SyntheticDocuments.AUTHORITY, "replaceSynthetic", null, Bundle().apply { putByteArray("bytes", "invalid synthetic file".toByteArray()) })
        repository.refresh(true)
        val failed = db.records().single(); assertNotNull(failed.failure); assertEquals("英語", failed.analysis!!.changes.single().after); assertEquals(changed.digest, failed.parsedDigest); assertNotEquals(changed.digest, failed.digest)
        repository.stopObserving(); c.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    @Test fun runtimeXmlDefensesAndPrivateBackupLocation() {
        assertEquals(1, XlsxParser.parse(syntheticXlsx("理科"), 2026).changes.size)
        try { SafeXml.parse("<!DOCTYPE x SYSTEM 'https://forbidden.invalid'><x/>".toByteArray()); fail("DTD accepted") } catch (_: IllegalArgumentException) { }
        assertFalse(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0)
    }
}
internal class MemorySettings(initial: Settings = Settings()) : DataStore<Settings> {
    override val data = MutableStateFlow(initial)
    override suspend fun updateData(transform: suspend (Settings) -> Settings): Settings = transform(data.value).also { data.value = it }
}
