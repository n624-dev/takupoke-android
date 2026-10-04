package jp.n624.takupoke.android

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.*

internal object OcrObservationProtocol {
    fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun confidence(value:Float):JsonObject=buildJsonObject {
        put("value",if(value.isFinite())JsonPrimitive(value) else JsonNull)
        if(!value.isFinite())put("nonFinite",value.toString())
    }
    fun readFailure(message:String?):JsonObject=buildJsonObject {
        put("readReturned",false);put("errorMessage",message?.take(1024))
        put("observedReturnedPages",0);put("attemptedPages",JsonNull);put("completedPagesBeforeException",JsonNull)
        put("failedPage","unknown: production whole-read exception does not retain earlier pages")
        put("pages",JsonArray((1..5).map { page -> buildJsonObject {put("page",page);put("executionAssessed",false)} }))
        put("formalQuality","unassessed")
    }
    fun transport(id:String,payload:JsonObject):List<String> {
        require(id.matches(Regex("[a-z0-9-]{1,32}")))
        val raw=payload.toString().toByteArray(Charsets.UTF_8)
        val bytes=ByteArrayOutputStream().also { stream -> GZIPOutputStream(stream).use { it.write(raw) } }.toByteArray()
        val chunks=Base64.getEncoder().encodeToString(bytes).chunked(2800)
        return listOf("TKPK_OCR_RECEIPT_BEGIN id=$id chunks=${chunks.size} gzipBytes=${bytes.size} jsonBytes=${raw.size} sha256=${sha(raw)}")+
            chunks.mapIndexed { index,data -> "TKPK_OCR_RECEIPT_CHUNK id=$id index=$index data=$data" }+
            "TKPK_OCR_RECEIPT_END id=$id"
    }
}
