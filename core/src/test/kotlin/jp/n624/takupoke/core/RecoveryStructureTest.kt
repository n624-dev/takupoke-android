package jp.n624.takupoke.core

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RecoveryStructureTest {
    fun page(interleaved:Boolean=true):Page {
        val glyphs=mutableListOf<Glyph>();val lines=mutableListOf<Line>()
        fun text(value:String,x:Double,y:Double,w:Double=12.0) { glyphs+=Glyph(value,x,y,w,3.0,glyphs.size) }
        text("2026年度",0.0,5.0,60.0);text("前期",70.0,5.0);text("3_CN",5.0,103.0,25.0)
        listOf("月","火","水","木","金").forEachIndexed { i,d->text(d,105.0+i*100,65.0) }
        (1..8).forEach { p->text(p.toString(),50.0,102.0+(p-1)*60) }
        text("科目:",102.0,108.0,8.0);text("架空科目A",130.0,108.0,20.0)
        text("担当教",102.0,120.0,8.0);text("員:",102.0,if(interleaved)132.0 else 126.0,5.0)
        text("架空担当B",130.0,if(interleaved)126.0 else 120.0,20.0)
        text("教室:",102.0,150.0,8.0);text("架空室C",130.0,150.0,20.0)
        listOf(0.0,40.0,100.0,200.0,300.0,400.0,500.0,600.0).forEach { x->lines+=Line(x,60.0,x,580.0) }
        listOf(60.0,80.0,100.0,580.0).forEach { y->lines+=Line(0.0,y,600.0,y) }
        (1..7).forEach { p->lines+=Line(40.0,100.0+p*60,600.0,100.0+p*60) }
        return Page(610.0,600.0,glyphs,lines)
    }
    fun preparation():RecoveryStructurePreparation=assertFailsWith { RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,page())),"a".repeat(64),MaterialKind.TIMETABLE) }
    fun proposal(request:RecoveryStructureRequest):List<RecoveryLesson> {
        fun field(parts:List<String>):RecoveryField {
            val labels=parts.map { text->request.units.single { it.text==text } }
            val top=labels.minOf { it.box.y };val bottom=labels.maxOf { it.box.y+it.box.height };val right=labels.maxOf { it.box.x+it.box.width }
            return RecoveryField(RecoveryValueState.PRESENT,"",labels.map { it.id }+listOf(request.cuts.last { it.axis=="horizontal" && it.position<=top }.id,request.cuts.first { it.axis=="horizontal" && it.position>=bottom }.id,request.cuts.first { it.axis=="vertical" && it.position>=right }.id))
        }
        return listOf(RecoveryLesson(field(listOf("科目:")),field(listOf("担当教","員:")),field(listOf("教室:")),emptyList(),emptyList()))
    }
    @Test fun foldedBodyInterleavedLabelUsesFiniteProposalThenRulesAndValidator()=runBlocking {
        val input=preparation();assertEquals(1,input.requests.size)
        val request=input.requests.single();assertNull(RecoveryStructure.cheap(request))
        var calls=0
        val metadata=RecoveryMetadata("liteRtLm","synthetic-structure","1","test","3",RecoveryValidator.SCHEMA_VERSION,RecoveryValidator.VERSION,"test")
        val provider=object:LocalRecoveryProvider {
            override val id="liteRtLm";override val localOnly=true;override val metadata=metadata
            override suspend fun availability()=LocalProviderState.READY
            override suspend fun recoverCell(cell:RecoveryPromptCell):List<RecoveryLesson> { calls++;assertEquals(RecoveryPromptMode.structureProposal,cell.mode);assertTrue(cell.structureCuts.isNotEmpty());return proposal(request) }
        }
        val resolved=RecoveryStructure.resolve(input.requests,listOf(provider),"android",36,{})
        assertEquals(1,calls);assertEquals(RecoveryJobState.AWAITING_CONFIRMATION,resolved.state)
        val doc=RecoveryLayout.prepare(input.pages,input.document.pdfHash,MaterialKind.TIMETABLE,requireNotNull(resolved.proposals)).copy(structureMetadata=resolved.metadata)
        val run=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null})
        assertEquals(RecoveryJobState.AWAITING_CONFIRMATION,run.state,run.errors.toString())
        val result=requireNotNull(run.result);assertEquals(metadata,result.metadata)
        val lesson=result.cells.single { it.state==RecoveryValueState.PRESENT }.lessons.single()
        assertEquals("架空科目A",lesson.subject.value);assertEquals("架空担当B",lesson.teacher.value);assertEquals("架空室C",lesson.room.value)
        assertContains(RecoveryValidator.validate(doc,result.copy(metadata=result.metadata.copy(provider="rule"))).errors,"structureMetadata")
    }
    @Test fun adjacentWrappedLabelRemainsRulesOnly()=runBlocking {
        val doc=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,page(false))),"a".repeat(64),MaterialKind.TIMETABLE)
        val run=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null})
        assertEquals(RecoveryJobState.AWAITING_CONFIRMATION,run.state,run.errors.toString());assertEquals("rule",run.result?.metadata?.provider)
    }
    @Test fun forgedCutWrongRoleOrOrphanBodyFailsIndependentCertificate() {
        val request=preparation().requests.single();val answer=proposal(request)
        RecoveryStructure.verify(request,answer)
        val lesson=answer.single()
        assertFailsWith<InvalidRecoveryOutput> { RecoveryStructure.verify(request,listOf(lesson.copy(teacher=lesson.teacher.copy(evidence=lesson.teacher.evidence.dropLast(1)+"x999")))) }
        assertFailsWith<InvalidRecoveryOutput> { RecoveryStructure.verify(request,listOf(lesson.copy(subject=lesson.teacher,teacher=lesson.subject))) }
        val orphan=RecoverySource("orphan","cell",1,"未読",RecoveryBox(130.0,141.0,12.0,3.0))
        assertFailsWith<InvalidRecoveryOutput> { RecoveryStructure.verify(request.copy(units=request.units+RecoveryStructureUnit("orphan",listOf(orphan),orphan.box)),answer) }
        assertFailsWith<InvalidRecoveryOutput> { RecoveryStructure.verify(request,listOf(lesson.copy(teacher=lesson.teacher.copy(value="AIの推測")))) }
    }
    @Test fun pendingBindingDeferralCannotHideAnotherCellsInvalidRoleScope() {
        val input=preparation();val pending=input.requests.map { it.id }.toSet()
        val invalid=input.document.copy(cells=input.document.cells.mapIndexed { i,c->if(i==1)c.copy(confirmedEmpty=false,bindingMode="roleProposal",parallelCount=1)else c })
        assertContains(RecoveryValidator.preparationErrors(invalid,pending),"roleScope")
        assertContains(RecoveryValidator.inputErrors(input.document),"lessonBinding")
    }
    @Test fun allPagesAreCheckedBeforeAiAndExtraProposalsCannotHideSources() {
        val first=page();val wrong=first.copy(glyphs=first.glyphs.map { if(it.text=="2026年度")it.copy(text="2025年度")else it })
        assertFailsWith<RecoveryPreparationFailure> { RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,first),RecoveryLayoutPage(2,wrong)),"a".repeat(64),MaterialKind.TIMETABLE) }
        val input=preparation();val answer=proposal(input.requests.single())
        assertFailsWith<RecoveryPreparationFailure> { RecoveryLayout.prepare(input.pages,input.document.pdfHash,MaterialKind.TIMETABLE,mapOf(input.requests.single().id to answer,"orphan" to answer)) }
    }
}
