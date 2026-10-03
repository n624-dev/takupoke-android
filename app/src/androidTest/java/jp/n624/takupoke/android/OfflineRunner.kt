package jp.n624.takupoke.android

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import okhttp3.RequestBody

/** Test application has no production HTTP transport, even for lifecycle/background work. */
class OfflineRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application = super.newApplication(cl, OfflineApplication::class.java.name, context)
}
class OfflineApplication : TakupokeApplication() {
    var offlineTransportInjected=false
        private set
    override fun createRepository() = AppRepository(this, RejectNetwork).also { offlineTransportInjected=true }
}
object RejectNetwork : Transport {
    override fun request(url: String, headers: Map<String, String>, body: RequestBody?, maxBytes: Int, head: Boolean): HttpResult = throw IllegalStateException("Unexpected network request rejected by offline test transport")
}
