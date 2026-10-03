package jp.n624.takupoke.android

import androidx.test.platform.app.InstrumentationRegistry
import jp.n624.takupoke.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Injected raw invented layout and bounded mock proposal, not an OCR/model accuracy test. */
class RecoveryStructureNativeTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun page():Page {
        val glyphs=mutableListOf<Glyph>();val lines=mutableListOf<Line>()
        fun text(value:String,x:Double,y:Double,w:Double=12.0) { glyphs+=Glyph(value,x,y,w,3.0,glyphs.size) }
        text("${schoolYear()}年度",0.0,5.0,60.0);text(if(retentionPeriod().endsWith("-1"))"前期"else "後期",70.0,5.0);text("3_CN",5.0,103.0,25.0)
        listOf("月","火","水","木","金").forEachIndexed { i,d->text(d,105.0+i*100,65.0) }
        (1..8).forEach { p->text(p.toString(),50.0,102.0+(p-1)*60) }
        text("科目:",102.0,108.0,8.0);text("架空科目A",130.0,108.0,20.0)
        text("担当教",102.0,120.0,8.0);text("架空担当B",130.0,126.0,20.0);text("員:",102.0,132.0,5.0)
        text("教室:",102.0,150.0,8.0);text("架空室C",130.0,150.0,20.0)
        listOf(0.0,40.0,100.0,200.0,300.0,400.0,500.0,600.0).forEach { x->lines+=Line(x,60.0,x,580.0) }
        listOf(60.0,80.0,100.0,580.0).forEach { y->lines+=Line(0.0,y,600.0,y) }
        (1..7).forEach { p->lines+=Line(40.0,100.0+p*60,600.0,100.0+p*60) }
        return Page(610.0,600.0,glyphs,lines)
    }
    private fun services(p:Page=page()):OfflineRecoveryServices {
        return OfflineRecoveryServices().apply {
            structurePages=listOf(RecoveryLayoutPage(1,p))
            structureAnswer={ prompt ->
                check(prompt.mode==RecoveryPromptMode.structureProposal)
                fun role(parts:List<String>):RecoveryField {
                    val labels=parts.map { word->prompt.sources.single { it.text==word } }
                    val top=labels.minOf { requireNotNull(it.box).y };val bottom=labels.maxOf { requireNotNull(it.box).let { b->b.y+b.height } };val right=labels.maxOf { requireNotNull(it.box).let { b->b.x+b.width } }
                    return RecoveryField(RecoveryValueState.PRESENT,"",labels.map { it.id }+listOf(prompt.structureCuts.last { it.axis=="horizontal" && it.position<=top }.id,prompt.structureCuts.first { it.axis=="horizontal" && it.position>=bottom }.id,prompt.structureCuts.first { it.axis=="vertical" && it.position>=right }.id))
                }
                listOf(RecoveryLesson(role(listOf("科目:")),role(listOf("担当教","員:")),role(listOf("教室:")),emptyList(),emptyList()))
            }
        }
    }
    @Test fun originalRebuildPreviewExplicitAdoptionAndSqliteReload():Unit=runBlocking {
        val service=services();val seed=OfflineRecoverySeed(context,service)
        try {
            seed.install();seed.repository.startRecovery(MaterialKind.TIMETABLE)
            assertEquals(1,service.providerCalls);assertEquals(1,service.providerClosures)
            val preview=requireNotNull(seed.repository.state.value.recoveryPreviews[MaterialKind.TIMETABLE])
            assertEquals("liteRtLm",preview.result.metadata.provider);assertEquals(preview.result.metadata,preview.document.structureMetadata)
            assertEquals(seed.oldAnalysis,seed.database.records().single().analysis)
            val actual=preview.analysis.lessons.single();assertEquals(Names("架空科目A","架空担当B","架空室C"),actual.names)
            seed.repository.adoptRecovery(MaterialKind.TIMETABLE,preview.resultHash)
            val rebuilt=AppRepository(seed.context,RejectNetwork,preferences=MemorySettings());rebuilt.activate(false)
            assertEquals(preview.analysis,rebuilt.state.value.materials.single().analysis)
            assertEquals("liteRtLm",rebuilt.state.value.materials.single().recoveryMetadata?.provider)
            rebuilt.foreground(false);rebuilt.stopObserving()
        } finally { seed.stop() }
    }
    @Test fun wrongSemesterAndCancellationRetainFormalAndCloseProvider():Unit=runBlocking {
        val wrong=page().let { p->p.copy(glyphs=p.glyphs.map { if(it.text in listOf("前期","後期"))it.copy(text=if(it.text=="前期")"後期"else "前期")else it }) }
        val service=services(wrong);val seed=OfflineRecoverySeed(context,service)
        try {
            seed.install()
            try { seed.repository.startRecovery(MaterialKind.TIMETABLE);fail("Wrong semester loaded a model") }catch(_:IllegalArgumentException) {}
            assertEquals(0,service.providerConstructions);assertEquals(0,service.providerCalls);assertEquals(seed.oldAnalysis,seed.database.records().single().analysis)
            service.structurePages=listOf(RecoveryLayoutPage(1,page()));service.holdStructure=true
            val pending=async(Dispatchers.IO) { seed.repository.startRecovery(MaterialKind.TIMETABLE) }
            withTimeout(5000){service.structureEntered.await()};seed.repository.cancel()
            try { withTimeout(5000){pending.await()};fail("Cancellation was ignored") }catch(_:CancellationException) {}
            assertEquals(1,service.providerClosures);assertEquals(seed.oldAnalysis,seed.database.records().single().analysis)
            assertEquals(RecoveryJobState.PENDING,seed.database.records().single().recoveryJob?.state)
            assertTrue(seed.repository.state.value.recoveryPreviews.isEmpty())
        } finally { seed.stop() }
    }
}
