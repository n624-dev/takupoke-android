package jp.n624.takupoke.core

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class CoreTest {
    @Test fun notificationPermissionAndSetupPreserveManualChoices() {
        assertFalse(Settings().notificationChoice("changes", false).changeNotifications)
        val enabled = Settings().notificationChoice("setup", true)
        assertTrue(enabled.changeNotifications); assertTrue(enabled.examNotifications)
        val manual = enabled.copy(changeNotifications = false)
        assertEquals(manual, manual.notificationChoice("setup", true))
        val denied = Settings().notificationChoice("setup", false)
        assertFalse(denied.changeNotifications); assertFalse(denied.examNotifications)
        assertTrue(denied.notificationsSetupComplete)
        assertTrue(denied.notificationChoice("exam", true).examNotifications)
        assertFalse(denied.notificationChoice("exam", true).changeNotifications)
        assertFalse(json.decodeFromString<Settings>("{}").notificationsSetupComplete)
    }
    @Test fun kanaAndRomajiSearch() {
        assertEquals(LinkSearch.normalize("システム"), LinkSearch.normalize("しすてむ"))
        assertTrue(LinkSearch.score("しすてむ|system", "sisutemu") >= 0)
        assertTrue(LinkSearch.score("じこくひょう|時刻表", "zikokuhyo") >= 0)
        assertTrue(LinkSearch.score("Office 365|オフィス", "ｏｆｆｉｃｅ") >= 0)
        assertEquals(-1, LinkSearch.score("system", "unrelated"))
    }
    @Test fun schoolCalendarAndWeekend() {
        assertEquals(2025, schoolYear(LocalDate.parse("2026-03-31")))
        assertEquals("2026-1", retentionPeriod(LocalDate.parse("2026-04-01")))
        assertEquals("2026-2", retentionPeriod(LocalDate.parse("2026-10-01")))
        assertEquals("2026-10-05", Schedule.week(LocalDate.parse("2026-10-03")).toString())
        assertEquals("2026-09-28", Schedule.week(LocalDate.parse("2026-10-01")).toString())
        assertTrue(Schedule.compatible("1_1", "1_IT")); assertFalse(Schedule.compatible("1_1", "2_IT"))
    }
    @Test fun datesAndNormalization() {
        assertEquals("2027-01-03", XlsxParser.date("1/3", 2026).toString())
        assertEquals("2026-04-03", XlsxParser.date("4月3日", 2026).toString())
        assertEquals("2026-01-01", XlsxParser.date("46023.75", 2026).toString())
        assertFails { XlsxParser.date("2/30", 2026) }
        assertEquals("AI_1", canonicalClass("1_AI"))
        assertEquals("ABC1", key(" ＡＢＣ　１ "))
        assertEquals(listOf(1, 2, 3), Change("", "", "１～３時限", "", "").periods())
        assertEquals(emptyList(), Change("", "", "9", "", "").periods())
    }
    @Test fun xlsxReadsSyntheticOOXMLAndExpandsClasses() {
        val a = XlsxParser.parse(xlsx(listOf(listOf("1-2", "CN,ES", "10/1", "1-2", "数学", "理科", "先生", "教室", "補講"))), 2026)
        assertEquals(4, a.changes.size); assertEquals(setOf("1_CN", "1_ES", "2_CN", "2_ES"), a.classes.toSet())
        assertEquals("2026-10-01", a.changes.first().date); assertEquals(emptyList(), a.changes.first().periods())
    }
    @Test fun xlsxWeekdayPreviewDoesNotSilentlyCommit() {
        val bytes = xlsx(listOf(listOf("1", "CN", "10/1", "1", "数学", "理科", "先生", "教室", "", "金")), formula = true)
        assertFailsWith<WeekdayWarning> { XlsxParser.parse(bytes, 2026) }
        assertEquals(1, XlsxParser.parse(bytes, 2026, true).changes.size)
        assertEquals(1, XlsxParser.parse(xlsx(listOf(listOf("1", "CN", "10/1", "1", "数学", "理科", "先生", "教室", "", "木")), true), 2026).changes.size)
    }
    @Test fun xlsxRejectsExternalMacrosBadHeadersAndFormulaInSubject() {
        assertFails { XlsxParser.parse(xlsx(listOf(listOf("1", "CN", "10/1", "1", "", "数学")), extra = mapOf("xl/vbaProject.bin" to "bad")), 2026) }
        assertFails { XlsxParser.parse(xlsx(listOf(listOf("1", "CN", "10/1", "1", "", "数学")), extra = mapOf("xl/externalLinks/link.xml" to "bad")), 2026) }
        assertFails { XlsxParser.parse(xlsx(listOf(listOf("", "CN", "10/1", "1", "", "数学"))), 2026) }
        assertFails { XlsxParser.parse(xlsx(listOf(listOf("1", "CN", "10/1", "1", "", "数学")), formulaColumn = 5), 2026) }
        assertFails { XlsxParser.parse(xlsx(listOf(listOf("1", "CN", "10/1", "1", "", "数学")), date1904 = true), 2026) }
    }
    @Test fun xmlAndZipBounds() {
        assertFails { SafeXml.parse("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///forbidden'>]><a>&x;</a>".toByteArray()) }
        assertFails { Archives.read(zip(mapOf("../file" to "bad".toByteArray()))) }
        assertFails { Archives.read(zip(mapOf("a" to ByteArray(101))), maxEntry = 100) }
        assertFails { Archives.read(zip(mapOf("a" to ByteArray(60), "b" to ByteArray(60))), maxTotal = 100) }
        assertFails { Archives.read("not a ZIP".toByteArray()) }
    }
    @Test fun mappingResolvesExactContextAndPreservesUnknownMetadata() {
        val m = Mapping(listOf(MappingRule("数", "数学"), MappingRule("数", "応用数学", listOf("2_CN"))), listOf(MappingRule("甲", "甲先生")), listOf(MappingRule("A", "A教室")), listOf(TeacherContext("乙", "乙先生", "数学", "1_CN", 2026)))
        assertEquals("応用数学", m.apply(Names("数"), "2_CN", 2026).subjectFull)
        assertEquals("乙先生", m.apply(Names("数", "乙"), "1_CN", 2026).teacherFull)
        assertEquals("甲先生, 未登録（共同）", m.apply(Names("数", "甲, 未登録（共同）"), "1_CN", 2026).teacherFull)
        assertEquals(Names("数", "甲", "A"), m.separate("数(甲)(A)", "1_CN", 2026))
        assertEquals(Names("数(未知)"), m.separate("数(未知)", "1_CN", 2026))
    }
    @Test fun mappingPackageIntegrity() {
        val body = json.encodeToString(Mapping.serializer(), Mapping(listOf(MappingRule("数", "数学")), emptyList(), emptyList())).toByteArray()
        fun packageBytes(hash: String = sha256(body)) = zip(mapOf("manifest.json" to """{"schemaVersion":2,"version":"test-1","publishedAt":"2026-10-01T00:00:00Z","mappings":{"bytes":${body.size},"sha256":"$hash"}}""".toByteArray(), "mappings.json" to body))
        assertEquals("数学", Mapping.decode(packageBytes(), "test-1").subjects.first().fullName)
        assertFails { Mapping.decode(packageBytes("0".repeat(64)), "test-1") }
        assertFails { Mapping.decode(packageBytes(), "test-2") }
        assertFails { Mapping(listOf(MappingRule("数", "数学"), MappingRule("数", "算数")), emptyList(), emptyList()).validate(2) }
    }
    @Test fun schedulePrioritiesAndNoFallbackForSpecialBlank() {
        val day = LocalDate.parse("2026-10-01"); val normal = Analysis(MaterialKind.TIMETABLE, 2026, 2, listOf(Lesson("1_CN", 4, 1, Names("数学"))))
        val exam = Analysis(MaterialKind.EXAM, 2026, lessons = listOf(Lesson("1_CN", 4, 2, Names("試験"), date = day.toString(), time = "09:00〜10:00")), dates = listOf(day.toString()), classes = listOf("1_CN"))
        val c = Change(day.toString(), "1_CN", "1", "数学", "補講", note = "補講")
        val changes = Analysis(MaterialKind.CHANGES, 2026, changes = listOf(c, c.copy(after = "別の変更", note = "")))
        fun render(a: List<Analysis>, enabled: Boolean = true) = Schedule.slots(day, "1_CN", a, emptyList(), null, null, enabled, false)
        assertEquals("数学", render(listOf(normal))[0].lessons.first().names.subject)
        assertTrue(render(listOf(normal, exam))[0].lessons.isEmpty())
        assertEquals("09:00〜10:00", render(listOf(exam))[1].time)
        assertEquals("補講", render(listOf(normal, changes))[0].lessons.first().names.subject)
        assertEquals(2, render(listOf(normal, changes))[0].changes.size)
        assertEquals("数学", render(listOf(normal, changes), false)[0].lessons.first().names.subject)
        assertTrue(Schedule.slots(LocalDate.parse("2026-04-01"), "1_CN", listOf(normal), emptyList(), null, null, true, false)[0].lessons.isEmpty())
    }
    @Test fun publicEventsAndTimesValidation() {
        val e = EventsPayload("v1", 2026, "a".repeat(64), events = listOf(Event("2026-10-01", "2026-10-01", "休業", "授業なし"))).validate(2026)
        val normal = Analysis(MaterialKind.TIMETABLE, 2026, 2, listOf(Lesson("1_CN", 4, 1, Names("数学"))))
        assertTrue(Schedule.slots(LocalDate.parse("2026-10-01"), "1_CN", listOf(normal), listOf(e), null, null, true, false)[0].lessons.isEmpty())
        assertFails { e.copy(events = listOf(Event("2026-10-01", "2026-10-01", "日曜日授業", "曜日振替"))).validate(2026) }
        val periods = Schedule.normalTimes.mapIndexed { i, value -> PeriodTime(i + 1, value.substringBefore('〜'), value.substringAfter('〜')) }
        assertEquals(8, TimesPayload(1, listOf(DayTimes("2026-10-01", periods))).validate().days[0].periods.size)
        assertFails { TimesPayload(1, listOf(DayTimes("2026-10-01", periods.reversed()))).validate() }
        assertFails { TimesPayload(1, listOf(DayTimes("2026-10-01", periods.map { it.copy(start = "12:00", end = "13:00") }))).validate() }
    }
    @Test fun notificationFirstImportAddEditDeleteAndOrdering() {
        val c = Change("2026-10-01", "1_CN", "1-2", "数", "理")
        val day = LocalDate.parse("2026-10-01")
        assertEquals(0, Schedule.changedSlots(null, listOf(c), "1_CN", day))
        assertEquals(1, Schedule.changedSlots(emptyList(), listOf(c), "1_CN", day))
        assertEquals(1, Schedule.changedSlots(listOf(c), emptyList(), "1_CN", day))
        assertEquals(1, Schedule.changedSlots(listOf(c), listOf(c.copy(after = "英")), "1_CN", day))
        assertEquals(0, Schedule.changedSlots(listOf(c), listOf(c.copy(raw = "layout changed")), "1_CN", day))
        assertEquals(0, Schedule.changedSlots(emptyList(), listOf(c), "2_CN", day))
        assertEquals(0, Schedule.changedSlots(emptyList(), listOf(c), "1_CN", day.plusDays(1)))
    }
    @Test fun gridRejectsReversedAndDuplicateText() {
        val g = Glyph("A", 5.0, 5.0, 3.0, 5.0, 0)
        val box = Box(0.0, 0.0, 30.0, 20.0)
        assertEquals(listOf("AB"), Grid(Page(30.0, 20.0, listOf(g, g.copy(text = "B", x = 9.0, order = 1)), emptyList())).text(box))
        assertFailsWith<ParseFailure> { Grid(Page(30.0, 20.0, listOf(g, g.copy(order = 1)), emptyList())).text(box) }
        assertFailsWith<ParseFailure> { Grid(Page(30.0, 20.0, listOf(g, g.copy(x = 4.0, order = 1)), emptyList())).text(box) }
        assertFailsWith<ParseFailure> { PdfSchoolParser.parse(emptyList(), MaterialKind.EXAM) }
    }
    @Test fun unsafeLinksAreRejected() {
        listOf("javascript:alert(1)", "http://example.com", "https://user:pass@example.com", "https://example.com\\evil", "https://example.com\n").forEach { assertFalse(validLink(it)) }
        assertTrue(validLink("https://example.com/path")); assertTrue(validLink("jrshikoku://route"))
    }
}

internal fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> files.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } } }.toByteArray()
internal fun xlsx(rows: List<List<String>>, formula: Boolean = false, formulaColumn: Int = if (formula) 9 else -1, extra: Map<String, String> = emptyMap(), date1904: Boolean = false): ByteArray {
    val headers = listOf("学 年", "学科・クラス", "月日", "時限", "変更前", "変更後", "教員", "教室", "備考", "曜日")
    fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;")
    val data = (listOf(headers) + rows).mapIndexed { row, fields -> "<row r=\"${row + 1}\">" + fields.mapIndexed { col, v -> val ref = ('A' + col).toString() + (row + 1); if (row > 0 && col == formulaColumn) "<c r=\"$ref\" t=\"str\"><f>TEXT(C${row + 1},\"aaa\")</f><v>${esc(v)}</v></c>" else "<c r=\"$ref\" t=\"inlineStr\"><is><t>${esc(v)}</t></is></c>" }.joinToString("") + "</row>" }.joinToString("")
    return zip((mapOf("xl/workbook.xml" to "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><workbookPr date1904=\"${if (date1904) 1 else 0}\"/><sheets><sheet name=\"時間割変更\" r:id=\"rId1\"/></sheets></workbook>", "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>", "xl/worksheets/sheet1.xml" to "<worksheet><sheetData>$data</sheetData></worksheet>") + extra).mapValues { it.value.toByteArray() })
}
