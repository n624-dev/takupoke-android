package jp.n624.takupoke.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

@Serializable data class RecoveryModelManifest(val modelId: String, val version: String, val url: String, val size: Long, val sha256: String, val runtime: String, val minimumOs: String, val minimumMemory: Long, val recommendedBackend: String, val license: String, val validated: Boolean) {
    fun isUsable(runtime: String, availableMemory: Long): Boolean = validated && runtime in listOf("coreAI", "llamaCpp", "liteRtLm", "foundryLocal") && this.runtime == runtime && minimumMemory > 0 && availableMemory >= minimumMemory && size in 1..8L * 1024 * 1024 * 1024 && modelId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")) && version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")) && sha256.matches(Regex("[a-f0-9]{64}")) && runCatching { val uri = URI(url); uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null }.getOrDefault(false) && minimumOs.matches(Regex("[0-9]{1,6}(?:\\.[0-9]{1,6}){0,3}")) && recommendedBackend in when (runtime) { "coreAI" -> listOf("CPU", "GPU", "ANE"); "llamaCpp" -> listOf("CPU", "Metal"); else -> listOf("CPU", "GPU", "NPU") } && license.isNotBlank()
    fun supportsOs(current: String): Boolean {
        if (!current.matches(Regex("[0-9]{1,6}(?:\\.[0-9]{1,6}){0,3}")) || !minimumOs.matches(Regex("[0-9]{1,6}(?:\\.[0-9]{1,6}){0,3}"))) return false
        val needed = minimumOs.split('.').map(String::toInt); val actual = current.split('.').map(String::toInt)
        for (i in 0..3) { val a = actual.getOrElse(i) { 0 }; val b = needed.getOrElse(i) { 0 }; if (a != b) return a > b }
        return true
    }
}
/** The download callback only receives a pinned model URL, never school data. */
class RecoveryModelStore(private val root: File) {
    @Synchronized fun install(manifest: RecoveryModelManifest, runtime: String, availableMemory: Long, osSupported: Boolean,
        openModel: (String) -> InputStream, prepareAndSmokeTest: (File) -> Unit, check: () -> Unit = ::interrupted): File {
        require(osSupported && manifest.isUsable(runtime, availableMemory))
        require(root.isDirectory || root.mkdirs())
        val staging = File(root, "staging-${UUID.randomUUID()}")
        val target = File(root, "$runtime-${manifest.modelId}-${manifest.version}-${manifest.sha256}.model")
        val pointer = File(root, "active.$runtime.json")
        val previous = if (pointer.exists()) json.decodeFromString<RecoveryModelManifest>(pointer.readText()) else null
        require(previous == null || previous.isUsable(runtime, Long.MAX_VALUE))
        var ownsTarget = false; var committed = false
        val pointerTemp = File(root, "pointer-${UUID.randomUUID()}")
        try {
            if (!target.exists()) {
                val hash = MessageDigest.getInstance("SHA-256"); var total = 0L
                openModel(manifest.url).use { input -> staging.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { check(); val read = input.read(buffer); if (read < 0) break; if (read == 0) continue; total += read; require(total <= manifest.size); hash.update(buffer, 0, read); output.write(buffer, 0, read) }
                    output.fd.sync()
                } }
                require(total == manifest.size && hash.digest().joinToString("") { "%02x".format(it) } == manifest.sha256)
                prepareAndSmokeTest(staging); check()
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE); ownsTarget = true
            } else {
                val hash = MessageDigest.getInstance("SHA-256")
                target.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { check(); val read = input.read(buffer); if (read < 0) break; hash.update(buffer, 0, read) } }
                require(target.length() == manifest.size && hash.digest().joinToString("") { "%02x".format(it) } == manifest.sha256)
                prepareAndSmokeTest(target)
            }
            check(); pointerTemp.outputStream().use { it.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            check(); Files.move(pointerTemp.toPath(), pointer.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            committed = true
            if (previous != null) { val old = File(root, "$runtime-${previous.modelId}-${previous.version}-${previous.sha256}.model"); if (old != target) old.delete() }
            return target
        } finally { staging.delete(); pointerTemp.delete(); if (ownsTarget && !committed) target.delete() }
    }
    /** Call before providers are started. Pass every model currently leased by a runtime. */
    @Synchronized fun cleanupAbandonedFiles(inUse: Set<File>) {
        if (!root.isDirectory) return
        val protected = inUse.map { it.canonicalFile }.toMutableSet()
        for (runtime in listOf("coreAI", "llamaCpp", "liteRtLm", "foundryLocal")) {
            val pointer = File(root, "active.$runtime.json")
            if (pointer.exists()) {
                val m = json.decodeFromString<RecoveryModelManifest>(pointer.readText()); require(m.isUsable(runtime, Long.MAX_VALUE))
                protected += File(root, "$runtime-${m.modelId}-${m.version}-${m.sha256}.model").canonicalFile
            }
        }
        val owned = Regex("(?:staging-[0-9A-Fa-f-]{36}|pointer-[0-9A-Fa-f-]{36}|(?:coreAI|llamaCpp|liteRtLm|foundryLocal)-[A-Za-z0-9._-]{1,81}-[A-Za-z0-9._-]{1,81}-[a-f0-9]{64}\\.model)")
        root.listFiles()?.forEach { file -> if (owned.matches(file.name) && file.isFile && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile !in protected) require(file.delete()) }
    }
    @Synchronized fun delete(runtime: String) {
        require(runtime in listOf("coreAI", "llamaCpp", "liteRtLm", "foundryLocal"))
        val pointer = File(root, "active.$runtime.json"); if (!pointer.exists()) return
        val manifest = json.decodeFromString<RecoveryModelManifest>(pointer.readText())
        require(manifest.isUsable(runtime, Long.MAX_VALUE)); require(pointer.delete())
        val target = File(root, "$runtime-${manifest.modelId}-${manifest.version}-${manifest.sha256}.model")
        require(!target.exists() || target.delete())
    }
}
