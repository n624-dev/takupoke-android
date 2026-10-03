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

data class AppState(val ready: Boolean = false, val busy: Boolean = false, val settings: Settings = Settings(), val materials: List<MaterialRecord> = emptyList(), val events: List<EventsPayload> = emptyList(), val links: LinksPayload? = null, val mapping: Mapping? = null, val times: TimesPayload? = null, val updates: Set<String> = emptySet(), val message: String? = null, val retentionFailure: Boolean = false, val updateUrl: String? = null, val sourceCheckMessage: String? = null, val startupFailure: Boolean = false, val period: String = "", val accountErrors: Map<String, String> = emptyMap(), val accountFetchedAt: Map<String, Long> = emptyMap(), val accountVersions: Map<String, String> = emptyMap(), val eventsFetchedAt: Map<Int, Long> = emptyMap(), val eventsCheckedAt: Map<Int, Long> = emptyMap(), val eventsError: String? = null, val automaticRefreshSuspended: Boolean = false, val recoveryPreviews: Map<MaterialKind,RecoveryPreview> = emptyMap(), val recoveryModel: RecoveryModelStatus = RecoveryModelStatus()) {
    val analyses get() = materials.mapNotNull { it.analysis }
}
class AppRepository(val context: Context, private val transport: Transport = HttpTransport(), private val db: Database = Database(context), private val preferences: DataStore<Settings> = settingsStore(context), private val recoveryServices:RecoveryServices = DeviceRecoveryServices(context)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex(); private val mutable = MutableStateFlow(AppState(recoveryModel=RecoveryModelStatus(offered=recoveryServices.offered))); val state: StateFlow<AppState> = mutable.asStateFlow()
    val auth = Oidc(transport)
    private val root = File(context.noBackupFilesDir, "school/materials").also { it.mkdirs(); Archives.configureTemporaryDirectory(it) }
    private var operation: Job? = null; private val operations = mutableSetOf<Job>(); private var signal: CancellationSignal? = null; private val observers = mutableListOf<ContentObserver>(); private var observerJob: Job? = null
    private var checkedSourceAtStartup = false
    private var checkedMappingAtStartup = false
    @Volatile private var foreground = false
    private var recoveryOperation: Job? = null
    private val recoveryCaptures = mutableMapOf<MaterialKind, Pair<String,RecoveryReadCapture>>()
    private var requestedRevisions: Map<String, String>? = null
    private var automaticRefreshSuspended = false
    private val observedRefreshQueue = ObservedRefreshQueue()
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
    fun cancel() { operations.toList().asReversed().forEach { it.cancel() }; auth.cancel(); requestedRevisions = null; transport.cancel(); signal?.cancel(); stopObserving() }
    fun suspendAutomaticRefresh() { automaticRefreshSuspended = true; mutable.update { it.copy(automaticRefreshSuspended = true) }; cancel() }
    private fun safeError(e: Exception): String = when (e) {
        is ParseFailure -> e.message.orEmpty()
        is XlsxFailure -> e.message.orEmpty()
        is WeekdayWarning -> e.message.orEmpty()
        is RecoveryPreparationFailure -> e.message.orEmpty()
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
        withContext(Dispatchers.IO) { mutex.withLock { retention(); Archives.cleanupTemporaryArchives(); recoveryServices.cleanup(); if(recoveryOperation==null)db.records().filter { it.recoveryJob?.state in setOf(RecoveryJobState.PREPARING,RecoveryJobState.RUNNING) }.forEach { record -> db.save(record.copy(recoveryJob=record.recoveryJob?.copy(state=RecoveryJobState.PENDING))) }; reload() } }
        if (refresh) refresh()
        if (refresh && foreground && !checkedSourceAtStartup) { checkedSourceAtStartup = true; withContext(Dispatchers.IO) { mutex.withLock { runInterruptible { checkSource() } } } }
        observe()
    }
    private fun retention() {
        val current = retentionPeriod()
        if (db.value("period") == current) return
        stopObserving(); recoveryCaptures.clear(); auth.cancel(); requestedRevisions = null; checkedMappingAtStartup = false
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
        mutable.update { it.copy(ready = true, startupFailure = false, period = db.value("period").orEmpty(), materials = db.records(), recoveryPreviews = db.records().mapNotNull { record -> db.value("recovery-preview:${record.kind.name}")?.let { value -> runCatching { json.decodeFromString<RecoveryPreview>(value) }.getOrNull()?.takeIf { p -> p.period == db.value("period") && p.uri == record.uri && p.document.pdfHash == record.digest && record.recoveryJob?.state == RecoveryJobState.AWAITING_CONFIRMATION && record.recoveryJob.resultHash==p.resultHash && runCatching { RecoveryValidator.validate(p.document,p.result).canAdopt }.getOrDefault(false) }?.let { p -> record.kind to p } } }.toMap(), recoveryModel = it.recoveryModel.copy(installed = recoveryServices.installed(), error = recoveryServices.error), events = db.events(), links = decode("links") { json.decodeFromString<LinksPayload>(it).validate() }, mapping = decode("mapping") { json.decodeFromString<Mapping>(it) }, times = decode("times") { json.decodeFromString<TimesPayload>(it).validate() },
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
            if (old?.digest == hash && old.uri == uri.toString() && old.analysis != null && old.parsedDigest == hash && old.analysis.parserVersion == PARSER_VERSION && (kind != MaterialKind.CHANGES || old.year == effectiveSchoolYear())) { db.save(old.copy(name = name, sourceModified = modified, checkedAt = now, failure = null)); return }
            val destination = File(root, "${kind.name}-$hash.${kind.extension}")
            if (!destination.exists()) require(staging.renameTo(destination))
            val selected = MaterialRecord(kind, uri.toString(), name, hash, now, now, modified, old?.parsedAt, old?.parsedDigest, old?.analysis, year = effectiveSchoolYear(), recoveryMetadata=old?.recoveryMetadata, recoveryAcceptance=old?.recoveryAcceptance)
            db.writableDatabase.execSQL("DELETE FROM value_store WHERE key=?", arrayOf("recovery-preview:${kind.name}"))
            recoveryCaptures.remove(kind)
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
        val startedPeriod = retentionPeriod()
        val capture = RecoveryReadCapture()
        val analysis = try {
            runInterruptible { if (record.kind == MaterialKind.CHANGES) XlsxParser.parse(file.readBytes(), year) else PdfReader.parse(file, record.kind, capture) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            require(retentionPeriod() == startedPeriod && db.value("period") == startedPeriod)
            val job = (e as? ParseFailure)?.takeIf { RecoveryPolicy.eligible(record.kind, it.code) }?.let {
                RecoveryJob(record.digest, requireNotNull(RecoveryPolicy.kind(record.kind)), RecoveryJobState.PENDING, System.currentTimeMillis())
            }
            if (job != null) recoveryCaptures[record.kind] = record.digest to capture
            db.save(record.copy(failure = safeError(e), recoveryJob = job)); throw e
        }
            currentCoroutineContext().ensureActive()
            require(retentionPeriod() == startedPeriod && db.value("period") == startedPeriod)
            val preferences = mutable.value.settings
            val changeCount = if (record.kind == MaterialKind.CHANGES) listOf(preferences.primaryClass, preferences.additionalClass).filter(String::isNotEmpty).distinct().sumOf { cls -> Schedule.changedSlots(record.analysis?.changes, analysis.changes, cls, today()) } else 0
            val examCount = if (record.kind in listOf(MaterialKind.EXAM, MaterialKind.RETURN) && record.parsedDigest != null && record.parsedDigest != record.digest) 1 else 0
            val database = db.writableDatabase; database.beginTransaction()
            try {
                db.save(record.copy(analysis = analysis, parsedAt = System.currentTimeMillis(), parsedDigest = record.digest, failure = null, year = year, recoveryJob = null, recoveryMetadata = null, recoveryAcceptance = null))
                if (Notifications(context).allowed() && ((changeCount > 0 && preferences.changeNotifications) || (examCount > 0 && preferences.examNotifications))) {
                val id = record.kind.name + ":" + record.digest
                db.writableDatabase.execSQL("DELETE FROM value_store WHERE key LIKE ?", arrayOf("notification:${record.kind.name}:%"))
                val classes=listOf(preferences.primaryClass,preferences.additionalClass).filter(String::isNotEmpty).map(::canonicalClass).toSet()
                val notice=if(changeCount>0)PendingMaterialNotice.changes(record.digest,record.analysis?.changes,analysis.changes,classes,today())else PendingMaterialNotice(record.kind,record.digest)
                db.put("notification:$id",json.encodeToString(PendingMaterialNotice.serializer(),notice))
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
            recoveryCaptures.remove(record.kind)
            db.writableDatabase.execSQL("DELETE FROM value_store WHERE key=?", arrayOf("recovery-preview:${record.kind.name}"))
            dispatchPendingNotifications()
    }
    /** Explicit user action only: the background refresh never enters this method. */
    suspend fun startRecovery(kind: MaterialKind) = withContext(Dispatchers.IO) { mutex.withLock {
        require(foreground && !locked() && RecoveryPolicy.kind(kind) != null)
        retention(); val record = db.records().single { it.kind == kind }
        require(record.recoveryJob?.pdfHash == record.digest)
        val epoch = db.value("period").orEmpty(); val operation = currentCoroutineContext().job
        recoveryOperation = operation
        fun checkCurrent() {
            operation.ensureActive()
            if (!foreground || locked()) throw CancellationException("アプリを開いている時に復旧してください。")
            require(epoch == retentionPeriod() && db.value("period") == epoch)
            val current = db.records().singleOrNull { it.kind == kind }
            require(current?.digest == record.digest && current.uri == record.uri)
        }
        fun stage(stage: RecoveryJobState) { checkCurrent(); db.save(record.copy(recoveryJob = record.recoveryJob!!.copy(state = stage))); reload() }
        try {
            stage(RecoveryJobState.PREPARING)
            val capture = recoveryCaptures[kind]?.takeIf { it.first == record.digest }?.second ?: RecoveryReadCapture().also { fresh ->
                try { analyze(record,file(record)); return@withLock }
                catch (e: CancellationException) { throw e }
                catch (e: ParseFailure) { require(RecoveryPolicy.eligible(kind,e.code)) }
                recoveryCaptures[kind]?.takeIf { it.first == record.digest }?.second?.let { existing ->
                    fresh.begin(existing.pages.size); existing.pages.forEach { page -> page.layout?.let { fresh.record(page.page,page.state,it) } }; if(existing.readerCompleted)fresh.finish()
                }
            }
            checkCurrent(); val original = file(record)
            require(runInterruptible { sha256(original.readBytes()) } == record.digest)
            val document=recoveryServices.prepare(original,record.digest,kind,capture)
            checkCurrent()
            require(document.schoolYear == schoolYear())
            if(kind == MaterialKind.TIMETABLE)require(document.term == if(epoch.endsWith("-1"))"前期" else "後期")
            stage(RecoveryJobState.RUNNING)
            val provider = recoveryServices.provider { foreground && epoch == retentionPeriod() }
            val run = try { RecoveryEngine.run(document,"android",android.os.Build.VERSION.SDK_INT,true,listOfNotNull(provider),{ null },::checkCurrent) } finally { (provider as? AutoCloseable)?.close() }
            checkCurrent()
            val result = run.result
            if(run.state == RecoveryJobState.AWAITING_CONFIRMATION && result != null) {
                val preview = RecoveryPreview(epoch,record.uri,document,result,System.currentTimeMillis())
                val prior = db.value("recovery-accepted:${kind.name}:${record.digest}")?.let { runCatching { json.decodeFromString<RecoveryAccepted>(it) }.getOrNull() }
                if(prior != null && RecoveryValidator.canReuse(prior.acceptance,document,result)) {
                    commitRecovery(record,preview,prior.acceptance,::checkCurrent); mutable.update { it.copy(message="確認済みの同じPDFの復旧結果を使用しました。") }
                } else {
                    val database=db.writableDatabase;database.beginTransaction()
                    try { checkCurrent();db.put("recovery-preview:${kind.name}",json.encodeToString(RecoveryPreview.serializer(),preview));db.save(record.copy(recoveryJob=record.recoveryJob!!.copy(state=RecoveryJobState.AWAITING_CONFIRMATION,resultHash=preview.resultHash)));database.setTransactionSuccessful() } finally { database.endTransaction() }
                    mutable.update { it.copy(message="復旧結果を元PDFと確認してから採用してください。") }
                }
            } else {
                db.save(record.copy(recoveryJob=record.recoveryJob!!.copy(state=run.state)))
                mutable.update { it.copy(message=if(run.state==RecoveryJobState.AWAITING_MODEL)"端末内AIモデルの準備が必要です。学校の資料は外部へ送信されません。" else "復旧の確認条件を満たせませんでした。前回の正常結果を保持しています。") }
            }
        } catch(e:CancellationException) {
            if(epoch==retentionPeriod() && db.value("period")==epoch)db.records().firstOrNull { it.kind==kind && it.digest==record.digest && it.uri==record.uri }?.let { db.save(it.copy(recoveryJob=it.recoveryJob?.copy(state=RecoveryJobState.PENDING))) }
            throw e
        } catch(e:Exception) {
            if(epoch==retentionPeriod() && db.value("period")==epoch)db.records().firstOrNull { it.kind==kind && it.digest==record.digest && it.uri==record.uri }?.let { db.save(it.copy(recoveryJob=it.recoveryJob?.copy(state=RecoveryJobState.FAILED))) }
            throw e
        } finally { if(recoveryOperation==operation)recoveryOperation=null;reload() }
    } }
    suspend fun adoptRecovery(kind: MaterialKind, resultHash: String) = withContext(Dispatchers.IO) { mutex.withLock {
        require(foreground && !locked()); retention()
        val record=db.records().single { it.kind==kind }
        val preview=json.decodeFromString<RecoveryPreview>(requireNotNull(db.value("recovery-preview:${kind.name}")))
        require(preview.resultHash==resultHash && record.recoveryJob?.resultHash==resultHash && record.recoveryJob.state==RecoveryJobState.AWAITING_CONFIRMATION)
        currentCoroutineContext().ensureActive()
        val acceptance=RecoveryAcceptance(record.digest,resultHash,RecoveryValidator.fingerprint(preview.document),preview.result.metadata,System.currentTimeMillis())
        val operation=currentCoroutineContext().job
        commitRecovery(record,preview,acceptance) { operation.ensureActive();require(foreground && !locked()) };reload()
    } }
    private fun commitRecovery(record:MaterialRecord,preview:RecoveryPreview,acceptance:RecoveryAcceptance,check:()->Unit) {
        check()
        require(foreground && !locked() && preview.period==retentionPeriod() && db.value("period")==preview.period && preview.uri==record.uri && preview.document.pdfHash==record.digest)
        val currentSelection=db.records().single { it.kind==record.kind }
        require(RecoveryAdoption.allowed(RecoverySelection(preview.period,preview.uri,record.kind,preview.document.pdfHash),RecoverySelection(db.value("period").orEmpty(),currentSelection.uri,currentSelection.kind,currentSelection.digest),sha256(file(record).readBytes()),preview.document,preview.result) && RecoveryValidator.canReuse(acceptance,preview.document,preview.result))
        require(preview.document.schoolYear==schoolYear())
        if(record.kind==MaterialKind.TIMETABLE)require(preview.document.term==if(preview.period.endsWith("-1"))"前期" else "後期")
        val current=db.records().single { it.kind==record.kind };require(current.digest==record.digest&&current.uri==record.uri)
        val analysis=preview.analysis;val database=db.writableDatabase;database.beginTransaction()
        try {
            check();require(preview.period==retentionPeriod() && foreground && !locked())
            db.save(current.copy(analysis=analysis,parsedAt=System.currentTimeMillis(),parsedDigest=current.digest,failure=null,year=analysis.schoolYear,recoveryJob=current.recoveryJob?.copy(state=RecoveryJobState.ADOPTED),recoveryMetadata=preview.result.metadata,recoveryAcceptance=acceptance))
            db.put("recovery-accepted:${record.kind.name}:${record.digest}",json.encodeToString(RecoveryAccepted.serializer(),RecoveryAccepted(preview,acceptance)))
            database.execSQL("DELETE FROM value_store WHERE key=?",arrayOf("recovery-preview:${record.kind.name}"))
            check();database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        recoveryCaptures.remove(record.kind)
        root.listFiles()?.filter { it.name.startsWith(record.kind.name+"-") && it!=file(record) }?.forEach { it.delete() }
    }
    suspend fun downloadRecoveryModel() = withContext(Dispatchers.IO) { mutex.withLock {
        val manifest=recoveryServices.offered ?: error("検証済みモデルはまだ配信されていません。")
        require(foreground);val job=currentCoroutineContext().job;recoveryOperation=job
        mutable.update { it.copy(recoveryModel=it.recoveryModel.copy(downloading=true,progressBytes=0,error=null)) }
        try { recoveryServices.download({foreground}) { bytes -> mutable.update { it.copy(recoveryModel=it.recoveryModel.copy(progressBytes=bytes)) } }; mutable.update { it.copy(recoveryModel=it.recoveryModel.copy(installed=recoveryServices.installed())) } }
        finally { if(recoveryOperation==job)recoveryOperation=null;mutable.update { it.copy(recoveryModel=it.recoveryModel.copy(downloading=false,installed=recoveryServices.installed(),error=recoveryServices.error)) } }
    } }
    suspend fun deleteRecoveryModel() = withContext(Dispatchers.IO) { mutex.withLock { require(recoveryOperation==null);recoveryServices.delete();mutable.update { it.copy(recoveryModel=it.recoveryModel.copy(installed=null,progressBytes=0,error=null)) } } }
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
    suspend fun refresh(manual: Boolean = false, sourcesOnly: Boolean = false) = withContext(Dispatchers.IO) {
        if (locked()) return@withContext
        mutex.withLock {
            retention()
            Archives.cleanupTemporaryArchives()
            root.listFiles()?.filter { it.name.startsWith("staging-") }?.forEach { it.delete() }
            if (manual) { automaticRefreshSuspended = false; mutable.update { it.copy(automaticRefreshSuspended = false) } }
            try { (if (!automaticRefreshSuspended) db.records() else emptyList()).forEach { record -> currentCoroutineContext().ensureActive()
                try { acquire(record.kind, Uri.parse(record.uri), record, manual) } catch (e: CancellationException) { throw e } catch (e: Exception) { db.records().firstOrNull { it.kind == record.kind }?.let { db.save(it.copy(failure = safeError(e))) } }
            }
            } finally { reload() }
            if (!sourcesOnly) {
                mutable.value.events.forEach { events -> try { runInterruptible { fetchEventsLocked(events.schoolYear) } } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(eventsError = "学校行事を確認できませんでした。保存済みの結果は保持しています。") } } }
                runInterruptible { checkRevisions(if (foreground && checkedMappingAtStartup) setOf("links", "times") else revisionTypes.keys) }; if (foreground) checkedMappingAtStartup = true
            }
            dispatchPendingNotifications(); reload()
        }
    }
    private fun dispatchPendingNotifications() {
        db.readableDatabase.rawQuery("SELECT key,value FROM value_store WHERE key LIKE 'notification:%'", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0); val id = key.removePrefix("notification:")
                val notice=runCatching { json.decodeFromString<PendingMaterialNotice>(cursor.getString(1)) }.getOrNull()
                val record=notice?.let { value -> db.records().firstOrNull { it.kind==value.kind && it.parsedDigest==it.digest } }
                val classes=listOf(mutable.value.settings.primaryClass,mutable.value.settings.additionalClass).filter(String::isNotEmpty).map(::canonicalClass).toSet()
                val count=if(record?.analysis!=null && notice!=null)notice.count(record.digest,record.analysis,classes,today())else 0
                val text=if(notice?.kind==MaterialKind.CHANGES)"時間割変更が${count}件あります" else "${notice?.kind?.title.orEmpty()}が更新されました"
                val enabled = if (id.substringBefore(':') == MaterialKind.CHANGES.name) mutable.value.settings.changeNotifications else mutable.value.settings.examNotifications
                val notifications = Notifications(context)
                if (count==0 || !enabled || !notifications.allowed() || notifications.send(id, text)) db.writableDatabase.execSQL("DELETE FROM value_store WHERE key=?", arrayOf(key))
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
        val requested = observedRefreshQueue.generation()
        // select/retention also call from IO. Observer handles and Jobs are owned
        // by Main; the queue invalidates stale callbacks immediately on any thread.
        scope.launch {
            val token = observedRefreshQueue.replace(requested) ?: return@launch
            clearObserverHandles()
            if (!foreground || automaticRefreshSuspended) return@launch
            mutable.value.materials.map { it.uri }.distinct().forEach { value ->
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        if (!selfChange && foreground && !automaticRefreshSuspended && observedRefreshQueue.request(token)) {
                            scheduleObservedRefresh(token)
                        }
                    }
                }
                runCatching { context.contentResolver.registerContentObserver(Uri.parse(value), false, observer); observers += observer }
            }
            if (observedRefreshQueue.hasPending(token)) scheduleObservedRefresh(token)
        }
    }
    private fun scheduleObservedRefresh(token: Long) {
        observerJob?.cancel()
        observerJob = scope.launch {
            delay(1500)
            if (!foreground || automaticRefreshSuspended || operations.any { it.isActive }) return@launch
            if (observedRefreshQueue.take(token)) action {
                fun originals() = mutable.value.materials.map { Triple(it.kind, it.uri, it.digest) }.toSet()
                val before = originals()
                try { refresh(sourcesOnly = true) }
                finally { observedRefreshQueue.complete(token, changed = before != originals()) }
            }
        }
    }
    fun foreground(active: Boolean) { if (active && !foreground) { automaticRefreshSuspended = false; mutable.update { it.copy(automaticRefreshSuspended = false) } }; foreground = active; if (!active) { recoveryOperation?.cancel(); stopObserving() } }
    fun stopObserving() {
        val stopped = observedRefreshQueue.stop()
        scope.launch { if (observedRefreshQueue.generation() == stopped) clearObserverHandles() }
    }
    private fun clearObserverHandles() {
        observerJob?.cancel(); observerJob = null
        observers.forEach { context.contentResolver.unregisterContentObserver(it) }; observers.clear()
    }
}
