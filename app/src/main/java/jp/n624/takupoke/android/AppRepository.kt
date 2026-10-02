package jp.n624.takupoke.android

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import jp.n624.takupoke.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

data class AppState(val ready: Boolean = false, val busy: Boolean = false, val settings: Settings = Settings(), val materials: List<MaterialRecord> = emptyList(), val events: List<EventsPayload> = emptyList(), val links: LinksPayload? = null, val mapping: Mapping? = null, val times: TimesPayload? = null, val updates: Set<String> = emptySet(), val message: String? = null, val retentionFailure: Boolean = false, val updateUrl: String? = null, val sourceCheckMessage: String? = null, val startupFailure: Boolean = false, val period: String = "", val accountErrors: Map<String, String> = emptyMap(), val accountFetchedAt: Map<String, Long> = emptyMap(), val accountVersions: Map<String, String> = emptyMap(), val eventsFetchedAt: Map<Int, Long> = emptyMap(), val eventsCheckedAt: Map<Int, Long> = emptyMap(), val eventsError: String? = null, val automaticRefreshSuspended: Boolean = false) {
    val analyses get() = materials.mapNotNull { it.analysis }
}
class AppRepository(val context: Context, private val transport: Transport = HttpTransport(), private val db: Database = Database(context), private val preferences: DataStore<Settings> = settingsStore(context)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex(); private val mutable = MutableStateFlow(AppState()); val state: StateFlow<AppState> = mutable.asStateFlow()
    val auth = Oidc(transport)
    private val root = File(context.noBackupFilesDir, "school/materials").also { it.mkdirs(); Archives.configureTemporaryDirectory(it) }
    private var operation: Job? = null; private val operations = mutableSetOf<Job>(); private var signal: CancellationSignal? = null; private val observers = mutableListOf<ContentObserver>(); private var observerJob: Job? = null
    private var checkedSourceAtStartup = false
    private var checkedMappingAtStartup = false
    private var foreground = false
    private var requestedRevisions: Map<String, String>? = null
    private var automaticRefreshSuspended = false
    init { scope.launch { preferences.data.catch { mutable.update { it.copy(message = "個人設定を読み取れません。保存データは削除していません。") } }.collect { value -> mutable.update { it.copy(settings = value) } } } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun action(queued: Boolean = false, block: suspend () -> Unit) {
        if (operations.any { it.isActive } && !queued) return
        val previous = operation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val current = currentCoroutineContext().job
            var running = false
            try {
                if (queued) previous?.join()
                ensureActive()
                running = true
                mutable.update { it.copy(busy = true, message = null) }
                block()
            } catch (_: CancellationException) { mutable.update { it.copy(startupFailure = !it.ready, message = "処理を中止しました。保存済みの結果は保持しています。") } }
            catch (e: Exception) { mutable.update { it.copy(startupFailure = !it.ready, message = safeError(e)) } }
            finally {
                if (running) { signal?.cancel(); signal = null }
                operations.remove(current)
                mutable.update { it.copy(busy = operations.any { pending -> pending.isActive }) }
                if (operations.none { it.isActive }) observe()
            }
        }
        operation = job; operations += job; job.start()
    }
    fun cancel() { auth.cancel(); requestedRevisions = null; transport.cancel(); signal?.cancel(); operations.toList().asReversed().forEach { it.cancel() }; stopObserving() }
    fun suspendAutomaticRefresh() { automaticRefreshSuspended = true; mutable.update { it.copy(automaticRefreshSuspended = true) }; cancel() }
    private fun safeError(e: Exception): String = when (e) {
        is ParseFailure -> e.message.orEmpty()
        is XlsxFailure -> e.message.orEmpty()
        is WeekdayWarning -> e.message.orEmpty()
        is SecurityException -> "ファイルへのアクセスが必要です。ファイルを選び直してください。"
        else -> "取得または処理を完了できませんでした。保存済みの結果は保持しています。"
    }
    suspend fun settings(transform: (Settings) -> Settings) {
        val saved = preferences.updateData(transform)
        mutable.update { it.copy(settings = saved) }
        withContext(Dispatchers.IO) { mutex.withLock {
            listOf(MaterialKind.CHANGES, MaterialKind.EXAM, MaterialKind.RETURN).filter { if (it == MaterialKind.CHANGES) !saved.changeNotifications else !saved.examNotifications }.forEach { kind ->
                db.writableDatabase.execSQL("DELETE FROM value_store WHERE key LIKE ?", arrayOf("notification:${kind.name}:%"))
                Notifications(context).clearKind(kind.name)
            }
        } }
    }
    fun preferenceAction(transform: (Settings) -> Settings) { scope.launch { try { settings(transform) } catch (_: Exception) { mutable.update { it.copy(message = "個人設定を保存できませんでした。") } } } }
    fun notificationChoice(mode: String, allowed: Boolean) { preferenceAction { it.notificationChoice(mode, allowed) }; if (!allowed) mutable.update { it.copy(message = "Androidの設定で通知を許可してください。") } }
    private fun locked() = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
    suspend fun activate(refresh: Boolean = true) {
        if (locked()) return
        withContext(Dispatchers.IO) { mutex.withLock { retention(); Archives.cleanupTemporaryArchives(); reload() } }
        if (refresh) refresh()
        if (refresh && foreground && !checkedSourceAtStartup) { checkedSourceAtStartup = true; withContext(Dispatchers.IO) { mutex.withLock { runInterruptible { checkSource() } } } }
        observe()
    }
    private fun retention() {
        val current = retentionPeriod()
        if (db.value("period") == current) return
        stopObserving(); auth.cancel(); requestedRevisions = null; checkedMappingAtStartup = false
        try {
            context.contentResolver.persistedUriPermissions.forEach { permission ->
                val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                if (flags != 0) context.contentResolver.releasePersistableUriPermission(permission.uri, flags)
            }
            if (root.exists()) require(root.deleteRecursively())
            require(root.mkdirs())
            Notifications(context).clear()
            db.clearSchool(current)
            mutable.update { it.copy(message = if (it.ready) "保存期間が切り替わりました。ファイルの再選択とデータの再取得が必要です。" else it.message, retentionFailure = false, updates = emptySet(), accountErrors = emptyMap(), accountFetchedAt = emptyMap(), accountVersions = emptyMap()) }
        } catch (e: Exception) { mutable.update { it.copy(ready = false, retentionFailure = true, materials = emptyList(), mapping = null, links = null, times = null) }; throw e }
    }
    private fun reload() {
        fun <T> decode(key: String, reader: (String) -> T): T? = db.value(key)?.let(reader)
        mutable.update { it.copy(ready = true, startupFailure = false, period = db.value("period").orEmpty(), materials = db.records(), events = db.events(), links = decode("links") { json.decodeFromString<LinksPayload>(it).validate() }, mapping = decode("mapping") { json.decodeFromString<Mapping>(it) }, times = decode("times") { json.decodeFromString<TimesPayload>(it).validate() },
            accountFetchedAt = listOf("links", "mapping", "times").mapNotNull { type -> db.value("fetched:$type")?.toLongOrNull()?.let { time -> type to time } }.toMap(),
            accountVersions = listOf("links", "mapping", "times").mapNotNull { type -> db.value("version:$type")?.let { version -> type to version } }.toMap(),
            eventsFetchedAt = db.events().mapNotNull { event -> db.value("events-fetched:${event.schoolYear}")?.toLongOrNull()?.let { time -> event.schoolYear to time } }.toMap(),
            eventsCheckedAt = db.events().mapNotNull { event -> db.value("events-checked:${event.schoolYear}")?.toLongOrNull()?.let { time -> event.schoolYear to time } }.toMap()) }
    }
    suspend fun select(kind: MaterialKind, uri: Uri, flags: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            automaticRefreshSuspended = false; mutable.update { it.copy(automaticRefreshSuspended = false) }
            retention(); require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
            val alreadyHeld = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val old = db.records().firstOrNull { it.kind == kind }
            try {
                acquire(kind, uri, old, manual = false)
            } catch (e: Exception) { if (!alreadyHeld && db.records().none { it.uri == uri.toString() }) release(uri.toString()); throw e }
            finally { try { if (old != null && old.uri != uri.toString() && db.records().none { it.uri == old.uri }) release(old.uri) } finally { reload() } }
        }
        observe()
    }
    private fun release(uri: String) { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    private suspend fun acquire(kind: MaterialKind, uri: Uri, old: MaterialRecord?, manual: Boolean) {
        val generation = retentionPeriod(); val resolver = context.contentResolver
        val cancellation = CancellationSignal().also { signal = it }
        if (manual) runCatching { resolver.refresh(uri, null, cancellation) }
        var name = "資料.${kind.extension}"; var modified: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null, cancellation)?.use { cursor ->
            if (cursor.moveToFirst()) { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }; cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !cursor.isNull(it) }?.let { modified = cursor.getLong(it) }; cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { require(cursor.getLong(it) in 1..50L * 1024 * 1024) } }
        }
        require(name.endsWith(".${kind.extension}", true) && !name.startsWith("~$"))
        val staging = File(root, "staging-${UUID.randomUUID()}"); val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
        try {
            val descriptor = requireNotNull(resolver.openAssetFileDescriptor(uri, "r", cancellation))
            descriptor.use { asset -> asset.createInputStream().use { input -> staging.outputStream().use { output ->
                val buffer = ByteArray(32768)
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 50L * 1024 * 1024); digest.update(buffer, 0, n); output.write(buffer, 0, n) }
            } } }
            require(total > 0); currentCoroutineContext().ensureActive(); require(generation == retentionPeriod())
            val hash = digest.digest().joinToString("") { "%02x".format(it) }; val now = System.currentTimeMillis()
            if (old?.digest == hash && old.uri == uri.toString() && old.analysis != null && old.parsedDigest == hash && old.analysis.parserVersion == PARSER_VERSION && (kind != MaterialKind.CHANGES || old.year == effectiveSchoolYear())) { db.save(old.copy(checkedAt = now, failure = null)); return }
            val destination = File(root, "${kind.name}-$hash.${kind.extension}")
            if (!destination.exists()) require(staging.renameTo(destination))
            val selected = MaterialRecord(kind, uri.toString(), name, hash, now, now, modified, old?.parsedAt, old?.parsedDigest, old?.analysis, year = effectiveSchoolYear())
            db.save(selected)
            analyze(selected, destination)
            root.listFiles()?.filter { it.name.startsWith(kind.name + "-") && it != destination }?.forEach { require(it.delete()) }
        } finally {
            staging.delete(); signal = null
            // Keep the current selected copy and the previous successful source only.
            val keep = db.records().filter { it.kind == kind }.flatMap { listOfNotNull(it.digest, it.parsedDigest) }.toSet()
            root.listFiles()?.filter { it.name.startsWith(kind.name + "-") && it.name.substringAfter('-').substringBeforeLast('.') !in keep }?.forEach { it.delete() }
        }
    }
    private suspend fun analyze(record: MaterialRecord, file: File, year: Int = record.year) {
        val analysis = try {
            runInterruptible { if (record.kind == MaterialKind.CHANGES) XlsxParser.parse(file.readBytes(), year) else PdfReader.parse(file, record.kind) }
        } catch (e: Exception) { db.save(record.copy(failure = safeError(e))); throw e }
            currentCoroutineContext().ensureActive()
            require(retentionPeriod() == db.value("period"))
            val preferences = mutable.value.settings
            val changeCount = if (record.kind == MaterialKind.CHANGES) listOf(preferences.primaryClass, preferences.additionalClass).filter(String::isNotEmpty).distinct().sumOf { cls -> Schedule.changedSlots(record.analysis?.changes, analysis.changes, cls, today()) } else 0
            val examCount = if (record.kind in listOf(MaterialKind.EXAM, MaterialKind.RETURN) && record.parsedDigest != null && record.parsedDigest != record.digest) 1 else 0
            val database = db.writableDatabase; database.beginTransaction()
            try {
                db.save(record.copy(analysis = analysis, parsedAt = System.currentTimeMillis(), parsedDigest = record.digest, failure = null, year = year))
                if (Notifications(context).allowed() && ((changeCount > 0 && preferences.changeNotifications) || (examCount > 0 && preferences.examNotifications))) {
                val id = record.kind.name + ":" + record.digest
                db.writableDatabase.execSQL("DELETE FROM value_store WHERE key LIKE ?", arrayOf("notification:${record.kind.name}:%"))
                db.put("notification:$id", if (changeCount > 0) "時間割変更が${changeCount}件あります" else "${record.kind.title}が${examCount}件更新されました")
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
            dispatchPendingNotifications()
    }
    private fun effectiveSchoolYear(): Int = mutable.value.settings.defaultSchoolYear.trim().toIntOrNull()?.takeIf { it in 1900..9998 } ?: schoolYear()
    suspend fun reparse(kind: MaterialKind, year: Int = effectiveSchoolYear()) = withContext(Dispatchers.IO) { mutex.withLock {
        automaticRefreshSuspended = false; mutable.update { it.copy(automaticRefreshSuspended = false) }; retention(); val record = db.records().single { it.kind == kind }; try { analyze(record, file(record), year) } finally { reload() }
    } }
    suspend fun preview(kind: MaterialKind, year: Int = effectiveSchoolYear()): Analysis = withContext(Dispatchers.IO) { mutex.withLock { retention(); val record = db.records().single { it.kind == kind }; require(record.failure?.contains("曜日") == true); XlsxParser.parse(file(record).readBytes(), year, true) } }
    fun file(record: MaterialRecord) = File(root, "${record.kind.name}-${record.digest}.${record.kind.extension}")
    suspend fun refreshMaterial(kind: MaterialKind) = withContext(Dispatchers.IO) { mutex.withLock {
        retention(); automaticRefreshSuspended = false; mutable.update { it.copy(automaticRefreshSuspended = false) }
        val record = db.records().single { it.kind == kind }
        try { acquire(kind, Uri.parse(record.uri), record, true) } catch (e: Exception) { db.records().firstOrNull { it.kind == kind }?.let { db.save(it.copy(failure = safeError(e))) }; throw e } finally { reload() }
    } }
    suspend fun refresh(manual: Boolean = false) = withContext(Dispatchers.IO) {
        if (locked()) return@withContext
        mutex.withLock {
            retention()
            Archives.cleanupTemporaryArchives()
            root.listFiles()?.filter { it.name.startsWith("staging-") }?.forEach { it.delete() }
            if (manual) { automaticRefreshSuspended = false; mutable.update { it.copy(automaticRefreshSuspended = false) } }
            (if (!automaticRefreshSuspended) db.records() else emptyList()).forEach { record -> currentCoroutineContext().ensureActive()
                try { acquire(record.kind, Uri.parse(record.uri), record, manual) } catch (e: CancellationException) { throw e } catch (e: Exception) { db.records().firstOrNull { it.kind == record.kind }?.let { db.save(it.copy(failure = safeError(e))) } }
            }
            reload()
            mutable.value.events.forEach { events -> try { runInterruptible { fetchEventsLocked(events.schoolYear) } } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(eventsError = "学校行事を確認できませんでした。保存済みの結果は保持しています。") } } }
            runInterruptible { checkRevisions(if (foreground && checkedMappingAtStartup) setOf("links", "times") else revisionTypes.keys) }; if (foreground) checkedMappingAtStartup = true
            dispatchPendingNotifications(); reload()
        }
    }
    private fun dispatchPendingNotifications() {
        db.readableDatabase.rawQuery("SELECT key,value FROM value_store WHERE key LIKE 'notification:%'", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0); val id = key.removePrefix("notification:")
                val enabled = if (id.substringBefore(':') == MaterialKind.CHANGES.name) mutable.value.settings.changeNotifications else mutable.value.settings.examNotifications
                val notifications = Notifications(context)
                if (!enabled || !notifications.allowed() || notifications.send(id, cursor.getString(1))) db.writableDatabase.execSQL("DELETE FROM value_store WHERE key=?", arrayOf(key))
            }
        }
    }
    private fun revision(path: String): String? {
        val installed = db.value("revision:$path")
        val response = transport.request(Endpoints.API + "/$path", installed?.let { mapOf("If-None-Match" to "\"$it\"", "Cache-Control" to "no-cache") } ?: mapOf("Cache-Control" to "no-cache"), maxBytes = 1024)
        require(response.bytes.isEmpty())
        if (response.status == 304) { require(installed != null); return installed }
        require(response.status == 200)
        val etag = requireNotNull(response.header("etag")); require(etag.matches(Regex("\"[A-Za-z0-9_-]{43}\"")))
        return etag.substring(1, etag.lastIndex)
    }
    private val revisionTypes = linkedMapOf("links" to "links-revision", "mapping" to "mapping-revision", "times" to "timetable-times-revision")
    private fun typeTitle(type: String) = when (type) { "links" -> "リンク一覧"; "mapping" -> "名称データ"; else -> "授業時刻" }
    private fun checkRevisions(types: Set<String> = revisionTypes.keys): Map<String, String> {
        val available = mutable.value.updates.toMutableSet(); val errors = mutable.value.accountErrors.toMutableMap(); val revisions = mutableMapOf<String, String>()
        revisionTypes.filterKeys { it in types }.forEach { (type, path) ->
            interrupted()
            try {
                val new = requireNotNull(revision(path)); revisions[type] = new
                if ((type == "times" || db.value("revision:$path") != null) && new != db.value("revision:$path")) available += path else available -= path
                errors.remove(path)
            } catch (e: InterruptedException) { throw e } catch (_: Exception) { errors[path] = "${typeTitle(type)}の更新を確認できませんでした。保存済みの結果は保持しています。" }
        }
        mutable.update { it.copy(updates = available, accountErrors = errors) }
        return revisions
    }
    suspend fun prepareAuth(): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        retention(); reload()
        val revisions = runInterruptible { checkRevisions() }
        requestedRevisions = revisions.filter { (type, revision) -> db.value(type) == null || db.value("revision:${revisionTypes.getValue(type)}") != revision }
        if (requestedRevisions!!.isEmpty()) { requestedRevisions = null; mutable.update { it.copy(message = if (revisions.size == 3) "リンク・名称・授業時刻は最新です。" else "更新を確認できませんでした。保存済みの結果は保持しています。") }; false } else true
    } }
    suspend fun checkLinkRevision() = withContext(Dispatchers.IO) { mutex.withLock { retention(); runInterruptible { checkRevisions(setOf("links")) } } }
    suspend fun finishAuth(uri: Uri) = withContext(Dispatchers.IO) { mutex.withLock {
        retention(); val generation = retentionPeriod(); val token = runInterruptible { auth.finish(uri) }; val errors = mutable.value.accountErrors.toMutableMap()
        val requested = requestedRevisions ?: runInterruptible { checkRevisions() }; requestedRevisions = null
        requested.forEach { (type, rev) ->
            currentCoroutineContext().ensureActive()
            val path = revisionTypes.getValue(type)
            try {
                val value = runInterruptible {
                    val endpoint = when (type) { "mapping" -> "/mappings/current"; "times" -> "/timetable-times"; else -> "/links" }
                    val response = transport.request(Endpoints.API + endpoint, mapOf("Authorization" to "Bearer $token"), maxBytes = if (type == "mapping") 8 * 1024 * 1024 else if (type == "times") 128 * 1024 else 3_000_000)
                    require(response.status == 200)
                    require(response.header("content-type")?.lowercase()?.startsWith(if (type == "mapping") "application/zip" else "application/json") == true)
                    if (type in listOf("mapping", "links")) require(response.header("etag")?.let(::validResponseETag) == true)
                    val expectedHeader = when (type) { "mapping" -> "x-mapping-revision"; "times" -> "x-timetable-times-revision"; else -> "x-links-revision" }
                    require(response.header(expectedHeader) == rev)
                    when (type) {
                        "mapping" -> json.encodeToString(Mapping.serializer(), Mapping.decode(response.bytes, requireNotNull(response.header("x-mapping-version")))) to response.header("x-mapping-version").orEmpty()
                        "times" -> json.encodeToString(TimesPayload.serializer(), json.decodeFromString<TimesPayload>(response.bytes.decodeToString()).validate()) to "1"
                        else -> json.decodeFromString<LinksPayload>(response.bytes.decodeToString()).validate().let { json.encodeToString(LinksPayload.serializer(), it) to it.linksVersion }
                    }
                }
                currentCoroutineContext().ensureActive(); require(generation == retentionPeriod()); val database = db.writableDatabase; database.beginTransaction()
                try { db.put(type, value.first); db.put("version:$type", value.second); db.put("revision:$path", rev); db.put("fetched:$type", System.currentTimeMillis().toString()); database.setTransactionSuccessful() } finally { database.endTransaction() }
                errors.remove(path); mutable.update { it.copy(updates = it.updates - path) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { errors[path] = "${typeTitle(type)}を取得できませんでした。保存済みの結果は保持しています。" }
        }
        reload(); mutable.update { it.copy(accountErrors = errors, message = if (errors.isEmpty()) "リンク・名称・授業時刻を取得しました。" else errors.values.joinToString("\n")) }
    } }

    suspend fun fetchEvents(year: Int) = withContext(Dispatchers.IO) { mutex.withLock { retention(); try { runInterruptible { fetchEventsLocked(year) }; mutable.update { it.copy(eventsError = null) } } catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.update { it.copy(eventsError = "学校行事を取得できませんでした。保存済みの結果は保持しています。") }; throw e } finally { reload() } } }
    private fun fetchEventsLocked(year: Int) {
        require(year in 1900..9998)
        val etag = db.value("events-etag:$year"); val response = transport.request(Endpoints.API + "/events?schoolYear=$year", etag?.let { mapOf("If-None-Match" to it) }.orEmpty(), maxBytes = 1_000_000)
        val received = response.header("etag")
        if (response.status == 304) { require(response.bytes.isEmpty() && db.value("events:$year") != null && etag != null && validResponseETag(etag) && received?.let(::validResponseETag) == true && received.removePrefix("W/") == etag.removePrefix("W/")); db.put("events-checked:$year", System.currentTimeMillis().toString()); return }
        require(response.status == 200 && received != null && validResponseETag(received))
        val payload = json.decodeFromString<EventsPayload>(response.bytes.decodeToString()).validate(year)
        val database = db.writableDatabase; database.beginTransaction(); try { db.put("events:$year", json.encodeToString(EventsPayload.serializer(), payload)); db.put("events-etag:$year", received); db.put("events-fetched:$year", System.currentTimeMillis().toString()); db.put("events-checked:$year", System.currentTimeMillis().toString()); database.setTransactionSuccessful() } finally { database.endTransaction() }
    }
    private fun checkSource() {
        val saved = mutable.value.events.firstOrNull { it.schoolYear == 2026 } ?: return
        val expected = saved.sourcePdfETag
        if (expected == null) { mutable.update { it.copy(sourceCheckMessage = "保存済みの学校行事には元PDFのETagがありません。") }; return }
        try {
            val response = transport.request("https://www.kagawa-nct.ac.jp/school_affairs/event/calendar.pdf", mapOf("Cache-Control" to "no-cache"), maxBytes = 0, head = true)
            require(response.status == 200 && response.header("etag") != null)
            mutable.update { it.copy(sourceCheckMessage = if (response.header("etag") == expected) null else "学校サイトの学校行事PDFが更新された可能性があります。APIの更新を確認してください。") }
        } catch (_: Exception) { mutable.update { it.copy(sourceCheckMessage = "学校サイトの元PDFを確認できませんでした。保存済みの学校行事は保持しています。") } }
    }
    suspend fun checkRelease() = withContext(Dispatchers.IO) {
        val result = transport.request("https://api.github.com/repos/n624-dev/takupoke-android/releases/latest", maxBytes = 1_000_000)
        if (result.status == 404) { mutable.update { it.copy(message = "配布版はまだ公開されていません。") }; return@withContext }
        require(result.status == 200)
        val release = json.parseToJsonElement(result.bytes.decodeToString()).jsonObject
        val tag = release.getValue("tag_name").jsonPrimitive.content
        val url = release.getValue("html_url").jsonPrimitive.content; require(url.startsWith("https://github.com/n624-dev/takupoke-android/releases/tag/"))
        mutable.update { it.copy(message = "最新の配布版: $tag", updateUrl = url) }
    }
    fun observe() {
        stopObserving()
        if (!foreground || automaticRefreshSuspended) return
        mutable.value.materials.map { it.uri }.distinct().forEach { value ->
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { if (!selfChange && operations.none { it.isActive }) { observerJob?.cancel(); observerJob = scope.launch { delay(1500); action { refresh() } } } } }
            runCatching { context.contentResolver.registerContentObserver(Uri.parse(value), false, observer); observers += observer }
        }
    }
    fun foreground(active: Boolean) { if (active && !foreground) { automaticRefreshSuspended = false; mutable.update { it.copy(automaticRefreshSuspended = false) } }; foreground = active; if (!active) stopObserving() }
    fun stopObserving() { observerJob?.cancel(); observers.forEach { context.contentResolver.unregisterContentObserver(it) }; observers.clear() }
}
