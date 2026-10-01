package jp.n624.takupoke.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class MappingRule(val alias: String, val fullName: String, val classes: List<String>? = null, val internationalStudent: Boolean? = null)
@Serializable data class TeacherContext(val alias: String, val fullName: String, val subject: String, val className: String, val schoolYear: Int)
@Serializable data class Mapping(val subjects: List<MappingRule>, val teachers: List<MappingRule>, val rooms: List<MappingRule>, val teacherContexts: List<TeacherContext> = emptyList()) {
    fun match(source: String, rules: List<MappingRule>, cls: String? = null): String? {
        val eligible = rules.filter { it.classes == null || cls in it.classes }.filter { normalized(it.alias) == normalized(source) }
        val specific = eligible.filter { it.classes != null }.ifEmpty { eligible }
        val exact = specific.filter { it.alias == source }.ifEmpty { specific }
        return exact.map { it.fullName }.distinct().singleOrNull()
    }
    fun metadata(source: String, rules: List<MappingRule>, context: (String) -> String? = { null }): String? {
        fun resolve(s: String) = context(s.trim()) ?: match(s, rules)
        resolve(source)?.let { return it }
        val fields = fields(source); if (fields.size < 2) return null
        var changed = false
        val result = fields.joinToString("") { (value, sep) -> val full = resolve(value)
            if (full == null) value + sep else { changed = true; value.takeWhile(Char::isWhitespace) + full + value.takeLastWhile(Char::isWhitespace) + sep }
        }
        return if (changed) result else null
    }
    fun apply(names: Names, cls: String, year: Int? = null): Names {
        val canonical = (subjects.filter { (it.classes == null || cls in it.classes) && normalized(names.subject) in listOf(normalized(it.alias), normalized(it.fullName)) }.let { rows -> rows.filter { it.classes != null }.ifEmpty { rows } }).map { normalized(it.fullName) }.distinct().singleOrNull()
        return names.copy(subjectFull = match(names.subject, subjects, cls).orEmpty(), teacherFull = metadata(names.teacher, teachers) { alias -> teacherContexts.filter { it.alias == alias && it.className == cls && it.schoolYear == year && normalized(it.subject) == canonical }.map { it.fullName }.distinct().singleOrNull() }.orEmpty(), roomFull = metadata(names.room, rooms).orEmpty())
    }
    fun separate(source: String, cls: String, year: Int): Names {
        var subject = source.trim(); var teacher = ""; var room = ""
        while (subject.endsWith(')') || subject.endsWith('）')) {
            val close = subject.last(); val open = if (close == ')') '(' else '（'; var depth = 0; var start = -1
            for (i in subject.indices.reversed()) { if (subject[i] == close) depth++ else if (subject[i] == open && --depth == 0) { start = i; break } }
            if (start < 0) break
            val token = subject.substring(start + 1, subject.lastIndex).trim(); val before = subject.substring(0, start).trim()
            fun confirmed(group: List<MappingRule>, isTeacher: Boolean): Boolean {
                fun found(value: String): Boolean = match(value.trim(), group) != null || (isTeacher && apply(Names(before, value), cls, year).teacherFull.isNotEmpty())
                return found(token) || (fields(token).size > 1 && fields(token).all { found(it.first) })
            }
            val t = confirmed(teachers, true); val r = confirmed(rooms, false)
            if (t == r || (t && teacher.isNotEmpty()) || (r && room.isNotEmpty())) break
            if (t) teacher = token else room = token
            subject = before
        }
        return Names(subject, teacher, room)
    }
    fun international(subject: String, cls: String): Boolean = normalized(subject).startsWith("留") || subjects.any { it.internationalStudent == true && (it.classes == null || cls in it.classes) && subject in listOf(it.alias, it.alias.removePrefix("留 ")) }
    private fun fields(source: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>(); var depth = 0; var start = 0
        source.forEachIndexed { i, c -> when (c) { '(', '（' -> depth++; ')', '）' -> depth = maxOf(0, depth - 1); ',', '，', '、' -> if (depth == 0) { result += source.substring(start, i) to c.toString(); start = i + 1 } } }
        result += source.substring(start) to ""; return result
    }
    fun validate(schema: Int): Mapping {
        require(schema in 1..2 && (schema == 2 || teacherContexts.isEmpty()))
        require(subjects.size + teachers.size + rooms.size + teacherContexts.size <= 10000)
        listOf(subjects, teachers, rooms).forEachIndexed { kind, group ->
            val seen = mutableSetOf<String>(); group.forEach { r ->
                require(r.alias.length in 1..512 && r.fullName.length in 1..512 && r.alias == r.alias.trim() && r.fullName == r.fullName.trim() && r.internationalStudent != false)
                require(kind == 0 || r.classes == null); require(r.internationalStudent != true || r.alias.startsWith("留 "))
                r.classes?.let { require(it.size in 1..100 && it.all { c -> c.length in 1..128 }) }
                (r.classes ?: listOf("")).forEach { require(seen.add(r.alias + "\u0000" + it)) }
            }
        }
        val seen = mutableSetOf<String>(); teacherContexts.forEach { r -> require(r.alias.length in 1..512 && r.fullName.length in 1..512 && r.subject.length in 1..512 && r.schoolYear in 2000..2099 && r.className in Schedule.classes); require(seen.add(listOf(r.alias, r.schoolYear.toString(), r.className, normalized(r.subject)).joinToString("\u0000"))) }
        return this
    }
    companion object {
        fun decode(bytes: ByteArray, version: String): Mapping {
            require(bytes.size <= 8 * 1024 * 1024 && version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
            val zip = Archives.read(bytes, 4 * 1024 * 1024, 8 * 1024 * 1024, 2); require(zip.keys == setOf("manifest.json", "mappings.json"))
            val manifest = json.parseToJsonElement(requireNotNull(zip["manifest.json"]).decodeToString()).jsonObject
            require(manifest.keys == setOf("schemaVersion", "version", "publishedAt", "mappings"))
            val data = requireNotNull(zip["mappings.json"]); val digest = manifest.getValue("mappings").jsonObject
            require(digest.keys == setOf("bytes", "sha256") && digest.getValue("bytes").jsonPrimitive.int == data.size && digest.getValue("sha256").jsonPrimitive.content == sha256(data))
            require(manifest.getValue("version").jsonPrimitive.content == version)
            java.time.Instant.parse(manifest.getValue("publishedAt").jsonPrimitive.content)
            return json.decodeFromString<Mapping>(data.decodeToString()).validate(manifest.getValue("schemaVersion").jsonPrimitive.int)
        }
    }
}
