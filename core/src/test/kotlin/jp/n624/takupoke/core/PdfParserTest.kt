package jp.n624.takupoke.core

import kotlin.test.*

/** Geometry is generated from scratch; it does not reproduce a school's private timetable. */
class PdfParserTest {
    @Test fun returnGridHasSeventeenClassesAndUsesSpecialFirstDayClockOnly() {
        val a = PdfSchoolParser.parse(listOf(returned()), MaterialKind.RETURN)
        assertEquals(17, a.classes.size); assertEquals(5, a.dates.size)
        assertTrue("AI_1" in a.classes && "AI_2" in a.classes)
        fun time(date: String) = Schedule.slots(java.time.LocalDate.parse(date), "1_1", listOf(a), emptyList(), null, null, true, false)[0].time
        assertEquals("08:00〜08:30", time("2026-10-01")); assertEquals(Schedule.normalTimes[0], time("2026-10-02"))
    }
    @Test fun returnWithoutRequiredClockNoteIsRejected() {
        val page = returned()
        assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(page.copy(glyphs = page.glyphs.filter { it.y != 650.0 })), MaterialKind.RETURN) }
    }
    @Test fun returnDateColumnsMustPreserveSpecialFirstDayAndNormalRange() {
        fun withDays(days:List<Int>):Page {
            val page=returned();var order=page.glyphs.maxOf { it.order }+1
            fun text(value:String,x:Double,y:Double)=value.mapIndexed { i,c -> Glyph(c.toString(),x+i*2.5,y,2.0,4.0,order++) }
            val glyphs=page.glyphs.filterNot { it.y in setOf(65.0,625.0,650.0) } +
                days.flatMapIndexed { i,day -> text("10/$day",70.0+i*80,65.0) } +
                text("10月${days.first()}日の時間割は以下のとおり",0.0,625.0) +
                text("10月${days[1]}日~${days.last()}日は通常の授業日どおりの授業時間",0.0,650.0)
            return page.copy(glyphs=glyphs)
        }
        val valid=PdfSchoolParser.parse(listOf(withDays(listOf(8,9,10,11,12))),MaterialKind.RETURN)
        assertEquals("08:00",valid.specialTimes.first().periods.first().start)
        assertEquals("2026-10-08",valid.specialTimes.first().date)
        for(days in listOf(listOf(8,9,7,10,12),listOf(9,8,10,11,12))) {
            val error=assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(withDays(days)),MaterialKind.RETURN) }
            assertEquals("日付順",error.stage)
        }
    }
    @Test fun ordinaryGridThreeFieldsAndFortyPeriods() {
        val a = PdfSchoolParser.parse(listOf(ordinary()), MaterialKind.TIMETABLE)
        assertEquals(2026, a.schoolYear); assertEquals(2, a.term)
        assertEquals(setOf("1_CN", "1_ES"), a.classes.toSet())
        assertEquals(2, a.lessons.size)
        assertEquals(Names("Math", "Teacher", "Room"), a.lessons.first().names)
        assertEquals(1, a.lessons.first().weekday); assertEquals(1, a.lessons.first().period)
    }
    @Test fun conflictingHeadingYearsAreRejectedButConsistentDuplicatesAreAllowed() {
        fun heading(page:Page,text:String):Page = page.copy(glyphs=page.glyphs.filterNot { it.y==10.0 }+text.mapIndexed { i,c -> Glyph(c.toString(),i*2.0,10.0,2.0,4.0,page.glyphs.size+i) })
        for(title in listOf("令和8年度令和9年度後期時間割","2027年度令和8年度後期時間割","令和8年度令和0年度後期時間割","令和8年度令和100年度後期時間割","令和8年度12026年度後期時間割"))
            assertEquals("年度",assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(heading(ordinary(),title)),MaterialKind.TIMETABLE) }.stage)
        for(title in listOf("令和8年度令和8年度後期時間割","2026年度令和8年度後期時間割"))
            assertEquals(2026,PdfSchoolParser.parse(listOf(heading(ordinary(),title)),MaterialKind.TIMETABLE).schoolYear)
        val pages=(1..6).map(::exam).toMutableList();pages[2]=heading(pages[2],"令和8年度令和9年度試験時間割")
        assertEquals("年度",assertFailsWith<ParseFailure> { PdfSchoolParser.parse(pages,MaterialKind.EXAM) }.stage)
    }
    @Test fun unsupportedUnicodeFiscalYearCannotDisappearDuringNormalization() {
        fun heading(page:Page,text:String):Page = page.copy(glyphs=page.glyphs.filterNot { it.y==10.0 }+text.mapIndexed { i,c -> Glyph(c.toString(),i*2.0,10.0,2.0,4.0,page.glyphs.size+i) })
        for(extra in listOf("令和Ⅸ年度","令和௰年度","ⅯⅯⅩⅩⅦ年度","令和九年度","令和年度","２０２７　年度")) {
            val title="令和8年度${extra}後期時間割"
            assertEquals("年度",assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(heading(ordinary(),title)),MaterialKind.TIMETABLE) }.stage)
            val pages=(1..6).map(::exam).toMutableList();pages[2]=heading(pages[2],"令和8年度${extra}試験時間割")
            assertEquals("年度",assertFailsWith<ParseFailure> { PdfSchoolParser.parse(pages,MaterialKind.EXAM) }.stage)
        }
        for(title in listOf("令和８年度２０２６年度後期時間割","令和8年度令和８年度後期時間割","２０２６　年度令和８　年度後期時間割"))
            assertEquals(2026,PdfSchoolParser.parse(listOf(heading(ordinary(),title)),MaterialKind.TIMETABLE).schoolYear)
    }
    @Test fun ordinaryRejectsAmbiguousSemesterAndMissingGrid() {
        val page = ordinary()
        assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(page.copy(lines = emptyList())), MaterialKind.TIMETABLE) }
        val extra = Glyph("前期", 0.0, 20.0, 10.0, 4.0, page.glyphs.size)
        assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(page.copy(glyphs = page.glyphs + extra)), MaterialKind.TIMETABLE) }
    }
    @Test fun sixPageExamRequiresSeventeenClassesAndFiveDates() {
        val pages = (1..6).map(::exam)
        val a = PdfSchoolParser.parse(pages, MaterialKind.EXAM)
        assertEquals(17, a.classes.size); assertEquals(5, a.dates.size)
        assertEquals(17, a.lessons.size); assertEquals("08:00〜08:30", a.lessons.first().time)
        assertTrue("AI_1" in a.classes && "AI_2" in a.classes)
        assertFailsWith<ParseFailure> { PdfSchoolParser.parse(pages.take(5), MaterialKind.EXAM) }
        assertFailsWith<ParseFailure> { PdfSchoolParser.parse(pages.dropLast(1) + pages[0], MaterialKind.EXAM) }
    }
    @Test fun seventeenCountCannotHideAnUnknownExamClass() {
        val pages=(1..6).map(::exam).toMutableList()
        // Header 3-ES spans four independently acquired glyphs on page three.
        val p=pages[2];val header=p.glyphs.filter { it.y==60.0 && it.x in 160.0..172.0 }.sortedBy { it.x }
        assertEquals("3-ES",header.joinToString("") { it.text })
        val wrong=header.takeLast(2).map { it.order }.toSet()
        pages[2]=p.copy(glyphs=p.glyphs.map { if(it.order in wrong)it.copy(text="X")else it })
        val error=assertFailsWith<ParseFailure> { PdfSchoolParser.parse(pages,MaterialKind.EXAM) }
        assertEquals("クラス数・授業数",error.stage)
    }
    @Test fun strictSpecialClocksRejectOverlapAndReversedPeriods() {
        fun clock(page:Page,value:String):Page = page.copy(glyphs=page.glyphs.filterNot { it.y==740.0 } + value.mapIndexed { i,c -> Glyph(c.toString(),i*2.5,740.0,2.0,4.0,page.glyphs.size+i) })
        for(value in listOf("2時限目08:15〜09:00","2時限目07:00〜07:30")) {
            val pages=(1..6).map { clock(exam(it),value) }
            assertEquals("授業時刻の順序・重複",assertFailsWith<ParseFailure> { PdfSchoolParser.parse(pages,MaterialKind.EXAM) }.stage)
            assertEquals("授業時刻の順序・重複",assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(clock(returned(),value)),MaterialKind.RETURN) }.stage)
        }
    }
    @Test fun examBlankCellsStayBlank() {
        val a = PdfSchoolParser.parse((1..6).map(::exam), MaterialKind.EXAM)
        assertEquals(1, a.lessons.count { it.className == "1_1" })
        val slots = Schedule.slots(java.time.LocalDate.parse("2026-10-02"), "1_1", listOf(a), emptyList(), null, null, true, false)
        assertTrue(slots.all { it.lessons.isEmpty() })
        assertEquals("08:00〜08:30", slots[0].time)
        assertNull(slots[6].time)
    }
    @Test fun ordinaryRequiresTitleAndValidEraInTopEighth() {
        val page = ordinary()
        assertEquals("P04", assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(page.copy(glyphs = page.glyphs.filter { it.y != 10.0 } + Glyph("令和8年度後期", 0.0, 10.0, 30.0, 4.0, page.glyphs.size))), MaterialKind.TIMETABLE) }.code)
        assertEquals("P03", assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(page.copy(glyphs = page.glyphs.filter { it.y != 10.0 } + Glyph("令和0年度後期時間割", 0.0, 10.0, 40.0, 4.0, page.glyphs.size))), MaterialKind.TIMETABLE) }.code)
        assertEquals("P03", assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(page.copy(glyphs = page.glyphs.map { if (it.y == 10.0) it.copy(y = 150.0) else it })), MaterialKind.TIMETABLE) }.code)
    }
    @Test fun ordinaryFailureIncludesCellPositionWithoutSourceText() {
        val page = ordinary(); val extra = Glyph("架空", 42.0, 126.0, 4.0, 2.0, page.glyphs.size)
        val error = assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(page.copy(glyphs = page.glyphs + extra)), MaterialKind.TIMETABLE) }
        assertEquals("P17", error.code); assertEquals(1, error.page); assertEquals(1, error.classRow); assertEquals(1, error.day); assertEquals(1, error.period)
        assertFalse(error.message.orEmpty().contains("架空"))
    }
    @Test fun ordinaryAndSpecialNeverShiftRoomWhenTeacherLineIsMissing() {
        val ordinary=ordinary();assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(ordinary.copy(glyphs=ordinary.glyphs.filterNot { it.y==113.0 })),MaterialKind.TIMETABLE) }
        val pages=(1..6).map(::exam).toMutableList();val first=pages[0];pages[0]=first.copy(glyphs=first.glyphs.filterNot { it.y==128.0 })
        assertFailsWith<ParseFailure> { PdfSchoolParser.parse(pages,MaterialKind.EXAM) }
    }
    @Test fun loneTeacherOrRoomCannotBeRelabeledSubject() {
        val p=ordinary();for(rows in listOf(setOf(106.0,113.0),setOf(106.0,120.0)))assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(p.copy(glyphs=p.glyphs.filterNot { it.y in rows })),MaterialKind.TIMETABLE) }
    }
    @Test fun outsideBodyNotesCannotCalibrateTeacherOnlyCellAsSubject() {
        val original=ordinary();val glyphs=original.glyphs.filterNot { it.y in setOf(106.0,120.0,137.0) }.toMutableList()
        for((row,value) in listOf("NoteA","NoteB","NoteC").withIndex())value.forEachIndexed { i,c->glyphs+=Glyph(c.toString(),351.0+i*.8,213.0+row*6,0.6,4.0,glyphs.size) }
        val lines=original.lines+listOf(Line(350.0,200.0,360.0,200.0),Line(350.0,230.0,360.0,230.0),Line(350.0,200.0,350.0,230.0),Line(360.0,200.0,360.0,230.0))
        assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(original.copy(glyphs=glyphs,lines=lines)),MaterialKind.TIMETABLE) }
    }
    @Test fun strictNeverAssignsInlineRoleLabelsByTheirLineOrder() {
        val p=ordinary();val extra=mutableListOf<Glyph>();var order=p.glyphs.size
        listOf("教員:Teacher","科目:Math","教室:Room").forEachIndexed { i,value -> value.forEachIndexed { j,c -> extra+=Glyph(c.toString(),41.0+j*.5,106.0+i*7,.4,4.0,order++) } }
        val changed=p.copy(glyphs=p.glyphs.filterNot { it.y in setOf(106.0,113.0,120.0) }+extra)
        assertEquals("P17",assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(changed),MaterialKind.TIMETABLE) }.code)
    }
    @Test fun strictRejectsEveryKnownAliasAfterAParallelSeparator() {
        for(label in RecoveryRoles.byLabel.keys) {
            val p=ordinary();val extra=mutableListOf<Glyph>();var order=p.glyphs.size
            listOf("架空A・$label:架空B","架空C・架空D","架空E・架空F").forEachIndexed { i,value -> value.forEachIndexed { j,c -> extra+=Glyph(c.toString(),41.0+j*.2,106.0+i*7,.18,4.0,order++) } }
            val changed=p.copy(glyphs=p.glyphs.filterNot { it.y in setOf(106.0,113.0,120.0) }+extra)
            assertEquals("P17",assertFailsWith<ParseFailure> { PdfSchoolParser.parse(listOf(changed),MaterialKind.TIMETABLE) }.code)
            val pages=(1..6).map(::exam).toMutableList();val first=pages.first()
            pages[0]=first.copy(glyphs=first.glyphs.map { if(it.x==52.0 && it.y==120.0)it.copy(text="架空A・$label:架空B",width=10.0)else it })
            assertFailsWith<ParseFailure> { PdfSchoolParser.parse(pages,MaterialKind.EXAM) }
        }
    }
    @Test fun repeatedHalfwidthMarksCollapse() {
        assertEquals("ｺﾞ", PdfSchoolParser.collapseMarks("ｺﾞﾞﾞ"))
        assertEquals("Aーー", PdfSchoolParser.collapseMarks("Aーー"))
    }
}
private class Geometry(val width: Double = 500.0, val height: Double = 1000.0) {
    val glyphs = mutableListOf<Glyph>(); val lines = mutableListOf<Line>()
    fun text(value: String, x: Double, y: Double, step: Double = 2.5) { value.forEachIndexed { i, c -> glyphs += Glyph(c.toString(), x + i * step, y, 2.0, 4.0, glyphs.size) } }
    fun horizontal(y: Double, left: Double = 0.0, right: Double = width) { lines += Line(left, y, right, y) }
    fun vertical(x: Double, top: Double, bottom: Double) { lines += Line(x, top, x, bottom) }
    fun page() = Page(width, height, glyphs, lines)
}
private fun ordinary(): Page = Geometry(450.0).apply {
    text("令和8年度後期時間割", 0.0, 10.0)
    for (col in 0 until 40) text((col % 8 + 1).toString(), 44.0 + col * 10, 82.0)
    for (y in listOf(80.0, 100.0, 160.0)) horizontal(y, right = 440.0)
    horizontal(130.0, left = 20.0, right = 440.0)
    for (x in listOf(0.0, 20.0) + (40..440 step 10).map(Int::toDouble)) vertical(x, 80.0, 160.0)
    text("1", 9.0, 132.0); text("CN", 27.0, 118.0); text("ES", 27.0, 143.0)
    text("Math", 42.0, 106.0, 1.5); text("Teacher", 41.0, 113.0, 1.0); text("Room", 42.0, 120.0, 1.5)
    text("English", 52.0, 137.0, 1.0)
}.page()
private fun exam(number: Int): Page = Geometry(400.0).apply {
    text("令和8年度試験時間割", 0.0, 10.0)
    val classes = if (number == 1) listOf("1-1", "1-2", "1-3") else if (number == 6) listOf("1年", "2年") else listOf("${number}-CN", "${number}-ES", "${number}-IT")
    classes.forEachIndexed { i, name -> text(name, 70.0 + i * 90, 60.0) }
    for (col in 0 until classes.size * 6) text((col % 6 + 1).toString(), 54.0 + col * 15, 82.0)
    for (x in listOf(0.0, 50.0) + (65..50 + classes.size * 90 step 15).map(Int::toDouble)) vertical(x, 80.0, 500.0)
    for (y in listOf(80.0, 100.0, 180.0, 260.0, 340.0, 420.0, 500.0)) horizontal(y, right = 50.0 + classes.size * 90)
    (0..4).forEach { day -> text("10月${day + 1}日", 5.0, 125.0 + day * 80) }
    classes.indices.forEach { col -> text("Test", 52.0 + col * 90, 120.0, 2.0) }; text("Teach",52.0,128.0,2.0);text("Room",52.0,136.0,2.0)
    (1..6).forEach { p -> text("${p}時限目${7 + p}:00〜${7 + p}:30", 0.0, 700.0 + p * 20) }
}.page()
private fun returned(): Page = Geometry(450.0).apply {
    text("令和8年度試験返却時間割", 0.0, 10.0)
    (0..4).forEach { day -> text("10/${day + 1}", 70.0 + day * 80, 65.0) }
    (0 until 40).forEach { col -> text((col % 8 + 1).toString(), 54.0 + col * 10, 82.0) }
    (0..17).forEach { row -> horizontal(100.0 + row * 20, right = 450.0) }; horizontal(80.0)
    (listOf(0.0, 40.0) + (50..450 step 10).map(Int::toDouble)).forEach { vertical(it, 80.0, 440.0) }
    val labels = listOf("1", "2", "3") + (2..5).flatMap { listOf("CN", "ES", "IT") } + listOf("1", "2")
    labels.forEachIndexed { i, value -> text(value, 47.0, 108.0 + i * 20, 1.5) }
    listOf("1" to 128.0, "2" to 188.0, "3" to 248.0, "4" to 308.0, "5" to 368.0, "AI" to 418.0).forEach { (value, y) -> text(value, 20.0, y) }
    text("Math", 51.0, 103.0, 1.5);text("Teach",51.0,109.0,1.0);text("Room",51.0,115.0,1.5)
    text("10月1日の時間割は以下のとおり", 0.0, 625.0)
    text("10月2日~5日は通常の授業日どおりの授業時間", 0.0, 650.0)
    (1..8).forEach { p -> text("${p}時限目${7 + p}:00〜${7 + p}:30", 0.0, 700.0 + p * 20) }
}.page()
