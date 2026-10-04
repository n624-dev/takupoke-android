package jp.n624.takupoke.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import jp.n624.takupoke.core.*
import java.time.Instant
import java.time.LocalDate

private fun AppState.classes() = listOf(settings.primaryClass, settings.additionalClass).filter(String::isNotEmpty).distinct()
private fun AppState.projection(changes: Boolean) = ScheduleProjection(analyses, events, mapping, times, changes, settings.international)
@Composable private fun UnreflectedMaterials(state: AppState) {
    val stale = state.materials.filter { record -> record.analysis != null &&
        (record.parsedDigest != record.digest || record.analysis.parserVersion != PARSER_VERSION || record.recoveryJob?.let { it.pdfHash == record.digest && it.state != RecoveryJobState.ADOPTED } == true) }
    if (stale.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Text(stale.joinToString("・") { it.kind.title } + "の新しい資料をまだ反映できていません。前回の正常結果を表示しています。", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
@Composable private fun schoolNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { now = Instant.now(); kotlinx.coroutines.delay(15_000) } } }
    return now
}
private fun accessibility(day: LocalDate, slot: Slot): String = listOf(day.toString(), slot.lessons.firstOrNull()?.className?.let(::displayClass).orEmpty(), "${slot.period}〜${slot.endPeriod}限", slot.type, slot.time ?: "時刻未確認").plus(slot.lessons.flatMap { l -> listOf("科目、${l.names.subjectFull.ifEmpty { l.names.subject }}", "教員、${l.names.teacherFull.ifEmpty { l.names.teacher }}", "教室、${l.names.roomFull.ifEmpty { l.names.room }}") }).joinToString("、").let(::displayContinuous)
@Composable fun HomeScreen(state: AppState, account: () -> Unit, classes: () -> Unit, select: (Pair<LocalDate, Slot>) -> Unit, timetable: () -> Unit, change: ((Settings) -> Settings) -> Unit = {}) {
    var editing by remember { mutableStateOf<LinkItem?>(null) }
    val now = schoolNow(); val date = now.atZone(schoolZone).toLocalDate(); val projection = state.projection(true)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UnreflectedMaterials(state)
        if (state.updates.isNotEmpty()) TextButton(onClick = account) { Text(listOf("links-revision" to "一覧", "mapping-revision" to "名称データ", "timetable-times-revision" to "授業時刻").filter { it.first in state.updates }.joinToString("・") { it.second } + "に更新があります") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("今日の予定", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton(onClick = timetable) { Text("時間割を見る") } }
        Text("${date.monthValue}月${date.dayOfMonth}日（${"月火水木金土日"[date.dayOfWeek.value - 1]}）")
        Schedule.events(date, state.events).map { it.title }.takeIf { it.isNotEmpty() }?.let { Text(displayKana(it.joinToString("・")), style = MaterialTheme.typography.titleMedium) }
        if (state.settings.primaryClass.isEmpty()) TextButton(onClick = classes) { Text("クラスを選択") }
        else {
            if (state.analyses.none { it.kind == MaterialKind.CHANGES }) Text("時間割変更の解析結果がありません。")
            if (!projection.eventsLoaded(date)) Text("学校行事は未取得です。")
            state.classes().forEach { cls ->
                Text(displayClass(cls), fontWeight = FontWeight.SemiBold)
                val missing = projection.missing(date, cls); missing.forEach { Text(it) }
                val blocks = projection.blocks(date, cls)
                if (blocks.isEmpty() && missing.isEmpty() && projection.eventsLoaded(date) && state.analyses.any { it.kind == MaterialKind.CHANGES }) Text("授業はありません。")
                blocks.forEach { (slot, _) ->
                    val active = projection.inProgress(date, slot, now)
                    OutlinedCard(onClick = { select(date to slot) }, modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = accessibility(date, slot) + if (active) "、授業中" else "" }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (active) Box(Modifier.width(3.dp).height(48.dp).background(MaterialTheme.colorScheme.primary))
                            Text(slot.time?.replace("〜", "\n～\n") ?: "時刻未確認", Modifier.padding(end = 12.dp), textAlign = TextAlign.Center)
                            Column(Modifier.weight(1f)) {
                                if (slot.changes.isNotEmpty()) Text(slot.type, color = Color(0xFFFF9500))
                                if (active) Text("授業中", color = MaterialTheme.colorScheme.primary)
                                slot.lessons.forEach { lesson ->
                                    if (slot.type != "休講") Text(displayContinuous(lesson.names.subject.ifEmpty { "変更を確認" }), style = MaterialTheme.typography.titleMedium)
                                    Text(listOf(lesson.names.teacher, lesson.names.room).filter(String::isNotEmpty).joinToString("・").let(::displayMetadata))
                                }
                            }
                        }
                    }
                }
            }
        }
        val visible = visibleLinks(state.links, state.settings)
        val favorites = visible.filter { it.id in state.settings.favorites }
        if (favorites.isNotEmpty()) Text("お気に入り", style = MaterialTheme.typography.titleMedium)
        favorites.forEach { link -> EditableLink(link, state.settings, { editing = link }, change) }
        val recommended = recommendedLinks(state.links, state.settings)
        if (recommended.isNotEmpty()) Text("おすすめ", style = MaterialTheme.typography.titleMedium)
        recommended.forEach { link -> EditableLink(link, state.settings, { editing = link }, change) }
    }
    editing?.let { LinkEditDialog(it, state.settings, { editing = null }, change) }
}
@Composable fun TimetableScreen(state: AppState, classes: () -> Unit, change: ((Settings) -> Settings) -> Unit = {}, todayRequest: String? = null, select: (Pair<LocalDate, Slot>) -> Unit) {
    val now = schoolNow(); val currentDay = now.atZone(schoolZone).toLocalDate()
    var mondayText by rememberSaveable { mutableStateOf(Schedule.week(currentDay).toString()) }
    var anchorText by rememberSaveable { mutableStateOf(currentDay.toString()) }
    var changes by rememberSaveable { mutableStateOf(state.settings.includesChanges) }
    var pickingClasses by remember { mutableStateOf(false) }
    LaunchedEffect(todayRequest) { if (todayRequest != null) { mondayText = Schedule.monday(today()).toString(); anchorText = today().toString() } }
    LaunchedEffect(state.settings.includesChanges) { changes = state.settings.includesChanges }
    val monday = LocalDate.parse(mondayText); val projection = state.projection(changes); val selectedClasses = state.classes()
    val bounds = projection.weekBounds(LocalDate.parse(anchorText), selectedClasses)
    val context = androidx.compose.ui.platform.LocalContext.current
    val days = projection.displayedDays(monday, selectedClasses)
    val changeClasses = if (state.settings.useTimetableClasses) selectedClasses.toSet() else state.settings.changeClasses.ifEmpty { selectedClasses.toSet() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UnreflectedMaterials(state)
        Text("表示クラス", style = MaterialTheme.typography.titleMedium)
        ClassSelectionRow(state.settings, classes)
        Text("週の時間割", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { mondayText = monday.minusWeeks(1).toString() }, enabled = monday.minusWeeks(1) in bounds) { Text("前週") }
            TextButton(onClick = {
                android.app.DatePickerDialog(context, { _, year, month, day -> val candidate = Schedule.monday(LocalDate.of(year, month + 1, day)); if (candidate in bounds) mondayText = candidate.toString() }, monday.year, monday.monthValue - 1, monday.dayOfMonth).apply {
                    datePicker.minDate = bounds.first.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    datePicker.maxDate = bounds.last.plusDays(6).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    setTitle("週を選ぶ")
                    setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "キャンセル") { dialog, _ -> dialog.cancel() }
                    show(); getButton(android.content.DialogInterface.BUTTON_POSITIVE).text = "この週へ移動"
                }
            }) { val end = monday.plusDays(6); Text("${monday.monthValue}/${monday.dayOfMonth}〜${end.monthValue}/${end.dayOfMonth}") }
            TextButton(onClick = { mondayText = monday.plusWeeks(1).toString() }, enabled = monday.plusWeeks(1) in bounds) { Text("翌週") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!changes, { changes = false; change { it.copy(includesChanges = false) } }, label = { Text("通常") }); FilterChip(changes, { changes = true; change { it.copy(includesChanges = true) } }, label = { Text("変更込み") }) }
        if (!projection.weekEventsLoaded(monday)) Text("学校行事は未取得です。")
        if (selectedClasses.isEmpty()) TextButton(onClick = classes) { Text("クラスを選択") }
        else WeekGrid(state, projection, days, selectedClasses, currentDay, select)
        Text("週の行事", style = MaterialTheme.typography.titleMedium)
        (0..6).forEach { offset -> val day = monday.plusDays(offset.toLong()); val titles = Schedule.events(day, state.events).map { it.title }; if (titles.isNotEmpty()) Text("${day.monthValue}/${day.dayOfMonth}　${displayKana(titles.joinToString("・"))}") }
        Text("時間割変更", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("今日以降", "この週", "全件").forEach { range -> FilterChip(state.settings.changeRange == range, { change { it.copy(changeRange = range) } }, label = { Text(range) }) } }
        TextButton(onClick = { pickingClasses = true }) { Text("変更一覧の対象クラス") }
        val records = projection.filteredChanges(changeClasses, state.settings.changeRange, currentDay, monday)
        if (state.analyses.none { it.kind == MaterialKind.CHANGES }) Text("時間割変更の解析結果がありません。") else if (records.isEmpty()) Text("時間割変更はありません。")
        records.forEach { record -> OutlinedCard(onClick = { select(LocalDate.parse(record.date) to projection.changeDetail(record)) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text("${record.date}　${displayClass(record.className)}　${if (record.period.isEmpty()) "時限未記載" else displayChangePeriod(record)}　${record.type}")
            Text("${displayContinuous(projection.changeBefore(record).subject)} → ${displayContinuous(record.after)}")
            if (record.note.isNotEmpty()) Text(displayContinuous(record.note))
        } } }
        Spacer(Modifier.height(12.dp))
    }
    if (pickingClasses) ChangeClassPicker(state, changeClasses, { pickingClasses = false }, change)
}

@Composable private fun WeekGrid(state: AppState, projection: ScheduleProjection, days: List<LocalDate>, classes: List<String>, today: LocalDate, select: (Pair<LocalDate, Slot>) -> Unit) {
    val density = LocalDensity.current; val scale = density.fontScale.coerceAtLeast(1f); val measurer = rememberTextMeasurer()
    val textStyle = TextStyle(fontSize = (11f * scale / density.fontScale).sp, textAlign = TextAlign.Center)
    val metadataStyle = textStyle.copy(fontSize = (9f * scale / density.fontScale).sp)
    val headerStyle = textStyle.copy(fontSize = (14f * scale / density.fontScale).sp)
    val layouts = days.associateWith { day -> classes.map { cls -> projection.blocks(day, cls) } }
    val fullDays = days.associateWith { projection.fullDayTitle(it, classes) }
    val allFull = fullDays.values.all { it != null }
    val commonTimes = (1..8).map { period ->
        val slots = days.flatMap { d -> classes.flatMap { cls -> projection.slots(d, cls).filter { it.period == period && it.lessons.isNotEmpty() } } }
        slots.takeIf { it.isNotEmpty() && it.all { s -> s.time != null } }?.map { it.time }?.distinct()?.singleOrNull()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val periodStyle = textStyle.copy(fontSize = (9f * scale / density.fontScale).sp)
        val periodLabels = listOf("時限") + (1..8).map(Int::toString) + commonTimes.filterNotNull().flatMap { it.split('〜') }
        val periodWidth = with(density) { periodLabels.maxOf { measurer.measure(it, periodStyle).size.width }.toDp() } + 2.dp
        val cardWidth = ((maxWidth - periodWidth / scale - 12.dp) / 5).coerceAtLeast(40.dp) * scale
        fun measuredHeight(text: String, width: androidx.compose.ui.unit.Dp, style: TextStyle = textStyle): androidx.compose.ui.unit.Dp = with(density) { measurer.measure(text, style, constraints = Constraints(maxWidth = (width - 6.dp).roundToPx().coerceAtLeast(1))).size.height.toDp() }
        val rowHeights = MutableList(8) { 72.dp * scale }
        val subjects = mutableMapOf<Slot, String>()
        layouts.values.flatten().flatten().forEach { (slot, _) ->
            val names = slot.lessons.first().names
            val source = displayContinuous(names.subject).replace('・', '•')
            val effective = slot.changes.lastOrNull { it.type == "補講" } ?: slot.changes.lastOrNull()
            val short = effective?.let { state.mapping?.shortSubject(it, state.analyses) }?.let(::displayContinuous)?.replace('・', '•')
            val candidates = listOfNotNull(source, halfwidthKana(source), short, short?.let(::halfwidthKana))
            val subject = if (effective == null) source else candidates.firstOrNull { candidate -> with(density) { measurer.measure(candidate, textStyle).size.width <= (cardWidth - 6.dp).roundToPx() } } ?: short?.let(::halfwidthKana) ?: source
            subjects[slot] = subject
            val text = listOfNotNull(if (slot.changes.isNotEmpty()) slot.type else null, if (slot.type != "休講") subject.ifEmpty { "変更を確認" } else null, displayMetadata(names.teacher), displayMetadata(names.room), slot.time?.replace('〜', '～')).filter(String::isNotEmpty).joinToString("\n")
            val needed = measuredHeight(text, cardWidth) + 10.dp
            val allocated = (slot.period..slot.endPeriod).fold(0.dp) { h, p -> h + rowHeights[p - 1] }
            if (needed > allocated) { val extra = (needed - allocated) / (slot.endPeriod - slot.period + 1); (slot.period..slot.endPeriod).forEach { rowHeights[it - 1] += extra } }
        }
        val headerTexts = days.associateWith { day -> "${day.monthValue}/${day.dayOfMonth}（${"月火水木金土日"[day.dayOfWeek.value - 1]}）\n" + (projection.headerTitles(day, classes) + classes.flatMap { projection.missing(day, it) }.distinct()).joinToString("\n").let(::displayContinuous) }
        val widths = days.associateWith { day -> layouts.getValue(day).sumOf { blocks -> (blocks.maxOfOrNull { it.lane } ?: 0) + 1 } }
        val headerHeight = days.maxOfOrNull { day -> measuredHeight(headerTexts.getValue(day), cardWidth * widths.getValue(day), headerStyle) }?.plus(8.dp) ?: 40.dp
        val gridHeight = if (allFull) days.maxOf { day -> measuredHeight(displayKana(fullDays.getValue(day).orEmpty()), cardWidth * widths.getValue(day), headerStyle) + 10.dp } else rowHeights.fold(0.dp) { a, b -> a + b }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Column(Modifier.width(periodWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(headerHeight), contentAlignment = Alignment.Center) { if (!allFull) Text("時限", style = periodStyle) }
                if (allFull) Spacer(Modifier.height(gridHeight)) else (1..8).forEach { p -> Box(Modifier.height(rowHeights[p - 1]).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("$p" + (commonTimes[p - 1]?.replace("〜", "\n～\n")?.let { "\n$it" } ?: ""), textAlign = TextAlign.Center, style = periodStyle) } }
            }
            days.forEach { day -> val width = cardWidth * widths.getValue(day)
                Column(Modifier.width(width)) {
                    Box(Modifier.height(headerHeight).fillMaxWidth().padding(3.dp), contentAlignment = Alignment.Center) { Text(headerTexts.getValue(day), style = headerStyle, color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                    val fullDay = fullDays[day]
                    if (fullDay != null) Box(Modifier.height(gridHeight).fillMaxWidth().border(.5.dp, MaterialTheme.colorScheme.outlineVariant).padding(3.dp), contentAlignment = Alignment.Center) { Text(displayKana(fullDay), style = headerStyle.copy(fontWeight = FontWeight.Bold)) }
                    else Box(Modifier.height(gridHeight).fillMaxWidth()) {
                        var classLane = 0
                        layouts.getValue(day).forEach { blocks ->
                            blocks.forEach { (slot, lane) ->
                                val y = rowHeights.take(slot.period - 1).fold(0.dp) { a, b -> a + b }
                                val height = rowHeights.subList(slot.period - 1, slot.endPeriod).fold(0.dp) { a, b -> a + b } - 2.dp
                                val names = slot.lessons.first().names
                                Column(Modifier.offset(x = cardWidth * (classLane + lane), y = y).width(cardWidth - 2.dp).height(height).border(.5.dp, MaterialTheme.colorScheme.outlineVariant).background(MaterialTheme.colorScheme.surfaceContainer).clickable { select(day to slot) }.clearAndSetSemantics { contentDescription = accessibility(day, slot); onClick("授業詳細を開きます") { select(day to slot); true } }.padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    if (slot.changes.isNotEmpty()) Text(slot.type, style = textStyle, color = Color(0xFFFF9500))
                                    if (slot.type != "休講") Text(subjects[slot].orEmpty().ifEmpty { "変更を確認" }, style = textStyle, maxLines = if (slot.changes.isNotEmpty()) Int.MAX_VALUE else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    if (names.teacher.isNotEmpty()) Text(displayMetadata(names.teacher), style = metadataStyle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    if (names.room.isNotEmpty()) Text(displayMetadata(names.room).let { full -> if (with(density) { measurer.measure(full, metadataStyle).size.width > (cardWidth - 6.dp).roundToPx() }) halfwidthKana(full) else full }, style = metadataStyle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    if (slot.period != slot.endPeriod || commonTimes[slot.period - 1] == null) slot.time?.let {
                                        val value = it.replace('〜', '～')
                                        val baseSize = (8.5f * scale / density.fontScale).sp
                                        val width = with(density) { (cardWidth - 6.dp).roundToPx() }
                                        val measured = measurer.measure(value, TextStyle(fontSize = baseSize)).size.width
                                        Text(value, fontSize = baseSize * (width.toFloat() / measured.coerceAtLeast(1)).coerceAtMost(1f), textAlign = TextAlign.Center, maxLines = 1)
                                    }
                                }
                            }
                            classLane += (blocks.maxOfOrNull { it.lane } ?: 0) + 1
                        }
                        (1..8).filter { p -> layouts.getValue(day).flatten().none { p in it.slot.period..it.slot.endPeriod } }.forEach { p ->
                            Box(Modifier.offset(y = rowHeights.take(p - 1).fold(0.dp) { a, b -> a + b }).height(rowHeights[p - 1]).fillMaxWidth().border(.5.dp, MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) { Text("—") }
                        }
                    }
                }
            }
            Spacer(Modifier.width(2.dp))
        }
    }
}
@Composable private fun ChangeClassPicker(state: AppState, initial: Set<String>, close: () -> Unit, change: ((Settings) -> Settings) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    val candidates = (Schedule.classes + state.analyses.flatMap { it.classes } + draft).distinct()
    AlertDialog(onDismissRequest = close, title = { Text("変更一覧の対象クラス") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("${draft.size} / 30クラス")
        candidates.groupBy { it.substringBefore('_') }.forEach { (group, values) -> Text(if (group == "AI") "専攻科" else "${group}年", fontWeight = FontWeight.Bold); values.forEach { cls -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cls in draft, { selected -> if (!selected) draft -= cls else if (draft.size < 30) draft += cls }); Text(displayClass(cls)) } } }
    } }, confirmButton = { TextButton(onClick = { change { it.copy(changeClasses = draft, useTimetableClasses = draft.isEmpty()) }; close() }) { Text("適用") } }, dismissButton = { Column { TextButton(onClick = { change { it.copy(changeClasses = emptySet(), useTimetableClasses = true) }; close() }) { Text("時間割と同じクラスに戻す") }; TextButton(onClick = close) { Text("キャンセル") } } })
}
