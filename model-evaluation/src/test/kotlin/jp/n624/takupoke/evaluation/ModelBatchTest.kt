package jp.n624.takupoke.evaluation

import com.google.gson.Gson
import jp.n624.takupoke.android.LiteRtEvaluationFixtures as Fixtures
import jp.n624.takupoke.core.*
import java.io.File
import kotlin.test.*

class ModelBatchTest {
    private fun raw(lessons:List<RecoveryLesson>):String = Gson().toJson(mapOf("lessons" to lessons.map { mapOf("subject" to it.subject,"teacher" to it.teacher,"room" to it.room) }))
    @Test fun exactLiteralControlsPassTheUnchangedScorer() {
        for(case in Fixtures.cases.filter(ModelBatch::positive)) {
            val (doc,cell)=ModelBatch.prepared(case)
            val expected=requireNotNull(RecoveryRules.recover(doc,cell)).lessons
            val score=ModelBatch.score(case,raw(expected))
            assertEquals(true,score["rawExact"],case.name)
            assertEquals(true,score["groundedExact"],case.name)
            assertEquals(true,score["validatorAdopt"],case.name)
            assertEquals(false,score["falseAdoption"],case.name)
        }
    }
    @Test fun duplicateJsonKeysAndCrossRoleReuseCannotAdopt() {
        val case=Fixtures.cases.first();val (doc,cell)=ModelBatch.prepared(case)
        val expected=requireNotNull(RecoveryRules.recover(doc,cell)).lessons
        val text=raw(expected)
        assertEquals(false,ModelBatch.score(case,text.replaceFirst("\"lessons\":","\"lessons\":[],\"lessons\":"))["schemaDecoded"])
        assertEquals(false,ModelBatch.score(case,raw(expected.map { it.copy(teacher=it.subject) }))["validatorAdopt"])
        assertEquals(false,ModelBatch.score(case,raw(expected.map { it.copy(subject=it.subject.copy(evidence=listOf("unknown-original-id"))) }))["validatorAdopt"])
    }
    @Test fun UnsafeOriginalInputNeverAdoptsEvenWithRulesFields() {
        for(case in Fixtures.cases.filter { it.incomplete || it.unknownEmpty }) {
            val (doc,cell)=ModelBatch.prepared(case)
            val safeCase=Fixtures.cases.single { it.name==if(case.unknownEmpty)"empty_teacher"else "baseline" }
            val (safeDoc,safeCell)=ModelBatch.prepared(safeCase)
            val expected=requireNotNull(RecoveryRules.recover(safeDoc,safeCell)).lessons
            assertEquals(false,ModelBatch.score(case,raw(expected))["validatorAdopt"],case.name)
        }
    }
    @Test fun errorRowsStayInDenominatorAndRulesControlsUseNoProvider() {
        val file=File.createTempFile("batch-scorer-", ".json")
        try {
            file.writeText(Gson().toJson(mapOf("rows" to Fixtures.cases.map { mapOf("name" to it.name,"attempted" to if(it.name=="baseline")null else false,"stage" to if(it.name=="missing_subject")"preparation_rejected"else "initialization_error") })))
            val result=Gson().fromJson(ModelBatch.replay(file),Map::class.java)
            assertEquals(16.0,result["plannedCases"])
            assertEquals(13.0,result["positiveDenominator"])
            assertEquals(0.0,result["modelRecoveryCases"])
            assertEquals(13,(result["rulesControls"] as List<*>).size)
            assertNull(((result["rows"] as List<*>).first() as Map<*,*>)["nativeAttempted"])
            assertEquals(false,result["qualityApproved"])
            assertTrue((result["rows"] as List<*>).all { (it as Map<*,*>)["validatorAdopt"]==false })
            file.writeText("{\"rows\":[]}")
            assertFailsWith<IllegalArgumentException> { ModelBatch.replay(file) }
        } finally { file.delete() }
    }
}
