package jp.n624.takupoke.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.n624.takupoke.core.*
import java.time.LocalDate

private fun slots(state: AppState, date: LocalDate, changes: Boolean): List<Slot> {
    val classes = listOf(state.settings.primaryClass, state.settings.additionalClass).filter(String::isNotEmpty).distinct()
    val byClass = classes.map { Schedule.slots(date, it, state.analyses, state.events, state.mapping, state.times, changes, state.settings.international) }
    return (1..8).map { period ->
        val values = byClass.map { it[period - 1] }
        Slot(period, values.flatMap { it.lessons }.distinct(), values.flatMap { it.changes }, values.firstOrNull { it.type != "通常" }?.type ?: "通常", values.mapNotNull { it.time }.distinct().singleOrNull())
    }
}
@Composable fun HomeScreen(state: AppState, account: () -> Unit, select: (Pair<LocalDate, Slot>) -> Unit, timetable: () -> Unit) {
    val date = today()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("たくポケ", style = MaterialTheme.typography.headlineMedium)
        Text("${date.year}年${date.monthValue}月${date.dayOfMonth}日（${"月火水木金土日"[date.dayOfWeek.value - 1]}）")
        if (state.updates.isNotEmpty()) Card { Column(Modifier.padding(12.dp)) { Text("リンク・名称・授業時刻の更新があります"); TextButton(onClick = account) { Text("データを取得する") } } }
        if (state.settings.primaryClass.isEmpty()) Text("設定からクラスと時間割ファイルを選択してください。")
        Schedule.events(date, state.events).forEach { Text("${it.title}（${it.tag}）", style = MaterialTheme.typography.titleMedium) }
        slots(state, date, true).filter { it.lessons.isNotEmpty() }.forEach { slot ->
            OutlinedCard(onClick = { select(date to slot) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("${slot.period}時限　${slot.time.orEmpty()}　${slot.type}"); slot.lessons.forEach { Text(it.names.subject, style = MaterialTheme.typography.titleMedium); Text(listOf(it.names.teacher, it.names.room).filter(String::isNotEmpty).joinToString("　")) } } }
        }
        Button(onClick = timetable) { Text("週間時間割を見る") }
        val recommended = state.links?.categories.orEmpty().flatMap { it.buttons }.filter { it.visible && it.recommended && it.id !in state.settings.hidden }.sortedBy { it.recommendationOrder }
        if (recommended.isNotEmpty()) Text("おすすめ", style = MaterialTheme.typography.titleMedium)
        recommended.forEach { LinkButton(it, state.settings) }
    }
}
@Composable fun TimetableScreen(state: AppState, change: ((Settings) -> Settings) -> Unit, select: (Pair<LocalDate, Slot>) -> Unit) {
    var mondayText by rememberSaveable { mutableStateOf(Schedule.week(today()).toString()) }
    var changes by rememberSaveable { mutableStateOf(true) }
    var days by rememberSaveable { mutableIntStateOf(5) }
    var font by rememberSaveable { mutableIntStateOf(14) }
    val monday = LocalDate.parse(mondayText)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { mondayText = monday.minusWeeks(1).toString() }) { Text("前の週") }
            TextButton(onClick = { mondayText = Schedule.week(today()).toString() }) { Text("今週") }
            TextButton(onClick = { mondayText = monday.plusWeeks(1).toString() }) { Text("次の週") }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("${monday.monthValue}/${monday.dayOfMonth}〜", Modifier.weight(1f))
            TextButton(onClick = { days = if (days == 5) 7 else 5 }) { Text("${days}日表示") }
            TextButton(onClick = { font = if (font >= 20) 12 else font + 2 }) { Text("文字 $font") }
        }
        Row(Modifier.padding(horizontal = 12.dp)) { FilterChip(changes, { changes = !changes }, label = { Text("変更を反映") }); Spacer(Modifier.width(8.dp)); Text(state.settings.primaryClass.replace('_', '-'), Modifier.padding(8.dp)) }
        Column(Modifier.padding(horizontal = 12.dp)) { ClassSettings(state.settings, change) }
        Column(Modifier.weight(1f).horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
            Row { Spacer(Modifier.width(44.dp)); (0 until days).forEach { offset -> val date = monday.plusDays(offset.toLong()); Column(Modifier.width(128.dp).padding(6.dp)) { Text("${date.monthValue}/${date.dayOfMonth}（${"月火水木金土日"[date.dayOfWeek.value - 1]}）", color = if (date == today()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Schedule.events(date, state.events).forEach { Text(it.title, fontSize = 12.sp) } } } }
            val weekSlots = (0 until days).map { slots(state, monday.plusDays(it.toLong()), changes) }
            (1..8).forEach { period -> Row(Modifier.height(IntrinsicSize.Min)) {
                Text("$period", Modifier.width(44.dp).padding(12.dp))
                (0 until days).forEach { offset -> val date = monday.plusDays(offset.toLong()); val slot = weekSlots[offset][period - 1]
                    Column(Modifier.width(128.dp).fillMaxHeight().defaultMinSize(minHeight = 96.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).background(if (slot.type == "変更") MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent).clickable { select(date to slot) }.padding(6.dp)) {
                        if (slot.type != "通常") Text(slot.type, fontSize = 11.sp)
                        slot.time?.let { Text(it, fontSize = 10.sp) }
                        slot.lessons.forEach { Text(it.names.subject, fontSize = font.sp); Text(listOf(it.names.teacher, it.names.room).filter(String::isNotEmpty).joinToString(" "), fontSize = (font - 2).sp) }
                    }
                }
            } }
        }
    }
}
