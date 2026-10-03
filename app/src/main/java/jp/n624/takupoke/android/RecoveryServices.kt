package jp.n624.takupoke.android

import android.content.Context
import jp.n624.takupoke.core.*
import java.io.File

/** Injectable acquisition/runtime boundary. Production always uses the device-only implementation. */
interface RecoveryServices {
    val offered:RecoveryModelManifest?
    val error:String?
    fun installed():RecoveryModelManifest?
    fun cleanup()
    fun delete()
    suspend fun prepare(file:File,hash:String,kind:MaterialKind,capture:RecoveryReadCapture):RecoveryDocument
    fun provider(foreground:()->Boolean):LocalRecoveryProvider?
    suspend fun download(foreground:()->Boolean,progress:(Long)->Unit)
}
class DeviceRecoveryServices(private val context:Context):RecoveryServices {
    private val models=AndroidRecoveryModels(context)
    override val offered get()=RecoveryModelCatalog.candidates.firstOrNull { it.validated }
    override val error get()=models.error
    override fun installed()=models.installed()?.first
    override fun cleanup()=models.cleanup()
    override fun delete()=models.delete()
    override suspend fun prepare(file:File,hash:String,kind:MaterialKind,capture:RecoveryReadCapture):RecoveryDocument {
        val rasterPages=capture.pages.filter { it.state!=RecoveryInputState.COMPLETE }.map { it.page }.toSet()
        val native=capture.pages.filter { it.state==RecoveryInputState.COMPLETE }.map { RecoveryLayoutPage(it.page,requireNotNull(it.layout)) }
        val raster=if(rasterPages.isEmpty()&&capture.pages.isNotEmpty())emptyList() else PdfRecoveryOcr.read(file,true,rasterPages.takeIf { it.isNotEmpty() }).map { it.layout() }
        return kotlinx.coroutines.runInterruptible { RecoveryLayout.prepare((native+raster).sortedBy { it.page },hash,kind) }
    }
    override fun provider(foreground:()->Boolean):LocalRecoveryProvider? = models.installed()?.let { LiteRtRecoveryProvider(context,it.first,it.second,foreground) }
    override suspend fun download(foreground:()->Boolean,progress:(Long)->Unit) { models.download(requireNotNull(offered),foreground,progress) }
}
