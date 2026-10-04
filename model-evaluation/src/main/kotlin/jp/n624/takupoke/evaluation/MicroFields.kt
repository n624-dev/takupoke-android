package jp.n624.takupoke.evaluation

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import jp.n624.takupoke.android.LiteRtEvaluationFixtures as Fixtures
import jp.n624.takupoke.core.*
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.io.File
import java.security.MessageDigest

/** Research only: all current fields are already determined by the original certificate. */
object MicroFields {
    const val INSTRUCTION_SHA="c6d1410ebe5de98ad1934627b3f5115ae396087d758814dbc538daafc750998c"
    const val ORIGINAL_CORPUS_SHA="d15657770607fbc1910c11bdc7a185b296b683905c5823427e05c39bf128ee5a"
    private val gson=GsonBuilder().serializeNulls().create()
    private val roles=listOf("subject","teacher","room")
    data class FieldTask(val caseName:String,val lessonIndex:Int,val role:String,val allIds:List<String>,val body:List<RecoveryPromptSource>,val state:RecoveryValueState) {
        val name get()="$caseName/$lessonIndex/$role"
    }
    data class Plan(val caseName:String,val status:String,val tasks:List<FieldTask>)
    private fun sha(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    fun plan(case:Fixtures.Case):Plan {
        val prepared=try { ModelBatch.prepared(case) }catch(_:RecoveryPreparationFailure){return Plan(case.name,"preparation_rejected",emptyList())}
        val (doc,cell)=prepared
        if(RecoveryValidator.inputErrors(doc).isNotEmpty())return Plan(case.name,"invalid_original",emptyList())
        // Keep the production Rules-first boundary. Missing original ink is not an AI inference task.
        if(RecoveryRules.recover(doc,cell)==null)return Plan(case.name,"unverified_original_field",emptyList())
        val prompt=Fixtures.prompt(doc,cell);val labels=cell.roleScopes.flatMap { it.labelSourceIds }.toSet()
        val tasks=(0 until cell.parallelCount).flatMap { index -> roles.map { role ->
            val scope=cell.roleScopes.singleOrNull { it.lessonIndex==index && it.role==role }
            val binding=cell.lessonBindings.getOrNull(index)
            val bound=when(role){"subject"->binding?.subject;"teacher"->binding?.teacher;else->binding?.room}
            val body=prompt.sources.filter { source -> source.id !in labels && source.id !in cell.separatorIds && if(cell.roleScopes.isNotEmpty())scope?.let { it.page==cell.page && source.box?.let(it.box::contains)==true }==true else source.id in bound.orEmpty() }
            val emptyProof=role!="subject" && if(cell.roleScopes.isNotEmpty())scope?.emptyVerified==true else role in cell.blankFields
            val state=if(body.isNotEmpty())RecoveryValueState.PRESENT else if(emptyProof)RecoveryValueState.EMPTY else RecoveryValueState.MISSING
            require(state!=RecoveryValueState.MISSING) { "Rules-resolved plan lost original proof" }
            FieldTask(case.name,index,role,prompt.sources.map { it.id },body,state)
        } }
        return Plan(case.name,"rules_resolved",tasks)
    }
    fun export():String {
        require(sha(ModelBatch.export().toByteArray())==ORIGINAL_CORPUS_SHA)
        val instruction=File("scripts/model-batch-micro-instruction.txt").readBytes();require(sha(instruction)==INSTRUCTION_SHA)
        return gson.toJson(mapOf("providerFingerprint" to ProviderContract.fingerprint,"originalCorpusSHA256" to ORIGINAL_CORPUS_SHA,
            "purpose" to "forced unnecessary ID-copy compliance only; original Rules resolve all positive fields; no useful AI qualification",
            "cases" to Fixtures.cases.flatMap { case -> val plan=plan(case)
                if(plan.tasks.isEmpty())listOf(mapOf("name" to case.name,"prepared" to false,"originalCase" to case.name,"preflightStatus" to plan.status))
                else plan.tasks.map { task -> mapOf("name" to task.name,"prepared" to true,"originalCase" to case.name,"instruction" to instruction.toString(Charsets.UTF_8),
                    "prompt" to mapOf("mode" to "deterministicBodyIdCopy","lessonIndex" to task.lessonIndex,"role" to task.role,"bodyCandidates" to task.body.map { mapOf("id" to it.id,"text" to it.text) }),
                    "schema" to mapOf("type" to "object","properties" to mapOf("ids" to mapOf("type" to "array","items" to mapOf("type" to "string","enum" to task.allIds),"maxItems" to 48)),"required" to listOf("ids"),"additionalProperties" to false)) }
            }))
    }
    fun decode(raw:String,task:FieldTask):List<String> {
        require(raw.toByteArray(Charsets.UTF_8).size<=16384) { "Oversized native output" }
        val ids=mutableListOf<String>()
        JsonReader(StringReader(raw)).use { reader ->
            reader.setStrictness(Strictness.STRICT)
            reader.beginObject();var seen=false
            while(reader.hasNext()) {
                require(reader.nextName()=="ids" && !seen) { "Unknown or duplicate object key" };seen=true
                reader.beginArray()
                while(reader.hasNext()) {
                    // This fixed shape permits depth two only; nested arrays/objects never recurse.
                    require(reader.peek()==JsonToken.STRING && ids.size<48)
                    ids+=reader.nextString()
                }
                reader.endArray()
            }
            reader.endObject();require(seen && reader.peek()==JsonToken.END_DOCUMENT)
        }
        require(ids.size<=48 && ids.distinct().size==ids.size && ids.all { it in task.allIds })
        require(ids==task.allIds.filter { it in ids }) { "Returned order differs from original source-array order" }
        return ids // No sorting, filtering, replacement, or value repair.
    }
    fun assembled(plan:Plan,selected:Map<String,List<String>>):String=gson.toJson(mapOf("lessons" to plan.tasks.groupBy { it.lessonIndex }.toSortedMap().values.map { tasks ->
        tasks.associate { task -> task.role to mapOf("state" to task.state.name,"value" to "","evidence" to selected.getValue(task.name)) }
    }))
    fun replay(file:File):String {
        require(file.length()<=4194304) { "Oversized native receipt" }
        val root=JsonParser.parseString(file.readText()).asJsonObject
        val native=if(root.has("native.json"))root.getAsJsonObject("native.json")else root
        val rows=native.getAsJsonArray("rows").map { it.asJsonObject };val exported=JsonParser.parseString(export()).asJsonObject.getAsJsonArray("cases")
        require(rows.map { it["name"].asString }==exported.map { it.asJsonObject["name"].asString })
        val rowByName=rows.associateBy { it["name"].asString }
        val cases=Fixtures.cases.map { case ->
            val plan=plan(case)
            if(plan.tasks.isEmpty())mapOf("name" to case.name,"positive" to false,"outcome" to "deterministic_original_rejection","preflightStatus" to plan.status,"nativeCalls" to 0,"modelSafeRejectionCredit" to false)
            else {
                val selected=mutableMapOf<String,List<String>>()
                val fields=plan.tasks.map { task -> val row=rowByName.getValue(task.name);val stage=row["stage"].asString
                    if(stage!="output")mapOf("name" to task.name,"stage" to stage,"primaryExact" to null)
                    else try { val ids=decode(row["raw"].asString,task);selected[task.name]=ids
                        mapOf("name" to task.name,"stage" to stage,"primaryExact" to (ids==task.body.map { it.id }),"returnedIds" to ids)
                    }catch(e:Exception){mapOf("name" to task.name,"stage" to stage,"primaryExact" to false,"decodeError" to e.javaClass.simpleName)}
                }
                val allReturned=fields.all { it["stage"]=="output" }
                val production=if(selected.size==plan.tasks.size)ModelBatch.score(case,assembled(plan,selected))else null
                val exact=if(allReturned)fields.all { it["primaryExact"]==true }else null
                mapOf("name" to case.name,"positive" to true,"allFieldsReturned" to allReturned,"primaryExact" to exact,"fields" to fields,"production" to production,
                    "outcome" to if(!allReturned)"execution_unassessed" else if(production?.get("validatorAdopt")==true && production["groundedExact"]!=true)"false_adoption" else if(production?.get("groundedExact")==true && production["validatorAdopt"]==true)"component_success" else "returned_component_failure")
            }
        }
        // Reuse the actual Engine/Validator/formal zero-provider controls, never a fake preparation path.
        val temp=File.createTempFile("micro-rules-control", ".json")
        val rules=try {
            temp.writeText(gson.toJson(mapOf("rows" to Fixtures.cases.map { mapOf("name" to it.name,"attempted" to false,"stage" to "not_attempted") })))
            JsonParser.parseString(ModelBatch.replay(temp)).asJsonObject.getAsJsonArray("rulesControls")
        } finally { temp.delete() }
        return gson.toJson(mapOf("originalCorpusSHA256" to ORIGINAL_CORPUS_SHA,"instructionSHA256" to INSTRUCTION_SHA,"plannedOriginalCases" to 16,"plannedPositiveCells" to 13,"plannedPositiveFields" to 42,
            "assessedPositiveCells" to cases.count { it["positive"]==true && it["allFieldsReturned"]==true },"positiveCellsExecutionUnassessed" to cases.count { it["positive"]==true && it["allFieldsReturned"]!=true },
            "primaryExactCells" to cases.count { it["primaryExact"]==true },"assessedFields" to cases.sumOf { (it["fields"] as? List<*>)?.count { field -> (field as Map<*,*>)["stage"]=="output" } ?: 0 },
            "exactFields" to cases.sumOf { (it["fields"] as? List<*>)?.count { field -> (field as Map<*,*>)["primaryExact"]==true } ?: 0 },"outcomes" to cases.groupingBy { it["outcome"] }.eachCount(),"rows" to cases,
            "preferredRulesControls" to rules,"preferredModelCalls" to 0,"genuineAIChoiceFields" to 0,"task" to "Unnecessary deterministic-ID COPY compliance; no semantic role selection remains","qualityApproved" to false))
    }
}
