package jp.n624.takupoke.core

import java.time.LocalDate
import kotlin.test.*

/** Entirely generated fixtures; no school documents, network or personal data. */
class ParityRegressionTest {
    private val day = LocalDate.parse("2026-10-01")
    private val normal = Analysis(MaterialKind.TIMETABLE, 2026, 2,
        lessons = listOf(Lesson("1_CN", 4, 1, Names("架空科目A"))))
    private fun projection(changes: List<Change>, international: Boolean = false, extra: List<Analysis> = emptyList()) =
        ScheduleProjection(listOf(normal, Analysis(MaterialKind.CHANGES, 2026, changes = changes)) + extra,
            emptyList(), null, null, true, international)
    private fun change(after: String, period: String = "1") = Change(day.toString(), "1_CN", period, "架空科目A", after)

    @Test fun hiddenChangeStillSuppressesOriginalLessonAndChangeList() {
        val hidden = change("留架空科目B")
        val p = projection(listOf(hidden))
        assertTrue(p.slots(day, "1_CN")[0].lessons.isEmpty())
        assertTrue(p.blocks(day, "1_CN").isEmpty())
        assertTrue(p.filteredChanges(setOf("1_CN"), "全件", day, Schedule.monday(day)).isEmpty())
        assertEquals("留架空科目B", projection(listOf(hidden), true).slots(day, "1_CN")[0].lessons.single().names.subject)
        assertEquals("架空科目A", Schedule.slots(day, "1_CN", listOf(normal), emptyList(), null, null, false, false)[0].lessons.single().names.subject)
        val visible = change("架空科目C")
        assertEquals("架空科目C", projection(listOf(visible, hidden)).slots(day, "1_CN")[0].lessons.single().names.subject)
    }

    @Test fun integerPeriodSpellingsMatchIosAndKeepUnsupportedNotationInList() {
        for (value in listOf("01", "+1", "００１")) assertEquals(listOf(1), change("", value).gridPeriods())
        assertEquals(listOf(1, 2), change("", "+01~02").gridPeriods())
        assertEquals(listOf(1, 3), change("", "01,+3").periods())
        for (value in listOf("1,,2", "1,01", "2,1", "1-2", "9", "", "1~1")) assertTrue(change("", value).gridPeriods().isEmpty(), value)
    }

    @Test fun missingPeriodKeepsChangesWhileDateWhitespaceIsAccepted() {
        val bytes = xlsx(listOf(listOf("1", "CN", "10 / 2", "", "架空科目A", "架空科目B")))
        val parsed = XlsxParser.parse(bytes, 2026).changes.single()
        assertEquals("2026-10-02", parsed.date); assertEquals("", parsed.period)
        assertEquals("2026-10-02", XlsxParser.date("10月 2日", 2026).toString())
        val files = Archives.read(bytes).toMutableMap()
        files["xl/worksheets/sheet1.xml"] = files.getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8).replace("<t>時限</t>", "<t>別の列</t>").toByteArray()
        assertEquals("", XlsxParser.parse(zip(files), 2026).changes.single().period)
        val source = xlsx(listOf(listOf("1", "CN", "10/2", "+01", "", "架空科目B")))
        assertEquals("+01", XlsxParser.parse(source, 2026).changes.single().period)
    }

    @Test fun xlsxRejectsWrongNamespaceTypeSettingsAndHeaderWithPreciseErrors() {
        val files = Archives.read(xlsx(listOf(listOf("1", "CN", "10/2", "1", "", "架空科目B"))))
        fun changed(path: String, from: String, to: String): ByteArray = zip(files + (path to files.getValue(path).toString(Charsets.UTF_8).replace(from, to).toByteArray()))
        assertFailsWith<XlsxFailure> { XlsxParser.parse(changed("xl/workbook.xml", "spreadsheetml/2006/main", "spreadsheetml/invalid"), 2026) }
        assertFailsWith<XlsxFailure> { XlsxParser.parse(changed("xl/_rels/workbook.xml.rels", "/worksheet\"", "/image\""), 2026) }
        assertFailsWith<XlsxFailure> { XlsxParser.parse(changed("xl/workbook.xml", "date1904=\"0\"", "date1904=\"bogus\""), 2026) }
        assertFailsWith<XlsxFailure> { XlsxParser.parse(changed("xl/worksheets/sheet1.xml", "学 年", "学年"), 2026) }
        assertFailsWith<XlsxFailure> { XlsxParser.parse(changed("xl/worksheets/sheet1.xml", "</sheetData>", "</sheetData><sheetData/>"), 2026) }
        val invalid = assertFailsWith<XlsxFailure> { XlsxParser.parse(changed("xl/worksheets/sheet1.xml", "10/2", "2/30"), 2026) }
        assertEquals(2, invalid.row); assertTrue(invalid.message.orEmpty().contains("月日"))
        assertFalse(invalid.message.orEmpty().contains("架空科目"))
    }

    @Test fun xlsxOutOfRangeColumnCannotOverflowIntoGradeColumn() {
        val source=xlsx(listOf(listOf("1","CN","10/2","1","架空科目A","架空科目B")))
        assertEquals("1_CN",XlsxParser.parse(source,2026).changes.single().className)
        val original=Archives.read(source)
        for(column in listOf("MWLQKWW","DY","XFD")) {
            val changed=original.toMutableMap()
            changed["xl/worksheets/sheet1.xml"]=original.getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8).replace("r=\"A2\"","r=\"${column}2\"").toByteArray()
            assertFailsWith<XlsxFailure> { XlsxParser.parse(zip(changed),2026) }
        }
    }

    @Test fun xlsxReadsOnlyNeededPartsAllowsDirectoriesAndBoundsDeclaredSize() {
        val rows = listOf(listOf("1", "CN", "10/2", "1", "", "架空科目B"))
        val files = Archives.read(xlsx(rows))
        assertEquals(1, XlsxParser.parse(zip(files + mapOf("xl/" to byteArrayOf(), "xl/media/unused.bin" to ByteArray(9 * 1024 * 1024))), 2026).changes.size)
        assertFailsWith<XlsxFailure> { XlsxParser.parse(zip(files + ("xl/media/unused.bin" to ByteArray(33 * 1024 * 1024))), 2026) }
        assertFails { Archives.withArchive(zip(mapOf("a/../b" to byteArrayOf()))) { } }
        assertFails { Archives.withArchive(zip(mapOf("a/./b" to byteArrayOf()))) { } }
    }

    @Test fun archiveRecoveryOnlyDeletesInactiveOwnedTemporaryCopies() {
        val directory = kotlin.io.path.createTempDirectory("takupoke-owned-test-").toFile()
        try {
            Archives.configureTemporaryDirectory(directory)
            val original = java.io.File(directory, "CHANGES-normal.xlsx").apply { writeText("架空原本") }
            val stale = java.io.File(directory, "takupoke-archive-abandoned.zip").apply { writeText("架空一時コピー") }
            Archives.withArchive(zip(mapOf("a" to byteArrayOf(1)))) { archive ->
                val active = directory.listFiles()!!.single { it.name.startsWith("takupoke-archive-") && it != stale }
                Archives.cleanupTemporaryArchives()
                assertFalse(stale.exists()); assertTrue(active.exists()); assertTrue(original.exists())
                assertContentEquals(byteArrayOf(1), archive.read("a"))
            }
            assertEquals(listOf(original), directory.listFiles()!!.toList())
            // The entire no-backup materials directory remains eligible for half-year deletion.
            assertTrue(directory.deleteRecursively()); assertFalse(directory.exists())
        } finally { Archives.configureTemporaryDirectory(null); directory.deleteRecursively() }
    }

    @Test fun archiveChecksSelectedCrcAndRejectsSymlinkMetadata() {
        val content = zip(mapOf("a" to "架空データ".toByteArray()))
        fun central(bytes: ByteArray): Int = (0..bytes.size - 4).first { i -> bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() }
        val crc = content.copyOf(); val header = central(crc)
        crc[header + 16] = (crc[header + 16].toInt() xor 1).toByte()
        assertFails { Archives.read(crc) }
        val symlink = content.copyOf()
        symlink[header + 5] = 3 // Unix creator platform.
        symlink[header + 41] = 0xa0.toByte() // S_IFLNK in upper 16 bits of external attributes.
        assertFails { Archives.withArchive(symlink) { } }
    }

    @Test fun xlsxDateCellTypeAndRowOrderingMatchIos() {
        val files = Archives.read(xlsx(listOf(listOf("1", "CN", "10/2", "1", "", "架空科目B"))))
        val sheet = files.getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8)
        val dateTyped = sheet.replace("<c r=\"C2\" t=\"inlineStr\"><is><t>10/2</t></is></c>", "<c r=\"C2\" t=\"d\"><v>2026-10-02</v></c>")
        assertEquals("2026-10-02", XlsxParser.parse(zip(files + ("xl/worksheets/sheet1.xml" to dateTyped.toByteArray())), 2026).changes.single().date)
        val header = Regex("<row r=\"1\">.*?</row>").find(sheet)!!.value
        val row = Regex("<row r=\"2\">.*?</row>").find(sheet)!!.value
        val reversed = sheet.replace(header + row, row + header)
        assertFailsWith<XlsxFailure> { XlsxParser.parse(zip(files + ("xl/worksheets/sheet1.xml" to reversed.toByteArray())), 2026) }
    }

    @Test fun disjointChangeDetailKeepsKnownRangeWhenAnotherRangeIsUnknown() {
        val exam = Analysis(MaterialKind.EXAM, 2026, lessons = listOf(Lesson("1_CN", 4, 1, Names("架空試験"), date = day.toString(), time = "08:00〜08:30")),
            classes = listOf("1_CN"), dates = listOf(day.toString()))
        val c = change("架空科目B", "1,3")
        assertEquals(listOf("08:00〜08:30", "未確認"), projection(listOf(c), extra = listOf(exam)).changeDetail(c).timeRanges)
        assertNull(projection(listOf(c), extra = listOf(exam)).changeDetail(c).time)
    }

    @Test fun sourceLinePreservesMixedSizeTextAndFragmentOrderFailsClosed() {
        val box = Box(0.0, 0.0, 100.0, 50.0)
        val glyphs = listOf(Glyph("架", 5.0, 5.0, 5.0, 10.0, 0, 1), Glyph("空", 10.0, 8.0, 3.0, 7.0, 1, 1),
            Glyph("教", 5.0, 20.0, 5.0, 10.0, 2, 2), Glyph("室", 10.0, 20.0, 5.0, 10.0, 3, 2))
        fun text(values: List<Glyph>) = Grid(Page(100.0, 50.0, values, emptyList())).text(box)
        assertEquals(listOf("架空", "教室"), text(glyphs))
        // Disjoint fragments from separate text matrices may join; overprinted or contained ranges may not.
        assertEquals(listOf("架空", "教室"), text(glyphs.map { if (it.order == 3) it.copy(sourceLine = 3) else it }))
        val error = assertFailsWith<ParseFailure> { text(glyphs + Glyph("重", 5.0, 20.0, 5.0, 10.0, 4, 3)) }
        assertEquals("P20", error.code)
        assertEquals("P13", assertFailsWith<ParseFailure> { text(glyphs.map { if (it.order == 1) it.copy(order = 0) else it }) }.code)
        assertEquals("P13", assertFailsWith<ParseFailure> { text(glyphs.map { if (it.order == 1) it.copy(sourceLine = null) else it }) }.code)
    }
}
