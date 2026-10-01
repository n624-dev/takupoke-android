package jp.n624.takupoke.android

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.n624.takupoke.core.*
import java.time.LocalDate

val mainColors = listOf(Color(0xFF1674CD), Color(0xFF24833B), Color(0xFF8B7200), Color(0xFFBA5400), Color(0xFFC73535), Color(0xFFBE437E), Color(0xFF7F4CBB))
val colorNames = listOf("青", "緑", "黄色", "オレンジ", "赤", "ピンク", "紫")
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TakupokeUi(repository: AppRepository, pick: (MaterialKind) -> Unit, login: () -> Unit, notifyPermission: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }; var page by rememberSaveable { mutableStateOf("") }; var setupOffered by rememberSaveable { mutableStateOf(false) }
    var selectedLesson by remember { mutableStateOf<Pair<LocalDate, Slot>?>(null) }
    var source by remember { mutableStateOf<MaterialRecord?>(null) }
    val scope = rememberCoroutineScope()
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = mainColors[state.settings.color.coerceIn(0, 6)]) else lightColorScheme(primary = mainColors[state.settings.color.coerceIn(0, 6)])
    fun settings(block: (Settings) -> Settings) { repository.action { repository.settings(block) } }
    LaunchedEffect(state.ready) { if (state.ready && !state.settings.setupComplete && !setupOffered) { page = "setup"; setupOffered = true } }
    LaunchedEffect(state.ready) { while (true) { kotlinx.coroutines.delay(30000); if (state.ready) repository.action { repository.activate(false) } } }
    MaterialTheme(colorScheme = colors) {
        BackHandler(page.isNotEmpty()) { page = "" }
        Scaffold(topBar = { TopAppBar(title = { Text(if (page.isEmpty()) listOf("ホーム", "一覧", "時間割", "設定")[tab] else mapOf("materials" to "時間割ファイル", "events" to "学校行事", "account" to "リンク・名称・授業時刻", "notifications" to "通知", "about" to "このアプリについて", "help" to "使い方", "setup" to "初期設定")[page].orEmpty()) }, navigationIcon = { if (page.isNotEmpty()) IconButton(onClick = { page = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } }, actions = { if (state.busy) TextButton(onClick = repository::cancel) { Text("中止") } }) }, bottomBar = {
            if (page.isEmpty()) NavigationBar { val icons = listOf(Icons.Default.Home, Icons.Default.List, Icons.Default.DateRange, Icons.Default.Settings)
                listOf("ホーム", "一覧", "時間割", "設定").forEachIndexed { i, name -> NavigationBarItem(tab == i, { tab = i }, icon = { Icon(icons[i], name) }, label = { Text(name) }) }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!state.ready) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(if (state.retentionFailure) "保存データを削除できませんでした。古いデータの利用を停止しています。" else "読み込み中⋯"); if (state.retentionFailure) Button(onClick = { repository.action { repository.activate() } }) { Text("再試行") } } }
                else when (page) {
                    "materials" -> MaterialsScreen(state, repository, pick) { source = it }
                    "account" -> AccountScreen(state, repository, login)
                    "events" -> EventsScreen(state, repository)
                    "notifications" -> Column(Modifier.padding(16.dp)) {
                        Toggle("時間割変更", state.settings.changeNotifications) { enabled -> if (enabled) notifyPermission(); settings { it.copy(changeNotifications = enabled) } }
                        Toggle("試験・返却PDFの更新", state.settings.examNotifications) { enabled -> if (enabled) notifyPermission(); settings { it.copy(examNotifications = enabled) } }
                        Text("初回取り込みは通知せず、その後の更新を通知します。更新の種類と件数を表示します。")
                        Text("バックグラウンドの実行時刻はAndroidが決定します。")
                    }
                    "about" -> AboutScreen(repository, state)
                    "help" -> HelpScreen()
                    "setup" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("たくポケへようこそ", style = MaterialTheme.typography.headlineSmall)
                        Text("学校アカウントでデータを取得し、時間割ファイルとクラスを設定します。試験・返却の資料は後から追加できます。")
                        Button(onClick = login, enabled = !state.busy) { Text("学校アカウントで取得") }
                        MaterialKind.entries.forEach { kind -> OutlinedButton(onClick = { pick(kind) }, enabled = !state.busy) { Text("${kind.title}を選択") } }
                        ClassSettings(state.settings) { transform -> settings(transform) }
                        Button(onClick = { notifyPermission(); settings { it.copy(setupComplete = true, changeNotifications = true, examNotifications = true) }; page = "" }) { Text("はじめる") }
                        TextButton(onClick = { settings { it.copy(setupComplete = true) }; page = "" }) { Text("あとで設定") }
                    }
                    else -> when (tab) {
                        0 -> HomeScreen(state, { page = "account" }, { selectedLesson = it }, { tab = 2 })
                        1 -> LinksScreen(state) { transform -> settings(transform) }
                        2 -> TimetableScreen(state, { transform -> settings(transform) }) { selectedLesson = it }
                        else -> SettingsScreen(state, { page = it }) { transform -> settings(transform) }
                    }
                }
            }
        }
        state.message?.let { message -> AlertDialog(onDismissRequest = repository::clearMessage, title = { Text("たくポケ") }, text = { Text(message) }, confirmButton = { TextButton(onClick = repository::clearMessage) { Text("閉じる") } }) }
        selectedLesson?.let { (date, slot) -> AlertDialog(onDismissRequest = { selectedLesson = null }, title = { Text("${date.monthValue}/${date.dayOfMonth} ${slot.period}時限") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { slot.time?.let { Text(it) }; slot.lessons.forEach { lesson -> val n = lesson.names; Text(n.subjectFull.ifEmpty { n.subject }, fontWeight = FontWeight.Bold); Text(listOf(n.teacherFull.ifEmpty { n.teacher }, n.roomFull.ifEmpty { n.room }).filter(String::isNotEmpty).joinToString("\n")); if (lesson.sourceText.isNotEmpty()) Text("元の記載\n${lesson.sourceText}") }; slot.changes.forEach { Text("${it.before} → ${it.after}\n${it.note}") } } }, confirmButton = { TextButton(onClick = { selectedLesson = null }) { Text("閉じる") } }) }
        source?.let { record -> PdfScreen(repository.file(record)) { source = null } }
    }
}
@Composable fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, change) } }
@Composable fun ClassSettings(settings: Settings, change: ((Settings) -> Settings) -> Unit) {
    var expanded by remember { mutableStateOf(false) }; var additional by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("クラス", Modifier.weight(1f)); TextButton(onClick = { expanded = true }) { Text(settings.primaryClass.ifEmpty { "選択" }.replace('_', '-')) }; DropdownMenu(expanded, { expanded = false }) { Schedule.classes.forEach { cls -> DropdownMenuItem(text = { Text(cls.replace('_', '-')) }, onClick = { change { it.copy(primaryClass = cls, additionalClass = it.additionalClass.takeIf { other -> Schedule.compatible(cls, other) }.orEmpty()) }; expanded = false }) } } }
    if (Schedule.classes.any { Schedule.compatible(settings.primaryClass, it) }) Row(verticalAlignment = Alignment.CenterVertically) { Text("併せて表示するクラス", Modifier.weight(1f)); TextButton(onClick = { additional = true }) { Text(settings.additionalClass.ifEmpty { "なし" }.replace('_', '-')) }; DropdownMenu(additional, { additional = false }) { (listOf("") + Schedule.classes.filter { Schedule.compatible(settings.primaryClass, it) }).forEach { cls -> DropdownMenuItem(text = { Text(cls.ifEmpty { "なし" }.replace('_', '-')) }, onClick = { change { it.copy(additionalClass = cls) }; additional = false }) } } }
    Toggle("留学生向け授業を表示", settings.international) { v -> change { it.copy(international = v) } }
}
@Composable fun SettingsScreen(state: AppState, navigate: (String) -> Unit, change: ((Settings) -> Settings) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("データ", style = MaterialTheme.typography.titleMedium)
        listOf("materials" to "時間割ファイル", "events" to "学校行事", "account" to "リンク・名称・授業時刻").forEach { (page, label) -> OutlinedButton(onClick = { navigate(page) }, modifier = Modifier.fillMaxWidth()) { Text(label) } }
        Text("アプリ設定", style = MaterialTheme.typography.titleMedium); ClassSettings(state.settings, change)
        OutlinedButton(onClick = { navigate("notifications") }, modifier = Modifier.fillMaxWidth()) { Text("通知") }
        var colorMenu by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("メインカラー", Modifier.weight(1f)); TextButton(onClick = { colorMenu = true }) { Text(colorNames[state.settings.color.coerceIn(0, 6)]) }; DropdownMenu(colorMenu, { colorMenu = false }) { colorNames.forEachIndexed { i, label -> DropdownMenuItem(text = { Text(label) }, onClick = { change { it.copy(color = i) }; colorMenu = false }) } } }
        Toggle("リンクをアプリ内で開く", state.settings.inAppBrowser) { v -> change { it.copy(inAppBrowser = v) } }
        Text("サポート", style = MaterialTheme.typography.titleMedium)
        listOf("setup" to "初期設定", "help" to "使い方", "about" to "このアプリについて").forEach { (page, label) -> OutlinedButton(onClick = { navigate(page) }, modifier = Modifier.fillMaxWidth()) { Text(label) } }
    }
}
@Composable fun AccountScreen(state: AppState, repository: AppRepository, login: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("リンク一覧" to (state.links?.categories?.sumOf { it.buttons.size }), "名称データ" to state.mapping?.let { it.subjects.size + it.teachers.size + it.rooms.size }, "授業時刻" to state.times?.days?.size).forEach { (name, count) -> Text("$name: ${count?.let { "取得済み（$it 件）" } ?: "未取得"}") }
        if (state.updates.isNotEmpty()) Text("新しいデータがあります。")
        Button(onClick = login, enabled = !state.busy) { Text("学校アカウントで取得") }
        TextButton(onClick = { repository.auth.cancel() }) { Text("認証を中止") }
    }
}
@Composable fun EventsScreen(state: AppState, repository: AppRepository) {
    var year by rememberSaveable { mutableStateOf(schoolYear().toString()) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(year, { year = it }, label = { Text("学校年度") }, singleLine = true)
        Button(onClick = { year.toIntOrNull()?.let { y -> repository.action { repository.fetchEvents(y) } } }, enabled = !state.busy && year.toIntOrNull() in 1900..9998) { Text("学校行事を取得") }
        state.sourceCheckMessage?.let { Text(it) }
        state.events.filter { it.schoolYear == year.toIntOrNull() }.flatMap { it.events }.forEach { Text("${it.startDate}${if (it.endDate != it.startDate) "〜${it.endDate}" else ""}\n${it.title}（${it.tag}）") }
    }
}
@Composable fun MaterialsScreen(state: AppState, repository: AppRepository, pick: (MaterialKind) -> Unit, open: (MaterialRecord) -> Unit) {
    var detail by remember { mutableStateOf<MaterialKind?>(null) }; var year by rememberSaveable { mutableStateOf(schoolYear().toString()) }; var preview by remember { mutableStateOf<Analysis?>(null) }; var warning by remember { mutableStateOf(false) }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedButton(onClick = { repository.action { repository.refresh(true) } }, enabled = !state.busy) { Text("同じファイルを再取得") } }
        items(MaterialKind.entries) { kind -> val record = state.materials.firstOrNull { it.kind == kind }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kind.title, style = MaterialTheme.typography.titleMedium); Text(record?.name ?: "未選択")
                Text(when { record?.failure != null -> "取得・解析を確認してください"; record?.analysis != null -> if (record.parsedDigest == record.digest) "解析済み" else "前回の解析結果"; else -> "未解析" })
                Row { Button(onClick = { pick(kind) }, enabled = !state.busy) { Text(if (record == null) "選択" else "選び直し") }; if (record != null) TextButton(onClick = { detail = kind; year = record.year.toString() }) { Text("詳細を見る") } }
            } }
        }
    }
    detail?.let { kind -> state.materials.firstOrNull { it.kind == kind }?.let { record -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(kind.title) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        record.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(record.name); Text("最終取得: ${dateTime(record.fetchedAt)}\n最終確認: ${dateTime(record.checkedAt)}\n最終解析: ${record.parsedAt?.let(::dateTime) ?: "未解析"}")
        record.sourceModified?.let { Text("元ファイルの更新: ${dateTime(it)}") }
        if (kind == MaterialKind.CHANGES) OutlinedTextField(year, { year = it }, label = { Text("補完する学校年度") }, singleLine = true)
        Button(onClick = { repository.action { repository.reparse(kind, year.toIntOrNull() ?: schoolYear()) } }, enabled = !state.busy) { Text("解析する") }
        if (kind != MaterialKind.CHANGES) OutlinedButton(onClick = { open(record) }) { Text("保存済みのPDFを見る") }
        if (record.failure?.contains("曜日") == true) OutlinedButton(onClick = { warning = true }) { Text("警告を確認して内容を見る") }
        record.analysis?.let { analysis -> Text("年度: ${analysis.schoolYear}\n件数: ${analysis.lessons.size + analysis.changes.size}"); analysis.changes.take(100).forEach { Text("${it.date} ${it.className} ${it.period}時限\n${it.before} → ${it.after}\n${it.note}") }; analysis.lessons.take(100).forEach { Text("${it.className} ${it.date ?: "${it.weekday}曜日"} ${it.period}時限\n${it.names.subject}\n${it.names.teacher} ${it.names.room}") } }
    } }, confirmButton = { TextButton(onClick = { detail = null }) { Text("閉じる") } }) } }
    if (warning) AlertDialog(onDismissRequest = { warning = false }, title = { Text("閲覧のみの表示") }, text = { Text("曜日の警告があります。日付を基準に内容を表示します。保存・時間割への反映は行いません。") }, confirmButton = { TextButton(onClick = { warning = false; detail?.let { kind -> repository.action { preview = repository.preview(kind, year.toIntOrNull() ?: schoolYear()) } } }) { Text("確認して表示") } }, dismissButton = { TextButton(onClick = { warning = false }) { Text("キャンセル") } })
    preview?.let { analysis -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("閲覧のみ") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { analysis.changes.forEach { Text("${it.date} ${it.className} ${it.period}\n${it.before} → ${it.after}") } } }, confirmButton = { TextButton(onClick = { preview = null }) { Text("閉じる") } }) }
}
fun dateTime(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(schoolZone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
