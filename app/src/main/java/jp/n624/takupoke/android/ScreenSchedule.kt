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
@Composable fun HomeScreen(state: AppState, account: () -> Unit, classes: () -> Unit, select: (Pair<LocalDate, Slot>) -> Unit, timetable: () -> Unit) {
    val date = today()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.updates.isNotEmpty()) TextButton(onClick = account) { Text(listOf("links-revision" to "一覧", "mapping-revision" to "名称データ", "timetable-times-revision" to "授業時刻").filter { it.first in state.updates }.joinToString("・") { it.second } + "に更新があります") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("今日の予定", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton(onClick = timetable) { Text("時間割を見る") } }
        Text("${date.monthValue}月${date.dayOfMonth}日（${"月火水木金土日"[date.dayOfWeek.value - 1]}）")
        if (state.settings.primaryClass.isEmpty()) TextButton(onClick = classes) { Text("クラスを選択") }
        Schedule.events(date, state.events).forEach { Text(it.title, style = MaterialTheme.typography.titleMedium) }
        slots(state, date, true).filter { it.lessons.isNotEmpty() }.forEach { slot ->
            OutlinedCard(onClick = { select(date to slot) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("${slot.period}限　${slot.time.orEmpty()}　${slot.type}"); slot.lessons.forEach { Text(it.names.subject, style = MaterialTheme.typography.titleMedium); Text(listOf(it.names.teacher, it.names.room).filter(String::isNotEmpty).joinToString("　")) } } }
        }
        val visible = state.links?.categories.orEmpty().flatMap { it.buttons }.filter { it.visible && it.id !in state.settings.hidden }
        val favorites = visible.filter { it.id in state.settings.favorites }
        if (favorites.isNotEmpty()) Text("お気に入り", style = MaterialTheme.typography.titleMedium)
        favorites.forEach { LinkButton(it, state.settings) }
        val recommended = visible.filter { it.recommended }.sortedBy { it.recommendationOrder }
        if (recommended.isNotEmpty()) Text("おすすめ", style = MaterialTheme.typography.titleMedium)
        recommended.forEach { LinkButton(it, state.settings) }
    }
}
@Composable fun TimetableScreen(state: AppState, classes: () -> Unit, select: (Pair<LocalDate, Slot>) -> Unit) {
    var mondayText by rememberSaveable { mutableStateOf(Schedule.week(today()).toString()) }
    var changes by rememberSaveable { mutableStateOf(true) }
    val font = 14
    val monday = LocalDate.parse(mondayText)
    val context = androidx.compose.ui.platform.LocalContext.current
    val days = if ((5..6).any { Schedule.events(monday.plusDays(it.toLong()), state.events).isNotEmpty() || slots(state, monday.plusDays(it.toLong()), changes).any { slot -> slot.lessons.isNotEmpty() } }) 7 else 5
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("表示クラス", style = MaterialTheme.typography.titleMedium)
        ClassSelectionRow(state.settings, classes)
        Text("週の時間割", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { mondayText = monday.minusWeeks(1).toString() }) { Text("前週") }
            TextButton(onClick = {
                android.app.DatePickerDialog(context, { _, year, month, day -> mondayText = Schedule.week(LocalDate.of(year, month + 1, day)).toString() }, monday.year, monday.monthValue - 1, monday.dayOfMonth).apply {
                    setTitle("週を選ぶ")
                    setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "キャンセル") { dialog, _ -> dialog.cancel() }
                    show()
                    getButton(android.content.DialogInterface.BUTTON_POSITIVE).text = "この週へ移動"
                }
            }) { val end = monday.plusDays(6); Text("${monday.monthValue}/${monday.dayOfMonth}〜${end.monthValue}/${end.dayOfMonth}") }
            TextButton(onClick = { mondayText = monday.plusWeeks(1).toString() }) { Text("翌週") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!changes, { changes = false }, label = { Text("通常") }); FilterChip(changes, { changes = true }, label = { Text("変更込み") }) }
        if (state.settings.primaryClass.isEmpty()) TextButton(onClick = classes) { Text("クラスを選択") }
        else Column(Modifier.weight(1f).horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
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
