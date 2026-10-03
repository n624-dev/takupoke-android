package jp.n624.takupoke.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import jp.n624.takupoke.core.*
import java.time.LocalDate

@Composable fun MaterialsScreen(state: AppState, repository: AppRepository, pick: (MaterialKind) -> Unit, setupMode: Boolean = false, events: () -> Unit = {}, open: (MaterialRecord) -> Unit) {
    var detail by remember { mutableStateOf<MaterialKind?>(null) }
    val record = state.materials.firstOrNull { it.kind == detail }
    BackHandler(detail != null) { detail = null }
    if (record != null) {
        MaterialAnalysisScreen(state, repository, record, { detail = null }, open)
        return
    }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (setupMode) item { Text("ファイルを選ぶ", style = MaterialTheme.typography.titleMedium); Text("通常時間割のPDFと時間割変更のExcelファイルを選びます。選択後、自動で解析します。"); Text("試験時間割・試験返却時間割のPDFは、手元にある場合に選んでください。") }
        if (!setupMode) item { TextButton(onClick = repository::suspendAutomaticRefresh, enabled = !state.automaticRefreshSuspended) { Text(if (state.automaticRefreshSuspended) "自動確認を中止中" else "自動確認を中止") } }
        item { RecoveryModelControls(state, repository) }
        items(MaterialKind.entries) { kind -> val source = state.materials.firstOrNull { it.kind == kind }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kind.title, style = MaterialTheme.typography.titleMedium); Text(source?.name ?: "未選択")
                if (source != null) Text(materialStatus(source))
                Row { Button(onClick = { pick(kind) }, enabled = !state.busy) { Text(if (source == null) "ファイルを選ぶ" else "ファイルを選び直す") }; if (source != null) TextButton(onClick = { detail = kind }) { Text("詳細を見る") } }
            } }
        }
        if (setupMode) item { TextButton(onClick = events) { Text("学校行事を取得") } }
    }
}
private fun materialStatus(record: MaterialRecord) = when {
    record.failure != null -> if (record.analysis == null) "要確認" else "要確認（前回結果あり）"
    record.analysis != null -> if (record.parsedDigest == record.digest) "解析済み" else "未解析（前回結果あり）"
    else -> "未解析"
}

@Composable private fun MaterialAnalysisScreen(state: AppState, repository: AppRepository, record: MaterialRecord, close: () -> Unit, open: (MaterialRecord) -> Unit) {
    var preview by remember(record.kind, record.digest, state.settings.defaultSchoolYear) { mutableStateOf<Analysis?>(null) }
    var warning by remember(record.kind,record.digest,state.settings.defaultSchoolYear) { mutableStateOf(false) }
    val kind = record.kind
    val recovery = state.recoveryPreviews[record.kind]
    var recoveryView by remember(record.kind,record.digest) { mutableStateOf(false) }
    val analysis = if(recoveryView && recovery != null) recovery.analysis else preview ?: record.analysis
    // Details capture a row. Close them when its accepted/preview data or names change,
    // even when a reparse or explicit adoption keeps the same source digest.
    var selectedLesson by remember(record.kind,record.digest,state.settings.defaultSchoolYear,record.analysis,record.parsedAt,analysis,state.mapping,recovery?.resultHash,recoveryView) { mutableStateOf<Lesson?>(null) }
    var selectedChange by remember(record.kind,record.digest,state.settings.defaultSchoolYear,record.analysis,record.parsedAt,analysis,state.mapping,recovery?.resultHash,recoveryView) { mutableStateOf<Change?>(null) }
    val year = state.settings.defaultSchoolYear
    val validYear = year.trim().isEmpty() || year.trim().toIntOrNull() in 1900..9998
    val selectedClass = when (kind) { MaterialKind.TIMETABLE -> state.settings.timetableFilter; MaterialKind.CHANGES -> state.settings.changesFilter; else -> "" }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = close) { Text("時間割ファイルに戻る") }; Text(kind.title, style = MaterialTheme.typography.titleMedium) } }
        item {
            Text("状態", style = MaterialTheme.typography.titleMedium)
            if (record.recoveryJob?.let { it.pdfHash == record.digest && it.state == RecoveryJobState.PENDING } == true)
                Text("端末内AIによる復旧待ちです。新しい資料をまだ反映できていません。前回の正常結果を保持しています。", color = MaterialTheme.colorScheme.error)
            Text(materialStatus(record)); record.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (kind == MaterialKind.CHANGES) {
                OutlinedTextField(year, { value -> repository.preferenceAction { it.copy(defaultSchoolYear = value) } }, label = { Text("補完年度（自動：${schoolYear()}年度）") }, singleLine = true, enabled = !state.busy)
                if (!validYear) Text("西暦1900〜9998の学校年度を入力してください。", color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { preview = null; repository.action { repository.reparse(kind) } }, enabled = !state.busy && (kind != MaterialKind.CHANGES || validYear)) { Text("解析する") }
            OutlinedButton(onClick = { repository.action { repository.refreshMaterial(kind) } }, enabled = !state.busy) { Text("同じファイルを再取得") }
            if (kind != MaterialKind.CHANGES) OutlinedButton(onClick = { open(record) }, enabled = !state.busy) { Text("保存済みのPDFを見る") }
            if (record.failure?.contains("曜日") == true) OutlinedButton(onClick = { warning = true }, enabled = !state.busy && (kind != MaterialKind.CHANGES || validYear)) { Text("警告を確認して内容を見る") }
        }
        if (kind != MaterialKind.CHANGES) item { RecoveryControls(state,repository,record,recoveryView,{ recoveryView=it },open) }
        item {
            Text("ファイル情報", style = MaterialTheme.typography.titleMedium)
            Text(record.name); Text("サイズ: ${android.text.format.Formatter.formatFileSize(context, repository.file(record).length())}")
            Text("最終取得: ${dateTime(record.fetchedAt)}\n最終確認: ${dateTime(record.checkedAt)}")
            record.sourceModified?.let { Text("元ファイルの更新: ${dateTime(it)}") }
        }
        if (analysis == null) item { Text("解析結果がありません。") }
        else {
            item {
                Text(if (recoveryView && recovery != null) "復旧結果のプレビュー（未採用）" else if (preview != null) "プレビュー（閲覧のみ）" else "解析結果", style = MaterialTheme.typography.titleMedium)
                if (preview != null) {
                    TextButton(onClick = { preview = null }) { Text("プレビューを閉じる") }
                    Text("警告のあるファイルを閲覧しています。日付を元ファイルで確認してください。", color = MaterialTheme.colorScheme.error)
                    Text("年なし日付の補完: ${if (year.trim().isEmpty()) schoolYear() else year.trim()}年度")
                    Text("曜日の警告: ${analysis.warningRows.size}件")
                    analysis.warningRows.forEach { row -> Text("${row}行目：曜日と月日の一致や、曜日の計算結果を確認できません。") }
                }
                Text("学校年度: ${analysis.schoolYear}年度\n件数: ${analysis.lessons.size + analysis.changes.size}件")
                if (kind == MaterialKind.TIMETABLE) Text("学期: ${when (analysis.term) { 1 -> "前期"; 2 -> "後期"; else -> "未確認" }}")
                Text("${if (recoveryView) "前回正式結果の解析日時" else "最終解析成功"}: ${record.parsedAt?.let(::dateTime) ?: "未解析"}")
                if (preview == null && !recoveryView && (record.parsedDigest != record.digest || analysis.parserVersion != PARSER_VERSION)) Text("前回の解析結果です。現在のファイルを解析してください。", color = MaterialTheme.colorScheme.error)
            }
            if (kind == MaterialKind.TIMETABLE || kind == MaterialKind.CHANGES) item {
                var menu by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("クラス", Modifier.weight(1f)); TextButton(onClick = { menu = true }) { Text(selectedClass.ifEmpty { "すべて" }.let(::displayClass)) }; DropdownMenu(menu, { menu = false }) { (listOf("") + analysis.classes + listOf(selectedClass)).distinct().forEach { cls -> DropdownMenuItem(text = { Text(cls.ifEmpty { "すべて" }.let(::displayClass)) }, onClick = { repository.preferenceAction { if (kind == MaterialKind.CHANGES) it.copy(changesFilter = cls) else it.copy(timetableFilter = cls) }; menu = false }) } } }
                if (selectedClass.isNotEmpty() && selectedClass !in analysis.classes) Text("選択したクラスは現在の解析結果にありません。選択は保持しています。")
                if (kind == MaterialKind.TIMETABLE) {
                    var weekdayMenu by remember { mutableStateOf(false) }
                    val weekdays = listOf("すべて", "月", "火", "水", "木", "金")
                    TextButton(onClick = { weekdayMenu = true }) { Text("曜日: ${weekdays[state.settings.timetableWeekdayFilter.coerceIn(0, 5)]}") }
                    DropdownMenu(weekdayMenu, { weekdayMenu = false }) { weekdays.forEachIndexed { i, label -> DropdownMenuItem(text = { Text(label) }, onClick = { repository.preferenceAction { it.copy(timetableWeekdayFilter = i) }; weekdayMenu = false }) } }
                }
            }
            items(analysis.changes.filter { selectedClass.isEmpty() || canonicalClass(it.className) == selectedClass }) { row ->
                OutlinedCard(onClick = { selectedChange = row }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(displayKana(row.after.ifEmpty { "記載なし" }), style = MaterialTheme.typography.titleMedium)
                    Text("${row.date} · ${displayClass(row.className)} · ${if (row.period.isEmpty()) "時限未記載" else displayChangePeriod(row)} · ${row.type}")
                    if (row.note.isNotEmpty()) Text(displayKana(row.note))
                } }
            }
            items(analysis.lessons.filter { (selectedClass.isEmpty() || it.className == selectedClass) && (kind != MaterialKind.TIMETABLE || state.settings.timetableWeekdayFilter == 0 || it.weekday == state.settings.timetableWeekdayFilter) }) { lesson ->
                OutlinedCard(onClick = { selectedLesson = lesson }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(displayContinuous(lesson.names.subject), style = MaterialTheme.typography.titleMedium)
                    Text("${lesson.date ?: "${"月火水木金土日"[(lesson.weekday - 1).coerceIn(0, 6)]}曜"} · ${displayClass(lesson.className)} · ${lesson.period}限")
                    if(recoveryView) { Text("教員: "+lesson.names.teacher.ifEmpty { "原文に記載なし" });Text("教室: "+lesson.names.room.ifEmpty { "原文に記載なし" }) } else Text(listOf(lesson.names.teacher, lesson.names.room).filter(String::isNotEmpty).joinToString(" / ").let(::displayContinuous))
                    lesson.time?.let { clock -> val key=if(lesson.spanStart==lesson.spanEnd)"${lesson.date}:${lesson.period}" else "${lesson.date}:${lesson.spanStart}-${lesson.spanEnd}";Text("時刻: $clock"+if(recoveryView && recovery?.document?.noteBasedClock(key)==true)"（PDF注記に基づく通常時間）" else "") };if(lesson.spanEnd>lesson.spanStart)Text("結合授業: ${lesson.spanStart}〜${lesson.spanEnd}限")
                } }
            }
        }
        if(recoveryView && recovery!=null)items(recovery.document.cells.filter { cell -> recovery.result.cells.firstOrNull { it.cellId==cell.id }?.state==RecoveryValueState.EMPTY && (selectedClass.isEmpty() || cell.slots.first().className==selectedClass) && (kind!=MaterialKind.TIMETABLE || state.settings.timetableWeekdayFilter==0 || cell.slots.first().day==state.settings.timetableWeekdayFilter.toString()) }) { cell ->
            val slot=cell.slots.first();OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("原文の空欄");Text("${displayClass(slot.className)} / "+(if(kind==MaterialKind.TIMETABLE)"${"月火水木金"[slot.day.toInt()-1]}曜" else slot.day)+" / "+cell.slots.joinToString("・") { "${it.period}限" })
                if(kind!=MaterialKind.TIMETABLE) { val key=if(cell.slots.size==1)"${slot.day}:${slot.period}" else "${slot.day}:${cell.slots.minOf { it.period }}-${cell.slots.maxOf { it.period }}";val clock=if(cell.slots.size==1)recovery.document.times[key]else recovery.document.spanTimes[key];clock?.let { Text("時刻: $it"+if(recovery.document.noteBasedClock(key))"（PDF注記に基づく通常時間）" else "（PDF記載の時刻から確認）") } }
            } }
        }
    }
    if (warning) AlertDialog(onDismissRequest = { warning = false }, title = { Text("曜日を確認できないファイルです") }, text = { Text("日付と曜日が合わないか、曜日の計算結果が保存されていません。日付欄を基準に内容を表示しますが、正しい内容かは元ファイルで確認してください。前回の正常データは置き換えません。") }, confirmButton = { TextButton(onClick = { warning = false; repository.action { val expectedDigest=record.digest; val expectedYear=year; val value=repository.preview(kind); if(repository.state.value.materials.firstOrNull { it.kind==kind }?.digest==expectedDigest && repository.state.value.settings.defaultSchoolYear==expectedYear)preview=value } }) { Text("確認して表示") } }, dismissButton = { TextButton(onClick = { warning = false }) { Text("キャンセル") } })
    selectedLesson?.let { AnalysisLessonDetail(it, kind, if(recoveryView) null else state.mapping) { selectedLesson = null } }
    selectedChange?.let { row -> AnalysisChangeDetail(row, state.mapping) { selectedChange = null } }

}

private fun RecoveryDocument.noteBasedClock(clockKey: String): Boolean {
    val refs=clockEvidence[clockKey].orEmpty()
    return kind==RecoveryDocumentKind.RETURN && clockKey.substringBefore(':') in days.sorted().drop(1) && normalTimeNoteEvidence.isNotEmpty() && refs.isNotEmpty() && refs.all { it in normalTimeNoteEvidence } && clockBindings[clockKey]==null
}

@Composable private fun AnalysisLessonDetail(lesson: Lesson, kind: MaterialKind, mapping: Mapping?, close: () -> Unit) {
    val names = if (kind == MaterialKind.TIMETABLE) mapping?.apply(lesson.names, lesson.className) ?: lesson.names else lesson.names.copy(subjectFull = "", teacherFull = "", roomFull = "")
    AlertDialog(onDismissRequest = close, title = { Text("授業詳細") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("クラス: ${displayClass(lesson.className)}")
        Text(lesson.date?.let { "日付: $it" } ?: "曜日: ${"月火水木金土日"[(lesson.weekday - 1).coerceIn(0, 6)]}")
        Text("時限: ${lesson.period}限"); lesson.time?.let { Text("時刻: $it") }
        Text("科目: ${displayContinuous(names.subjectFull.ifEmpty { names.subject })}")
        Text("教員: ${displayContinuous(names.teacherFull.ifEmpty { names.teacher }.ifEmpty { "記載なし" })}")
        Text("教室: ${displayContinuous(names.roomFull.ifEmpty { names.room }.ifEmpty { "記載なし" })}")
        if (lesson.sourceText.isNotEmpty()) Text("元のセルの記載\n${lesson.sourceText}")
    } }, confirmButton = { TextButton(onClick = close) { Text("閉じる") } })
}

@Composable private fun AnalysisChangeDetail(row: Change, mapping: Mapping?, close: () -> Unit) {
    val cls = canonicalClass(row.className); val year = schoolYear(LocalDate.parse(row.date))
    val beforeSource = mapping?.separate(row.before, cls, year) ?: Names(row.before)
    val before = mapping?.apply(beforeSource, cls, year) ?: beforeSource
    // Only this source row participates; accepted timetable data must not fill preview fields.
    val after = ScheduleProjection(emptyList(), emptyList(), mapping, null, true, true).changeDetail(row).lessons.single().names
    AlertDialog(onDismissRequest = close, title = { Text("時間割変更") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("日付: ${row.date}"); Text("クラス: ${displayClass(row.className)}")
        if (row.period.isNotEmpty()) Text("時限: ${displayChangePeriod(row)}")
        fun fields(label: String, names: Names): List<Pair<String, String>> = listOf(label to names.subjectFull.ifEmpty { names.subject }, "${label}の教員" to names.teacherFull.ifEmpty { names.teacher }, "${label}の教室" to names.roomFull.ifEmpty { names.room })
        (fields("変更前", before) + fields("変更後", after) + listOf("備考" to row.note)).filter { it.second.isNotEmpty() }.forEach { (label, value) -> Text("$label: ${displayKana(value)}") }
        if (row.raw.isNotEmpty()) Text("元の記載\n${row.raw}")
    } }, confirmButton = { TextButton(onClick = close) { Text("閉じる") } })
}

@Composable private fun RecoveryControls(state:AppState, repository:AppRepository, record:MaterialRecord, viewing:Boolean, view:(Boolean)->Unit, open:(MaterialRecord)->Unit) {
    var adopt by remember(record.digest) { mutableStateOf(false) }
    val preview=state.recoveryPreviews[record.kind]
    val job=record.recoveryJob?.takeIf { it.pdfHash==record.digest }
    if(job!=null && job.state!=RecoveryJobState.ADOPTED) {
        Text("PDFの端末内復旧",style=MaterialTheme.typography.titleMedium)
        Text("文字・罫線を再利用し、必要な部分だけ端末内AIで読み取ります。学校の資料は外部へ送信されません。")
        Text("状態: "+when(job.state){RecoveryJobState.PENDING->"復旧待ち";RecoveryJobState.PREPARING->"原文を確認中";RecoveryJobState.RUNNING->"復旧中";RecoveryJobState.AWAITING_MODEL->"モデルの準備待ち";RecoveryJobState.AWAITING_CONFIRMATION->"確認・採用待ち";RecoveryJobState.FAILED->"安全に復旧できませんでした";else->"未採用"})
        if(preview!=null) {
            Text("${preview.document.schoolYear}年度 / ${preview.document.term.orEmpty()} / ${preview.document.classes.size}クラス / ${preview.document.cells.size}セル")
            Text("採用対象は資料全体の全クラス・全日・全時限です。下のクラス・曜日の絞り込みは表示だけに使います。")
            Text("${preview.result.cells.count { it.state==RecoveryValueState.EMPTY }}セルは原文の空欄として確認しました。科目・教員・教室・結合時限を元PDFと確認してください。")
            TextButton(onClick={view(!viewing)}) { Text(if(viewing)"前回の正式結果を見る" else "復旧結果をプレビュー") }
            OutlinedButton(onClick={open(record)}) { Text("元PDFを確認") }
            Button(onClick={adopt=true},enabled=!state.busy) { Text("この復旧結果を使用") }
        } else Button(onClick={repository.action { repository.startRecovery(record.kind) }},enabled=!state.busy) { Text(if(job.state==RecoveryJobState.FAILED)"端末内で復旧を再試行" else "端末内で復旧する") }
    }
    if(record.recoveryMetadata!=null && record.parsedDigest==record.digest)Text("復旧結果を採用済み: ${record.recoveryMetadata.provider} / ${record.recoveryMetadata.modelId} / ${record.recoveryMetadata.modelVersion}")
    RecoveryModelControls(state, repository)
    if(adopt && preview!=null)AlertDialog(onDismissRequest={adopt=false},title={Text("復旧結果を採用しますか")},text={Text("この資料全体（全クラス・全日・全時限）の復旧結果で正式な時間割を置き換えます。表示中の絞り込みに関係なく全体を採用します。元PDFと科目・教員・教室・時刻・空欄を確認してください。更新されたPDFは再確認が必要です。")},confirmButton={TextButton(onClick={adopt=false;repository.action { repository.adoptRecovery(record.kind,preview.resultHash);view(false) }}){Text("確認した結果を採用")}},dismissButton={TextButton(onClick={adopt=false}){Text("戻る")}})
}
@Composable private fun RecoveryModelControls(state: AppState, repository: AppRepository) {
    var download by remember { mutableStateOf(false) }
    val model=state.recoveryModel
    val offer=model.offered
    Text("端末内AIモデル",style=MaterialTheme.typography.titleMedium)
    if(model.installed!=null || model.error!=null) {
        model.installed?.let { Text("${it.modelId} (${it.version}) / ${it.license}") };model.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick={repository.action { repository.deleteRecoveryModel() }},enabled=!state.busy) { Text("追加モデルを削除") }
    } else {
        if(offer!=null) { Text("約${(offer.size+1024*1024-1)/(1024*1024)} MB / ${offer.license} / ${offer.recommendedBackend}"); OutlinedButton(onClick={download=true},enabled=!state.busy) { Text("必要なAIモデルを準備") } }
        else Text("この版では検証済みの追加モデルをまだ配信していません。原文から確定できる復旧は利用できます。")
    }
    if(model.downloading) { LinearProgressIndicator(progress={if(offer!=null)model.progressBytes.toFloat()/offer.size else 0f},modifier=Modifier.fillMaxWidth());Text("モデル取得: ${model.progressBytes/(1024*1024)} MB") }
    if(download && offer!=null)AlertDialog(onDismissRequest={download=false},title={Text("端末内AIモデルをダウンロード")},text={Text("約${(offer.size+1024*1024-1)/(1024*1024)} MBのモデルを取得します。学校の資料は外部へ送信されません。空きメモリが${(offer.minimumMemory+1024*1024-1)/(1024*1024)} MB以上必要です。準備中はアプリを開いてください。ライセンス: ${offer.license}")},confirmButton={TextButton(onClick={download=false;repository.action { repository.downloadRecoveryModel() }}){Text("ダウンロードして準備")}},dismissButton={TextButton(onClick={download=false}){Text("キャンセル")}})
}
