package jp.n624.takupoke.core

import kotlin.test.*
import kotlinx.coroutines.runBlocking

class RecoveryLayoutTest {
    private fun page(proposal: Boolean = false): Page {
        val glyphs=mutableListOf<Glyph>(); val lines=mutableListOf<Line>()
        fun text(value:String,x:Double,y:Double,w:Double=20.0,h:Double=3.0) { glyphs+=Glyph(value,x,y,w,h,glyphs.size) }
        text("2026年度",0.0,5.0,60.0);text("前期",70.0,5.0)
        text("3_CN",5.0,103.0,25.0)
        listOf("月","火","水","木","金").forEachIndexed { i,day -> text(day,105.0+i*100,65.0) }
        (1..8).forEach { p ->text(p.toString(),50.0,102.0+(p-1)*20) }
        for(d in 0..4)for(p in 0..7) {
            val x=100.0+d*100;val y=100.0+p*20
            if(proposal&&d==0&&p==0) {
                listOf("科目","教員","教室").forEachIndexed { i,label -> text(label,x+2,y+2+i*6,12.0);text(listOf("架空科目","架空担当","架空教室")[i],x+40,y+2+i*6,30.0) }
            } else { text("架空科目",x+30,y+2,30.0);text("架空担当",x+30,y+8,30.0);text("架空教室",x+30,y+14,30.0) }
        }
        listOf(0.0,40.0,100.0,200.0,300.0,400.0,500.0,600.0).forEach { x->lines+=Line(x,60.0,x,260.0) }
        listOf(60.0,80.0,100.0,260.0).forEach { y->lines+=Line(0.0,y,600.0,y) }
        (1..7).forEach { p->lines+=Line(40.0,100.0+p*20,600.0,100.0+p*20) }
        return Page(610.0,280.0,glyphs,lines)
    }
    @Test fun layoutRecoversChangedGridWithoutStrictColumnWidths() = runBlocking {
        val doc=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,page())),"a".repeat(64),MaterialKind.TIMETABLE)
        val run=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null})
        assertEquals(RecoveryJobState.AWAITING_CONFIRMATION,run.state)
        val analysis=RecoveryAnalysis.convert(doc,requireNotNull(run.result));assertEquals(40,analysis.lessons.size);assertEquals("架空担当",analysis.lessons[0].names.teacher)
    }
    @Test fun missedRoleLineCannotShiftRoomIntoTeacher() {
        val page=page().copy(glyphs=page().glyphs.filterNot { it.text=="架空担当"&&it.y==108.0&&it.x==130.0 })
        assertFailsWith<RecoveryPreparationFailure> { RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,page)),"a".repeat(64),MaterialKind.TIMETABLE) }
    }
    @Test fun independentRoleScopesUseRulesBeforeAnyLocalProvider() = runBlocking {
        val doc=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,page(true))),"a".repeat(64),MaterialKind.TIMETABLE)
        var calls=0
        val provider=object:LocalRecoveryProvider {
            override val id="liteRtLm";override val localOnly=true
            override val metadata=RecoveryMetadata(id,"synthetic","1","test","2",RecoveryValidator.SCHEMA_VERSION,RecoveryValidator.VERSION,"test")
            override suspend fun availability()=LocalProviderState.READY
            override suspend fun recoverCell(cell:RecoveryPromptCell):List<RecoveryLesson> {
                calls++
                fun field(role:String):RecoveryField { val scope=cell.roleScopes.single { it.role==role };val labels=cell.roleScopes.flatMap { it.labelSourceIds }.toSet();val ids=cell.sources.filter { it.id !in labels && scope.box.contains(requireNotNull(it.box)) }.map { it.id };return RecoveryField(RecoveryValueState.PRESENT,"AIが書いた値は使わない",ids) }
                return listOf(RecoveryLesson(field("subject"),field("teacher"),field("room"),emptyList(),emptyList()))
            }
        }
        val run=RecoveryEngine.run(doc,"android",36,true,listOf(provider),{null});assertEquals(0,calls);assertEquals("rule",run.result?.metadata?.provider);assertEquals(emptyList(),run.errors);assertEquals(RecoveryJobState.AWAITING_CONFIRMATION,run.state)
        assertEquals("架空科目",run.result!!.cells.first { it.state==RecoveryValueState.PRESENT }.lessons.first().subject.value)
        val result=run.result!!;val changed=result.cells.mapIndexed { i,c->if(i==0)c.copy(lessons=c.lessons.map { it.copy(teacher=it.room) })else c }
        assertContains(RecoveryValidator.validate(doc,result.copy(cells=changed)).errors,"fieldEvidence")
    }
    @Test fun everyKnownAliasHasIndependentRecoveryRoleProof() = runBlocking {
        val original=page(true)
        for((role,aliases) in RecoveryRoles.labels)for(alias in aliases) {
            val canonical=RecoveryRoles.labels.getValue(role).first()
            val p=original.copy(glyphs=original.glyphs.map { if(it.text==canonical)it.copy(text=alias)else it })
            val doc=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,p)),"a".repeat(64),MaterialKind.TIMETABLE)
            val run=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null})
            assertEquals(emptyList(),run.errors);assertEquals("rule",run.result?.metadata?.provider)
        }
    }
    @Test fun fixedBindingRejectsEveryAliasInLaterParallelParts() {
        val doc=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,page())),"a".repeat(64),MaterialKind.TIMETABLE)
        val subject=doc.cells.first().lessonBindings.single().subject.first()
        for(label in RecoveryRoles.byLabel.keys)for(separator in listOf("・","･","/")) {
            val changed=doc.copy(sources=doc.sources.map { if(it.id==subject)it.copy(text="架空A$separator$label：架空B")else it })
            assertContains(RecoveryValidator.inputErrors(changed),"unboundRoleLabel")
            assertTrue(RecoveryRoles.explicitLabel("架空A$separator$label：架空B"))
            assertFalse(RecoveryRoles.prefix.containsMatchIn("架空A$separator$label：架空B"))
        }
    }
    private fun specialPages(kind: MaterialKind, merged:Boolean=false,firstDayClocksOnly:Boolean=false):List<RecoveryLayoutPage> = (1..5).map { day ->
        val glyphs=mutableListOf<Glyph>();val lines=mutableListOf<Line>();val max=if(kind==MaterialKind.EXAM)6 else 8
        fun text(value:String,x:Double,y:Double,w:Double=40.0,h:Double=3.0) { glyphs+=Glyph(value,x,y,w,h,glyphs.size) }
        text("2026年度",0.0,5.0,60.0)
        if(kind==MaterialKind.RETURN)text("10月1日の時間割は以下のとおり。10月2日〜5日は通常の授業日どおりの授業時間。",0.0,25.0,700.0)
        text("10月${day}日",110.0,65.0)
        for(p in 1..max) { val x=100.0+(p-1)*100;text(p.toString(),x+5,85.0)
            val time=if(kind==MaterialKind.RETURN)Schedule.normalTimes[p-1] else "%02d:00〜%02d:45".format(7+p,7+p)
            if(!firstDayClocksOnly || day==1)text(time,x+5,105.0,85.0)
        }
        RecoveryValidator.specialClasses.forEachIndexed { clsIndex,cls -> val y=120.0+clsIndex*20;text(cls,5.0,y+2,50.0)
            for(p in 1..max) { if(merged && clsIndex==0 && p==2)continue;val x=100.0+(p-1)*100;text("架空科目$p",x+30,y+2);text("架空担当$p",x+30,y+8);text("架空教室$p",x+30,y+14) }
        }
        // Body vertical cuts stop at the day-heading band, so its region spans every column.
        for(p in 0..max) { val x=100.0+p*100;lines+=Line(x,80.0,x,120.0);if(!(merged&&p==1))lines+=Line(x,120.0,x,460.0) else lines+=Line(x,140.0,x,460.0) }
        lines+=Line(0.0,60.0,0.0,460.0);lines+=Line(100.0,60.0,100.0,460.0);lines+=Line(100.0+max*100,60.0,100.0+max*100,460.0)
        for(y in listOf(60.0,80.0,100.0,120.0)+ (1..17).map { 120.0+it*20 })lines+=Line(0.0,y,100.0+max*100,y)
        if(merged && (!firstDayClocksOnly || day==1)) {
            // A separate explicit clock and its source span label are required for the merged lesson.
            text("1・2時限連続",5.0,465.0,90.0);text(if(kind==MaterialKind.RETURN)"08:50〜10:20" else "08:00〜09:45",110.0,465.0,85.0)
            lines+=Line(0.0,460.0,0.0,480.0);lines+=Line(100.0,460.0,100.0,480.0);lines+=Line(200.0,460.0,200.0,480.0);lines+=Line(0.0,480.0,200.0,480.0)
        }
        RecoveryLayoutPage(day,Page(920.0,490.0,glyphs,lines))
    }
    @Test fun fullSeventeenClassExamAndReturnUseNativeRecoveryAndFormalAnalysis() = runBlocking {
        for(kind in listOf(MaterialKind.EXAM,MaterialKind.RETURN)) {
            val doc=RecoveryLayout.prepare(specialPages(kind),"a".repeat(64),kind)
            val run=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null})
            assertEquals(RecoveryJobState.AWAITING_CONFIRMATION,run.state)
            val analysis=RecoveryAnalysis.convert(doc,requireNotNull(run.result));assertEquals(17,analysis.classes.size);assertEquals(5,analysis.dates.size);assertEquals(17*5*(if(kind==MaterialKind.EXAM)6 else 8),analysis.lessons.size)
        }
    }
    @Test fun returnRestDaysUseOnlyTheApplicablePdfNote() = runBlocking {
        val pages=specialPages(MaterialKind.RETURN,firstDayClocksOnly=true)
        val doc=RecoveryLayout.prepare(pages,"a".repeat(64),MaterialKind.RETURN)
        val run=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null});assertEquals(emptyList(),run.errors)
        assertEquals(8,doc.clockBindings.size)
        assertTrue(doc.clockEvidence.getValue("2026-10-02:1").all { it in doc.normalTimeNoteEvidence })
        assertEquals(Schedule.normalTimes[0],RecoveryAnalysis.convert(doc,run.result!!).specialTimes[1].periods.first().let { "${it.start}〜${it.end}" })
        val wrong=pages.map { input->input.copy(layout=input.layout.copy(glyphs=input.layout.glyphs.map { if(it.text.contains("通常の授業日"))it.copy(text=it.text.replace("〜5日","〜6日"))else it })) }
        assertFailsWith<RecoveryPreparationFailure> { RecoveryLayout.prepare(wrong,"a".repeat(64),MaterialKind.RETURN) }
    }
    @Test fun returnPoliteTwoLineNotePreservesItsSourceGroups() = runBlocking {
        val pages=specialPages(MaterialKind.RETURN,firstDayClocksOnly=true).map { input -> val p=input.layout;val old=p.glyphs.single { it.text.contains("通常の授業日") };input.copy(layout=p.copy(glyphs=p.glyphs.filterNot { it==old }+listOf(old.copy(text="10月1日の時間割は以下のとおりです。",width=250.0),Glyph("10月2日〜5日は通常の授業日どおりの授業時間です。",0.0,42.0,450.0,3.0,p.glyphs.size)))) }
        val doc=RecoveryLayout.prepare(pages,"a".repeat(64),MaterialKind.RETURN)
        assertTrue(doc.normalTimeNoteGroups.all { it.size==2 });assertEquals(5,doc.normalTimeNoteGroups.size)
        val run=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null});assertEquals(emptyList(),run.errors)
    }
    @Test fun returnMergedRestDaysDeriveSpanOnlyFromApplicableOriginalNote() = runBlocking {
        val doc=RecoveryLayout.prepare(specialPages(MaterialKind.RETURN,merged=true,firstDayClocksOnly=true),"a".repeat(64),MaterialKind.RETURN)
        assertEquals("08:50〜10:20",doc.spanTimes.getValue("2026-10-02:1-2"))
        assertNull(doc.clockBindings["2026-10-02:1-2"])
        assertEquals(doc.normalTimeNoteEvidence,doc.clockEvidence.getValue("2026-10-02:1-2"))
        val run=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null});assertEquals(emptyList(),run.errors)
        val wrong=doc.copy(spanTimes=doc.spanTimes+("2026-10-02:1-2" to "08:50〜10:30"))
        assertTrue("normalSpanTimeCondition" in RecoveryValidator.inputErrors(wrong))
    }
    @Test fun consecutiveExamUsesExplicitSpanClock() = runBlocking {
        val doc=RecoveryLayout.prepare(specialPages(MaterialKind.EXAM,true),"a".repeat(64),MaterialKind.EXAM)
        assertEquals(5,doc.cells.count { it.slots.size==2 })
        val run=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null});assertEquals(emptyList(),run.errors)
        assertEquals("08:00〜09:45",RecoveryAnalysis.convert(doc,requireNotNull(run.result)).lessons.first { it.spanEnd==2 }.time)
    }
    @Test fun returnDoesNotInferTheOtherFourDaysWithoutThePdfNote() {
        val pages=specialPages(MaterialKind.RETURN).map { it.copy(layout=it.layout.copy(glyphs=it.layout.glyphs.filterNot { it.text.contains("通常の授業日") })) }
        assertFailsWith<RecoveryPreparationFailure> { RecoveryLayout.prepare(pages,"a".repeat(64),MaterialKind.RETURN) }
    }
    @Test fun nativeJoinedHeadingAndReorderedInlineLabelsKeepExactBoundaries() {
        val p=page();val replacement=mutableListOf<Glyph>();var order=p.glyphs.size
        fun characters(value:String,x:Double,y:Double) { value.forEachIndexed { i,c -> replacement+=Glyph(c.toString(),x+i*3,y,2.0,3.0,order++) } }
        characters("2026年度前期時間割",0.0,5.0)
        listOf("教員:架空担当","科目:架空科目","教室:架空教室").forEachIndexed { i,value -> characters(value,102.0,102.0+i*6) }
        val changed=p.copy(glyphs=p.glyphs.filterNot { it.y==5.0 || it.x==130.0 && it.y in setOf(102.0,108.0,114.0) }+replacement)
        val doc=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,changed)),"a".repeat(64),MaterialKind.TIMETABLE)
        val first=doc.cells.first();assertEquals("roleProposal",first.bindingMode)
        assertEquals(listOf("teacher","subject","room"),first.roleScopes.map { it.role })
        assertEquals("2026年度",doc.sources.single { it.id==doc.yearEvidence.single() }.text)
        assertEquals("前期",doc.term);assertEquals("時間割",doc.sources.single { it.id==doc.titleEvidence.single() }.text)
    }
    @Test fun adoptionRejectsPeriodSelectionSourceAndSemesterRaces() = runBlocking {
        val day=java.time.LocalDate.of(2026,9,30);val doc=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,page())),"a".repeat(64),MaterialKind.TIMETABLE);val result=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null}).result!!
        val selection=RecoverySelection("2026-1","content://synthetic/a",MaterialKind.TIMETABLE,doc.pdfHash)
        assertTrue(RecoveryAdoption.allowed(selection,selection,doc.pdfHash,doc,result,day))
        assertFalse(RecoveryAdoption.allowed(selection,selection.copy(uri="content://synthetic/b"),doc.pdfHash,doc,result,day))
        assertFalse(RecoveryAdoption.allowed(selection,selection,"b".repeat(64),doc,result,day))
        assertFalse(RecoveryAdoption.allowed(selection,selection,doc.pdfHash,doc,result,day.plusDays(1)))
        assertFalse(RecoveryAdoption.allowed(selection,selection.copy(kind=MaterialKind.EXAM),doc.pdfHash,doc,result,day))
    }
    @Test fun explicitThreeLineParallelGroupsPreserveTeachersAndRooms() = runBlocking {
        val original=page();val replacement=mutableListOf<Glyph>();var order=original.glyphs.size
        for((i,role) in listOf("科目","担当","教室").withIndex()) { val y=102.0+i*6;replacement+=Glyph("架空${role}A",115.0,y,25.0,3.0,order++);replacement+=Glyph("・",145.0,y,3.0,3.0,order++);replacement+=Glyph("架空${role}B",153.0,y,25.0,3.0,order++) }
        val p=original.copy(glyphs=original.glyphs.filterNot { it.x==130.0&&it.y in setOf(102.0,108.0,114.0) }+replacement)
        val doc=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,p)),"a".repeat(64),MaterialKind.TIMETABLE)
        assertEquals(1,doc.cells.count { it.parallelCount==2 });val result=RecoveryEngine.run(doc,"android",36,true,emptyList(),{null}).result!!
        val lessons=result.cells.first().lessons;assertEquals("架空担当A",lessons[0].teacher.value);assertEquals("架空教室B",lessons[1].room.value)
        val wrong=result.copy(cells=listOf(result.cells.first().copy(lessons=listOf(lessons[0].copy(room=lessons[1].room),lessons[1])))+result.cells.drop(1));assertFalse(RecoveryValidator.validate(doc,wrong).canAdopt)
    }
    @Test fun partiallyLabeledReorderedParallelRowsCannotBecomeFixedSubjects() {
        val original=page();val replacement=mutableListOf<Glyph>();var order=original.glyphs.size
        for((i,role) in listOf("担当","科目","教室").withIndex()) { val y=102.0+i*6;replacement+=Glyph("架空${role}A",115.0,y,25.0,3.0,order++);replacement+=Glyph("・",145.0,y,3.0,3.0,order++);replacement+=Glyph("架空${role}B",153.0,y,25.0,3.0,order++) }
        replacement+=Glyph("教員:",101.0,102.0,12.0,3.0,order)
        val p=original.copy(glyphs=original.glyphs.filterNot { it.x==130.0&&it.y in setOf(102.0,108.0,114.0) }+replacement)
        assertFailsWith<RecoveryPreparationFailure> { RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,p)),"a".repeat(64),MaterialKind.TIMETABLE) }
        val doc=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,original)),"a".repeat(64),MaterialKind.TIMETABLE)
        val subject=doc.cells.first().lessonBindings.single().subject.first()
        assertContains(RecoveryValidator.inputErrors(doc.copy(sources=doc.sources.map { if(it.id==subject)it.copy(text="教員:架空担当")else it })),"unboundRoleLabel")
    }
    @Test fun unrecognizedLongCharacterStrokeCannotCountAsRuling() {
        val pixels=IntArray(200*200){-1};for(x in 30..140)pixels[100*200+x]=0xff000000.toInt()
        val result=RecoveryRasterGeometry.analyze(200,200,pixels,emptyList());assertFalse(result.complete);assertTrue(result.lines.isEmpty())
    }
    @Test fun rasterInkOutsideRecognitionCannotBecomeFreePeriod() {
        val pixels=IntArray(100*100){-1};pixels[55*100+55]=0xff000000.toInt()
        assertFalse(RecoveryRasterGeometry.analyze(100,100,pixels,emptyList()).complete)
    }
    @Test fun faintOrColoredUnrecognizedInkCannotBecomeAnEmptyCell() {
        for(color in listOf(0xffc8c8c8.toInt(),0xffff0000.toInt(),0xfffefefe.toInt())) {
            val pixels=IntArray(200*200){-1}
            for(i in 10..190) { pixels[10*200+i]=0xff000000.toInt();pixels[190*200+i]=0xff000000.toInt();pixels[i*200+10]=0xff000000.toInt();pixels[i*200+190]=0xff000000.toInt() }
            pixels[100*200+100]=color
            val result=RecoveryRasterGeometry.analyze(200,200,pixels,emptyList())
            assertFalse(result.complete);assertTrue(result.blankBoxes.none { it.x<100 && it.x+it.width>100 && it.y<100 && it.y+it.height>100 })
        }
    }
    @Test fun rasterEmptyCellNeedsPixelProof() {
        val p=page().copy(glyphs=page().glyphs.filterNot { it.x==130.0&&it.y in setOf(102.0,108.0,114.0) })
        assertFailsWith<RecoveryPreparationFailure> { RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,p,true)),"a".repeat(64),MaterialKind.TIMETABLE) }
    }
}
