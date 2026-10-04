package jp.n624.takupoke.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** A shared instruction revision; this does not enable a model or change its schema. */
internal object RecoveryPromptAssets {
    const val VERSION = "4"
    const val FIELD_ASSET = "recovery-prompts/field-extraction-v4.txt"
    const val FIELD_SHA256 = "c24039ae4317a433a14f01697d77813424a3a1c20a70327189964b2fc60bb188"
    private const val FIELD_BYTES = 2939

    fun fieldExtraction(open: () -> InputStream): String {
        val bytes = open().use { input ->
            val output = ByteArrayOutputStream(FIELD_BYTES)
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 4096) { "Recovery instruction is oversized" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size == FIELD_BYTES && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == FIELD_SHA256) { "Recovery instruction bytes changed" }
        return bytes.toString(Charsets.UTF_8)
    }
}
