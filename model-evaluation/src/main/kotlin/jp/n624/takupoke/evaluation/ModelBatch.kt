package jp.n624.takupoke.evaluation

import com.google.gson.Gson
import com.google.gson.JsonParser
import jp.n624.takupoke.android.LiteRtEvaluationFixtures as Fixtures
import jp.n624.takupoke.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File

/** Research component replay; no model is installed, approved, or needed by these Rules controls. */
object ModelBatch {
    private val gson = Gson()
    private val metadata = RecoveryMetadata("researchNativeCPU", "unapprovedBatch", "pinnedInReceipt", "LiteRT-LM:0.17.1", "3", RecoveryValidator.SCHEMA_VERSION, RecoveryValidator.VERSION, System.getProperty("os.name"))
    fun positive(case:Fixtures.Case) = !case.incomplete && !case.unknownEmpty && case.name != "missing_subject"
    fun prepared(case:Fixtures.Case):Pair<RecoveryDocument,RecoveryCell> {
        val doc=Fixtures.fixture(case)
        return doc to doc.cells.single { !it.confirmedEmpty }
    }
    fun export():String = gson.toJson(mapOf(
        "providerFingerprint" to ProviderContract.fingerprint,
        "purpose" to "consumed invented component dev comparison; Rules resolved; no qualification",
        "cases" to Fixtures.cases.map { case ->
            try {
                val (doc,cell)=prepared(case);val prompt=Fixtures.prompt(doc,cell)
                mapOf("name" to case.name,"prepared" to true,"inputErrors" to RecoveryValidator.inputErrors(doc),
                    "instruction" to ProviderContract.instruction(prompt),"prompt" to JsonParser.parseString(json.encodeToString(prompt)),
                    "schema" to JsonParser.parseString(ProviderContract.schema(prompt)))
            } catch(e:RecoveryPreparationFailure) {
                require(case.name=="missing_subject")
                mapOf("name" to case.name,"prepared" to false,"preparationRejected" to true,"error" to e.javaClass.simpleName)
            }
        }))
    fun score(case:Fixtures.Case, raw:String):Map<String,Any?> {
        val (doc,cell)=prepared(case);val prompt=Fixtures.prompt(doc,cell)
        val generated=try { ProviderContract.parse(raw,prompt) } catch(e:Exception) {
            return mapOf("schemaDecoded" to false,"decodeError" to e.javaClass.simpleName,"decodeDetail" to e.message,"rawExact" to false,"groundedExact" to false,"validatorAdopt" to false,"falseAdoption" to false)
        }
        val rawLessons=generated.map { it.copy(dateEvidence=cell.dayHeaderIds,periodEvidence=cell.periodHeaderIds) }
        val expected=RecoveryRules.recover(doc,cell)?.lessons
        val rawExact=positive(case) && runCatching { Fixtures.checkIntendedValues(case,rawLessons);require(rawLessons==expected) }.isSuccess
        return try {
            val grounded=Fixtures.grounded(doc,cell,generated)
            val result=Fixtures.result(doc,cell,grounded,metadata)
            val validation=RecoveryValidator.validate(doc,result)
            val groundedExact=positive(case) && runCatching {
                Fixtures.checkIntendedValues(case,grounded)
                val oracle=Fixtures.result(doc,cell,requireNotNull(expected),metadata)
                require(result==oracle) // Full cells, slots/header citations, metadata normalized to the same receipt.
                require(RecoveryAnalysis.convert(doc,result)==RecoveryAnalysis.convert(doc,oracle))
            }.isSuccess
            mapOf("schemaDecoded" to true,"rawExact" to rawExact,"groundedExact" to groundedExact,
                "validatorAdopt" to validation.canAdopt,"falseAdoption" to (validation.canAdopt && !groundedExact),"validatorErrors" to validation.errors)
        } catch(e:Exception) {
            mapOf("schemaDecoded" to true,"rawExact" to rawExact,"groundedExact" to false,"validatorAdopt" to false,"falseAdoption" to false,"groundingError" to e.javaClass.simpleName,"groundingDetail" to e.message)
        }
    }
    fun replay(file:File):String {
        val native=JsonParser.parseString(file.readText()).asJsonObject
        val rows=native.getAsJsonArray("rows").map { it.asJsonObject }
        require(rows.map { it["name"].asString }==Fixtures.cases.map { it.name }) { "Missing, reordered or duplicate planned rows" }
        val scored=Fixtures.cases.zip(rows).map { (case,row) ->
            val base=mapOf("name" to case.name,"positive" to positive(case),"nativeAttempted" to row["attempted"]?.takeUnless { it.isJsonNull }?.asBoolean,"stage" to row["stage"].asString)
            if(row["stage"].asString=="output") base+score(case,row["raw"].asString)
            else base+mapOf("rawExact" to false,"groundedExact" to false,"validatorAdopt" to false,"falseAdoption" to false)
        }
        val rules=runBlocking { Fixtures.cases.filter(::positive).map { case ->
            val (doc,cell)=prepared(case);var availabilityCalls=0;var recoverCalls=0
            val unavailable=object:LocalRecoveryProvider {
                override val id="countingUnavailable";override val localOnly=true;override val metadata=ModelBatch.metadata
                override suspend fun availability():LocalProviderState { availabilityCalls++;return LocalProviderState.NOT_READY }
                override suspend fun recoverCell(cell:RecoveryPromptCell):List<RecoveryLesson> { recoverCalls++;error("Rules control invoked a model") }
            }
            val run=RecoveryEngine.run(doc,"linux",1,true,listOf(unavailable),{null})
            val result=requireNotNull(run.result);val expected=requireNotNull(RecoveryRules.recover(doc,cell)).lessons
            Fixtures.checkIntendedValues(case,expected)
            require(result.copy(metadata=metadata)==Fixtures.result(doc,cell,expected,metadata))
            require(RecoveryValidator.validate(doc,result).canAdopt)
            RecoveryAnalysis.convert(doc,result)
            require(availabilityCalls==0 && recoverCalls==0)
            mapOf("name" to case.name,"exact" to true,"availabilityCalls" to availabilityCalls,"recoverCalls" to recoverCalls)
        } }
        return gson.toJson(mapOf("providerFingerprint" to ProviderContract.fingerprint,"plannedCases" to Fixtures.cases.size,"positiveDenominator" to Fixtures.cases.count(::positive),"unsafePreparedControls" to 2,
            "rows" to scored,"rulesControls" to rules,"modelRecoveryCases" to 0,"qualityApproved" to false,"catalogValidated" to false,
            "limitation" to "Consumed synthetic field component dev only; no exam/return/heldout or physical Android qualification"))
    }
}
fun main(args:Array<String>) {
    require(args.size>=2)
    when(args[0]) {
        "export" -> File(args[1]).writeText(ModelBatch.export())
        "score" -> { require(args.size==3);File(args[2]).writeText(ModelBatch.replay(File(args[1]))) }
        "score-state-evidence" -> { require(args.size==3);File(args[2]).writeText(StateEvidenceScore.replay(File(args[1]))) }
        else -> error("Expected export or score")
    }
}
