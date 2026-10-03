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
    @Test fun queuedImportsWaitForForegroundRefreshAndKeepSelectionOrder() = runBlocking {
        val repository = AppRepository(isolated(), RejectNetwork, preferences = MemorySettings())
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        val selected = mutableListOf<String>()
        instrumentation.runOnMainSync {
            repository.action { entered.complete(Unit); release.await() }
            repository.action(queued = true) { selected += "fixture-one.xlsx" }
            repository.action(queued = true) { selected += "fixture-two.xlsx"; finished.complete(Unit) }
        }
        kotlinx.coroutines.withTimeout(5000) { entered.await() }
        assertTrue(selected.isEmpty())
        release.complete(Unit)
        kotlinx.coroutines.withTimeout(5000) { finished.await() }
        assertEquals(listOf("fixture-one.xlsx", "fixture-two.xlsx"), selected)
        instrumentation.waitForIdleSync()
        assertFalse(repository.state.value.busy)
    }
    @Test fun cancelStopsRunningAndQueuedImportsWithoutStickingBusyState() = runBlocking {
        val repository = AppRepository(isolated(), RejectNetwork, preferences = MemorySettings())
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var queuedImportRan = false
        instrumentation.runOnMainSync {
            repository.action { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            repository.action(queued = true) { queuedImportRan = true }
        }
        kotlinx.coroutines.withTimeout(5000) { entered.await() }
        instrumentation.runOnMainSync { repository.cancel() }
        kotlinx.coroutines.withTimeout(5000) { while (repository.state.value.busy) kotlinx.coroutines.delay(10) }
        assertFalse(queuedImportRan)
        val resumed = kotlinx.coroutines.CompletableDeferred<Unit>()
        instrumentation.runOnMainSync { repository.action { resumed.complete(Unit) } }
        kotlinx.coroutines.withTimeout(5000) { resumed.await() }
    }
    @Test fun missingTimesHaveUpdateNoticeWhileMissingLinksAndMappingDoNot() = runBlocking {
        val revision = "a".repeat(43)
        val fake = object : Transport {
            override fun request(url: String, headers: Map<String, String>, body: RequestBody?, maxBytes: Int, head: Boolean): HttpResult {
                require(url.endsWith("-revision")); return HttpResult(200, byteArrayOf(), mapOf("etag" to "\"$revision\""))
            }
        }
        val repository = AppRepository(isolated(), fake, preferences = MemorySettings())
        repository.activate(false); assertTrue(repository.prepareAuth())
        assertEquals(setOf("timetable-times-revision"), repository.state.value.updates)
        repository.foreground(true); repository.suspendAutomaticRefresh()
        assertTrue(repository.state.value.automaticRefreshSuspended)
        repository.foreground(false); repository.foreground(true)
        assertFalse(repository.state.value.automaticRefreshSuspended)
    }
    @Test fun unchangedRevisionsAvoidAuthenticationAndFailedChecksKeepSavedData() = runBlocking {
        val c = isolated(); val db = Database(c)
        val revision = "a".repeat(43)
        var failChecks = false
        var calls = 0
        val fake = object : Transport {
            override fun request(url: String, headers: Map<String, String>, body: RequestBody?, maxBytes: Int, head: Boolean): HttpResult {
                require(url in listOf("links-revision", "mapping-revision", "timetable-times-revision").map { Endpoints.API + "/" + it })
                calls++; assertNull(body); assertNull(headers["Authorization"])
                if (failChecks) error("Synthetic offline failure")
                assertEquals("\"$revision\"", headers["If-None-Match"])
                return HttpResult(304, byteArrayOf(), emptyMap())
            }
        }
        val repository = AppRepository(c, fake, db, MemorySettings()); repository.activate(false)
        val links = LinksPayload("v1", "sha256-" + "a".repeat(64), listOf(LinkCategory("fixture", "架空カテゴリ", 0, emptyList())))
        db.put("links", json.encodeToString(LinksPayload.serializer(), links))
        db.put("mapping", json.encodeToString(Mapping.serializer(), Mapping(emptyList(), emptyList(), emptyList())))
        db.put("times", json.encodeToString(TimesPayload.serializer(), TimesPayload(1, emptyList())))
        listOf("links-revision", "mapping-revision", "timetable-times-revision").forEach { db.put("revision:$it", revision) }
        assertFalse(repository.prepareAuth()); assertEquals(3, calls); assertEquals(links, repository.state.value.links)
        failChecks = true
        assertFalse(repository.prepareAuth()); assertEquals(3, repository.state.value.accountErrors.size)
        assertEquals(links, repository.state.value.links); assertNotNull(repository.state.value.mapping)
    }
    @Test fun notificationBaselineAndPrivateCountOnlyDelivery() = runBlocking {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val c = isolated(); val db = Database(c)
        val repository = AppRepository(c, RejectNetwork, db, MemorySettings(Settings(primaryClass = "1_CN", changeNotifications = true)))
        repository.activate(false)
        kotlinx.coroutines.withTimeout(5000) { while (repository.state.value.settings.primaryClass != "1_CN") kotlinx.coroutines.delay(10) }
        val uri = DocumentsContract.buildDocumentUri(SyntheticDocuments.AUTHORITY, "changes")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val date = today().plusDays(2).toString()
        replaceDocument(syntheticXlsx("理科", date), grant = true)
        try {
            repository.select(MaterialKind.CHANGES, uri, flags); assertTrue(manager.activeNotifications.isEmpty())
            replaceDocument(syntheticXlsx("英語", date)); repository.refresh(true)
            kotlinx.coroutines.withTimeout(5000) { while (manager.activeNotifications.isEmpty()) kotlinx.coroutines.delay(10) }
            val notification = manager.activeNotifications.single().notification
            assertEquals("時間割変更が1件あります", notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertFalse(notification.extras.toString().contains("英語"))
            repository.settings { it.copy(changeNotifications = false) }
            kotlinx.coroutines.withTimeout(5000) { while (manager.activeNotifications.isNotEmpty()) kotlinx.coroutines.delay(10) }
            replaceDocument(syntheticXlsx("国語", date)); repository.refresh(true)
            assertTrue(manager.activeNotifications.isEmpty())
        } finally { repository.stopObserving(); Notifications(context).clear(); context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
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
        replaceDocument(syntheticXlsx("理科"), grant = true)
        repository.select(MaterialKind.CHANGES, uri, flags)
        val initial = db.records().single(); assertEquals("理科", initial.analysis!!.changes.single().after); assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
        replaceDocument(syntheticXlsx("英語"))
        repository.refresh(true)
        val changed = db.records().single(); assertNotEquals(initial.digest, changed.digest); assertEquals("英語", changed.analysis!!.changes.single().after)
        replaceDocument("invalid synthetic file".toByteArray())
        repository.refresh(true)
        val failed = db.records().single(); assertNotNull(failed.failure); assertEquals("英語", failed.analysis!!.changes.single().after); assertEquals(changed.digest, failed.parsedDigest); assertNotEquals(changed.digest, failed.digest)
        repository.stopObserving(); c.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private fun replaceDocument(bytes: ByteArray, grant: Boolean = false) {
        context.contentResolver.call(SyntheticControl.AUTHORITY, "replaceSynthetic", null, Bundle().apply { putByteArray("bytes", bytes); putBoolean("grant", grant) })
    }
    @Test fun unchangedDigestUpdatesSafNameAndSourceTimeWithoutReparsing() = runBlocking {
        val c=isolated();val db=Database(c);val repository=AppRepository(c,RejectNetwork,db,MemorySettings())
        val uri=DocumentsContract.buildDocumentUri(SyntheticDocuments.AUTHORITY,"changes")
        val bytes=syntheticXlsx("架空の更新後科目")
        fun publish(name:String,modified:Long,grant:Boolean=false) {
            context.contentResolver.call(SyntheticControl.AUTHORITY,"replaceSynthetic",null,Bundle().apply { putByteArray("bytes",bytes);putBoolean("grant",grant);putString("displayName",name);putLong("modified",modified) })
        }
        publish("架空の旧表示名.xlsx",1000,true)
        try {
            repository.select(MaterialKind.CHANGES,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            val initial=db.records().single().copy(fetchedAt=123,parsedAt=456,checkedAt=1)
            db.save(initial);publish("架空の新表示名.xlsx",2000)
            repository.refreshMaterial(MaterialKind.CHANGES)
            val current=db.records().single()
            assertEquals("架空の新表示名.xlsx",current.name);assertEquals(2000L,current.sourceModified)
            assertEquals(initial.digest,current.digest);assertEquals(initial.parsedDigest,current.parsedDigest);assertEquals(initial.analysis,current.analysis)
            assertEquals(123L,current.fetchedAt);assertEquals(456L,current.parsedAt);assertTrue(current.checkedAt>1);assertNull(current.failure)
            assertEquals(current,repository.state.value.materials.single())
        } finally { repository.stopObserving();c.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    @Test fun defaultYearPersistsAndReparsesIdenticalFileOnRefreshAndReselect() = runBlocking {
        val c = isolated(); val db = Database(c)
        val preferences = MemorySettings(Settings(defaultSchoolYear = "2020"))
        val repository = AppRepository(c, RejectNetwork, db, preferences)
        repository.activate(false)
        repository.settings { it.copy(defaultSchoolYear = "2020") }
        val uri = DocumentsContract.buildDocumentUri(SyntheticDocuments.AUTHORITY, "changes")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        replaceDocument(syntheticXlsx("架空科目B", "10/1"), grant = true)
        try {
            repository.select(MaterialKind.CHANGES, uri, flags)
            val digest = db.records().single().digest
            assertEquals("2020-10-01", db.records().single().analysis!!.changes.single().date)
            repository.settings { it.copy(defaultSchoolYear = "2021") }
            repository.refreshMaterial(MaterialKind.CHANGES)
            assertEquals(digest, db.records().single().digest)
            assertEquals("2021-10-01", db.records().single().analysis!!.changes.single().date)
            repository.settings { it.copy(defaultSchoolYear = "2022") }
            repository.select(MaterialKind.CHANGES, uri, flags)
            assertEquals("2022-10-01", db.records().single().analysis!!.changes.single().date)
            repository.settings { it.copy(defaultSchoolYear = "") }
            repository.reparse(MaterialKind.CHANGES)
            assertEquals("${schoolYear()}-10-01", db.records().single().analysis!!.changes.single().date)
            assertEquals("", preferences.data.value.defaultSchoolYear)
        } finally { repository.stopObserving(); c.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
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
