package jp.n624.takupoke.core

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.SAXParserFactory

object Archives {
    private val temporaryLock = Any()
    private var temporaryDirectory: java.io.File? = null
    private val activeTemporaryArchives = mutableSetOf<String>()
    private const val temporaryPrefix = "takupoke-archive-"

    /** Android supplies its no-backup materials directory; JVM tests can use the default temp directory. */
    fun configureTemporaryDirectory(directory: java.io.File?) = synchronized(temporaryLock) {
        temporaryDirectory = directory?.absoluteFile
    }
    /** Recover only owned, inactive copies left by an interrupted process. Never touch material originals. */
    fun cleanupTemporaryArchives() = synchronized(temporaryLock) {
        val directory = temporaryDirectory ?: java.io.File(System.getProperty("java.io.tmpdir"))
        directory.listFiles()?.filter { it.isFile && it.name.startsWith(temporaryPrefix) && it.name.endsWith(".zip") && it.absolutePath !in activeTemporaryArchives }
            ?.forEach { require(it.delete() || !it.exists()) }
        Unit
    }
    private fun createTemporaryArchive(): java.io.File = synchronized(temporaryLock) {
        val directory = temporaryDirectory
        if (directory != null) require(directory.isDirectory || directory.mkdirs())
        java.io.File.createTempFile(temporaryPrefix, ".zip", directory).also { activeTemporaryArchives += it.absolutePath }
    }

    class Archive internal constructor(private val zip: java.util.zip.ZipFile, val names: Set<String>, private val maximum: Int) {
        fun read(name: String, limit: Int = maximum): ByteArray {
            val entry = requireNotNull(zip.getEntry(name)); require(!entry.isDirectory && entry.size in 0..limit.toLong())
            val out = ByteArrayOutputStream(); val crc = java.util.zip.CRC32()
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(32768)
                while (true) { interrupted(); val count = input.read(buffer); if (count < 0) break
                    require(out.size().toLong() + count <= limit); out.write(buffer, 0, count); crc.update(buffer, 0, count)
                }
            }
            require(out.size().toLong() == entry.size && crc.value == entry.crc)
            return out.toByteArray()
        }
    }
    // ZipFile provides bounded random access. Inspect the central directory too:
    // encrypted entries, symlinks and ZIP64 are outside the supported XLSX format.
    private fun validateDirectory(bytes: ByteArray, maximumEntries: Int) {
        fun u16(at: Int): Int { require(at >= 0 && at + 2 <= bytes.size); return (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) }
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
        } ?: throw IllegalArgumentException("ZIP構造を確認できません")
        val count = u16(end + 10)
        require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == count && count in 1..maximumEntries && count < 65535)
        val offset = u32(end + 16); val size = u32(end + 12)
        require(offset + size == end.toLong())
        var cursor = offset.toInt()
        repeat(count) {
            interrupted(); require(u32(cursor) == 0x02014b50L && u16(cursor + 8) and 1 == 0)
            require(u16(cursor + 34) == 0)
            val host = u16(cursor + 4) ushr 8; val mode = (u32(cursor + 38) ushr 16).toInt()
            require(host != 3 || mode and 0xf000 != 0xa000)
            require(u32(cursor + 42) < offset)
            cursor += 46 + u16(cursor + 28) + u16(cursor + 30) + u16(cursor + 32)
            require(cursor <= end)
        }
        require(cursor == end)
    }
    fun <T> withArchive(bytes: ByteArray, maxEntry: Int = 32 * 1024 * 1024, maxTotal: Int = 64 * 1024 * 1024, maxEntries: Int = 2048, block: (Archive) -> T): T {
        require(bytes.isNotEmpty() && bytes.size <= 50 * 1024 * 1024)
        validateDirectory(bytes, maxEntries)
        val temporary = createTemporaryArchive()
        try {
            temporary.outputStream().use { it.write(bytes) }
            java.util.zip.ZipFile(temporary).use { zip ->
                val names = linkedSetOf<String>(); var total = 0L
                zip.entries().asSequence().forEach { entry ->
                    interrupted(); val path = entry.name.removeSuffix(if (entry.isDirectory) "/" else "")
                    require(names.size < maxEntries && names.add(entry.name) && path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && path.split('/').none { it in listOf("..", ".", "") })
                    require(entry.size in 0..maxEntry.toLong() && entry.compressedSize in 0..(50L * 1024 * 1024) && entry.method in listOf(java.util.zip.ZipEntry.STORED, java.util.zip.ZipEntry.DEFLATED))
                    total += entry.size; require(total <= maxTotal)
                }
                require(names.isNotEmpty())
                return block(Archive(zip, names, maxEntry))
            }
        } finally {
            synchronized(temporaryLock) {
                activeTemporaryArchives -= temporary.absolutePath
                require(temporary.delete() || !temporary.exists())
            }
        }
    }
    fun read(bytes: ByteArray, maxEntry: Int = 8 * 1024 * 1024, maxTotal: Int = 64 * 1024 * 1024, maxEntries: Int = 2048): Map<String, ByteArray> =
        withArchive(bytes, maxEntry, maxTotal, maxEntries) { archive -> archive.names.associateWith { archive.read(it) } }
}

fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
data class XmlNode(val name: String, val attrs: Map<String, String>, val namespace: String = "", val children: MutableList<XmlNode> = mutableListOf(), var text: String = "") {
    fun child(name: String) = children.firstOrNull { it.name == name && it.namespace == namespace }
    fun descendants(name: String): List<XmlNode> = children.filter { it.namespace == namespace }.flatMap { (if (it.name == name) listOf(it) else emptyList()) + it.descendants(name) }
}
object SafeXml {
    fun parse(bytes: ByteArray, expectedRoot: String? = null, expectedNamespace: String? = null): XmlNode {
        require(bytes.size <= 8 * 1024 * 1024)
        val prefix = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in prefix)
        require(!prefix.contains("<!DOCTYPE", true) && !prefix.contains("<!ENTITY", true) && !prefix.contains("UTF-16", true))
        val factory = SAXParserFactory.newInstance(); factory.isNamespaceAware = true
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val stack = mutableListOf<XmlNode>(); var root: XmlNode? = null; var count = 0
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
                interrupted(); require(++count <= 200000 && stack.size < 64)
                require(!uri.contains("purl.oclc.org"))
                val node = XmlNode(localName.ifEmpty { qName.substringAfter(':') }, (0 until attrs.length).associate {
                    (if (attrs.getURI(it).isNullOrEmpty()) attrs.getLocalName(it).ifEmpty { attrs.getQName(it) } else "{${attrs.getURI(it)}}${attrs.getLocalName(it)}") to attrs.getValue(it)
                }, uri)
                if (stack.isNotEmpty() && uri == stack.last().namespace && node.name in setOf("workbookPr", "sheets", "sheetData", "v", "is", "f", "mergeCells")) require(stack.last().children.none { it.name == node.name && it.namespace == uri })
                if (stack.isEmpty()) { require(root == null); root = node } else stack.last().children += node
                stack += node
            }
            override fun endElement(uri: String, localName: String, qName: String) { stack.removeAt(stack.lastIndex) }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (stack.isNotEmpty()) { val n = stack.last(); require(n.text.length + length <= 8 * 1024 * 1024); n.text += String(ch, start, length) } }
        })
        return requireNotNull(root).also { require(expectedRoot == null || it.name == expectedRoot); require(expectedNamespace == null || it.namespace == expectedNamespace) }
    }
}
