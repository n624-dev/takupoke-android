package jp.n624.takupoke.evaluation

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import jp.n624.takupoke.android.LiteRtEvaluationFixtures as Fixtures
import jp.n624.takupoke.core.*
import kotlin.test.*

class StateEvidenceScoreTest {
    private fun raw(lessons:List<RecoveryLesson>)=Gson().toJson(mapOf("lessons" to lessons.map { mapOf("subject" to it.subject,"teacher" to it.teacher,"room" to it.room) }))
    private fun control(name:String):Pair<Fixtures.Case,List<RecoveryLesson>> {
        val case=Fixtures.cases.single { it.name==name };val (doc,cell)=ModelBatch.prepared(case)
        return case to requireNotNull(RecoveryRules.recover(doc,cell)).lessons
    }
    @Test fun wrongGeneratedPresentValueIsOnlyDiagnosticWhenOriginalIdsAreCorrect() {
        val (case,expected)=control("baseline")
        val text=raw(expected.map { it.copy(subject=it.subject.copy(value="invented wrong copied value")) })
        val before=text.toByteArray()
        assertEquals(true,StateEvidenceScore.primary(case,text)["primaryStateEvidenceExact"])
        val production=ModelBatch.score(case,text)
        assertEquals(false,production["rawExact"])
        assertEquals(true,production["groundedExact"])
        assertEquals(true,production["validatorAdopt"])
        assertContentEquals(before,text.toByteArray())
    }
    @Test fun primaryEmptyProofDoesNotOverrideProductionEmptyValueCheck() {
        val (case,expected)=control("empty_teacher")
        val text=raw(expected.map { it.copy(teacher=it.teacher.copy(value="invented wrong empty value")) })
        assertEquals(true,StateEvidenceScore.primary(case,text)["primaryStateEvidenceExact"])
        assertEquals(false,ModelBatch.score(case,text)["validatorAdopt"])
        val (presentCase,present)=control("baseline")
        val falseSubject=raw(present.map { it.copy(subject=RecoveryField(RecoveryValueState.EMPTY,"",emptyList())) })
        assertEquals(false,StateEvidenceScore.primary(presentCase,falseSubject)["primaryStateEvidenceExact"])
        assertEquals(false,ModelBatch.score(presentCase,falseSubject)["validatorAdopt"])
    }
    @Test fun runtimeFailureIsUnassessedAndShortOutputKeepsPlannedFields() {
        val rows=Fixtures.cases.map { case ->
            if(case.name=="missing_subject")mapOf("name" to case.name,"stage" to "preparation_rejected")
            else if(case.name=="parallel_two")mapOf("name" to case.name,"stage" to "inference_error")
            else mapOf("name" to case.name,"stage" to "output","raw" to "{}")
        }
        val file=File.createTempFile("state-evidence-runtime", ".json")
        try {
            file.writeText(Gson().toJson(mapOf("rows" to rows)))
            val score=JsonParser.parseString(StateEvidenceScore.replay(file)).asJsonObject
            assertEquals(13,score["primaryPositiveDenominator"].asInt)
            assertEquals(12,score["primaryAssessedPositiveDenominator"].asInt)
            assertEquals(1,score["positiveExecutionUnassessed"].asInt)
            assertEquals(12,score["readablePositiveRecoveryFailures"].asInt)
            val failed=score["rows"].asJsonArray.single { it.asJsonObject["name"].asString=="parallel_two" }.asJsonObject
            assertFalse(failed.has("primaryStateEvidenceExact") && !failed["primaryStateEvidenceExact"].isJsonNull)
        } finally { file.delete() }
        val (case,expected)=control("parallel_two")
        assertEquals(6,StateEvidenceScore.primary(case,raw(expected.take(1)))["fieldDenominator"])
        assertEquals(false,StateEvidenceScore.primary(case,raw(expected.take(1)))["primaryStateEvidenceExact"])
    }
    @Test fun correctLiteralTextCannotRescueWrongRoleLabelOrDuplicateIds() {
        val (case,expected)=control("baseline");val (_,cell)=ModelBatch.prepared(case)
        val label=cell.roleScopes.single { it.role=="subject" }.labelSourceIds
        for(ids in listOf(label,expected.first().subject.evidence+expected.first().subject.evidence,listOf("unknown-original-id"))) {
            val text=raw(expected.map { it.copy(subject=it.subject.copy(evidence=ids)) })
            assertEquals(false,StateEvidenceScore.primary(case,text)["primaryStateEvidenceExact"])
            assertEquals(false,ModelBatch.score(case,text)["validatorAdopt"])
        }
    }
}
