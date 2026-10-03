package jp.n624.takupoke.android

import android.app.ActivityManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.*
import jp.n624.takupoke.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Manual optimized-APK component evaluation. All document atoms are invented.
 * The host fetches the public, pinned model; this process has RejectNetwork.
 * Direct component calls exercise an unapproved candidate without changing the
 * production catalog, availability gates, Rules priority or model installation. */
class LiteRtRuntimeEvaluationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val gson=com.google.gson.Gson()
    private fun report(event:String,values:Map<String,Any?>) { Log.i("TakupokeRuntimeEvaluation",gson.toJson(mapOf("event" to event)+values)) }
    private val cases=LiteRtEvaluationFixtures.cases
    private fun fixture(case:LiteRtEvaluationFixtures.Case)=LiteRtEvaluationFixtures.fixture(case)
    private fun prompt(doc:RecoveryDocument,cell:RecoveryCell)=LiteRtEvaluationFixtures.prompt(doc,cell)
    private fun grounded(doc:RecoveryDocument,cell:RecoveryCell,lessons:List<RecoveryLesson>)=LiteRtEvaluationFixtures.grounded(doc,cell,lessons)
    private fun result(doc:RecoveryDocument,cell:RecoveryCell,lessons:List<RecoveryLesson>,metadata:RecoveryMetadata)=LiteRtEvaluationFixtures.result(doc,cell,lessons,metadata)
    private fun memory():Map<String,Long> {
        val m=Debug.MemoryInfo();Debug.getMemoryInfo(m)
        return mapOf("totalPssKiB" to m.totalPss.toLong(),"privateFootprintKiB" to (m.totalPrivateDirty.toLong()+m.totalPrivateClean),"nativePssKiB" to m.nativePss.toLong(),"nativeHeapAllocatedBytes" to Debug.getNativeHeapAllocatedSize(),"javaHeapUsedBytes" to (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()))
    }
    @Test(timeout=900000) fun pinnedCandidateCpu4096SchemaValidatorCancellationAndRelease()=runBlocking {
        check(InstrumentationRegistry.getArguments().getString("runtimeEvaluation")=="true")
        val manifest=json.decodeFromString<RecoveryModelManifest>(instrumentation.context.assets.open("litert-evaluation-candidate.json").bufferedReader().use { it.readText() })
        assertEquals(RecoveryModelCatalog.candidates.first(),manifest);assertFalse(manifest.validated)
        val model=File(requireNotNull(InstrumentationRegistry.getArguments().getString("modelPath")))
        assertEquals(File(requireNotNull(context.getExternalFilesDir("runtime-evaluation")),"candidate.litertlm").canonicalPath,model.canonicalPath)
        assertEquals(manifest.size,model.length())
        val sha=MessageDigest.getInstance("SHA-256");model.inputStream().use { input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;sha.update(b,0,n)} }
        assertEquals(manifest.sha256,sha.digest().joinToString("") { "%02x".format(it) })
        val available=ActivityManager.MemoryInfo();context.getSystemService(ActivityManager::class.java).getMemoryInfo(available)
        report("configuration",mapOf("modelId" to manifest.modelId,"revision" to manifest.version,"sha256" to manifest.sha256,"bytes" to manifest.size,"validated" to false,"sdk" to Build.VERSION.SDK_INT,"abis" to Build.SUPPORTED_ABIS.toList(),"device" to Build.MODEL,"backend" to "CPU","contextTokens" to 4096,"runtime" to "LiteRT-LM:0.17.1","availableMemoryBytes" to available.availMem,"totalMemoryBytes" to available.totalMem,"measurementScope" to "synthetic optimized APK; emulator CPU, not physical GPU/NPU qualification"))
        val peakPss=AtomicLong();val peakPrivate=AtomicLong();val peakNative=AtomicLong();val sampler=launch(Dispatchers.Default) { while(isActive){val m=memory();peakPss.accumulateAndGet(m.getValue("totalPssKiB"),::maxOf);peakPrivate.accumulateAndGet(m.getValue("privateFootprintKiB"),::maxOf);peakNative.accumulateAndGet(m.getValue("nativePssKiB"),::maxOf);delay(100)} }
        val provider=LiteRtRecoveryProvider(context,manifest,model,{true});var initialized=false;var released=false;var cancellation=false;var rawExact=0;var accepted=0;var falseAdoptions=0;var invalidControls=0
        try {
            // Catalog remains unapproved; these explicit component calls never
            // install a pointer or route through production availability.
            assertEquals(LocalProviderState.DOWNLOAD_REQUIRED,provider.availability())
            val initializeStart=SystemClock.elapsedRealtime()
            Engine(EngineConfig(model.absolutePath,Backend.CPU(),maxNumTokens=4096)).use { engine ->
                engine.initialize();initialized=true;report("initialize",mapOf("elapsedMs" to SystemClock.elapsedRealtime()-initializeStart,"memory" to memory()))
                engine.createConversation(ConversationConfig(systemInstruction=Contents.of("Return OK."),automaticToolCalling=false,tools=emptyList(),maxOutputToken=16,thinkingConfig=ThinkingConfig(enableThinking=false))).use { c ->
                    val start=SystemClock.elapsedRealtime();val output=c.sendMessage("Reply OK.").toString();assertTrue(output.isNotBlank())
                    report("smoke",mapOf("elapsedMs" to SystemClock.elapsedRealtime()-start,"nonempty" to true,"characters" to output.length,"memory" to memory()))
                }
                engine.createConversation(ConversationConfig(automaticToolCalling=false,tools=emptyList(),maxOutputToken=1024,thinkingConfig=ThinkingConfig(enableThinking=false))).use { c ->
                    val entered=CompletableDeferred<Unit>();val pending=async(Dispatchers.IO) { entered.complete(Unit);runCatching { c.sendMessage("Write the word synthetic 800 times, separated by spaces.").toString() } }
                    entered.await();delay(100);val active=pending.isActive;val start=SystemClock.elapsedRealtime();c.cancelProcess();pending.cancel();withTimeout(45000){pending.join()}
                    cancellation=active;report("native_cancel",mapOf("requestedWhileActive" to active,"joined" to pending.isCompleted,"elapsedMs" to SystemClock.elapsedRealtime()-start,"memory" to memory()))
                    assertTrue("Native cancellation probe finished before cancellation; no cancellation evidence",active)
                }
            }
            for(case in cases) {
                val start=SystemClock.elapsedRealtime()
                val doc=try { fixture(case) }catch(e:RecoveryPreparationFailure){
                    assertTrue(case.name=="missing_subject");report("case",mapOf("name" to case.name,"nativeInvoked" to false,"preparationRejected" to true,"rawExact" to null,"validatorAdopt" to false,"falseAdoption" to false));continue
                }
                val cell=doc.cells.single { !it.confirmedEmpty };val expected=RecoveryRules.recover(doc,cell)?.lessons
                val inputErrors=RecoveryValidator.inputErrors(doc)
                if(inputErrors.isNotEmpty()) { assertTrue(case.incomplete || case.unknownEmpty || case.name=="missing_subject");report("case",mapOf("name" to case.name,"nativeInvoked" to false,"inputErrors" to inputErrors,"rawExact" to null,"validatorAdopt" to false,"falseAdoption" to false));continue }
                if(!case.incomplete && !case.unknownEmpty && case.name!="missing_subject")LiteRtEvaluationFixtures.checkIntendedValues(case,requireNotNull(expected))
                try {
                    val generated=provider.recoverCell(prompt(doc,cell))
                    val raw=generated.map { it.copy(dateEvidence=cell.dayHeaderIds,periodEvidence=cell.periodHeaderIds) }
                    val rawMatch=expected!=null && raw==expected
                    val grounded=grounded(doc,cell,generated);val validation=RecoveryValidator.validate(doc,result(doc,cell,grounded,provider.metadata))
                    val safeMatch=expected!=null && grounded==expected && !case.incomplete && !case.unknownEmpty
                    val falseAdoption=validation.canAdopt && !safeMatch
                    if(rawMatch)rawExact++;if(validation.canAdopt)accepted++;if(falseAdoption)falseAdoptions++
                    report("case",mapOf("name" to case.name,"nativeInvoked" to true,"schemaDecoded" to true,"rawExact" to rawMatch,"rawSyntheticSample" to json.encodeToString(raw).take(256),"validatorAdopt" to validation.canAdopt,"groundedExact" to safeMatch,"falseAdoption" to falseAdoption,"validatorErrors" to validation.errors,"elapsedMs" to SystemClock.elapsedRealtime()-start,"memory" to memory()))
                } catch(e:InvalidRecoveryOutput) { report("case",mapOf("name" to case.name,"nativeInvoked" to true,"schemaDecoded" to false,"decodeError" to e.cause?.javaClass?.simpleName,"decodeDetail" to e.cause?.message?.take(128),"rawExact" to false,"validatorAdopt" to false,"falseAdoption" to false,"elapsedMs" to SystemClock.elapsedRealtime()-start)) }
                // Independent dangerous-output controls test adoption rejection
                // even if native output was a safe failure for this case.
                if(expected!=null) {
                    val first=expected.first();val corrupt=expected.toMutableList();corrupt[0]=first.copy(teacher=first.subject)
                    assertFalse(RecoveryValidator.validate(doc,result(doc,cell,corrupt,provider.metadata)).canAdopt);invalidControls++
                }
            }
            val probe=fixture(cases.first());val cell=probe.cells.single { !it.confirmedEmpty }
            val entered=CompletableDeferred<Unit>();val pending=launch(Dispatchers.IO) { entered.complete(Unit);runCatching { provider.recoverCell(prompt(probe,cell).copy(parallelCount=4)) } }
            entered.await();delay(100);val active=pending.isActive;val start=SystemClock.elapsedRealtime();pending.cancel();withTimeout(45000){pending.join()}
            report("provider_cancel",mapOf("requestedWhileActive" to active,"joined" to pending.isCompleted,"elapsedMs" to SystemClock.elapsedRealtime()-start,"memory" to memory()))
            assertTrue("Provider cancellation needs an active native call",active)
            provider.close();released=true
            try { provider.recoverCell(prompt(probe,cell));fail("Closed provider still generated") }catch(_:IllegalStateException) {}
            report("release",mapOf("closedProviderRejectedNewCall" to true,"memory" to memory()))
            report("summary",mapOf("cases" to cases.size,"rawExact" to rawExact,"validatorAccepted" to accepted,"falseAdoptions" to falseAdoptions,"dangerousControlsRejected" to invalidControls,"initialized" to initialized,"nativeCancellationDemonstrated" to cancellation,"released" to released,"peakTotalPssKiB" to peakPss.get(),"peakPrivateFootprintKiB" to peakPrivate.get(),"peakNativePssKiB" to peakNative.get(),"catalogValidated" to false,"qualityApproved" to false,"qualification" to "16 synthetic component cases cannot establish real timetable false-adoption rate or physical-device suitability"))
            assertEquals(0,falseAdoptions);assertTrue(invalidControls>=10)
        } finally { try { if(!released)provider.close() } finally { sampler.cancelAndJoin();report("cleanup",mapOf("memory" to memory(),"catalogValidated" to RecoveryModelCatalog.candidates.first().validated)) } }
    }
}
