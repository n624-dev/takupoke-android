package jp.n624.takupoke.evaluation

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import jp.n624.takupoke.android.LiteRtEvaluationFixtures as Fixtures
import jp.n624.takupoke.core.*
import java.io.File

/** Posthoc scoring only. Reads unchanged raw; never repairs a proposal or changes production adoption. */
object StateEvidenceScore {
    fun primary(case:Fixtures.Case, raw:String):Map<String,Any?> {
        require(ModelBatch.positive(case))
        val (doc,cell)=ModelBatch.prepared(case)
        val expected=requireNotNull(RecoveryRules.recover(doc,cell)).lessons
        Fixtures.checkIntendedValues(case,expected)
        val prompt=Fixtures.prompt(doc,cell)
        val generated=try { ProviderContract.parse(raw,prompt) } catch(e:Exception) {
            return mapOf("schemaDecoded" to false,"primaryStateEvidenceExact" to false,"fieldDenominator" to case.parallel*3,"decodeError" to e.javaClass.simpleName)
        }
        val sourceOrder=prompt.sources.map { it.id }
        val labels=cell.roleScopes.flatMap { it.labelSourceIds }.toSet()
        val fields=generated.zip(expected).flatMapIndexed { lessonIndex,(actual,oracle) ->
            listOf("subject" to (actual.subject to oracle.subject),"teacher" to (actual.teacher to oracle.teacher),"room" to (actual.room to oracle.room)).map { (role,pair) ->
                val (field,reference)=pair
                val scoped=cell.roleScopes.singleOrNull { it.lessonIndex==lessonIndex && it.role==role }
                val blankProof=role!="subject" && reference.evidence.isEmpty() && if(cell.roleScopes.isNotEmpty())scoped?.emptyVerified==true else role in cell.blankFields
                val owned=field.evidence.none { it in labels } && field.evidence==reference.evidence && field.evidence==sourceOrder.filter { it in field.evidence }
                val state=field.state==reference.state && (field.state!=RecoveryValueState.EMPTY || blankProof && field.evidence.isEmpty())
                mapOf("lessonIndex" to lessonIndex,"role" to role,"stateExact" to state,"originalBodyIdsExact" to owned,"primaryExact" to (state && owned),
                    "generatedValueExactDiagnostic" to (field.value==reference.value))
            }
        }
        return mapOf("schemaDecoded" to true,"primaryStateEvidenceExact" to (generated.size==expected.size && fields.all { it["primaryExact"]==true }),"fieldDenominator" to expected.size*3,"fields" to fields)
    }
    fun replay(receipt:File):String {
        val root=JsonParser.parseString(receipt.readText()).asJsonObject
        val native=if(root.has("native.json"))root.getAsJsonObject("native.json")else root
        val rows=native.getAsJsonArray("rows").map { it.asJsonObject }
        require(rows.map { it["name"].asString }==Fixtures.cases.map { it.name })
        val scored=Fixtures.cases.zip(rows).map { (case,row) ->
            val positive=ModelBatch.positive(case);val stage=row["stage"].asString
            if(stage!="output") mapOf("name" to case.name,"positive" to positive,"stage" to stage,"outcome" to if(stage=="preparation_rejected" && case.name=="missing_subject")"correct_preparation_rejection"else "execution_unavailable_or_error","primaryStateEvidenceExact" to null)
            else {
                val raw=row["raw"].asString;val production=ModelBatch.score(case,raw)
                val outcome=when {
                    production["schemaDecoded"]!=true -> "invalid_model_output"
                    production["validatorAdopt"]==true && production["groundedExact"]!=true -> "false_adoption"
                    production["validatorAdopt"]==true -> "recovery_success"
                    positive -> "readable_positive_recovery_failure"
                    production.containsKey("validatorErrors") -> "correct_unsafe_rejection"
                    else -> "invalid_model_output"
                }
                mapOf("name" to case.name,"positive" to positive,"stage" to stage,"outcome" to outcome,"production" to production)+if(positive)primary(case,raw)else mapOf("primaryStateEvidenceExact" to null)
            }
        }
        return GsonBuilder().serializeNulls().create().toJson(mapOf("providerFingerprint" to ProviderContract.fingerprint,"plannedCases" to Fixtures.cases.size,"primaryPositiveDenominator" to Fixtures.cases.count(ModelBatch::positive),
            "primaryAssessedPositiveDenominator" to scored.count { it["positive"]==true && it["stage"]=="output" },
            "positiveExecutionUnassessed" to scored.count { it["positive"]==true && it["stage"]!="output" },
            "primaryStateEvidenceExact" to scored.count { it["positive"]==true && it["primaryStateEvidenceExact"]==true },"primaryFieldDenominator" to Fixtures.cases.filter(ModelBatch::positive).sumOf { it.parallel*3 },
            "primaryAssessedFieldDenominator" to Fixtures.cases.zip(rows).filter { (case,row) -> ModelBatch.positive(case) && row["stage"].asString=="output" }.sumOf { it.first.parallel*3 },
            "readablePositiveRecoveryFailures" to scored.count { it["positive"]==true && it["stage"]=="output" && it["outcome"]!="recovery_success" },"outcomes" to scored.groupingBy { it["outcome"] }.eachCount(),"rows" to scored,
            "rawUnchanged" to true,"generatedValue" to "diagnostic; production reconstructs PRESENT from original IDs and still validates EMPTY value", "modelRecoveryCases" to 0,"qualityApproved" to false))
    }
}
