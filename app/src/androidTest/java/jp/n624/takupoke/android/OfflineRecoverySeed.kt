package jp.n624.takupoke.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import jp.n624.takupoke.core.*
import kotlinx.coroutines.*
import java.io.File

/** Entirely invented geometry/data. No production model download, OCR or school URL is used. */
internal class OfflineRecoveryServices(var offer:Boolean=true,var hasModel:Boolean=true):RecoveryServices {
    var preparedDocument:RecoveryDocument?=null
    private val manifest=RecoveryModelManifest("synthetic-offline","1","https://models.example.invalid/synthetic",1024,"a".repeat(64),"liteRtLm","29",4L*1024*1024*1024,"CPU","test-only",true)
    override val offered get()=manifest.takeIf { offer }
    override val error:String?=null
    override fun installed()=manifest.takeIf { hasModel }
    override fun cleanup() {}
    var deletions=0;var downloads=0;var providerCalls=0
    val preparationEntered=CompletableDeferred<Unit>();var holdPreparation=false
    val downloadEntered=CompletableDeferred<Unit>();var holdDownload=false
    override fun delete() { deletions++;hasModel=false }
    override suspend fun download(foreground:()->Boolean,progress:(Long)->Unit) { check(foreground());downloads++;progress(512);downloadEntered.complete(Unit);if(holdDownload)awaitCancellation();hasModel=true;progress(1024) }
    override fun provider(foreground:()->Boolean)=object:LocalRecoveryProvider {
        override val id="liteRtLm";override val localOnly=true
        override val metadata=RecoveryMetadata(id,"synthetic-offline","1","fake-runtime","2",RecoveryValidator.SCHEMA_VERSION,RecoveryValidator.VERSION,"test-only")
        override suspend fun availability()=if(hasModel)LocalProviderState.READY else LocalProviderState.DOWNLOAD_REQUIRED
        override suspend fun recoverCell(cell:RecoveryPromptCell):List<RecoveryLesson> { providerCalls++;error("Known role scopes must use Rules before this mock provider") }
    }
    override suspend fun prepare(file:File,hash:String,kind:MaterialKind,capture:RecoveryReadCapture):RecoveryDocument {
        preparationEntered.complete(Unit);if(holdPreparation)awaitCancellation()
        preparedDocument?.let { doc -> check(RecoveryPolicy.kind(kind)==doc.kind);return doc.copy(pdfHash=hash) }
        return RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,layout())),hash,kind)
    }
    private fun layout():Page {
        val glyphs=mutableListOf<Glyph>();val lines=mutableListOf<Line>()
        fun text(value:String,x:Double,y:Double,w:Double=20.0) { glyphs+=Glyph(value,x,y,w,3.0,glyphs.size) }
        text("${schoolYear()}年度",0.0,5.0,60.0);text(if(retentionPeriod().endsWith("-1"))"前期" else "後期",70.0,5.0)
        text("3_CN",5.0,103.0,25.0)
        listOf("月","火","水","木","金").forEachIndexed { i,day->text(day,105.0+i*100,65.0) }
        (1..8).forEach { p->text(p.toString(),50.0,102.0+(p-1)*20) }
        listOf("科目","教員","教室").forEachIndexed { i,label->text(label,102.0,102.0+i*6,12.0);text(listOf("架空復旧科目","架空復旧担当","架空復旧教室")[i],140.0,102.0+i*6,40.0) }
        listOf(0.0,40.0,100.0,200.0,300.0,400.0,500.0,600.0).forEach { x->lines+=Line(x,60.0,x,260.0) }
        listOf(60.0,80.0,100.0,260.0).forEach { y->lines+=Line(0.0,y,600.0,y) }
        (1..7).forEach { p->lines+=Line(40.0,100.0+p*20,600.0,100.0+p*20) }
        return Page(610.0,280.0,glyphs,lines)
    }
}
internal class OfflineRecoverySeed(context:Context,val services:OfflineRecoveryServices=OfflineRecoveryServices(),val kind:MaterialKind=MaterialKind.TIMETABLE) {
    val context=object:ContextWrapper(context) { private val root=File(context.cacheDir,"offline-recovery-${java.util.UUID.randomUUID()}").also { it.mkdirs() };override fun getNoBackupFilesDir()=root }
    val database=Database(this.context)
    val preferences=MemorySettings(Settings(primaryClass="3_CN",setupComplete=true))
    val repository=AppRepository(this.context,RejectNetwork,database,preferences,services)
    val oldAnalysis=Analysis(kind,schoolYear(),if(kind!=MaterialKind.TIMETABLE)0 else if(retentionPeriod().endsWith("-1"))1 else 2,listOf(Lesson("3_CN",1,1,Names("架空の前回科目","架空の前回担当","架空の前回教室"))),classes=listOf("3_CN"))
    suspend fun install() {
        database.put("period",retentionPeriod())
        val folder=File(context.noBackupFilesDir,"school/materials").also { it.mkdirs() }
        val bytes=java.io.ByteArrayOutputStream().also { output -> PdfDocument().let { pdf -> try { val page=pdf.startPage(PdfDocument.PageInfo.Builder(600,300,1).create());page.canvas.drawText("Synthetic recovery source only",30f,40f,Paint().apply { textSize=12f });pdf.finishPage(page);pdf.writeTo(output) } finally { pdf.close() } } }.toByteArray()
        val hash=sha256(bytes);File(folder,"${kind.name}-$hash.pdf").writeBytes(bytes)
        database.save(MaterialRecord(kind,"content://example.invalid/synthetic-recovery","synthetic-recovery.pdf",hash,1,1,parsedAt=1,parsedDigest="b".repeat(64),analysis=oldAnalysis,failure="架空PDFの通常解析を完了できませんでした。",recoveryJob=RecoveryJob(hash,requireNotNull(RecoveryPolicy.kind(kind)),RecoveryJobState.PENDING,1)))
        repository.foreground(true);repository.activate(false)
    }
    fun stop() { repository.foreground(false);repository.cancel();repository.stopObserving() }
}
