package jp.n624.takupoke.android

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.*
import jp.n624.takupoke.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** This catalog is pinned by the app release. Never query a 'latest' model endpoint. */
object RecoveryModelCatalog {
    val candidates = listOf(RecoveryModelManifest("qwen3-0.6b-int4", "a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76", "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",344671744,"03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856","liteRtLm","29",4L*1024*1024*1024,"CPU","Apache-2.0",false))
}
data class RecoveryModelStatus(val installed: RecoveryModelManifest? = null, val offered: RecoveryModelManifest? = RecoveryModelCatalog.candidates.firstOrNull { it.validated }, val progressBytes: Long = 0, val downloading: Boolean = false, val error: String? = null)
class AndroidRecoveryModels(private val context: Context) {
    private val root = File(context.noBackupFilesDir,"ai-models")
    private val store = RecoveryModelStore(root)
    var error: String? = null; private set
    fun cleanup() { try { store.cleanupAbandonedFiles(emptySet()) } catch (_: Exception) { error="追加モデルを確認できません。削除して準備し直してください。" } }
    fun installed(): Pair<RecoveryModelManifest,File>? = try {
        val pointer=File(root,"active.liteRtLm.json"); if(!pointer.isFile)null else {
        val manifest=json.decodeFromString<RecoveryModelManifest>(pointer.readText())
        require(manifest in RecoveryModelCatalog.candidates && manifest.validated)
        manifest to File(root,"liteRtLm-${manifest.modelId}-${manifest.version}-${manifest.sha256}.model") }
    } catch (_: Exception) { error="追加モデルを確認できません。削除して準備し直してください。"; null }
    fun delete() { require(!root.exists() || root.deleteRecursively());error=null }
    suspend fun download(manifest: RecoveryModelManifest, foreground: () -> Boolean, progress: (Long)->Unit) = withContext(Dispatchers.IO) {
        require(manifest in RecoveryModelCatalog.candidates && manifest.validated && foreground())
        require(android.os.Process.is64Bit())
        val job=currentCoroutineContext().job
        val cancellationScope=CoroutineScope(currentCoroutineContext())
        val memory=ActivityManager.MemoryInfo();context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        fun check() { job.ensureActive(); if(!foreground())throw CancellationException("アプリを開いている時に準備してください。") }
        store.install(manifest,"liteRtLm",memory.availMem,manifest.supportsOs(Build.VERSION.SDK_INT.toString()),{ url ->
            check();val connection=URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout=15000;connection.readTimeout=15000;connection.instanceFollowRedirects=false
            var current=connection;var redirects=0
            try { while(true) {
                check();val code=current.responseCode
                if(code !in listOf(301,302,303,307,308))break
                require(++redirects<=5);val next=URL(current.url,current.getHeaderField("Location"));require(next.protocol=="https"&&next.userInfo==null)
                current.disconnect();current=(next.openConnection() as HttpURLConnection).apply { connectTimeout=15000;readTimeout=15000;instanceFollowRedirects=false }
            }
            require(current.responseCode==200)
            val input=current.inputStream
            object:java.io.FilterInputStream(input) { var bytes=0L
                override fun read(buffer:ByteArray,off:Int,len:Int):Int { check();val count=super.read(buffer,off,len);if(count>0){bytes+=count;progress(bytes)};return count }
                override fun close() { try { super.close() } finally { current.disconnect() } }
            } } catch(e:Throwable) { current.disconnect();throw e }
        },{ file ->
            check();val backend=when(manifest.recommendedBackend){"CPU"->Backend.CPU();"GPU"->Backend.GPU();"NPU"->Backend.NPU(context.applicationInfo.nativeLibraryDir);else->error("実行方式")}
            // No school input is used for runtime preparation.
            Engine(EngineConfig(file.absolutePath,backend,maxNumTokens=4096)).use { engine -> engine.initialize();check();engine.createConversation(ConversationConfig(systemInstruction=Contents.of("Return OK."),automaticToolCalling=false,tools=emptyList(),maxOutputToken=16,thinkingConfig=ThinkingConfig(enableThinking=false))).use { conversation ->
                val cancellation=cancellationScope.launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { conversation.cancelProcess() } }
                try { val response=conversation.sendMessage("Reply OK.").toString();check();require(response.isNotBlank()&&response.length<4096) } finally { runBlocking(NonCancellable) { cancellation.cancelAndJoin() } }
            } }
        },::check)
        error=null;cleanup()
    }
}
