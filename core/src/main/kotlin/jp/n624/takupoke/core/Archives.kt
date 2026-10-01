package jp.n624.takupoke.core

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

object Archives {
    fun read(bytes: ByteArray, maxEntry: Int = 8 * 1024 * 1024, maxTotal: Int = 64 * 1024 * 1024, maxEntries: Int = 2048): Map<String, ByteArray> {
        require(bytes.isNotEmpty() && bytes.size <= 50 * 1024 * 1024)
        val result = linkedMapOf<String, ByteArray>(); var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                interrupted(); val entry = zip.nextEntry ?: break
                require(result.size < maxEntries && !entry.isDirectory && !entry.name.startsWith('/') && entry.name.split('/').none { it == ".." || it.isEmpty() } && '\\' !in entry.name && entry.name !in result)
                require(entry.size <= maxEntry && entry.compressedSize <= 50 * 1024 * 1024)
                val out = ByteArrayOutputStream(); val buffer = ByteArray(32768)
                while (true) { interrupted(); val count = zip.read(buffer); if (count < 0) break
                    total += count; require(out.size() + count <= maxEntry && total <= maxTotal); out.write(buffer, 0, count)
                }
                zip.closeEntry(); result[entry.name] = out.toByteArray()
            }
        }
        require(result.isNotEmpty()); return result
    }
}
fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
data class XmlNode(val name: String, val attrs: Map<String, String>, val children: MutableList<XmlNode> = mutableListOf(), var text: String = "") {
    fun child(name: String) = children.firstOrNull { it.name == name }
    fun descendants(name: String): List<XmlNode> = children.flatMap { (if (it.name == name) listOf(it) else emptyList()) + it.descendants(name) }
}
object SafeXml {
    fun parse(bytes: ByteArray): XmlNode {
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
                val node = XmlNode(localName.ifEmpty { qName.substringAfter(':') }, (0 until attrs.length).associate { attrs.getLocalName(it).ifEmpty { attrs.getQName(it).substringAfter(':') } to attrs.getValue(it) })
                if (stack.isEmpty()) { require(root == null); root = node } else stack.last().children += node
                stack += node
            }
            override fun endElement(uri: String, localName: String, qName: String) { stack.removeAt(stack.lastIndex) }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (stack.isNotEmpty()) { val n = stack.last(); require(n.text.length + length <= 8 * 1024 * 1024); n.text += String(ch, start, length) } }
        })
        return requireNotNull(root)
    }
}
