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
    // Direct unresolved request injection tests component contracts only. The real Builder now resolves this page with Rules.
    fun preparation():RecoveryStructurePreparation {
        val pages=listOf(RecoveryLayoutPage(1,page()))
        val doc=RecoveryLayout.prepare(pages,"a".repeat(64),MaterialKind.TIMETABLE)
        val cell=doc.cells.single { !it.confirmedEmpty }
        val request=RecoveryStructure.request(cell.id,cell.page,cell.box,cell.slots,doc.sources.filter { it.id in cell.sourceIds })
        val pending=doc.copy(cells=doc.cells.map { if(it.id==cell.id)it.copy(bindingMode="fixed",roleScopes=emptyList(),lessonBindings=emptyList())else it })
        return RecoveryStructurePreparation(pending,listOf(request),pages)
    }
    fun proposal(request:RecoveryStructureRequest):List<RecoveryLesson> {
        fun field(parts:List<String>):RecoveryField {
            val labels=parts.map { text->request.units.single { it.text==text } }
            val top=labels.minOf { it.box.y };val bottom=labels.maxOf { it.box.y+it.box.height };val right=labels.maxOf { it.box.x+it.box.width }
            return RecoveryField(RecoveryValueState.PRESENT,"",labels.map { it.id }+listOf(request.cuts.last { it.axis=="horizontal" && it.position<=top }.id,request.cuts.first { it.axis=="horizontal" && it.position>=bottom }.id,request.cuts.first { it.axis=="vertical" && it.position>=right }.id))
        }
        return listOf(RecoveryLesson(field(listOf("科目:")),field(listOf("担当教","員:")),field(listOf("教室:")),emptyList(),emptyList()))
    }
    @Test fun injectedStructureRequestExercisesProviderCertificateRebuildAndMetadata()=runBlocking {
        val input=preparation();assertEquals(1,input.requests.size)
        val request=input.requests.single();assertNotNull(RecoveryStructure.cheap(request))
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
    @Test fun interleavedFoldedLabelIsRulesOnlyAndNeverCallsProvider()=runBlocking {
        val doc=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,page())),"a".repeat(64),MaterialKind.TIMETABLE)
        val provider=object:LocalRecoveryProvider {
            override val id="liteRtLm";override val localOnly=true
            override val metadata get()=error("Rules must not request model metadata")
            override suspend fun availability():LocalProviderState=error("Rules must not check model availability")
            override suspend fun recoverCell(cell:RecoveryPromptCell):List<RecoveryLesson> = error("Rules must not generate")
        }
        val run=RecoveryEngine.run(doc,"android",36,true,listOf(provider),{null})
        assertEquals(RecoveryJobState.AWAITING_CONFIRMATION,run.state,run.errors.toString())
        assertEquals("rule",run.result?.metadata?.provider)
        assertEquals("3",run.result?.metadata?.modelVersion);assertEquals("3",run.result?.metadata?.runtimeVersion)
        assertEquals("2",run.result?.metadata?.promptVersion);assertEquals("2",run.result?.metadata?.recoveryVersion)
        val lesson=requireNotNull(run.result).cells.single { it.state==RecoveryValueState.PRESENT }.lessons.single()
        assertEquals("架空科目A",lesson.subject.value);assertEquals("架空担当B",lesson.teacher.value);assertEquals("架空室C",lesson.room.value)
    }
    @Test fun boundedRailSearchPreservesThreeFragmentLabelAndRejectsOrphanBody() {
        val request=preparation().requests.single()
        val first=request.units.single { it.text=="担当教" }
        val split=first.copy(id="split",sources=first.sources.map { it.copy(text="当教",box=it.box.copy(y=123.0)) },box=first.box.copy(y=123.0))
        val initial=first.copy(sources=first.sources.map { it.copy(text="担") })
        val changed=request.copy(units=request.units.map { if(it.id==first.id)initial else it }+split)
        // Recompute finite cuts from the measured sources; no oracle IDs or cuts are injected.
        val measured=RecoveryStructure.request(changed.id,changed.page,changed.box,changed.slots,changed.units.flatMap { it.sources }.mapIndexed { i,u->u.copy(id="s$i") })
        val answer=assertNotNull(RecoveryStructure.cheap(measured));RecoveryStructure.verify(measured,answer)
        val orphan=RecoverySource("orphan","cell",1,"未読",RecoveryBox(130.0,141.0,12.0,3.0))
        assertNull(RecoveryStructure.cheap(request.copy(units=request.units+RecoveryStructureUnit("orphan",listOf(orphan),orphan.box))))
    }
    @Test fun foldedRailUsesLabelsWithPermutedRolesAndVerifiedEmptyFields()=runBlocking {
        val original=page()
        val changed=original.copy(glyphs=original.glyphs.filter { it.text!="架空担当B" }.map { glyph ->
            when(glyph.text) {
                "科目:"->glyph.copy(text="教室:")
                "教室:"->glyph.copy(text="科目:")
                "架空科目A","架空室C"->glyph.copy(text="架空同名I10O")
                else->glyph
            }
        })
        val doc=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,changed)),"c".repeat(64),MaterialKind.TIMETABLE)
        val run=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null})
        assertEquals(RecoveryJobState.AWAITING_CONFIRMATION,run.state,run.errors.toString())
        val result=requireNotNull(run.result);assertEquals("rule",result.metadata.provider)
        val lesson=result.cells.single { it.state==RecoveryValueState.PRESENT }.lessons.single()
        assertEquals("架空同名I10O",lesson.subject.value);assertEquals("架空同名I10O",lesson.room.value)
        assertTrue(lesson.subject.evidence.toSet().intersect(lesson.room.evidence.toSet()).isEmpty())
        assertEquals(RecoveryField(RecoveryValueState.EMPTY,"",emptyList()),lesson.teacher)
    }
    @Test fun unequalFragmentHeightsDoNotManufactureAProviderRequirement() {
        val original=preparation().requests.single()
        val sources=original.units.flatMap { it.sources }.map { source ->
            when(source.text) {
                "担当教"->source.copy(box=source.box.copy(x=105.0))
                "員:"->source.copy(box=source.box.copy(x=105.0,height=6.0))
                else->source
            }
        }
        val request=RecoveryStructure.request(original.id,original.page,original.box,original.slots,sources)
        val verified=RecoveryStructure.verify(request,assertNotNull(RecoveryStructure.cheap(request)))
        assertEquals(listOf("担当教","員:"),verified.single { it.role=="teacher" }.labels.map { it.text })
        assertEquals("架空担当B",verified.single { it.role=="teacher" }.body.single().text)
    }
    @Test fun bodyAliasFragmentDoesNotCountAgainstTheRequiredNineLabelRail() {
        val sources=mutableListOf<RecoverySource>()
        fun source(text:String,x:Double,y:Double,height:Double=6.0) { sources+=RecoverySource("s${sources.size}","cell",1,text,RecoveryBox(x,y,1.0,height)) }
        source("科",2.0,4.0);source("目",2.0,12.0);source(":",2.0,20.0);source("科",5.5,13.0,3.0)
        source("担",2.0,34.0);source("当",2.0,42.0);source("教員:",2.0,50.0);source("架空担当",20.0,43.0,3.0)
        source("教",2.0,64.0);source("室",2.0,72.0);source(":",2.0,80.0);source("架空教室",20.0,73.0,3.0)
        val request=RecoveryStructure.request("cell",1,RecoveryBox(0.0,0.0,100.0,100.0),emptyList(),sources)
        fun field(from:Double,to:Double):RecoveryField {
            val labels=request.units.filter { it.box.x==2.0 && it.box.y in from..to }
            return RecoveryField(RecoveryValueState.PRESENT,"",labels.map { it.id }+listOf(request.cuts.last { it.axis=="horizontal" && it.position<=from }.id,request.cuts.first { it.axis=="horizontal" && it.position>=to+6.0 }.id,request.cuts.first { it.axis=="vertical" && it.position>=3.0 }.id))
        }
        val reference=listOf(RecoveryLesson(field(4.0,20.0),field(34.0,50.0),field(64.0,80.0),emptyList(),emptyList()))
        val intended=RecoveryStructure.verify(request,reference)
        val actual=RecoveryStructure.verify(request,assertNotNull(RecoveryStructure.cheap(request)))
        assertEquals(intended,actual)
        assertEquals("科",actual.single { it.role=="subject" }.body.single().text)
    }
    @Test fun railSearchCannotSwallowCancellationOrWorkLimit() {
        val request=preparation().requests.single()
        Thread.currentThread().interrupt()
        try { assertFailsWith<InterruptedException> { RecoveryStructure.cheap(request) } }
        finally { Thread.interrupted() }
        val oversized=request.copy(units=request.units.map { unit->if(unit.text=="担当教")unit.copy(sources=unit.sources.map { it.copy(text="担".repeat(100001)) })else unit })
        assertFailsWith<RecoveryWorkLimit> { RecoveryStructure.cheap(oversized) }
        var charged=0
        assertFailsWith<RecoveryWorkLimit> { RecoveryStructure.cheap(request) { cost->charged+=cost;if(charged>100)throw RecoveryWorkLimit() } }
        assertTrue(charged>100)
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
