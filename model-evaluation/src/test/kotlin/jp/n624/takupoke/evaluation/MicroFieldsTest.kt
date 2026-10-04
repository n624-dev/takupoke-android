package jp.n624.takupoke.evaluation

import com.google.gson.Gson
import com.google.gson.JsonParser
import jp.n624.takupoke.android.LiteRtEvaluationFixtures as Fixtures
import java.io.File
import kotlin.test.*

class MicroFieldsTest {
    @Test fun originalPlannerDeterminesAll42FieldsAndRejectsUnsafeOriginals() {
        val plans=Fixtures.cases.map(MicroFields::plan)
        assertEquals(13,plans.count { it.status=="rules_resolved" })
        assertEquals(42,plans.sumOf { it.tasks.size })
        assertEquals(2,plans.flatMap { it.tasks }.count { it.body.isEmpty() })
        assertTrue(plans.filter { it.status!="rules_resolved" }.all { it.tasks.isEmpty() })
        for(case in Fixtures.cases.filter { ModelBatch.positive(it) }) {
            val plan=MicroFields.plan(case);val selected=plan.tasks.associate { it.name to it.body.map { source -> source.id } }
            val score=ModelBatch.score(case,MicroFields.assembled(plan,selected))
            assertEquals(true,score["groundedExact"]);assertEquals(true,score["validatorAdopt"])
        }
    }
    @Test fun strictSmallParserRejectsDuplicateKeysTypesSizeDepthAndOrderWithoutRepair() {
        val original=MicroFields.plan(Fixtures.cases.first()).tasks.first()
        val task=original.copy(allIds=listOf("a","b"));assertEquals(listOf("a","b"),MicroFields.decode("{\"ids\":[\"a\",\"b\"]}",task))
        for(raw in listOf("{\"ids\":[],\"ids\":[\"a\"]}","{ids:[]}","{\"ids\":[NaN]}","{\"ids\":[1e999]}","{\"ids\":[[\"a\"]]}","{\"ids\":[] } {}","{\"ids\":[\"b\",\"a\"]}","{\"ids\":[\"a\",\"a\"]}","{\"ids\":[\"unknown\"]}","{\"ids\":[],\"state\":\"EMPTY\"}"," ".repeat(16385)+"{\"ids\":[]}")) {
            assertFails { MicroFields.decode(raw,task) }
        }
    }
    @Test fun missingReturnedIdsDoNotInferEmptyAndOtherRoleIdsStillFailProduction() {
        val case=Fixtures.cases.first();val plan=MicroFields.plan(case)
        val selected=plan.tasks.associate { it.name to it.body.map { source -> source.id } }.toMutableMap()
        val subject=plan.tasks.single { it.role=="subject" };val teacher=plan.tasks.single { it.role=="teacher" }
        selected[subject.name]=emptyList()
        val missing=MicroFields.assembled(plan,selected)
        assertEquals("PRESENT",JsonParser.parseString(missing).asJsonObject["lessons"].asJsonArray[0].asJsonObject["subject"].asJsonObject["state"].asString)
        assertEquals(false,ModelBatch.score(case,missing)["validatorAdopt"])
        selected[subject.name]=MicroFields.decode(Gson().toJson(mapOf("ids" to selected.getValue(teacher.name))),subject)
        assertEquals(false,ModelBatch.score(case,MicroFields.assembled(plan,selected))["validatorAdopt"])
    }
    @Test fun replaySeparatesInterruptedObligationAndKeepsPreferredActualRulesZeroCalls() {
        val exported=JsonParser.parseString(MicroFields.export()).asJsonObject
        val plans=Fixtures.cases.flatMap { MicroFields.plan(it).tasks }.associateBy { it.name }
        val rows=exported["cases"].asJsonArray.map { item -> val obj=item.asJsonObject;val name=obj["name"].asString;val task=plans[name]
            when { task==null -> mapOf("name" to name,"stage" to "preparation_rejected","attempted" to false)
                name=="baseline/0/subject" -> mapOf("name" to name,"stage" to "resource_stop","attempted" to true)
                else -> mapOf("name" to name,"stage" to "output","attempted" to true,"raw" to Gson().toJson(mapOf("ids" to task.body.map { it.id }))) }
        }
        val temp=File.createTempFile("micro-injected-stop", ".json")
        try {
            temp.writeText(Gson().toJson(mapOf("rows" to rows)))
            val score=JsonParser.parseString(MicroFields.replay(temp)).asJsonObject
            assertEquals(12,score["assessedPositiveCells"].asInt);assertEquals(1,score["positiveCellsExecutionUnassessed"].asInt)
            assertEquals(12,score["primaryExactCells"].asInt);assertEquals(41,score["assessedFields"].asInt)
            assertEquals(0,score["preferredModelCalls"].asInt)
            assertEquals(13,score["preferredRulesControls"].asJsonArray.size())
            assertFalse(score["qualityApproved"].asBoolean)
        }finally { temp.delete() }
    }
}
