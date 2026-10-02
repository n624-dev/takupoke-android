package jp.n624.takupoke.android

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.n624.takupoke.core.*
import java.time.LocalDate

@Composable private fun NamesDetail(names: Names) {
    Text("科目: ${displayKana(names.subjectFull.ifEmpty { names.subject }.ifEmpty { "記載なし" })}", fontWeight = FontWeight.Bold)
    Text("教員: ${displayKana(names.teacherFull.ifEmpty { names.teacher }.ifEmpty { "記載なし" })}")
    Text("教室: ${displayKana(names.roomFull.ifEmpty { names.room }.ifEmpty { "記載なし" })}")
}
@Composable fun LessonDetailScreen(state: AppState, date: LocalDate, slot: Slot, close: () -> Unit) {
    val projection = ScheduleProjection(state.analyses, state.events, state.mapping, state.times, true, state.settings.international)
    AlertDialog(onDismissRequest = close, title = { Text(if (slot.changes.isEmpty()) "授業詳細" else "時間割変更") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("日付: $date")
            Text("時限: " + (slot.selectedChange?.period ?: if (slot.period == slot.endPeriod) "${slot.period}限" else "${slot.period}〜${slot.endPeriod}限"))
            Text("時刻: ${slot.time?.replace('〜', '～') ?: "時刻未確認"}")
            if (slot.changes.isEmpty()) slot.lessons.forEach { lesson ->
                Text("クラス: ${displayClass(lesson.className)}")
                Text("資料: ${lesson.kind?.title ?: "通常時間割"}")
                NamesDetail(lesson.names)
                if (lesson.sourceText.isNotEmpty()) Text("元のセルの記載\n${lesson.sourceText}")
            } else (listOfNotNull(slot.selectedChange) + slot.changes.filter { it != slot.selectedChange }).forEachIndexed { index, change ->
                if (index > 0) Text("同じ時限のほかの変更", fontWeight = FontWeight.Bold)
                Text("${displayClass(change.className)}　${change.period}限　${change.type}", fontWeight = FontWeight.Bold)
                Text("変更前"); NamesDetail(projection.changeBefore(change))
                Text("変更後"); projection.changeDetail(change).lessons.forEach { NamesDetail(it.names) }
                if (change.note.isNotEmpty()) Text("備考: ${displayKana(change.note)}")
                Text("元の記載\n${change.raw.ifEmpty { "${change.before} → ${change.after}" }}")
            }
            if (slot.changes.isNotEmpty() && slot.originals.isNotEmpty()) {
                Text("変更前の授業", fontWeight = FontWeight.Bold)
                slot.originals.forEach { lesson ->
                    Text("${displayClass(lesson.className)}　${lesson.period}限　${lesson.kind?.title ?: "通常時間割"}")
                    NamesDetail(lesson.names)
                    if (lesson.sourceText.isNotEmpty()) Text("元のセルの記載\n${lesson.sourceText}")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("閉じる") } })
}

@Composable fun AccountDetailScreen(state: AppState, key: String, close: () -> Unit) {
    val type = when (key) { "links-revision" -> "links"; "mapping-revision" -> "mapping"; else -> "times" }
    val title = when (type) { "links" -> "リンク一覧"; "mapping" -> "名称データ"; else -> "授業時刻" }
    val count = when (type) { "links" -> state.links?.categories?.sumOf { it.buttons.size }; "mapping" -> state.mapping?.let { it.subjects.size + it.teachers.size + it.rooms.size + it.teacherContexts.size }; else -> state.times?.days?.sumOf { it.periods.size } }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("状態: " + if (key in state.accountErrors) "要確認" else if (count == null) "未取得" else if (key in state.updates) "更新あり" else "取得済み")
            state.accountErrors[key]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.accountFetchedAt[type]?.let { Text("最終取得: ${dateTime(it)}") }
            count?.let { Text("件数: ${it}件") }
            state.accountVersions[type]?.let { Text("バージョン: $it") }
            if (type == "mapping") state.mapping?.let { m -> Text("科目: ${m.subjects.size}件\n教員: ${m.teachers.size}件\n教室: ${m.rooms.size}件\n教員の条件: ${m.teacherContexts.size}件") }
            if (type == "times") state.times?.days?.forEach { day -> Text(day.date, fontWeight = FontWeight.Bold); day.periods.forEach { Text("${it.period}限　${it.start}～${it.end}") } }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("閉じる") } })
}
