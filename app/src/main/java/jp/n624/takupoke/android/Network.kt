package jp.n624.takupoke.android

import jp.n624.takupoke.core.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

data class HttpResult(val status: Int, val bytes: ByteArray, val headers: Map<String, String>) { fun header(name: String) = headers[name.lowercase()] }
interface Transport { fun request(url: String, headers: Map<String, String> = emptyMap(), body: RequestBody? = null, maxBytes: Int = 3_000_000, head: Boolean = false): HttpResult }
class HttpTransport : Transport {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).followRedirects(false).build()
    override fun request(url: String, headers: Map<String, String>, body: RequestBody?, maxBytes: Int, head: Boolean): HttpResult {
        require(url.startsWith("https://"))
        val request = Request.Builder().url(url); headers.forEach { (k, v) -> request.header(k, v) }
        if (head) request.head() else if (body != null) request.post(body)
        client.newCall(request.build()).execute().use { response ->
            val stream = response.body?.byteStream(); val output = java.io.ByteArrayOutputStream()
            stream?.use { val buffer = ByteArray(32768); while (true) { interrupted(); val n = it.read(buffer); if (n < 0) break; require(output.size() + n <= maxBytes); output.write(buffer, 0, n) } }
            return HttpResult(response.code, output.toByteArray(), response.headers.names().associate { it.lowercase() to response.header(it).orEmpty() })
        }
    }
}
object Endpoints {
    const val API = "https://takupoke-api.n624.jp"
    const val ISSUER = "https://takuma-gakunin.n624.jp"
    const val CLIENT = "takupoke-android"
    const val CALLBACK = "jp.n624.takupoke.android:/oauth/callback"
}
