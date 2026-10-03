package jp.n624.takupoke.core

import kotlin.test.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

class RecoveryTest {
    private fun fixture(): Pair<RecoveryDocument, RecoveryResult> {
        val slots = (1..5).flatMap { day -> (1..8).map { RecoverySlot("3_CN", day.toString(), it) } }
        val box = RecoveryBox(110.0, 110.0, 60.0, 10.0)
        val sources = mutableListOf(RecoverySource("heading", "header", 1, "2026年度", RecoveryBox(0.0, 0.0, 90.0, 10.0)), RecoverySource("subject", "c0", 1, "架空科目A", box), RecoverySource("teacher", "c0", 1, "架空教員A", box), RecoverySource("room", "c0", 1, "架空教室A", box))
        sources += RecoverySource("term", "header", 1, "前期", RecoveryBox(0.0, 20.0, 40.0, 10.0))
        sources += RecoverySource("class", "header", 1, "3_CN", RecoveryBox(10.0, 110.0, 20.0, 10.0))
        sources += (1..5).map { RecoverySource("day$it", "header", 1, listOf("月", "火", "水", "木", "金")[it - 1], RecoveryBox(it * 100.0 + 10.0, 20.0, 60.0, 10.0)) }
        sources += (1..8).map { RecoverySource("period$it", "header", 1, it.toString(), RecoveryBox(50.0, it * 100.0 + 10.0, 20.0, 10.0)) }
        val cells = slots.mapIndexed { i, slot -> RecoveryCell("c$i", 1, RecoveryBox(slot.day.toInt() * 100.0, slot.period * 100.0, 100.0, 100.0), RecoveryInputState.COMPLETE, listOf(slot), if (i == 0) listOf("subject", "teacher", "room") else emptyList(), emptyList(), i != 0, classHeaderIds = listOf("class"), dayHeaderIds = listOf("day${slot.day}"), periodHeaderIds = listOf("period${slot.period}"), lessonBindings = if (i == 0) listOf(RecoveryLessonBinding(listOf("subject"), listOf("teacher"), listOf("room"))) else emptyList(), classRegion = RecoveryHeaderRegion(1, RecoveryBox(0.0, 100.0, 40.0, 800.0), RecoveryHeaderAxis.LEFT), dayRegion = RecoveryHeaderRegion(1, RecoveryBox(slot.day.toInt() * 100.0, 0.0, 100.0, 50.0), RecoveryHeaderAxis.ABOVE), periodRegions = mapOf(slot.period.toString() to RecoveryHeaderRegion(1, RecoveryBox(40.0, slot.period * 100.0, 40.0, 100.0), RecoveryHeaderAxis.LEFT))) }
        val doc = RecoveryDocument("a".repeat(64), RecoveryDocumentKind.TIMETABLE, 2026, "前期", listOf("3_CN"), (1..5).map { it.toString() }, slots, cells, sources, true, listOf("heading"), listOf("term"), (1..5).associate { it.toString() to listOf("day$it") }, mapOf("3_CN" to listOf("class")), (1..8).associate { it.toString() to listOf("period$it") }, emptyMap(), emptyList(), emptyList())
        val lesson = RecoveryLesson(RecoveryField(RecoveryValueState.PRESENT, "架空科目A", listOf("subject")), RecoveryField(RecoveryValueState.PRESENT, "架空教員A", listOf("teacher")), RecoveryField(RecoveryValueState.PRESENT, "架空教室A", listOf("room")), listOf("day1"), listOf("period1"))
        val result = RecoveryResult(doc.pdfHash, doc.kind, 2026, "前期", cells.mapIndexed { i, cell -> RecoveredCell(cell.id, if (i == 0) RecoveryValueState.PRESENT else RecoveryValueState.EMPTY, if (i == 0) listOf(lesson) else emptyList()) }, RecoveryMetadata("rule", "rules", "1", "1", "1", RecoveryValidator.SCHEMA_VERSION, RecoveryValidator.VERSION, "test"))
        return doc to result
    }
    private class ProbeProvider(override val id: String, value: RecoveryMetadata) : LocalRecoveryProvider {
        override val localOnly = true; override val metadata = value.copy(provider = id)
        var state = LocalProviderState.READY; var availabilityCalls = 0; var recoveryCalls = 0
        override suspend fun availability(): LocalProviderState { availabilityCalls++; return state }
        override suspend fun recoverCell(cell: RecoveryPromptCell): List<RecoveryLesson> { recoveryCalls++; throw InvalidRecoveryOutput() }
    }
    private fun uncertain(): RecoveryDocument { val (d, _) = fixture(); return d.copy(sources = d.sources.filter { it.id != "teacher" }, cells = d.cells.mapIndexed { i, c -> if (i == 0) c.copy(sourceIds = c.sourceIds.filter { it != "teacher" }, lessonBindings = listOf(c.lessonBindings[0].copy(teacher = emptyList()))) else c }) }
    @Test fun groundedRulesDoNotLoadLanguageModel() = runBlocking { val (d, r) = fixture(); val p = ProbeProvider("liteRtLm", r.metadata); val run = RecoveryEngine.run(d, "android", 36, true, listOf(p), { null }); assertEquals(RecoveryJobState.AWAITING_CONFIRMATION, run.state); assertEquals("rule", run.result?.metadata?.provider); assertEquals(0, p.availabilityCalls) }
    @Test fun modelNotReadyWaitsWithoutDownloadingFallback() = runBlocking { val (_, r) = fixture(); val p = ProbeProvider("systemLanguageModel", r.metadata).apply { state = LocalProviderState.NOT_READY }; val fallback = ProbeProvider("coreAI", r.metadata); val run = RecoveryEngine.run(uncertain(), "ios", 27, true, listOf(p, fallback), { null }); assertEquals(RecoveryJobState.AWAITING_MODEL, run.state); assertEquals(0, fallback.availabilityCalls) }
    @Test fun malformedOutputIsTerminalBeforeNextProvider() = runBlocking { val (_, r) = fixture(); val p = ProbeProvider("windowsLanguageModel", r.metadata); val fallback = ProbeProvider("foundryLocal", r.metadata); val run = RecoveryEngine.run(uncertain(), "windows", 10, true, listOf(p, fallback), { null }); assertEquals(listOf("invalidOutput"), run.errors); assertNull(run.result); assertEquals(0, fallback.availabilityCalls) }
    @Test fun completeGroundedResultCanBePreviewed() { val (d, r) = fixture(); assertEquals(emptyList(), RecoveryValidator.validate(d, r).errors) }
    @Test fun unknownIsNeverFreePeriod() { val (d, r) = fixture(); listOf(RecoveryValueState.UNREADABLE, RecoveryValueState.MISSING, RecoveryValueState.AMBIGUOUS).forEach { state -> assertContains(RecoveryValidator.validate(d, r.copy(cells = r.cells.mapIndexed { i, c -> if (i == 0) c.copy(state = state) else c })).errors, "cellState") } }
    @Test fun incompleteReaderIsRejected() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d.copy(complete = false), r).errors, "incompleteDocument") }
    @Test fun partialCellIsRejected() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d.copy(cells = d.cells.mapIndexed { i, c -> if (i == 0) c.copy(inputState = RecoveryInputState.PARTIAL) else c }), r).errors, "incompleteCell") }
    @Test fun missingSlotIsRejected() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d.copy(cells = d.cells.drop(1)), r).errors, "coverage") }
    @Test fun missingResultCellIsRejected() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d, r.copy(cells = r.cells.drop(1))).errors, "resultCoverage") }
    @Test fun duplicateIdsRejectWithoutThrowing() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d.copy(sources = d.sources + d.sources.first()), r).errors, "duplicateIds") }
    @Test fun neighbourEvidenceIsRejected() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d.copy(sources = d.sources.map { if (it.id == "room") it.copy(cellId = "c1") else it }), r).errors, "sourcePosition") }
    @Test fun inventedValueIsRejected() { val (d, r) = fixture(); val c = r.cells.first(); val l = c.lessons.first(); assertContains(RecoveryValidator.validate(d, r.copy(cells = listOf(c.copy(lessons = listOf(l.copy(room = l.room.copy(value = "架空教室B"))))) + r.cells.drop(1))).errors, "fieldEvidence") }
    @Test fun contentCannotBecomeEmpty() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d, r.copy(cells = r.cells.mapIndexed { i, c -> if (i == 0) c.copy(state = RecoveryValueState.EMPTY, lessons = emptyList()) else c })).errors, "falseEmpty") }
    @Test fun changedYearIsRejected() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d, r.copy(schoolYear = 2025)).errors, "documentIdentity") }
    @Test fun changedHashIsRejected() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d, r.copy(pdfHash = "b".repeat(64))).errors, "sourceHash") }
    @Test fun approvalOnlyReusesExactValidatedResult() { val (d, r) = fixture(); val a = RecoveryAcceptance(d.pdfHash, RecoveryValidator.fingerprint(r), RecoveryValidator.fingerprint(d), r.metadata, 0); assertTrue(RecoveryValidator.canReuse(a, d, r)); assertFalse(RecoveryValidator.canReuse(a, d, r.copy(metadata = r.metadata.copy(modelVersion = "2")))) }
    @Test fun previousValidatorApprovalCannotAuthorizeNewOrOldResult() {
        val (doc,current)=fixture()
        val old=current.copy(metadata=current.metadata.copy(validatorVersion=2))
        val oldApproval=RecoveryAcceptance(doc.pdfHash,RecoveryValidator.fingerprint(old),RecoveryValidator.fingerprint(doc),old.metadata,0)
        assertContains(RecoveryValidator.validate(doc,old).errors,"versions")
        assertFalse(RecoveryValidator.canReuse(oldApproval,doc,old))
        assertFalse(RecoveryValidator.canReuse(oldApproval,doc,current))
    }
    @Test fun wrongSchemaRejects() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d, r.copy(metadata = r.metadata.copy(recoverySchemaVersion = 999))).errors, "versions") }
    @Test fun xlsxAndTemporaryNotReadyNeverTriggerFallback() { assertNull(RecoveryPolicy.kind(MaterialKind.CHANGES)); assertFalse(RecoveryPolicy.mayTryNext(LocalProviderState.NOT_READY)); assertFalse(RecoveryPolicy.mayTryNext(LocalProviderState.DISABLED)); assertEquals(listOf("liteRtLm"), RecoveryPolicy.providers("android")) }
    @Test fun wrongHeaderTextIsRejected() { val (d, r) = fixture(); assertFalse(RecoveryValidator.validate(d.copy(sources = d.sources.map { if (it.id == "class") it.copy(text = "3_ES") else it }), r).canAdopt) }
    @Test fun fieldsCannotSwapRoles() { val (d, r) = fixture(); val l = r.cells[0].lessons[0]; assertContains(RecoveryValidator.validate(d, r.copy(cells = r.cells.mapIndexed { i, c -> if (i == 0) c.copy(lessons = listOf(l.copy(subject = l.teacher, teacher = l.subject))) else c })).errors, "fieldEvidence") }
    @Test fun truncatedNamesAreRejected() { val (d, r) = fixture(); val l = r.cells[0].lessons[0]; assertContains(RecoveryValidator.validate(d, r.copy(cells = r.cells.mapIndexed { i, c -> if (i == 0) c.copy(lessons = listOf(l.copy(teacher = l.teacher.copy(value = "架空教")))) else c })).errors, "fieldEvidence") }
    @Test fun knownTextCannotBecomeBlank() { val (d, r) = fixture(); val l = r.cells[0].lessons[0]; assertContains(RecoveryValidator.validate(d.copy(cells = d.cells.mapIndexed { i, c -> if (i == 0) c.copy(blankFields = listOf("teacher")) else c }), r.copy(cells = r.cells.mapIndexed { i, c -> if (i == 0) c.copy(lessons = listOf(l.copy(teacher = RecoveryField(RecoveryValueState.EMPTY, "", emptyList())))) else c })).errors, "falseBlankField") }
    @Test fun overlappingCellsAreRejected() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d.copy(cells = d.cells.mapIndexed { i, c -> if (i == 1) c.copy(box = d.cells[0].box) else c }), r).errors, "cellOverlap") }
    @Test fun wrongHeadingAxisIsRejected() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d.copy(cells = d.cells.mapIndexed { i, c -> if (i == 0) c.copy(dayRegion = c.dayRegion!!.copy(axis = RecoveryHeaderAxis.LEFT)) else c }), r).errors, "dayBinding") }
    @Test fun overflowingBoundsReject() { assertFalse(RecoveryBox(Double.MAX_VALUE, 1.0, Double.MAX_VALUE, 1.0).valid) }
    @Test fun cancelledRuleRecoveryDoesNotReturnPreview() { val (d, r) = fixture(); assertFailsWith<CancellationException> { runBlocking(Job().apply { cancel() }) { RecoveryEngine.run(d, "android", 36, true, emptyList(), { c -> r.cells.first { it.cellId == c.id } }) } } }
    @Test fun manifestRequiresKnownBackendAndMinimumOS() { val m = RecoveryModelManifest("synthetic", "1", "https://models.example.invalid/model", 1, "a".repeat(64), "liteRtLm", "36", 1, "CPU", "test-only", true); assertTrue(m.isUsable("liteRtLm", 1)); assertFalse(m.supportsOs("35")); assertTrue(m.supportsOs("36")); assertFalse(m.copy(recommendedBackend = "BOGUS").isUsable("liteRtLm", 1)) }

    @Serializable private data class SpecialFixture(val document: RecoveryDocument, val result: RecoveryResult)
    private fun special(kind: String = "exam"): Pair<RecoveryDocument, RecoveryResult> { val fixture = json.decodeFromString<SpecialFixture>(requireNotNull(javaClass.classLoader.getResourceAsStream("recovery-$kind.json")).bufferedReader().use { it.readText() }); return fixture.document to fixture.result.copy(metadata = fixture.result.metadata.copy(recoverySchemaVersion = RecoveryValidator.SCHEMA_VERSION, validatorVersion = RecoveryValidator.VERSION)) }
    @Test fun specialRecoveryPeriodRequiresEveryDateAndHandlesJanuaryAndOctoberBoundary() {
        for(kind in listOf("exam","return")) {
            val(d,_)=special(kind)
            assertTrue(RecoveryAdoption.matchesPeriod(d,"2026-2"));assertFalse(RecoveryAdoption.matchesPeriod(d,"2026-1"))
            val may=d.copy(days=(1..5).map { "2026-05-0$it" })
            assertTrue(RecoveryAdoption.matchesPeriod(may,"2026-1"));assertFalse(RecoveryAdoption.matchesPeriod(may,"2026-2"))
            assertTrue(RecoveryAdoption.matchesPeriod(d.copy(days=(1..5).map { "2027-01-0$it" }),"2026-2"))
            val crossing=d.copy(days=listOf("2026-09-30","2026-10-01"))
            assertFalse(RecoveryAdoption.matchesPeriod(crossing,"2026-1"));assertFalse(RecoveryAdoption.matchesPeriod(crossing,"2026-2"))
            assertFalse(RecoveryAdoption.matchesPeriod(d.copy(days=emptyList()),"2026-2"));assertFalse(RecoveryAdoption.matchesPeriod(d.copy(days=listOf("2026-10-32")),"2026-2"))
            assertFalse(RecoveryAdoption.matchesPeriod(d,"2025-2"));assertFalse(RecoveryAdoption.matchesPeriod(d,"2026-02"))
        }
    }
    @Test fun fullyValidSpecialRecoveryCannotReplaceAnotherSemestersFormalData() {
        for(kind in listOf("exam","return")) {
            val(d,r)=special(kind);assertTrue(RecoveryValidator.validate(d,r).canAdopt)
            val material=if(kind=="exam")MaterialKind.EXAM else MaterialKind.RETURN
            val same=RecoverySelection("2026-2","content://synthetic/$kind",material,d.pdfHash)
            assertTrue(RecoveryAdoption.allowed(same,same,d.pdfHash,d,r,java.time.LocalDate.of(2026,10,3)))
            assertTrue(RecoveryAdoption.allowed(same,same,d.pdfHash,d,r,java.time.LocalDate.of(2027,1,3)))
            val other=same.copy(period="2026-1")
            assertFalse(RecoveryAdoption.allowed(other,other,d.pdfHash,d,r,java.time.LocalDate.of(2026,5,3)))
        }
    }
    @Test fun specialSchedulesWithFullScopeAndExplicitSpanTimesPass() { listOf("exam", "return").forEach { val (d, r) = special(it); assertEquals(emptyList(), RecoveryValidator.validate(d, r).errors, it) } }
    @Test fun inventoryCannotDiscardTextToClaimEmpty() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d.copy(cells = d.cells.mapIndexed { i, c -> if (i == 0) c.copy(sourceIds = emptyList(), lessonBindings = emptyList(), confirmedEmpty = true) else c }), r.copy(cells = r.cells.mapIndexed { i, c -> if (i == 0) c.copy(state = RecoveryValueState.EMPTY, lessons = emptyList()) else c })).errors, "sourceInventory") }
    @Test fun unassignedTextInBlankCellRejects() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d.copy(sources = d.sources + RecoverySource("unassigned", "unassigned", d.cells[1].page, "架空の未割当文字", d.cells[1].box)), r).errors, "unassignedCellText") }
    @Test fun unboundLiteralCannotBeClassifiedAsPeriodHeader() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d.copy(sources = d.sources + RecoverySource("orphan", "unassigned", 1, "1", RecoveryBox(650.0, 200.0, 10.0, 10.0)), periodEvidence = d.periodEvidence + ("1" to (d.periodEvidence.getValue("1") + "orphan"))), r).errors, "periodHeaderCoverage") }
    @Test fun unusedSpanCannotClassifyOrphanAsClock() { val (d, r) = special(); assertContains(RecoveryValidator.validate(d.copy(sources = d.sources + RecoverySource("orphan", "unassigned", 1, "架空の授業漏れ", RecoveryBox(360.0, 200.0, 10.0, 10.0)), spanTimes = d.spanTimes + ("2026-10-02:3-4" to "架空の授業漏れ"), clockEvidence = d.clockEvidence + ("2026-10-02:3-4" to listOf("orphan"))), r).errors, "clockScope") }
    @Test fun orphanCannotBeHiddenInYearOrClassEvidence() { val (d, r) = fixture(); val s = d.copy(sources = d.sources + RecoverySource("orphan", "unassigned", 1, "架空の授業漏れ", RecoveryBox(650.0, 200.0, 10.0, 10.0))); assertContains(RecoveryValidator.validate(s.copy(yearEvidence = s.yearEvidence + "orphan"), r).errors, "yearEvidenceText"); assertContains(RecoveryValidator.validate(s.copy(classEvidence = s.classEvidence + ("3_CN" to (s.classEvidence.getValue("3_CN") + "orphan"))), r).errors, "classEvidence") }
    @Test fun orphanCannotBeHiddenInReturnNote() { val (d, r) = special("return"); assertContains(RecoveryValidator.validate(d.copy(sources = d.sources + RecoverySource("orphan", "unassigned", 1, "架空の授業漏れ", RecoveryBox(360.0, 200.0, 10.0, 10.0)), normalTimeNoteEvidence = d.normalTimeNoteEvidence + "orphan"), r).errors, "normalTimeNote") }
    @Test fun orphanTextOutsideCellsRejects() { val (d, r) = fixture(); assertContains(RecoveryValidator.validate(d.copy(sources = d.sources + RecoverySource("orphan", "unassigned", 1, "架空の授業漏れ", RecoveryBox(650.0, 200.0, 10.0, 10.0))), r).errors, "unclassifiedSource") }
    @Test fun commonClockCannotOverrideDateSpecificTime() { val (d, r) = special(); val first = "2026-10-01:1"; val second = "2026-10-02:1"; assertContains(RecoveryValidator.validate(d.copy(times = d.times + (second to d.times.getValue(first)), clockEvidence = d.clockEvidence + (second to d.clockEvidence.getValue(first)), clockBindings = d.clockBindings + (second to d.clockBindings.getValue(first).copy(day = "*"))), r).errors, "clockEvidence") }
    @Test fun anotherDayClockCannotBeQuoted() { val (d, r) = special(); val first = "2026-10-01:1"; val second = "2026-10-02:1"; assertContains(RecoveryValidator.validate(d.copy(times = d.times + (first to d.times.getValue(second)), clockEvidence = d.clockEvidence + (first to d.clockEvidence.getValue(second))), r).errors, "clockEvidence") }
    @Test fun reversedSpanClockCannotPassEvenWhenTextExists() { val (d, r) = special(); assertContains(RecoveryValidator.validate(d.copy(spanTimes = d.spanTimes + ("2026-10-01:1-2" to "18:00〜08:00"), sources = d.sources.map { if (it.id == "span-clock") it.copy(text = "18:00〜08:00") else it }), r).errors, "spanTimeEvidence") }
    @Test fun returnNormalTimeRequiresActualApplicableNote() { val (d, r) = special("return"); assertContains(RecoveryValidator.validate(d.copy(sources = d.sources.map { if (it.id == "normal-note") it.copy(text = "架空の無関係な注記") else it }), r).errors, "normalTimeNote") }
    @Test fun seventeenClassesCannotIncludeAnUnexpectedReplacement() { val (d, r) = special(); assertContains(RecoveryValidator.validate(d.copy(classes = listOf("1_CN") + d.classes.drop(1)), r).errors, "specialScope") }

}
