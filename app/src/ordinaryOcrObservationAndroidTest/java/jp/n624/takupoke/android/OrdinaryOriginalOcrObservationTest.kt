package jp.n624.takupoke.android

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import androidx.test.platform.app.InstrumentationRegistry
import jp.n624.takupoke.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OrdinaryOriginalOcrObservationTest {
    @Test fun acquisitionOnly()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.context
        val source=InstrumentationRegistry.getArguments().getString("takupoke.sourceCommit") ?: error("Missing source commit")
        require(source.matches(Regex("[a-f0-9]{40}")))
        assertEquals("NaN",OcrObservationProtocol.confidence(Float.NaN)["nonFinite"]!!.jsonPrimitive.content)
        assertEquals(JsonNull,OcrObservationProtocol.confidence(Float.POSITIVE_INFINITY)["value"])
        assertEquals(5,OcrObservationProtocol.readFailure("native read failed")["pages"]!!.jsonArray.size)
        val manifest=Json.parseToJsonElement(context.assets.open("inputs.json").bufferedReader().use { it.readText() }).jsonObject
        require(manifest["sourceCommit"]!!.jsonPrimitive.content=="3aab761f4aedb8e78fedf3868e952faff190ae70")
        val known=linkedMapOf("ordinary-literal.pdf" to "233dec852ec7e43c93c70c42d8f88238a0c2e8aef146445c4b94d674bb6cc187",
            "ordinary-verifiedblank.pdf" to "2625bf49044d59088b53109fc15ba7813073fdedac71bde5eb82925c297050f8")
        val inputs=manifest["inputs"]!!.jsonArray
        require(inputs.map { it.jsonObject["file"]!!.jsonPrimitive.content }==known.keys.toList())
        require(context.assets.list("")!!.toSet()==known.keys+"inputs.json")
        val results=mutableListOf<JsonObject>();var started=0;var returned=0
        val memory=ActivityManager.MemoryInfo().also { (instrumentation.targetContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        fun publish(id:String) {
            val payload=buildJsonObject {
                put("sourceCommit",source);put("sdkVersion","com.google.mlkit:text-recognition-japanese:16.0.1 bundled")
                put("inputManifest",manifest);put("androidApi",Build.VERSION.SDK_INT);put("buildFingerprint",Build.FINGERPRINT)
                put("abis",JsonArray(Build.SUPPORTED_ABIS.map(::JsonPrimitive)));put("deviceTotalRamBytes",memory.totalMem)
                put("currentProcessPssKiB",Debug.getPss());put("memoryScope","Instantaneous process PSS, not peak or complete process-tree memory")
                put("productionReadCallsStarted",started);put("productionReadCallsReturned",returned)
                put("counterScope","Counts at this completed snapshot; later interrupted calls may have started or returned unobserved. Non-final snapshots are lower bounds")
                put("callerForeground",true);put("onlyPages",JsonNull);put("pageOrder","Production sequential whole-reader page order; no retry")
                put("plannedOriginals",2);put("remainingOriginalsUnassessed",2-results.size)
                put("results",JsonArray(results));put("qualification","Acquisition-only observation; no AI/whole-PDF quality qualification or production activation")
            }
            OcrObservationProtocol.transport(id,payload).forEach { line -> instrumentation.sendStatus(0,Bundle().apply { putString("stream","\n$line\n") }) }
        }
        publish("start")
        for ((index,rawInput) in inputs.withIndex()) {
            val input=rawInput.jsonObject;val filename=input["file"]!!.jsonPrimitive.content
            require(input["plannedPages"]!!.jsonPrimitive.int==5 && input["sha256"]!!.jsonPrimitive.content==known[filename])
            val file=File.createTempFile("ordinary-ocr-",".pdf",instrumentation.targetContext.cacheDir)
            try {
                context.assets.open(filename).use { from -> file.outputStream().use { from.copyTo(it) } }
                require(file.length()==input["bytes"]!!.jsonPrimitive.long && OcrObservationProtocol.sha(file.readBytes())==known[filename])
                val pdfDimensions=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        (0 until renderer.pageCount).map { page -> renderer.openPage(page).use { it.width to it.height } }
                    }
                }
                val start=SystemClock.elapsedRealtime();started++
                val read=runCatching { PdfRecoveryOcr.read(file,foreground=true) }
                if(read.isSuccess)returned++
                val result=if(read.isFailure)buildJsonObject {
                    put("id",input["id"]!!);put("elapsedMilliseconds",SystemClock.elapsedRealtime()-start)
                    OcrObservationProtocol.readFailure(read.exceptionOrNull()?.message).forEach { (key,value)->put(key,value) }
                    put("errorType",read.exceptionOrNull()?.javaClass?.simpleName)
                } else try {
                    val pages=read.getOrThrow()
                    buildJsonObject {
                        put("id",input["id"]!!);put("readReturned",true);put("elapsedMilliseconds",SystemClock.elapsedRealtime()-start)
                        put("returnedPageSequenceMatchesPlanned",pages.map { it.page }==(1..5).toList())
                        put("pages",JsonArray(pages.map { page -> buildJsonObject {
                            put("page",page.page);put("executionAssessed",true);put("width",page.width);put("height",page.height);put("inputState",page.inputState.name)
                            put("nativeRenderedPixelSha256",JsonNull);put("pixelHashAvailability","Production API does not expose or retain rendered pixels; no substitute render performed")
                            val dimensions=pdfDimensions.getOrNull(page.page-1)
                            if(dimensions!=null) {
                                put("pdfRendererPageWidth",dimensions.first);put("pdfRendererPageHeight",dimensions.second)
                                put("productionRenderScale",minOf(2.0,2048.0/maxOf(dimensions.first,dimensions.second)))
                            }
                            val confidencePass=RecoveryOcrQuality.complete(true,page.confidenceScores)
                            put("confidenceGuardPass",confidencePass)
                            put("rawGeometryComplete",JsonNull)
                            put("geometryScope",if(page.inputState==RecoveryInputState.COMPLETE)"inferred true from COMPLETE and unchanged production conjunction" else if(confidencePass)"inferred false from PARTIAL with valid confidence conjunction" else "unavailable: failing confidence cannot determine geometry predicate")
                            put("guardReason",if(page.inputState==RecoveryInputState.COMPLETE)"passed acquisition prerequisites" else if(!confidencePass)"confidence prerequisite fails; pixel prerequisite may also fail" else "pixel coverage prerequisite fails")
                            put("sources",JsonArray(page.sources.map { Json.encodeToJsonElement(RecoverySource.serializer(),it) }))
                            put("confidenceScores",JsonArray(page.confidenceScores.map(OcrObservationProtocol::confidence)))
                            put("confidenceScope","Original production word-plus-symbol flattened sequence; per-source association not retained")
                            put("zeroConfidenceMeaning","Primitive zero cannot distinguish SDK unavailable confidence from actual zero")
                            put("lines",JsonArray(page.lines.map { l -> JsonArray(listOf(l.x1,l.y1,l.x2,l.y2).map(::JsonPrimitive)) }))
                            put("verifiedBlankBoxes",JsonArray(page.verifiedBlankBoxes.map { Json.encodeToJsonElement(RecoveryBox.serializer(),it) }))
                        } }))
                        put("completeMeaning","Acquisition COMPLETE does not establish literal text correctness")
                        var stage="builder"
                        try {
                            val doc=RecoveryLayout.prepare(pages.map { it.layout() },known.getValue(filename),MaterialKind.TIMETABLE)
                            put("builderReturned",true);put("requiredSlots",doc.requiredSlots.size)
                            put("preparedDocument",Json.encodeToJsonElement(RecoveryDocument.serializer(),doc))
                            stage="engine"
                            val run=RecoveryEngine.run(doc,"android",Build.VERSION.SDK_INT,true,emptyList(),{null})
                            put("ruleEngineState",run.state.name);put("ruleErrors",JsonArray(run.errors.map(::JsonPrimitive)))
                            run.result?.let { output ->
                                put("recoveryResult",Json.encodeToJsonElement(RecoveryResult.serializer(),output))
                                stage="validator"
                                val validated=RecoveryValidator.validate(doc,output)
                                put("validatorCanAdopt",validated.canAdopt);put("validatorErrors",JsonArray(validated.errors.map(::JsonPrimitive)))
                                if(validated.canAdopt) {
                                    stage="formal-conversion"
                                    val analysis=RecoveryAnalysis.convert(doc,output)
                                    put("formalConversionReturned",true);put("formalAnalysis",Json.encodeToJsonElement(Analysis.serializer(),analysis))
                                }
                            }
                        }catch(error:Exception) {
                            if(stage=="builder")put("builderReturned",false)
                            put("pipelineErrorStage",stage);put("pipelineErrorType",error.javaClass.simpleName);put("pipelineError",error.message?.take(1024))
                        }
                        put("formalQuality","unassessed: independent original literal scoring occurs after recorded native output")
                    }
                }catch(error:Exception) { buildJsonObject {
                    put("id",input["id"]!!);put("elapsedMilliseconds",SystemClock.elapsedRealtime()-start)
                    put("readReturned",true);put("observationErrorType",error.javaClass.simpleName);put("observationError",error.message?.take(1024))
                    put("formalQuality","unassessed")
                    put("rawReturnedFallback",JsonArray(read.getOrThrow().map { page -> buildJsonObject {
                        put("page",page.page);put("inputState",page.inputState.name)
                        put("sources",JsonArray(page.sources.map { source -> buildJsonObject {
                            put("id",source.id);put("text",source.text)
                            put("originalBoxNumbers",JsonArray(listOf(source.box.x,source.box.y,source.box.width,source.box.height).map { JsonPrimitive(it.toString()) }))
                        } }))
                        put("confidenceScores",JsonArray(page.confidenceScores.map(OcrObservationProtocol::confidence)))
                    } }))
                } }
                results+=result;publish("file-${index+1}")
            }finally { require(file.delete()) { "Owned temporary PDF cleanup failed" } }
        }
        publish("final");assertEquals(2,started)
    }
}
