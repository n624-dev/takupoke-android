package jp.n624.takupoke.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate

@Serializable enum class RecoveryDocumentKind { TIMETABLE, EXAM, RETURN }
@Serializable enum class RecoveryValueState { PRESENT, EMPTY, UNREADABLE, MISSING, AMBIGUOUS }
@Serializable enum class RecoveryInputState { COMPLETE, PARTIAL, RASTER_ONLY }
@Serializable enum class RecoveryJobState { PENDING, PREPARING, AWAITING_MODEL, RUNNING, AWAITING_CONFIRMATION, ADOPTED, FAILED, SUPERSEDED }
@Serializable enum class LocalProviderState { READY, NOT_READY, DISABLED, UNSUPPORTED, DOWNLOAD_REQUIRED, INSUFFICIENT_MEMORY }
@Serializable data class RecoveryBox(val x: Double, val y: Double, val width: Double, val height: Double) {
    val valid get() = listOf(x, y, width, height, x + width, y + height).all(Double::isFinite) && x >= 0 && y >= 0 && width > 0 && height > 0
    fun contains(other: RecoveryBox) = valid && other.valid && other.x >= x && other.y >= y && other.x + other.width <= x + width && other.y + other.height <= y + height
}
@Serializable data class RecoverySource(val id: String, val cellId: String, val page: Int, val text: String, val box: RecoveryBox, val fromOcr: Boolean = false, val sourceLine: Int? = null, val sourceOrder: Int? = null)
@Serializable data class RecoveryField(val state: RecoveryValueState, val value: String, val evidence: List<String>)
@Serializable data class RecoverySlot(val className: String, val day: String, val period: Int)
@Serializable enum class RecoveryHeaderAxis { ABOVE, LEFT }
@Serializable data class RecoveryHeaderRegion(val page: Int, val box: RecoveryBox, val axis: RecoveryHeaderAxis)
@Serializable data class RecoveryClockBinding(val page: Int, val box: RecoveryBox, val day: String, val spanStart: Int, val spanEnd: Int, val dayHeaderIds: List<String>, val dayRegion: RecoveryHeaderRegion?, val periodHeaderIds: List<String>, val periodRegion: RecoveryHeaderRegion)
@Serializable data class RecoveryLessonBinding(val subject: List<String>, val teacher: List<String>, val room: List<String>)
@Serializable data class RecoveryRoleScope(val lessonIndex: Int, val role: String, val page: Int, val box: RecoveryBox, val labelSourceIds: List<String>, val labelRegion: RecoveryHeaderRegion, val proof: String, val emptyVerified: Boolean = false)
@Serializable data class RecoveryCell(val id: String, val page: Int, val box: RecoveryBox, val inputState: RecoveryInputState, val slots: List<RecoverySlot>, val sourceIds: List<String>, val blankFields: List<String>, val confirmedEmpty: Boolean = false, val parallelCount: Int = 1, val classHeaderIds: List<String> = emptyList(), val dayHeaderIds: List<String> = emptyList(), val periodHeaderIds: List<String> = emptyList(), val lessonBindings: List<RecoveryLessonBinding> = emptyList(), val classRegion: RecoveryHeaderRegion? = null, val dayRegion: RecoveryHeaderRegion? = null, val periodRegions: Map<String, RecoveryHeaderRegion> = emptyMap(), val bindingMode: String = "fixed", val roleScopes: List<RecoveryRoleScope> = emptyList(), val separatorIds: List<String> = emptyList())
@Serializable data class RecoveryDocument(val pdfHash: String, val kind: RecoveryDocumentKind, val schoolYear: Int, val term: String?, val classes: List<String>, val days: List<String>, val requiredSlots: List<RecoverySlot>, val cells: List<RecoveryCell>, val sources: List<RecoverySource>, val complete: Boolean, val yearEvidence: List<String>, val termEvidence: List<String>, val dayEvidence: Map<String, List<String>>, val classEvidence: Map<String, List<String>>, val periodEvidence: Map<String, List<String>>, val times: Map<String, String>, val timeEvidence: List<String>, val normalTimeNoteEvidence: List<String>, val clockEvidence: Map<String, List<String>> = emptyMap(), val spanTimes: Map<String, String> = emptyMap(), val clockBindings: Map<String, RecoveryClockBinding> = emptyMap(), val titleEvidence: List<String> = emptyList(), val normalTimeNoteGroups: List<List<String>> = emptyList())
@Serializable data class RecoveryLesson(val subject: RecoveryField, val teacher: RecoveryField, val room: RecoveryField, val dateEvidence: List<String>, val periodEvidence: List<String>)
@Serializable data class RecoveredCell(val cellId: String, val state: RecoveryValueState, val lessons: List<RecoveryLesson>)
@Serializable data class RecoveryMetadata(val provider: String, val modelId: String, val modelVersion: String, val runtimeVersion: String, val promptVersion: String, val recoverySchemaVersion: Int, val validatorVersion: Int, val osVersion: String, val recoveryVersion: String = "2")
@Serializable data class RecoveryResult(val pdfHash: String, val kind: RecoveryDocumentKind, val schoolYear: Int, val term: String?, val cells: List<RecoveredCell>, val metadata: RecoveryMetadata)
@Serializable data class RecoveryJob(val pdfHash: String, val kind: RecoveryDocumentKind, val state: RecoveryJobState, val createdAt: Long, val resultHash: String? = null)
@Serializable data class RecoveryAcceptance(val pdfHash: String, val resultHash: String, val scopeHash: String, val metadata: RecoveryMetadata, val acceptedAt: Long)
data class RecoveryValidation(val errors: List<String>) { val canAdopt get() = errors.isEmpty() }

object RecoveryNotes {
    fun text(value:String)=key(value).replace("以下のとおりです","以下のとおり").replace("授業時間です","授業時間").replace("。", "").replace('~','〜')
    fun span(start: Int, end: Int): String? = if(start in 1..8 && end in start..8) Schedule.normalTimes[start-1].substringBefore('〜')+"〜"+Schedule.normalTimes[end-1].substringAfter('〜') else null
}
object RecoveryRoles {
    val labels=mapOf("subject" to listOf("科目","科目名","授業","授業名","授業科目"),"teacher" to listOf("教員","教員名","教師","教師名","担当","担当者","担当教員"),"room" to listOf("教室","教室名","場所","会場","授業教室"))
    val byLabel=labels.flatMap { (role,labels)->labels.map { it to role } }.toMap()
    val prefix=Regex("^("+byLabel.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }+")[:：]")
    private val inlinePrefix=Regex("(?:^|[・･/])("+byLabel.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }+")[:：]")
    // Reject partial labels in every parallel part, not just the first word.
    // Keep `prefix` anchored because only a leading, exactly bounded label can
    // be split into independent source atoms during acquisition.
    fun explicitLabel(value:String)=inlinePrefix.containsMatchIn(key(value))
}

object RecoveryValidator {
    const val SCHEMA_VERSION = 2
    const val VERSION = 3
    inline fun <reified T> fingerprint(value: T): String = sha256(json.encodeToString(value).toByteArray(Charsets.UTF_8))
    private fun text(value: String) = normalized(value).replace(Regex("\\s+"), "")
    val specialClasses = listOf("1_1", "1_2", "1_3") + (2..5).flatMap { year -> listOf("CN", "ES", "IT").map { "${year}_$it" } } + listOf("AI_1", "AI_2")
    val knownClasses = specialClasses + listOf("1_CN", "1_ES", "1_IT")
    private fun dayLabels(day: String, kind: RecoveryDocumentKind): List<String> {
        if (kind == RecoveryDocumentKind.TIMETABLE) { val label = mapOf("1" to "月", "2" to "火", "3" to "水", "4" to "木", "5" to "金")[day] ?: "?"; return listOf(label, "${label}曜", "${label}曜日") }
        val parts = day.split('-').mapNotNull(String::toIntOrNull)
        if (parts.size != 3) return emptyList()
        return listOf(day, "${parts[0]}/${parts[1]}/${parts[2]}", "${parts[1]}/${parts[2]}", "${parts[1]}月${parts[2]}日")
    }
    fun validate(doc: RecoveryDocument, result: RecoveryResult): RecoveryValidation {
        if (doc.schoolYear !in 1900..9998 || doc.classes.size !in 1..64 || doc.days.size !in 1..31 || doc.cells.size !in 1..20000 || doc.sources.size > 100000 || result.cells.size > 20000) return RecoveryValidation(listOf("inputLimit"))
        val errors = linkedSetOf<String>()
        fun check(ok: Boolean, code: String) { if (!ok) errors += code }
        check(doc.pdfHash.matches(Regex("[a-f0-9]{64}")) && result.pdfHash == doc.pdfHash, "sourceHash")
        check(doc.complete && doc.cells.size in 1..20000 && doc.sources.size <= 100000, "incompleteDocument")
        check(result.kind == doc.kind && result.schoolYear == doc.schoolYear && result.term == doc.term, "documentIdentity")
        check(doc.schoolYear in 1900..9998 && (doc.kind != RecoveryDocumentKind.TIMETABLE || doc.term in listOf("前期", "後期")), "yearTerm")
        check(result.metadata.recoverySchemaVersion == SCHEMA_VERSION && result.metadata.validatorVersion == VERSION && listOf(result.metadata.provider, result.metadata.modelId, result.metadata.modelVersion, result.metadata.runtimeVersion, result.metadata.promptVersion, result.metadata.osVersion, result.metadata.recoveryVersion).all { it.isNotBlank() }, "versions")
        check(doc.classes.isNotEmpty() && doc.classes.distinct().size == doc.classes.size && doc.classes.all { it in knownClasses } && doc.days.isNotEmpty() && doc.days.distinct().size == doc.days.size, "scope")
        if (doc.kind != RecoveryDocumentKind.TIMETABLE) check(doc.classes.toSet() == specialClasses.toSet() && doc.days.size == 5, "specialScope")
        val maxPeriod = if (doc.kind == RecoveryDocumentKind.EXAM) 6 else 8
        if (doc.kind == RecoveryDocumentKind.TIMETABLE) check(doc.days.sorted() == listOf("1", "2", "3", "4", "5"), "weekdays")
        else doc.days.forEach { day -> check(runCatching { val date = LocalDate.parse(day); date.toString() == day && date >= LocalDate.of(doc.schoolYear, 4, 1) && date < LocalDate.of(doc.schoolYear + 1, 4, 1) }.getOrDefault(false), "dates") }
        val required = doc.classes.flatMap { cls -> doc.days.flatMap { day -> (1..maxPeriod).map { RecoverySlot(cls, day, it) } } }.toSet()
        check(doc.requiredSlots.size == required.size && doc.requiredSlots.toSet() == required, "requiredScope")
        val slots = doc.cells.flatMap { it.slots }
        check(slots.size == required.size && slots.toSet() == required, "coverage")
        check(doc.cells.map { it.id }.distinct().size == doc.cells.size && doc.sources.map { it.id }.distinct().size == doc.sources.size, "duplicateIds")
        val sources = doc.sources.associateBy { it.id }; val cells = result.cells.associateBy { it.cellId }
        check(cells.size == result.cells.size, "duplicateCells")
        check(cells.keys == doc.cells.map { it.id }.toSet(), "resultCoverage")
        fun evidence(ids: List<String>, allowed: List<String>, value: String? = null): Boolean = ids.isNotEmpty() && ids.distinct().size == ids.size && ids.all { it in allowed && it in sources } && (value == null || text(value).isNotEmpty() && text(ids.joinToString("") { sources[it]?.text.orEmpty() }) == text(value))
        val order = doc.sources.mapIndexed { i, source -> source.id to i }.toMap()
        fun ordered(ids: List<String>) = ids.all { it in order } && ids.map { order.getValue(it) }.zipWithNext().all { (a, b) -> a < b }
        fun header(ids: List<String>, allowed: List<String>, labels: List<String>, cell: RecoveryCell? = null, region: RecoveryHeaderRegion? = null): Boolean {
            if (!evidence(ids, allowed) || !ordered(ids)) return false
            val joinedMatches = text(ids.joinToString("") { sources[it]?.text.orEmpty() }) in labels.map(::text)
            if (cell == null) return joinedMatches || ids.all { text(sources[it]?.text.orEmpty()) in labels.map(::text) }
            if (region == null || region.page != cell.page || !region.box.valid) return false
            val aligned = if (region.axis == RecoveryHeaderAxis.ABOVE) region.box.y + region.box.height <= cell.box.y && minOf(region.box.x + region.box.width, cell.box.x + cell.box.width) > maxOf(region.box.x, cell.box.x) else region.box.x + region.box.width <= cell.box.x && minOf(region.box.y + region.box.height, cell.box.y + cell.box.height) > maxOf(region.box.y, cell.box.y)
            return joinedMatches && aligned && ids.all { id -> sources[id]?.let { it.page == region.page && region.box.contains(it.box) } == true }
        }
        check(doc.sources.all { it.page > 0 && it.box.valid && it.text.length <= 4096 }, "sourceLimit")
        check(evidence(doc.yearEvidence, doc.yearEvidence), "yearEvidence")
        val yearText = text(doc.yearEvidence.joinToString("") { sources[it]?.text.orEmpty() })
        val yearLabels = listOf("${doc.schoolYear}年度", "令和${doc.schoolYear - 2018}年度")
        check(yearText in yearLabels || doc.yearEvidence.all { text(sources[it]?.text.orEmpty()) in yearLabels }, "yearEvidenceText")
        doc.term?.let { check(evidence(doc.termEvidence, doc.termEvidence) && (text(doc.termEvidence.joinToString("") { id -> sources[id]?.text.orEmpty() }) == text(it) || doc.termEvidence.all { id -> text(sources[id]?.text.orEmpty()) == text(it) }), "termEvidence") }
        doc.classes.forEach { check(doc.classEvidence[it]?.let { ids -> header(ids, ids, listOf(it, it.replace('_', '-'), it.replace("_", ""))) } == true, "classEvidence") }
        doc.days.forEach { check(doc.dayEvidence[it]?.let { ids -> header(ids, ids, dayLabels(it, doc.kind)) } == true, "dayEvidence") }
        (1..maxPeriod).forEach { check(doc.periodEvidence[it.toString()]?.let { ids -> header(ids, ids, listOf(it.toString(), "${it}限", "${it}時限", "第${it}時限")) } == true, "periodEvidence") }
        fun clockBound(day: String, start: Int, end: Int, clock: String): Boolean {
            val suffix = if (start == end) "$start" else "$start-$end"; val key = "$day:$suffix"; val ids = doc.clockEvidence[key].orEmpty()
            val binding = doc.clockBindings[key] ?: return false
            if (binding.day != day || binding.spanStart != start || binding.spanEnd != end || binding.page < 1 || !binding.box.valid || !evidence(ids, doc.timeEvidence, clock) || ids.any { id -> sources[id]?.let { it.page == binding.page && binding.box.contains(it.box) } != true }) return false
            val parts = clock.split('〜'); if (parts.size != 2 || parts[0] >= parts[1]) return false
            val virtual = RecoveryCell("clock", binding.page, binding.box, RecoveryInputState.COMPLETE, emptyList(), ids, emptyList())
            val labels = if (start == end) listOf("$start", "${start}限", "${start}時限", "第${start}時限") else listOf("${start}・${end}時限連続", "${start}〜${end}時限連続", "${start}〜${end}限", "$start-${end}限")
            val allowed = if (start == end) doc.periodEvidence[start.toString()].orEmpty() else binding.periodHeaderIds
            if (!header(binding.periodHeaderIds, allowed, labels, virtual, binding.periodRegion)) return false

            return header(binding.dayHeaderIds, doc.dayEvidence[day].orEmpty(), dayLabels(day, doc.kind), virtual, binding.dayRegion)
        }
        val validClocks = doc.clockBindings.filter { (key, binding) ->
            val clock = if (binding.spanStart == binding.spanEnd) doc.times[key] else doc.spanTimes[key]
            clock != null && clockBound(binding.day, binding.spanStart, binding.spanEnd, clock)
        }.values
        doc.classes.forEach { cls -> check(doc.classEvidence[cls].orEmpty().toSet() == doc.cells.filter { it.slots.firstOrNull()?.className == cls }.flatMap { it.classHeaderIds }.toSet(), "classHeaderCoverage") }
        doc.days.forEach { day -> check(doc.dayEvidence[day].orEmpty().toSet() == (doc.cells.filter { it.slots.firstOrNull()?.day == day }.flatMap { it.dayHeaderIds } + validClocks.filter { it.day == day }.flatMap { it.dayHeaderIds }).toSet(), "dayHeaderCoverage") }
        (1..maxPeriod).forEach { period ->
            val allowed = doc.periodEvidence[period.toString()].orEmpty()
            val bound = doc.cells.filter { c -> c.slots.any { it.period == period } }.flatMap { it.periodHeaderIds }.filter { it in allowed } + validClocks.filter { it.spanStart == period && it.spanEnd == period }.flatMap { it.periodHeaderIds }
            check(allowed.toSet() == bound.toSet(), "periodHeaderCoverage")
        }
        doc.cells.groupBy { it.page }.values.forEach { page ->
            val active = mutableListOf<RecoveryCell>()
            page.sortedBy { it.box.x }.forEach { cell ->
                active.removeAll { it.box.x + it.box.width <= cell.box.x }
                check(active.none { minOf(it.box.y + it.box.height, cell.box.y + cell.box.height) > maxOf(it.box.y, cell.box.y) }, "cellOverlap")
                active += cell
            }
        }
        check(doc.titleEvidence.distinct().size == doc.titleEvidence.size && doc.titleEvidence.all { id -> sources[id]?.let { s -> text(s.text) in listOf("時間割", "通常時間割", "試験時間割", "試験返却時間割", "クラス", "曜日", "日付", "時限", "授業時間", "学年") && doc.cells.filter { it.page == s.page }.minOfOrNull { it.box.y }?.let { s.box.y + s.box.height <= it } == true } == true }, "titleEvidence")
        val classified = (doc.titleEvidence + doc.cells.flatMap { c -> c.sourceIds + c.roleScopes.flatMap { it.labelSourceIds } } + doc.yearEvidence + if (doc.term != null) doc.termEvidence else emptyList()).toMutableSet()
        doc.classes.forEach { classified += doc.classEvidence[it].orEmpty() }
        doc.days.forEach { classified += doc.dayEvidence[it].orEmpty() }
        (1..maxPeriod).forEach { classified += doc.periodEvidence[it.toString()].orEmpty() }
        if (doc.kind != RecoveryDocumentKind.TIMETABLE) {
            (doc.times.keys + doc.spanTimes.keys).forEach { classified += doc.clockEvidence[it].orEmpty(); classified += doc.clockBindings[it]?.periodHeaderIds.orEmpty() }
            if (doc.kind == RecoveryDocumentKind.RETURN) classified += doc.normalTimeNoteEvidence
        }
        check(doc.sources.map { it.id }.toSet() == classified, "unclassifiedSource")
        val noteText = RecoveryNotes.text(doc.normalTimeNoteEvidence.joinToString("") { sources[it]?.text.orEmpty() })
        val dates = doc.days.sorted().map { it.split('-').mapNotNull(String::toIntOrNull) }
        val expectedNote = if(dates.size==5 && dates.all { it.size==3 }) "${dates[0][1]}月${dates[0][2]}日の時間割は以下のとおり${dates[1][1]}月${dates[1][2]}日〜${dates[4][2]}日は通常の授業日どおりの授業時間" else ""
        val noteGrouping = doc.normalTimeNoteGroups.isEmpty() || doc.normalTimeNoteGroups.flatten()==doc.normalTimeNoteEvidence && doc.normalTimeNoteGroups.all { ids -> evidence(ids,doc.normalTimeNoteEvidence) && RecoveryNotes.text(ids.joinToString("") { sources[it]?.text.orEmpty() })==expectedNote }
        val noteValid = noteGrouping && doc.kind == RecoveryDocumentKind.RETURN && evidence(doc.normalTimeNoteEvidence, doc.normalTimeNoteEvidence) && dates.size == 5 && dates.all { it.size == 3 } && dates[1][1] == dates[4][1] && (!doc.normalTimeNoteGroups.isEmpty() || noteText.replace("。", "") == expectedNote || doc.normalTimeNoteEvidence.all { RecoveryNotes.text(sources[it]?.text.orEmpty()) == expectedNote })
        if (doc.kind != RecoveryDocumentKind.TIMETABLE) {
            val spanKeys = doc.cells.filter { it.slots.size > 1 }.mapNotNull { c -> c.slots.firstOrNull()?.let { "${it.day}:${c.slots.minOf { s -> s.period }}-${c.slots.maxOf { s -> s.period }}" } }.toSet()
            val clockKeys = doc.times.keys + spanKeys
            check(doc.spanTimes.keys == spanKeys && doc.clockEvidence.keys == clockKeys && doc.clockBindings.keys.all { it in clockKeys }, "clockScope")
            val allClockIds = doc.clockEvidence.values.flatten().toSet()
            check(doc.timeEvidence.all { it in allClockIds }, "clockCoverage")
            check(doc.times.size == doc.days.size * maxPeriod && evidence(doc.timeEvidence, doc.timeEvidence), "times")
            doc.days.forEach { day ->
                var previous = "00:00"
                (1..maxPeriod).forEach { p ->
                    val clock = doc.times["$day:$p"]
                    val valid = clock?.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d〜(?:[01]\\d|2[0-3]):[0-5]\\d")) == true
                    check(valid, "clock")
                    val clockIds = doc.clockEvidence["$day:$p"].orEmpty()
                    val explicit = clock != null && clockBound(day, p, p, clock)
                    val normal = doc.kind == RecoveryDocumentKind.RETURN && day != doc.days.sorted().firstOrNull() && noteValid && clock == Schedule.normalTimes[p - 1] && evidence(clockIds, doc.normalTimeNoteEvidence)
                    check(explicit || normal, "clockEvidence")
                    check(doc.clockBindings["$day:$p"] == null || explicit, "clockBinding")
                    if (doc.kind == RecoveryDocumentKind.RETURN && day != doc.days.sorted().firstOrNull()) check(noteValid && clock == Schedule.normalTimes[p - 1], "normalTimeCondition")
                    if (valid) { val parts = clock!!.split('〜'); check(parts[0] >= previous && parts[1] > parts[0], "clockOrder"); previous = parts[1] }
                }
            }
            if (doc.kind == RecoveryDocumentKind.RETURN) check(noteValid, "normalTimeNote")
        }
        doc.cells.forEach { cell ->
            check(doc.sources.filter { it.cellId == cell.id }.map { it.id }.toSet() == cell.sourceIds.toSet(), "sourceInventory")
            check(doc.sources.filter { it.page == cell.page && minOf(it.box.x + it.box.width, cell.box.x + cell.box.width) > maxOf(it.box.x, cell.box.x) && minOf(it.box.y + it.box.height, cell.box.y + cell.box.height) > maxOf(it.box.y, cell.box.y) }.all { it.cellId == cell.id && it.id in cell.sourceIds }, "unassignedCellText")
            check(cell.inputState == RecoveryInputState.COMPLETE && cell.box.valid && cell.page > 0, "incompleteCell")
            check(cell.sourceIds.distinct().size == cell.sourceIds.size && cell.sourceIds.all { id -> sources[id]?.let { it.cellId == cell.id && it.page == cell.page && cell.box.contains(it.box) } == true }, "sourcePosition")
            cell.slots.firstOrNull()?.let { slot ->
                check(header(cell.classHeaderIds, doc.classEvidence[slot.className].orEmpty(), listOf(slot.className, slot.className.replace('_', '-'), slot.className.replace("_", "")), cell, cell.classRegion), "classBinding")
                check(header(cell.dayHeaderIds, doc.dayEvidence[slot.day].orEmpty(), dayLabels(slot.day, doc.kind), cell, cell.dayRegion), "dayBinding")
                check(cell.slots.all { s -> val ids = cell.periodHeaderIds.filter { it in doc.periodEvidence[s.period.toString()].orEmpty() }; header(ids, doc.periodEvidence[s.period.toString()].orEmpty(), listOf(s.period.toString(), "${s.period}限", "${s.period}時限", "第${s.period}時限"), cell, cell.periodRegions[s.period.toString()]) }, "periodBinding")
            }
            val periods = cell.slots.map { it.period }.sorted()
            if (doc.kind != RecoveryDocumentKind.TIMETABLE && periods.size > 1) {
                val day=cell.slots.first().day;val key = "$day:${periods.first()}-${periods.last()}";val clock=doc.spanTimes[key]
                val explicit=clock!=null && clockBound(day, periods.first(), periods.last(), clock)
                val normal=doc.kind==RecoveryDocumentKind.RETURN && day!=doc.days.sorted().firstOrNull() && noteValid && clock!=null && clock==RecoveryNotes.span(periods.first(),periods.last()) && evidence(doc.clockEvidence[key].orEmpty(),doc.normalTimeNoteEvidence)
                check((explicit || normal) && clock?.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]〜(?:[01][0-9]|2[0-3]):[0-5][0-9]"))==true, "spanTimeEvidence")
                check(doc.clockBindings[key]==null || explicit,"spanClockBinding")
                if(doc.kind==RecoveryDocumentKind.RETURN && day!=doc.days.sorted().firstOrNull())check(noteValid && clock==RecoveryNotes.span(periods.first(),periods.last()),"normalSpanTimeCondition")
            }
            val bindingIds = cell.lessonBindings.flatMap { it.subject + it.teacher + it.room }
            val proposal = cell.bindingMode == "roleProposal"
            check(cell.bindingMode in listOf("fixed", "roleProposal"), "bindingMode")
            check(proposal || cell.sourceIds.none { RecoveryRoles.explicitLabel(sources[it]?.text.orEmpty()) }, "unboundRoleLabel")
            check(if (cell.confirmedEmpty) cell.lessonBindings.isEmpty() && cell.roleScopes.isEmpty() else if (proposal) cell.lessonBindings.isEmpty() else cell.roleScopes.isEmpty() && cell.lessonBindings.size == cell.parallelCount && bindingIds.distinct().size == bindingIds.size && bindingIds.toSet() == (cell.sourceIds - cell.separatorIds.toSet()).toSet(), "lessonBinding")
            check(cell.separatorIds.distinct().size == cell.separatorIds.size && (cell.separatorIds.isEmpty() || !proposal && cell.parallelCount==2 && cell.separatorIds.size==3 && cell.separatorIds.all { it in cell.sourceIds && sources[it]?.text in listOf("・","･") }), "parallelSeparator")
            if(cell.separatorIds.isNotEmpty()) {
                val left=cell.lessonBindings.getOrNull(0);val right=cell.lessonBindings.getOrNull(1)
                if(left!=null && right!=null)listOf(left.subject to right.subject,left.teacher to right.teacher,left.room to right.room).forEach { (a,b) ->
                    check(a.isNotEmpty() && b.isNotEmpty() && cell.separatorIds.count { id -> val separator=sources[id] ?: return@count false;val la=a.mapNotNull { sources[it] };val rb=b.mapNotNull { sources[it] }; la.size==a.size && rb.size==b.size && la.all { it.box.x+it.box.width<=separator.box.x && minOf(it.box.y+it.box.height,separator.box.y+separator.box.height)>maxOf(it.box.y,separator.box.y) } && rb.all { it.box.x>=separator.box.x+separator.box.width && minOf(it.box.y+it.box.height,separator.box.y+separator.box.height)>maxOf(it.box.y,separator.box.y) } }==1,"parallelSeparatorPosition")
                }
            }
            val labels = cell.roleScopes.flatMap { it.labelSourceIds }.toSet()
            val body = cell.sourceIds.filter { it !in labels }
            if (proposal) {
                val roles = setOf("subject", "teacher", "room")
                check(cell.parallelCount in 1..4 && cell.roleScopes.size == 3 * cell.parallelCount && cell.roleScopes.map { it.lessonIndex to it.role }.toSet() == (0 until cell.parallelCount).flatMap { i -> roles.map { i to it } }.toSet(), "roleScope")
                cell.roleScopes.forEach { scope ->
                    val roleLabels = RecoveryRoles.labels[scope.role].orEmpty()
                    val allowedLabels = roleLabels.flatMap { listOf(it, "$it:", "$it：") }
                    val virtual = cell.copy(box = scope.box)
                    val within = scope.page == cell.page && cell.box.contains(scope.box)
                    check(within && scope.proof in listOf("inlineLabel", "columnHeader") && (scope.proof != "inlineLabel" || scope.labelSourceIds.all { it in cell.sourceIds }) && header(scope.labelSourceIds, if (scope.proof == "columnHeader") sources.keys.toList() else cell.sourceIds, allowedLabels, virtual, scope.labelRegion), "roleLabel")
                    val atoms = body.filter { sources[it]?.let { s -> s.page == scope.page && scope.box.contains(s.box) } == true }
                    check(!scope.emptyVerified || atoms.isEmpty() && scope.role != "subject", "roleEmpty")
                }
                check(cell.roleScopes.indices.all { i -> cell.roleScopes.drop(i + 1).none { s -> val a = cell.roleScopes[i]; minOf(a.box.x + a.box.width, s.box.x + s.box.width) > maxOf(a.box.x, s.box.x) && minOf(a.box.y + a.box.height, s.box.y + s.box.height) > maxOf(a.box.y, s.box.y) } }, "roleOverlap")
                check(body.all { id -> cell.roleScopes.count { it.box.contains(sources[id]?.box ?: RecoveryBox(-1.0, -1.0, 0.0, 0.0)) } == 1 }, "roleBodyCoverage")
            }
            check(cell.slots.isNotEmpty() && cell.slots.map { it.className to it.day }.distinct().size == 1 && cell.slots.map { it.period }.sorted().zipWithNext().all { (a, b) -> b == a + 1 }, "span")
            val recovered = cells[cell.id] ?: return@forEach
            if (recovered.state == RecoveryValueState.EMPTY) { check(cell.confirmedEmpty && cell.sourceIds.isEmpty() && recovered.lessons.isEmpty(), "falseEmpty"); return@forEach }
            check(!cell.confirmedEmpty && recovered.state == RecoveryValueState.PRESENT && cell.parallelCount in 1..4 && recovered.lessons.size == cell.parallelCount, "cellState")
            recovered.lessons.forEachIndexed { lessonIndex, lesson ->
                val binding = cell.lessonBindings.getOrNull(lessonIndex) ?: RecoveryLessonBinding(emptyList(), emptyList(), emptyList())
                listOf("subject" to lesson.subject, "teacher" to lesson.teacher, "room" to lesson.room).forEach { (name, field) ->
                    check(field.value.length <= 1024, "fieldLimit")
                    val scope = cell.roleScopes.singleOrNull { it.lessonIndex == lessonIndex && it.role == name }
                    val ids = if (proposal) body.filter { id -> scope?.box?.contains(sources.getValue(id).box) == true } else when (name) { "subject" -> binding.subject; "teacher" -> binding.teacher; else -> binding.room }
                    if (field.state == RecoveryValueState.EMPTY) check(ids.isEmpty() && name != "subject" && field.value.isEmpty() && field.evidence.isEmpty() && (if (proposal) scope?.emptyVerified == true else name in cell.blankFields), "falseBlankField")
                    else { check(field.state == RecoveryValueState.PRESENT && field.evidence.toSet() == ids.toSet() && field.evidence.size == ids.size && ordered(field.evidence) && evidence(field.evidence, ids, field.value), "fieldEvidence") }
                }
                check(cell.slots.firstOrNull()?.day?.let { doc.dayEvidence[it]?.let { _ -> evidence(lesson.dateEvidence, cell.dayHeaderIds) } } == true, "lessonDateEvidence")
                val periodIds = cell.periodHeaderIds
                check(evidence(lesson.periodEvidence, periodIds) && cell.slots.all { slot -> lesson.periodEvidence.any { it in doc.periodEvidence[slot.period.toString()].orEmpty() } }, "lessonPeriodEvidence")
            }
            if (proposal && recovered.state == RecoveryValueState.PRESENT) { val assigned = recovered.lessons.flatMap { it.subject.evidence + it.teacher.evidence + it.room.evidence }; check(assigned.distinct().size == assigned.size && assigned.toSet() == body.toSet(), "rolePartition") }
            check(recovered.lessons.distinct().size == recovered.lessons.size, "parallelDuplicate")
        }
        return RecoveryValidation(errors.toList())
    }
    fun inputErrors(doc: RecoveryDocument): List<String> = validate(doc, RecoveryResult(doc.pdfHash, doc.kind, doc.schoolYear, doc.term, doc.cells.map { RecoveredCell(it.id, RecoveryValueState.MISSING, emptyList()) }, RecoveryMetadata("rule", "rules", "1", "1", "1", SCHEMA_VERSION, VERSION, "preflight"))).errors.filter { it != "cellState" }
    fun canReuse(acceptance: RecoveryAcceptance, doc: RecoveryDocument, result: RecoveryResult) = acceptance.pdfHash == doc.pdfHash && acceptance.resultHash == fingerprint(result) && acceptance.scopeHash == fingerprint(doc) && acceptance.metadata == result.metadata && validate(doc, result).canAdopt
}

object RecoveryPolicy {
    fun kind(kind: MaterialKind): RecoveryDocumentKind? = when (kind) {
        MaterialKind.TIMETABLE -> RecoveryDocumentKind.TIMETABLE
        MaterialKind.EXAM -> RecoveryDocumentKind.EXAM
        MaterialKind.RETURN -> RecoveryDocumentKind.RETURN
        MaterialKind.CHANGES -> null
    }
    fun eligible(kind: MaterialKind, code: String) = kind(kind) != null && code in setOf("raster", "P01", "P02", "P03", "P04", "P05", "P06", "P07", "P08", "P10", "P12", "P13", "P14", "P15", "P17", "P18", "P19", "P20", "P21")
    fun providers(os: String, majorVersion: Int = 0): List<String> = when (os) {
        "ios" -> if (majorVersion >= 27) listOf("systemLanguageModel", "coreAI", "llamaCpp") else listOf("systemLanguageModel", "llamaCpp")
        "android" -> listOf("liteRtLm")
        "windows" -> listOf("windowsLanguageModel", "foundryLocal")
        else -> emptyList()
    }
    fun mayTryNext(state: LocalProviderState) = state in listOf(LocalProviderState.UNSUPPORTED, LocalProviderState.INSUFFICIENT_MEMORY)
}

@Serializable data class RecoveryPromptSource(val id: String, val text: String, val box: RecoveryBox? = null, val sourceLine: Int? = null, val sourceOrder: Int? = null)
@Serializable data class RecoveryPromptCell(val cellId: String, val slots: List<RecoverySlot>, val sources: List<RecoveryPromptSource>, val blankFields: List<String>, val parallelCount: Int, val lessonBindings: List<RecoveryLessonBinding>, val roleScopes: List<RecoveryRoleScope> = emptyList())
interface LocalRecoveryProvider {
    val id: String
    val localOnly: Boolean
    val metadata: RecoveryMetadata
    suspend fun availability(): LocalProviderState
    suspend fun recoverCell(cell: RecoveryPromptCell): List<RecoveryLesson>
}
data class RecoveryRun(val state: RecoveryJobState, val result: RecoveryResult?, val errors: List<String>)
class InvalidRecoveryOutput(cause: Throwable? = null) : Exception("復旧出力を確認できません。", cause)
object RecoveryRules {
    fun recover(doc: RecoveryDocument, cell: RecoveryCell): RecoveredCell? {
        if (cell.inputState != RecoveryInputState.COMPLETE || cell.confirmedEmpty) return null
        val sources = doc.sources.associateBy { it.id }
        if(cell.bindingMode=="roleProposal") {
            val labels=cell.roleScopes.flatMap { it.labelSourceIds }.toSet()
            val body=doc.sources.filter { it.id in cell.sourceIds && it.id !in labels }
            val lessons=(0 until cell.parallelCount).map { index ->
                fun role(name:String):RecoveryField? {
                    val scope=cell.roleScopes.singleOrNull { it.lessonIndex==index && it.role==name } ?: return null
                    val ids=body.filter { it.page==scope.page && scope.box.contains(it.box) }.map { it.id }
                    if(ids.isEmpty())return if(name!="subject" && scope.emptyVerified)RecoveryField(RecoveryValueState.EMPTY,"",emptyList())else null
                    return RecoveryField(RecoveryValueState.PRESENT,ids.joinToString("") { sources.getValue(it).text },ids)
                }
                RecoveryLesson(role("subject") ?: return null,role("teacher") ?: return null,role("room") ?: return null,cell.dayHeaderIds,cell.periodHeaderIds)
            }
            return RecoveredCell(cell.id,RecoveryValueState.PRESENT,lessons)
        }
        if (cell.bindingMode != "fixed" || cell.lessonBindings.size != cell.parallelCount) return null
        fun field(ids: List<String>, name: String): RecoveryField? {
            if (ids.isEmpty()) return if (name != "subject" && name in cell.blankFields) RecoveryField(RecoveryValueState.EMPTY, "", emptyList()) else null
            if (ids.any { sources[it]?.cellId != cell.id }) return null
            return RecoveryField(RecoveryValueState.PRESENT, ids.joinToString("") { sources.getValue(it).text }, ids)
        }
        val lessons = cell.lessonBindings.map { binding ->
            val subject = field(binding.subject, "subject") ?: return null
            val teacher = field(binding.teacher, "teacher") ?: return null
            val room = field(binding.room, "room") ?: return null
            RecoveryLesson(subject, teacher, room, cell.dayHeaderIds, cell.periodHeaderIds)
        }
        return RecoveredCell(cell.id, RecoveryValueState.PRESENT, lessons)
    }
}
object RecoveryEngine {
    suspend fun run(doc: RecoveryDocument, os: String, osMajor: Int, foreground: Boolean, providers: List<LocalRecoveryProvider>, rule: (RecoveryCell) -> RecoveredCell?, check: () -> Unit = ::interrupted): RecoveryRun {
        suspend fun alive() { currentCoroutineContext().ensureActive(); check() }
        alive()
        if (os in listOf("ios", "android") && !foreground) return RecoveryRun(RecoveryJobState.PENDING, null, emptyList())
        if (!doc.complete || doc.cells.any { it.inputState != RecoveryInputState.COMPLETE }) return RecoveryRun(RecoveryJobState.FAILED, null, listOf("incompleteDocument"))
        val inputErrors = RecoveryValidator.inputErrors(doc)
        if (inputErrors.isNotEmpty()) return RecoveryRun(RecoveryJobState.FAILED, null, inputErrors)
        val recovered = doc.cells.map { alive(); if (it.confirmedEmpty && it.sourceIds.isEmpty()) RecoveredCell(it.id, RecoveryValueState.EMPTY, emptyList()) else RecoveryRules.recover(doc, it) ?: rule(it) }.toMutableList()
        val missing = doc.cells.indices.filter { recovered[it] == null }
        fun result(metadata: RecoveryMetadata) = RecoveryResult(doc.pdfHash, doc.kind, doc.schoolYear, doc.term, recovered.map { requireNotNull(it) }, metadata)
        suspend fun validated(value: RecoveryResult): RecoveryRun { alive(); val validation = RecoveryValidator.validate(doc, value); alive(); return RecoveryRun(if (validation.canAdopt) RecoveryJobState.AWAITING_CONFIRMATION else RecoveryJobState.FAILED, value.takeIf { validation.canAdopt }, validation.errors) }
        if (missing.isEmpty()) return validated(result(RecoveryMetadata("rule", "rules", "2", "2", "2", RecoveryValidator.SCHEMA_VERSION, RecoveryValidator.VERSION, "$os:$osMajor")))
        var runtimeFailed = false
        for (id in RecoveryPolicy.providers(os, osMajor)) {
            alive(); val matching = providers.filter { it.id == id && it.localOnly }
            if (matching.size > 1) return RecoveryRun(RecoveryJobState.FAILED, null, listOf("duplicateProviders"))
            val provider = matching.singleOrNull() ?: continue
            val availability = try { provider.availability().also { alive() } }
                catch (e: java.util.concurrent.CancellationException) { throw e }
                catch (e: InterruptedException) { throw e }
                catch (_: Exception) { runtimeFailed = true; continue }
            if (availability != LocalProviderState.READY) {
                if (RecoveryPolicy.mayTryNext(availability) || os == "windows" && availability == LocalProviderState.NOT_READY) continue
                return RecoveryRun(RecoveryJobState.AWAITING_MODEL, null, listOf(availability.name))
            }
            try {
                for (i in missing) {
                    alive(); val cell = doc.cells[i]; val ids = cell.sourceIds.toSet()
                    val prompt = RecoveryPromptCell(cell.id, cell.slots, doc.sources.filter { it.id in ids }.map { RecoveryPromptSource(it.id, it.text, it.box, it.sourceLine, it.sourceOrder) }, (cell.blankFields + cell.roleScopes.filter { it.emptyVerified }.map { it.role }).distinct(), cell.parallelCount, cell.lessonBindings, cell.roleScopes)
                    if (json.encodeToString(prompt).toByteArray(Charsets.UTF_8).size > 8192) return RecoveryRun(RecoveryJobState.FAILED, null, listOf("promptLimit"))
                    val generated = provider.recoverCell(prompt); alive()
                    fun grounded(field: RecoveryField) = if (field.state == RecoveryValueState.PRESENT) field.copy(value = field.evidence.joinToString("") { id -> doc.sources.singleOrNull { it.id == id && it.cellId == cell.id }?.text ?: throw InvalidRecoveryOutput() }) else field
                    val lessons = generated.map { it.copy(subject = grounded(it.subject), teacher = grounded(it.teacher), room = grounded(it.room), dateEvidence = cell.dayHeaderIds, periodEvidence = cell.periodHeaderIds) }
                    recovered[i] = RecoveredCell(cell.id, RecoveryValueState.PRESENT, lessons)
                }
                return validated(result(provider.metadata))
            } catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: InterruptedException) { throw e }
            catch (_: InvalidRecoveryOutput) { return RecoveryRun(RecoveryJobState.FAILED, null, listOf("invalidOutput")) }
            catch (_: Exception) { runtimeFailed = true }
        }
        alive()
        return RecoveryRun(if (runtimeFailed) RecoveryJobState.FAILED else RecoveryJobState.AWAITING_MODEL, null, listOf(if (runtimeFailed) "runtimeFailure" else "noLocalProvider"))
    }
}

/** Ephemeral, confined to the current reader attempt; a partial page is never complete model input. */
data class RecoveryReadPage(val page: Int, val state: RecoveryInputState, val layout: Page?)
class RecoveryReadCapture {
    var pages: List<RecoveryReadPage> = emptyList(); private set
    var readerCompleted = false; private set
    val complete get() = readerCompleted && pages.isNotEmpty() && pages.all { it.state == RecoveryInputState.COMPLETE }
    fun reset() { pages = emptyList(); readerCompleted = false }
    fun begin(pageCount: Int) { require(pageCount in 1..12); pages = (1..pageCount).map { RecoveryReadPage(it, RecoveryInputState.RASTER_ONLY, null) }; readerCompleted = false }
    fun record(page: Int, state: RecoveryInputState, layout: Page) { require(page in 1..pages.size); pages = pages.map { if (it.page == page) RecoveryReadPage(page, state, layout.copy(glyphs = layout.glyphs.toList(), lines = layout.lines.toList())) else it } }
    fun finish() { readerCompleted = true }
}
