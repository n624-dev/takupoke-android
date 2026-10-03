package jp.n624.takupoke.core

import java.time.LocalDate
import kotlin.math.abs

/** Geometry comes from the reader/OCR, never from the language model. */
data class RecoveryLayoutPage(val page: Int, val layout: Page, val raster: Boolean = false, val acquisitionComplete: Boolean = true, val verifiedBlankBoxes: List<RecoveryBox> = emptyList())
class RecoveryPreparationFailure(val reason: String) : IllegalArgumentException("復旧の確認条件を満たせませんでした（$reason）。前回の正常結果を保持しています。")
object RecoveryLayout {
    private data class Region(val box: RecoveryBox, val ids: List<String>, val text: String)
    private fun Box.recovery() = RecoveryBox(left, top, right - left, bottom - top)
    private fun union(boxes: List<RecoveryBox>): RecoveryBox {
        val x = boxes.minOf { it.x }; val y = boxes.minOf { it.y }
        return RecoveryBox(x, y, boxes.maxOf { it.x + it.width } - x, boxes.maxOf { it.y + it.height } - y)
    }
    fun prepare(pages: List<RecoveryLayoutPage>, hash: String, kind: MaterialKind, structureProposals: Map<String,List<RecoveryLesson>> = emptyMap()): RecoveryDocument {
        fun requireSource(ok: Boolean, reason: String) { if (!ok) throw RecoveryPreparationFailure(reason) }
        var work=0L
        fun step(cost:Int=1) {
            work+=cost
            if(cost>1 || work%128==0L)interrupted()
            requireSource(work<=20000000,"表構造の比較上限")
        }
        val documentKind = RecoveryPolicy.kind(kind) ?: throw RecoveryPreparationFailure("対象外")
        requireSource(pages.size in 1..12 && pages.all { step();it.acquisitionComplete }, "文字取得未完了")
        val sources = mutableListOf<RecoverySource>();val sourceIndices=mutableMapOf<String,Int>(); val cells = mutableListOf<RecoveryCell>()
        val requests=mutableListOf<RecoveryStructureRequest>();val usedProposals=mutableSetOf<String>()
        val yearIds = mutableListOf<String>(); val termIds = mutableListOf<String>(); val titleIds = mutableListOf<String>()
        val classes = linkedMapOf<String, MutableList<String>>(); val days = linkedMapOf<String, MutableList<String>>(); val periods = linkedMapOf<String, MutableList<String>>()
        val times = linkedMapOf<String, String>(); val clockEvidence = linkedMapOf<String, List<String>>(); val clockBindings = linkedMapOf<String, RecoveryClockBinding>(); val spans = linkedMapOf<String, String>(); val notes = mutableListOf<String>();val noteGroups=mutableListOf<List<String>>()
        var year: Int? = null; var term: String? = null
        val maxPeriod = if (kind == MaterialKind.EXAM) 6 else 8
        for (input in pages) {
            interrupted(); val p = input.layout; requireSource(p.glyphs.size <= 100000 && p.lines.size <= 100000 && p.width > 0 && p.height > 0, "ページ上限")
            val grid = Grid(p)
            fun measuredBox(x:Double,y:Double):Box? { step(p.lines.size*6);return try { grid.box(x,y) }catch(_:ParseFailure){null} }
            // Preserve the complete inventory. Whitespace has no semantic content; no visible atom is dropped.
            val atoms = Grid.rows(p.glyphs.filter { step();key(it.text).isNotEmpty() }).flatMap { row ->
                val groups = mutableListOf<MutableList<Glyph>>()
                row.forEach { glyph ->
                    val last = groups.lastOrNull()?.lastOrNull()
                    if (last == null || glyph.text in listOf("・","･") || last.text in listOf("・","･") || glyph.x - last.x - last.width > maxOf(2.0, minOf(last.height, glyph.height) * .55)) groups += mutableListOf(glyph) else groups.last() += glyph
                }
                val fragments=groups.flatMap { group ->
                    val full=group.joinToString("") { it.text }
                    val heading=Regex("^((?:(?:令和[0-9]{1,2}|[0-9]{4})年度)+)(前期|後期)?(通常時間割|試験返却時間割|試験時間割|時間割)$").matchEntire(full)
                    val prefix=RecoveryRoles.prefix.find(full)?.value
                    val parts=if(heading!=null)Regex("(?:令和[0-9]{1,2}|[0-9]{4})年度").findAll(heading.groupValues[1]).map { it.value }.toList()+heading.groupValues.drop(2).filter(String::isNotEmpty) else if(prefix!=null && prefix.length<full.length)listOf(prefix,full.drop(prefix.length))else emptyList()
                    if(parts.isEmpty())listOf(group)else {
                        var cursor=0;val fragments=mutableListOf<List<Glyph>>()
                        for(part in parts) { val start=cursor;var count=0;while(cursor<group.size&&count<part.length){count+=group[cursor].text.length;cursor++};if(count!=part.length){fragments.clear();break};fragments+=group.subList(start,cursor) }
                        if(fragments.size==parts.size && cursor==group.size)fragments else listOf(group)
                    }
                }
                fragments.map { group ->
                    val box = RecoveryBox(group.minOf { it.x }.coerceAtLeast(0.0), group.minOf { it.y }.coerceAtLeast(0.0), group.maxOf { it.x + it.width } - group.minOf { it.x }, group.maxOf { it.y + it.height } - group.minOf { it.y })
                    RecoverySource("p${input.page}-a${sources.size}", "unassigned", input.page, group.joinToString("") { it.text }.trim(), box, input.raster, group.first().sourceLine, group.first().order).also { sourceIndices[it.id]=sources.size;sources += it }
                }
            }
            requireSource(atoms.all { step();it.box.valid }, "文字の境界")
            val localYear = atoms.filter { step();key(it.text).matches(Regex("(?:令和[0-9]{1,2}|[0-9]{4})年度")) }
            requireSource(localYear.isNotEmpty(), "年度原文")
            localYear.forEach { atom -> val number = Regex("[0-9]+").find(key(atom.text))!!.value.toInt(); val found = if (key(atom.text).startsWith("令和")) number + 2018 else number; requireSource(year == null || year == found, "年度の不一致"); year = found; yearIds += atom.id }
            atoms.filter { step();RecoveryNotes.text(it.text).matches(Regex("[0-9]{1,2}月[0-9]{1,2}日の時間割は以下のとおり[0-9]{1,2}月[0-9]{1,2}日[〜~][0-9]{1,2}日は通常の授業日どおりの授業時間")) }.forEach { notes += it.id;noteGroups+=listOf(it.id) }
            val noteHeads=atoms.filter { step();RecoveryNotes.text(it.text).matches(Regex("[0-9]{1,2}月[0-9]{1,2}日の時間割は以下のとおり")) }
            val noteTails=atoms.filter { step();RecoveryNotes.text(it.text).matches(Regex("[0-9]{1,2}月[0-9]{1,2}日[〜~][0-9]{1,2}日は通常の授業日どおりの授業時間")) }
            if(noteHeads.size==1 && noteTails.size==1) { val ids=listOf(noteHeads.single().id,noteTails.single().id);notes+=ids;noteGroups+=ids }
            // Repeated headings remain in the source inventory.
            atoms.filter { step();key(it.text) in listOf("前期", "後期") }.forEach { requireSource(term == null || term == key(it.text), "学期の不一致"); term = key(it.text); termIds += it.id }
            val cutsX = p.lines.filter { step();it.vertical }.map { it.x1 }.distinct().sorted(); val cutsY = p.lines.filter { step();it.horizontal }.map { it.y1 }.distinct().sorted()
            requireSource(cutsX.size in 2..1000 && cutsY.size in 2..1000 && cutsX.size.toLong() * cutsY.size <= 100000, "表の罫線")
            val boxes = cutsX.zipWithNext().flatMap { (a,b) -> cutsY.zipWithNext().mapNotNull { (c,d) -> step(); if (b-a < 2 || d-c < 2) null else measuredBox((a+b)/2,(c+d)/2)?.recovery() } }.distinct()
            val regions = boxes.map { box -> val owned = atoms.filter { step();box.contains(it.box) }; Region(box, owned.map { it.id }, key(owned.joinToString("") { it.text })) }
            val classRegions = regions.mapNotNull { r -> step();canonicalClass(r.text.replace('-', '_')).takeIf { it in RecoveryValidator.knownClasses }?.let { it to r } }.toMutableList()
            // Existing sheets split the grade and course across adjacent ruled cells.
            regions.filter { step();it.text.matches(Regex("[1-5]|AI")) }.forEach { grade -> regions.filter { step();it.text.matches(Regex("[1-3]|CN|ES|IT")) && grade.box.height > it.box.height + 1 && abs(it.box.x - grade.box.x - grade.box.width) < 1 && it.box.y >= grade.box.y && it.box.y + it.box.height <= grade.box.y + grade.box.height }.forEach { course -> val name = canonicalClass("${grade.text}_${course.text}"); if (name in RecoveryValidator.knownClasses) classRegions += name to Region(union(listOf(grade.box, course.box)), grade.ids + course.ids, name) } }
            val dayRegions = regions.mapNotNull { r -> step();
                val value = if (kind == MaterialKind.TIMETABLE) listOf("月", "火", "水", "木", "金").indexOf(r.text.removeSuffix("曜日").removeSuffix("曜")).takeIf { it >= 0 }?.plus(1)?.toString()
                else runCatching { val m = Regex("^(?:([0-9]{4})[-/])?([0-9]{1,2})[月/]([0-9]{1,2})日?$").matchEntire(r.text) ?: return@runCatching null; val month = m.groupValues[2].toInt(); LocalDate.of(m.groupValues[1].toIntOrNull() ?: (year!! + if (month < 4) 1 else 0), month, m.groupValues[3].toInt()).toString() }.getOrNull()
                value?.let { it to r }
            }
            val periodRegions = regions.mapNotNull { r -> step();Regex("^(?:第)?([1-8])(?:時限目|時限|限)?$").matchEntire(r.text)?.groupValues?.get(1)?.takeIf { it.toInt() <= maxPeriod }?.let { it to r } }
            fun aligned(header: Region, box: RecoveryBox): RecoveryHeaderRegion? {
                return when {
                    header.box.x + header.box.width <= box.x + .01 && minOf(header.box.y + header.box.height, box.y + box.height) > maxOf(header.box.y, box.y) -> RecoveryHeaderRegion(input.page, header.box, RecoveryHeaderAxis.LEFT)
                    header.box.y + header.box.height <= box.y + .01 && minOf(header.box.x + header.box.width, box.x + box.width) > maxOf(header.box.x, box.x) -> RecoveryHeaderRegion(input.page, header.box, RecoveryHeaderAxis.ABOVE)
                    else -> null
                }
            }
            fun nearest(headers: List<Pair<String, Region>>, box: RecoveryBox): Pair<Pair<String, Region>, RecoveryHeaderRegion>? {
                val candidates = headers.mapNotNull { h -> step();aligned(h.second, box)?.let { h to it } }
                val filtered = candidates.filter { candidate -> step();candidates.none { other -> step();other != candidate && other.second.axis == candidate.second.axis && (if (other.second.axis == RecoveryHeaderAxis.LEFT) other.second.box.x > candidate.second.box.x else other.second.box.y > candidate.second.box.y) } }
                return filtered.singleOrNull()
            }
            val spanRegions = regions.mapNotNull { r -> step();Regex("^([1-8])[・〜-]([1-8])(?:時限連続|限)$").matchEntire(r.text)?.let { m -> val a=m.groupValues[1].toInt();val b=m.groupValues[2].toInt();if(a<b&&b<=maxPeriod)"$a-$b" to r else null } }
            val headerIds = (classRegions + dayRegions + periodRegions).flatMap { it.second.ids }.toSet()
            regions.filter { r -> step();r.ids.none { step();it in headerIds || it in yearIds || it in termIds } }.forEach { region ->
                val cls = nearest(classRegions, region.box); val day = nearest(dayRegions, region.box); val per = nearest(periodRegions, region.box); val span = nearest(spanRegions,region.box)
                if (day != null && (per != null || span != null) && region.text.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9][〜~](?:[01][0-9]|2[0-3]):[0-5][0-9]"))) {
                    val periodHeader=span ?: requireNotNull(per);val clockKey = "${day.first.first}:${periodHeader.first.first}"; val target=if(span!=null)spans else times; requireSource(clockKey !in target, "時刻重複"); target[clockKey] = region.text.replace('~','〜'); clockEvidence[clockKey] = region.ids
                    val bounds=periodHeader.first.first.split('-').map(String::toInt)
                    clockBindings[clockKey] = RecoveryClockBinding(input.page, region.box, day.first.first, bounds.first(), bounds.last(), day.first.second.ids, day.second, periodHeader.first.second.ids, periodHeader.second)
                    days.getOrPut(day.first.first) { mutableListOf() } += day.first.second.ids; if(span==null)periods.getOrPut(periodHeader.first.first) { mutableListOf() } += periodHeader.first.second.ids
                    return@forEach
                }
                val alignedPeriods=periodRegions.mapNotNull { h -> step();aligned(h.second,region.box)?.let { h to it } }
                val boundPeriods=alignedPeriods.filter { candidate -> step();alignedPeriods.none { other -> step();other!=candidate && other.second.axis==candidate.second.axis && (if(other.second.axis==RecoveryHeaderAxis.ABOVE)other.second.box.y>candidate.second.box.y else other.second.box.x>candidate.second.box.x) } }.sortedBy { it.first.first.toInt() }
                if (cls == null || day == null || boundPeriods.isEmpty()) return@forEach
                requireSource(boundPeriods.map { it.first.first.toInt() }.zipWithNext().all { (a,b)->step();b==a+1 }, "結合時限の見出し")
                val id = "cell-${input.page}-${cells.size}"
                val owned = atoms.filter { step();region.box.contains(it.box) }
                owned.forEach { source -> sources[sourceIndices.getValue(source.id)] = source.copy(cellId = id) }
                val ownedByPosition=owned.associateBy { Triple(it.text,it.box.x,it.box.y) }
                requireSource(ownedByPosition.size==owned.size,"重なる原文文字")
                val rows = Grid.rows(owned.mapIndexed { i,s -> Glyph(s.text,s.box.x,s.box.y,s.box.width,s.box.height,i) }).map { row -> row.map { g -> ownedByPosition.getValue(Triple(g.text,g.x,g.y)).id } }
                val empty = owned.isEmpty() && (!input.raster || input.verifiedBlankBoxes.any { step();it.contains(region.box) })
                val separatorIds=owned.filter { step();it.text in listOf("・","･") }.map { it.id }
                val parallel=owned.none { step();RecoveryRoles.explicitLabel(it.text) } && rows.size==3 && separatorIds.size==3 && rows.all { row -> step();row.count { it in separatorIds }==1 && row.indexOfFirst { it in separatorIds } in 1 until row.lastIndex }
                val bindingRows=if(parallel)(0..1).map { part -> rows.map { row -> val split=row.indexOfFirst { it in separatorIds };if(part==0)row.take(split)else row.drop(split+1) } } else listOf(rows)
                val roles = mutableListOf<RecoveryRoleScope>()
                val aliases = RecoveryRoles.byLabel
                val roleLabels=owned.filter { step();key(it.text).removeSuffix(":").removeSuffix("：") in aliases }
                val labelColumns=roleLabels.map { it.box.x }.distinct().sorted()
                roleLabels.forEach { label ->
                    val nextY = roleLabels.filter { step();it.box.y > label.box.y + label.box.height && abs(it.box.x-label.box.x)<1 }.minOfOrNull { it.box.y } ?: (region.box.y + region.box.height)
                    val right = label.box.x + label.box.width
                    val scopeBox = RecoveryBox(right, label.box.y, (labelColumns.firstOrNull { it > label.box.x+1 } ?: (region.box.x+region.box.width)) - right, nextY - label.box.y)
                    if (scopeBox.valid) roles += RecoveryRoleScope(labelColumns.indexOf(label.box.x), aliases.getValue(key(label.text).removeSuffix(":").removeSuffix("：")), input.page, scopeBox, listOf(label.id), RecoveryHeaderRegion(input.page, label.box, RecoveryHeaderAxis.LEFT), "inlineLabel", !input.raster && owned.none { step();it.id != label.id && scopeBox.contains(it.box) })
                }
                var proposal = labelColumns.size in 1..4 && roles.size == 3*labelColumns.size && roles.map { it.lessonIndex to it.role }.distinct().size == roles.size
                val fixed = rows.size == 3 && owned.none { step();'・' in it.text || '･' in it.text || RecoveryRoles.explicitLabel(it.text) }
                if(!empty && !proposal && !parallel && !fixed) {
                    val slots=boundPeriods.map { RecoverySlot(cls.first.first,day.first.first,it.first.first.toInt()) }
                    val request=try { RecoveryStructure.request(id,input.page,region.box,slots,owned) }catch(_:IllegalArgumentException) { throw RecoveryPreparationFailure("表構造の候補上限") }
                    val answer=structureProposals[id]?.also { usedProposals+=id } ?: RecoveryStructure.cheap(request)
                    if(answer==null)requests+=request
                    else {
                        val verified=RecoveryStructure.verify(request,answer)
                        roles.clear()
                        verified.forEach { role ->
                            roles+=RecoveryRoleScope(0,role.role,input.page,role.scope,role.labels.flatMap { it.sources }.map { it.id },RecoveryHeaderRegion(input.page,role.labelBox,RecoveryHeaderAxis.LEFT),"inlineLabel",!input.raster && role.body.isEmpty())
                        }
                        proposal=true
                    }
                }
                cells += RecoveryCell(id,input.page,region.box,RecoveryInputState.COMPLETE,boundPeriods.map { RecoverySlot(cls.first.first,day.first.first,it.first.first.toInt()) },owned.map { it.id },emptyList(),empty,parallelCount=if(proposal)roles.size/3 else if(parallel)2 else 1, classHeaderIds=cls.first.second.ids,dayHeaderIds=day.first.second.ids,periodHeaderIds=boundPeriods.flatMap { it.first.second.ids },lessonBindings=if (!empty && !proposal && (parallel || fixed)) bindingRows.map { RecoveryLessonBinding(it[0],it[1],it[2]) } else emptyList(),classRegion=cls.second,dayRegion=day.second,periodRegions=boundPeriods.associate { it.first.first to it.second },bindingMode=if(proposal) "roleProposal" else "fixed",roleScopes=if(proposal)roles else emptyList(),separatorIds=if(parallel)separatorIds else emptyList())
                classes.getOrPut(cls.first.first) { mutableListOf() } += cls.first.second.ids; days.getOrPut(day.first.first) { mutableListOf() } += day.first.second.ids; boundPeriods.forEach { period -> periods.getOrPut(period.first.first) { mutableListOf() } += period.first.second.ids }
            }
            atoms.filter { step();key(it.text) in listOf("時間割","通常時間割","試験時間割","試験返却時間割","クラス","曜日","日付","時限","授業時間","学年") && it.box.y + it.box.height <= (cells.filter { c -> step();c.page == input.page }.minOfOrNull { c -> c.box.y } ?: 0.0) }.forEach { titleIds += it.id }
        }
        requireSource(yearIds.isNotEmpty() && (kind != MaterialKind.TIMETABLE || termIds.isNotEmpty()), "見出しの不一致")
        val yearValue = year ?: throw RecoveryPreparationFailure("年度")
        val classesList = classes.keys.sorted(); val daysList = days.keys.sorted()
        if(kind==MaterialKind.RETURN && daysList.size==5 && noteGroups.isNotEmpty()) {
            val dates=daysList.map { LocalDate.parse(it) }
            val expected="${dates[0].monthValue}月${dates[0].dayOfMonth}日の時間割は以下のとおり${dates[1].monthValue}月${dates[1].dayOfMonth}日〜${dates[4].dayOfMonth}日は通常の授業日どおりの授業時間"
            val valid=dates[1].monthValue==dates[4].monthValue && noteGroups.all { ids -> step();RecoveryNotes.text(ids.joinToString("") { id -> sources.single { step();it.id==id }.text })==expected }
            if(valid)daysList.drop(1).forEach { day ->
                (1..8).forEach { period -> val clockKey="$day:$period";if(clockKey !in times){times[clockKey]=Schedule.normalTimes[period-1];clockEvidence[clockKey]=notes.toList()} }
                cells.filter { step();it.slots.size>1 && it.slots.first().day==day }.forEach { cell -> val start=cell.slots.minOf { it.period };val end=cell.slots.maxOf { it.period };val clockKey="$day:$start-$end";if(clockKey !in spans){spans[clockKey]=requireNotNull(RecoveryNotes.span(start,end));clockEvidence[clockKey]=notes.toList()} }
            }
        }
        val doc = RecoveryDocument(hash,documentKind,yearValue,if(kind==MaterialKind.TIMETABLE)term else null,classesList,daysList,classesList.flatMap { cls -> daysList.flatMap { day -> (1..maxPeriod).map { RecoverySlot(cls,day,it) } } },cells,sources,true,yearIds,if(kind==MaterialKind.TIMETABLE)termIds else emptyList(),days.mapValues { it.value.distinct() },classes.mapValues { it.value.distinct() },periods.mapValues { it.value.distinct() },times,clockEvidence.values.flatten().distinct(),notes,clockEvidence,spans,clockBindings,titleIds,noteGroups)
        requireSource(usedProposals==structureProposals.keys,"未使用の表構造候補")
        val errors = RecoveryValidator.preparationErrors(doc,requests.map { it.id }.toSet())
        if(requests.isNotEmpty()) {
            // Process every page and independently validate all identity,
            // headers, coverage and source inventory before any AI is loaded.
            requireSource(errors.isEmpty(),errors.joinToString(","))
            throw RecoveryStructurePreparation(doc,requests,pages)
        }
        requireSource(errors.isEmpty(), errors.joinToString(","))
        return doc
    }
}
object RecoveryAnalysis {
    fun convert(doc: RecoveryDocument, result: RecoveryResult): Analysis {
        require(RecoveryValidator.validate(doc, result).canAdopt)
        val kind = when(doc.kind) { RecoveryDocumentKind.TIMETABLE -> MaterialKind.TIMETABLE; RecoveryDocumentKind.EXAM -> MaterialKind.EXAM; RecoveryDocumentKind.RETURN -> MaterialKind.RETURN }
        val work=RecoveryWork()
        val cells = doc.cells.associateBy { work.step();it.id };val indexed=RecoverySources(doc,work)
        val raw=doc.cells.associate { cell -> work.step();cell.id to cell.sourceIds.joinToString("\n") { id -> work.step();work.read(requireNotNull(indexed.byId[id]).text) } }
        val lessons = result.cells.flatMap { recovered -> work.step(); val cell = cells.getValue(recovered.cellId); val first = cell.slots.minBy { it.period }; val last = cell.slots.maxOf { it.period }; recovered.lessons.flatMap { lesson -> work.step();cell.slots.map { slot -> work.step(); Lesson(slot.className,if(kind==MaterialKind.TIMETABLE)slot.day.toInt() else LocalDate.parse(slot.day).dayOfWeek.value,slot.period,Names(lesson.subject.value,lesson.teacher.value,lesson.room.value),raw.getValue(cell.id),if(kind==MaterialKind.TIMETABLE)null else slot.day,first.period,last,if(kind==MaterialKind.TIMETABLE)null else if(first.period==last)doc.times["${slot.day}:${slot.period}"] else doc.spanTimes["${slot.day}:${first.period}-$last"],kind) } } }
        val times = if(kind==MaterialKind.TIMETABLE)emptyList() else doc.days.map { day -> DayTimes(day,(1..if(kind==MaterialKind.EXAM)6 else 8).map { p -> val value=doc.times.getValue("$day:$p");PeriodTime(p,value.substringBefore('〜'),value.substringAfter('〜')) }) }
        return Analysis(kind,doc.schoolYear,if(doc.term=="前期")1 else if(doc.term=="後期")2 else 0,lessons,dates=if(kind==MaterialKind.TIMETABLE)emptyList() else doc.days,classes=doc.classes,specialTimes=times)
    }
}
