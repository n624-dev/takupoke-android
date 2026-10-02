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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import jp.n624.takupoke.core.*
import java.time.LocalDate

val mainColors = listOf(Color(0xFF1674CD), Color(0xFF24833B), Color(0xFF8B7200), Color(0xFFBA5400), Color(0xFFC73535), Color(0xFFBE437E), Color(0xFF7F4CBB))
val colorNames = listOf("青", "緑", "黄色", "オレンジ", "赤", "ピンク", "紫")
val darkMainColors = listOf(Color(0xFF90CAF9), Color(0xFFA5D6A7), Color(0xFFFFF59D), Color(0xFFFFCC80), Color(0xFFEF9A9A), Color(0xFFF48FB1), Color(0xFFCE93D8))
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TakupokeUi(repository: AppRepository, pick: (MaterialKind) -> Unit, login: () -> Unit, notifyPermission: (String) -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }; var page by rememberSaveable { mutableStateOf("") }; var setupOffered by rememberSaveable { mutableStateOf(false) }
    var setupStep by rememberSaveable { mutableIntStateOf(0) }
    var returnToSetup by rememberSaveable { mutableStateOf(false) }
    var selectedLesson by remember { mutableStateOf<Pair<LocalDate, Slot>?>(null) }
    var source by remember { mutableStateOf<MaterialRecord?>(null) }
    var todayRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var clockPeriod by remember { mutableStateOf(retentionPeriod()) }
    var expiryQueued by remember(state.period) { mutableStateOf(false) }
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val selectedColor = state.settings.color?.takeIf { it in 0..6 }
    val colors = if (selectedColor == null) {
        if (android.os.Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
        else if (dark) darkColorScheme() else lightColorScheme()
    } else if (dark) darkColorScheme(primary = darkMainColors[selectedColor]) else lightColorScheme(primary = mainColors[selectedColor])
    fun settings(block: (Settings) -> Settings) { repository.preferenceAction(block) }
    fun closePage() { page = if (returnToSetup) "setup" else ""; returnToSetup = false }
    LaunchedEffect(state.ready) { if (state.ready && !state.settings.setupComplete && !setupOffered) { page = "setup"; setupOffered = true } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.ready, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { kotlinx.coroutines.delay(1000); clockPeriod = retentionPeriod(); if (state.ready && state.period != clockPeriod && !expiryQueued) { expiryQueued = true; repository.cancel(); repository.action(queued = true) { repository.activate(false) } } } } }
    LaunchedEffect(tab) { if (tab == 1 && state.ready) repository.action { repository.checkLinkRevision() } }
    LaunchedEffect(state.period) { selectedLesson = null; source = null }
    MaterialTheme(colorScheme = colors) {
        BackHandler(page.isNotEmpty()) { if (page == "setup" && setupStep > 0) setupStep-- else closePage() }
        Scaffold(topBar = { TopAppBar(title = { Text(if (page.isEmpty()) listOf("たくポケ", "一覧", "時間割", "設定")[tab] else if (page == "setup") listOf("データを取得", "時間割ファイル", "クラス")[setupStep] else mapOf("materials" to "時間割ファイル", "events" to "学校行事", "account" to "リンク・名称・授業時刻", "classes" to "クラス", "notifications" to "通知", "about" to "このアプリについて", "help" to "使い方")[page].orEmpty()) }, navigationIcon = { if (page.isNotEmpty() && page != "setup") IconButton(onClick = ::closePage) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } }, actions = { if (page == "setup") TextButton(onClick = { settings { it.copy(setupComplete = true) }; page = "" }) { Text("あとで設定") }; if (state.busy) TextButton(onClick = repository::cancel) { Text("中止") } }) }, bottomBar = {
            if (page == "setup") Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                if (setupStep > 0) TextButton(onClick = { setupStep-- }) { Text("戻る") } else Spacer(Modifier.width(64.dp))
                Text("${setupStep + 1} / 3")
                Button(onClick = { if (setupStep < 2) setupStep++ else { if (!state.settings.notificationsSetupComplete) notifyPermission("setup"); settings { it.copy(setupComplete = true) }; page = "" } }) { Text(if (setupStep == 2) "はじめる" else "次へ") }
            }
            if (page.isEmpty()) NavigationBar { val icons = listOf(Icons.Default.Home, Icons.Default.List, Icons.Default.DateRange, Icons.Default.Settings)
                listOf("ホーム", "一覧", "時間割", "設定").forEachIndexed { i, name -> NavigationBarItem(tab == i, { tab = i }, icon = { Icon(icons[i], name) }, label = { Text(name) }) }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.busy) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("処理中⋯") }
                if (!state.ready || state.period != clockPeriod) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(if (state.retentionFailure) "保存データを削除できませんでした。古いデータの利用を停止しています。" else if (state.startupFailure) "保存データを読み込めませんでした。削除はしていません。" else if (state.ready && state.period != clockPeriod) "保存期間が切り替わりました。古いデータの利用を停止しています。" else "読み込み中⋯"); if (state.retentionFailure || state.startupFailure || state.ready && state.period != clockPeriod) Button(onClick = { repository.action { repository.activate() } }, enabled = !state.busy) { Text("再試行") } } }
                else when (page) {
                    "materials" -> MaterialsScreen(state, repository, pick) { source = it }
                    "account" -> AccountScreen(state, repository, login)
                    "events" -> EventsScreen(state, repository)
                    "classes" -> Column(Modifier.padding(16.dp)) { ClassSettings(state.settings) { transform -> settings(transform) } }
                    "notifications" -> Column(Modifier.padding(16.dp)) {
                        Toggle("時間割変更", state.settings.changeNotifications) { enabled -> if (enabled) notifyPermission("changes") else settings { it.copy(changeNotifications = false, notificationsSetupComplete = true) } }
                        Toggle("試験・返却", state.settings.examNotifications) { enabled -> if (enabled) notifyPermission("exam") else settings { it.copy(examNotifications = false, notificationsSetupComplete = true) } }
                    }
                    "about" -> AboutScreen(repository, state)
                    "help" -> HelpScreen()
                    "setup" -> when (setupStep) {
                        0 -> AccountScreen(state, repository, login, setupMode = true)
                        1 -> MaterialsScreen(state, repository, pick, setupMode = true, events = { returnToSetup = true; page = "events" }) { source = it }
                        else -> Column(Modifier.padding(16.dp)) { ClassSettings(state.settings) { transform -> settings(transform) } }
                    }
                    else -> when (tab) {
                        0 -> HomeScreen(state, { page = "account" }, { page = "classes" }, { selectedLesson = it }, { todayRequest = java.util.UUID.randomUUID().toString(); tab = 2 })
                        1 -> LinksScreen(state, { page = "account" }) { transform -> settings(transform) }
                        2 -> TimetableScreen(state, { page = "classes" }, { transform -> settings(transform) }, todayRequest) { selectedLesson = it }
                        else -> SettingsScreen(state, { setupStep = 0; page = it }) { transform -> settings(transform) }
                    }
                }
            }
        }
        state.message?.let { message -> AlertDialog(onDismissRequest = repository::clearMessage, title = { Text("たくポケ") }, text = { Text(message) }, confirmButton = { TextButton(onClick = repository::clearMessage) { Text("閉じる") } }) }
        if (state.ready && state.period == retentionPeriod()) {
            selectedLesson?.let { (date, slot) -> LessonDetailScreen(state, date, slot) { selectedLesson = null } }

            source?.let { record -> PdfScreen(repository.file(record)) { source = null } }
        }
    }
}
@Composable fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, change) } }
@Composable fun ClassSettings(settings: Settings, change: ((Settings) -> Settings) -> Unit) {
    var expanded by remember { mutableStateOf(false) }; var additional by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("クラス", Modifier.weight(1f)); TextButton(onClick = { expanded = true }) { Text(settings.primaryClass.ifEmpty { "クラスを選択" }.replace('_', '-')) }; DropdownMenu(expanded, { expanded = false }) { (listOf("") + Schedule.classes).forEach { cls -> DropdownMenuItem(text = { Text(cls.ifEmpty { "クラスを選択" }.replace('_', '-')) }, onClick = { change { it.copy(primaryClass = cls, additionalClass = it.additionalClass.takeIf { other -> Schedule.compatible(cls, other) }.orEmpty()) }; expanded = false }) } } }
    val compatible = Schedule.classes.filter { Schedule.compatible(settings.primaryClass, it) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("追加クラス（1年生のみ・任意）", Modifier.weight(1f)); TextButton(onClick = { additional = true }, enabled = compatible.isNotEmpty()) { Text(settings.additionalClass.ifEmpty { "追加なし" }.replace('_', '-')) }; DropdownMenu(additional, { additional = false }) { (listOf("") + compatible).forEach { cls -> DropdownMenuItem(text = { Text(cls.ifEmpty { "追加なし" }.replace('_', '-')) }, onClick = { change { it.copy(additionalClass = cls) }; additional = false }) } } }
    Toggle("留学生向けの授業も表示", settings.international) { v -> change { it.copy(international = v) } }
}
@Composable fun ClassSelectionRow(settings: Settings, select: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("クラス", Modifier.weight(1f)); TextButton(onClick = select) { Text(listOf(settings.primaryClass, settings.additionalClass).filter(String::isNotEmpty).joinToString("・") { it.replace('_', '-') }.ifEmpty { "未選択" }) } }
}
@Composable fun SettingsScreen(state: AppState, navigate: (String) -> Unit, change: ((Settings) -> Settings) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("データ", style = MaterialTheme.typography.titleMedium)
        listOf("materials" to "時間割ファイル", "events" to "学校行事", "account" to "リンク・名称・授業時刻").forEach { (page, label) -> OutlinedButton(onClick = { navigate(page) }, modifier = Modifier.fillMaxWidth()) { Text(label) } }
        Text("アプリ設定", style = MaterialTheme.typography.titleMedium); ClassSelectionRow(state.settings) { navigate("classes") }
        OutlinedButton(onClick = { navigate("notifications") }, modifier = Modifier.fillMaxWidth()) { Text("通知") }
        var colorMenu by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("メインカラー", Modifier.weight(1f)); TextButton(onClick = { colorMenu = true }) { Text(state.settings.color?.takeIf { it in 0..6 }?.let { colorNames[it] } ?: "デフォルト") }; DropdownMenu(colorMenu, { colorMenu = false }) { DropdownMenuItem(text = { Text("デフォルト") }, onClick = { change { it.copy(color = null) }; colorMenu = false }); colorNames.forEachIndexed { i, label -> DropdownMenuItem(text = { Text(label) }, onClick = { change { it.copy(color = i) }; colorMenu = false }) } } }
        var browserMenu by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("リンクの開き方", Modifier.weight(1f)); TextButton(onClick = { browserMenu = true }) { Text(if (state.settings.inAppBrowser) "アプリ内で開く" else "デフォルトのブラウザ") }; DropdownMenu(browserMenu, { browserMenu = false }) { listOf(false to "デフォルトのブラウザ", true to "アプリ内で開く").forEach { (inApp, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { change { it.copy(inAppBrowser = inApp) }; browserMenu = false }) } } }
        Text("サポート", style = MaterialTheme.typography.titleMedium)
        listOf("setup" to "初期設定", "help" to "使い方", "about" to "このアプリについて").forEach { (page, label) -> OutlinedButton(onClick = { navigate(page) }, modifier = Modifier.fillMaxWidth()) { Text(label) } }
    }
}
@Composable fun AccountScreen(state: AppState, repository: AppRepository, login: () -> Unit, setupMode: Boolean = false) {
    var detail by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (setupMode) { Text("学校アカウントで取得する", style = MaterialTheme.typography.titleMedium); Text("リンク一覧・名称データ・授業時刻をまとめて取得します。") }
        Button(onClick = login, enabled = !state.busy) { Text(if (state.links == null || state.mapping == null || state.times == null) "学校アカウントで取得" else "更新を確認") }
        Text("データ", style = MaterialTheme.typography.titleMedium)
        listOf(Triple("リンク一覧", "links-revision", state.links != null), Triple("名称データ", "mapping-revision", state.mapping != null), Triple("授業時刻", "timetable-times-revision", state.times != null)).forEach { (name, key, acquired) ->
            Row(Modifier.fillMaxWidth().clickable { detail = key }) { Text(name, Modifier.weight(1f)); Text(if (key in state.accountErrors) "要確認" else if (!acquired) "未取得" else if (key in state.updates) "更新あり" else "取得済み"); Text("　›") }
        }
        state.accountErrors.values.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) TextButton(onClick = { repository.auth.cancel(); repository.cancel() }) { Text("中止") }
    }
    detail?.let { key -> AccountDetailScreen(state, key) { detail = null } }
}
@Composable fun EventsScreen(state: AppState, repository: AppRepository) {
    var year by rememberSaveable { mutableStateOf(schoolYear().toString()) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(year, { year = it }, label = { Text("学校年度") }, singleLine = true)
        val selectedYear = if (year.isBlank()) schoolYear() else year.toIntOrNull()
        val saved = state.events.firstOrNull { it.schoolYear == selectedYear }
        Text(if (state.eventsError != null) "要確認" else if (saved == null) "未取得" else "取得済み")
        state.eventsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        selectedYear?.let { state.eventsFetchedAt[it]?.let { time -> Text("最終取得: ${dateTime(time)}") }; state.eventsCheckedAt[it]?.let { time -> Text("最終確認: ${dateTime(time)}") } }
        saved?.let { Text("件数: ${it.events.size}件") }
        Button(onClick = { selectedYear?.let { y -> repository.action { repository.fetchEvents(y) } } }, enabled = !state.busy && selectedYear in 1900..9998) { Text(if (saved == null) "学校行事を取得" else "学校行事を更新") }
        state.sourceCheckMessage?.let { Text(it) }
        state.events.filter { it.schoolYear == selectedYear }.flatMap { it.events }.forEach { Text("${it.startDate}${if (it.endDate != it.startDate) "〜${it.endDate}" else ""}\n${it.title}（${it.tag}）") }
    }
}
@Composable fun MaterialsScreen(state: AppState, repository: AppRepository, pick: (MaterialKind) -> Unit, setupMode: Boolean = false, events: () -> Unit = {}, open: (MaterialRecord) -> Unit) {
    var detail by remember { mutableStateOf<MaterialKind?>(null) }; var year by rememberSaveable { mutableStateOf(schoolYear().toString()) }; var preview by remember { mutableStateOf<Analysis?>(null) }; var warning by remember { mutableStateOf(false) }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (setupMode) item { Text("ファイルを選ぶ", style = MaterialTheme.typography.titleMedium); Text("通常時間割のPDFと時間割変更のExcelファイルを選びます。選択後、自動で解析します。"); Text("試験時間割・試験返却時間割のPDFは、手元にある場合に選んでください。") }
        if (!setupMode) item { TextButton(onClick = repository::suspendAutomaticRefresh, enabled = !state.automaticRefreshSuspended) { Text(if (state.automaticRefreshSuspended) "自動確認を中止中" else "自動確認を中止") } }
        items(MaterialKind.entries) { kind -> val record = state.materials.firstOrNull { it.kind == kind }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kind.title, style = MaterialTheme.typography.titleMedium); Text(record?.name ?: "未選択")
                if (record != null) Text(when { record.failure != null -> if (record.analysis == null) "要確認" else "要確認（前回結果あり）"; record.analysis != null -> if (record.parsedDigest == record.digest) "解析済み" else "未解析（前回結果あり）"; else -> "未解析" })
                Row { Button(onClick = { pick(kind) }, enabled = !state.busy) { Text(if (record == null) "ファイルを選ぶ" else "ファイルを選び直す") }; if (record != null) TextButton(onClick = { detail = kind; year = record.year.toString() }) { Text("詳細を見る") } }
            } }
        }
        if (setupMode) item { TextButton(onClick = events) { Text("学校行事を取得") } }
    }
    detail?.let { kind -> state.materials.firstOrNull { it.kind == kind }?.let { record -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(kind.title) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        record.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(record.name); Text("サイズ: ${android.text.format.Formatter.formatFileSize(LocalContext.current, repository.file(record).length())}"); Text("最終取得: ${dateTime(record.fetchedAt)}\n最終確認: ${dateTime(record.checkedAt)}\n最終解析成功: ${record.parsedAt?.let(::dateTime) ?: "未解析"}")
        record.sourceModified?.let { Text("元ファイルの更新: ${dateTime(it)}") }
        if (kind == MaterialKind.CHANGES) OutlinedTextField(year, { year = it }, label = { Text("補完年度") }, singleLine = true)
        Button(onClick = { repository.action { repository.reparse(kind, year.toIntOrNull() ?: schoolYear()) } }, enabled = !state.busy && (year.isBlank() || year.toIntOrNull() in 1900..9998)) { Text("解析する") }
        OutlinedButton(onClick = { repository.action { repository.refreshMaterial(kind) } }, enabled = !state.busy) { Text("同じファイルを再取得") }
        if (kind != MaterialKind.CHANGES) OutlinedButton(onClick = { open(record) }) { Text("保存済みのPDFを見る") }
        if (record.failure?.contains("曜日") == true) OutlinedButton(onClick = { warning = true }) { Text("警告を確認して内容を見る") }
        record.analysis?.let { analysis ->
            Text("学校年度: ${analysis.schoolYear}年度\n件数: ${analysis.lessons.size + analysis.changes.size}件")
            if (kind == MaterialKind.TIMETABLE) Text("学期: ${when (analysis.term) { 1 -> "前期"; 2 -> "後期"; else -> "未確認" }}")
            if (record.parsedDigest != record.digest || analysis.parserVersion != PARSER_VERSION) Text("前回の解析結果です。現在のファイルを解析してください。", color = MaterialTheme.colorScheme.error)
            val selected = if (kind == MaterialKind.CHANGES) state.settings.changesFilter else state.settings.timetableFilter
            var menu by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("クラス", Modifier.weight(1f)); TextButton(onClick = { menu = true }) { Text(selected.ifEmpty { "すべて" }.let(::displayClass)) }; DropdownMenu(menu, { menu = false }) { (listOf("") + analysis.classes + listOf(selected)).distinct().forEach { cls -> DropdownMenuItem(text = { Text(cls.ifEmpty { "すべて" }.let(::displayClass)) }, onClick = { repository.preferenceAction { if (kind == MaterialKind.CHANGES) it.copy(changesFilter = cls) else it.copy(timetableFilter = cls) }; menu = false }) } } }
            if (selected.isNotEmpty() && selected !in analysis.classes) Text("選択したクラスは現在の解析結果にありません。選択は保持しています。")
            if (kind == MaterialKind.TIMETABLE) {
                var weekdayMenu by remember { mutableStateOf(false) }
                val weekdays = listOf("すべて", "月", "火", "水", "木", "金")
                TextButton(onClick = { weekdayMenu = true }) { Text("曜日: ${weekdays[state.settings.timetableWeekdayFilter.coerceIn(0, 5)]}") }
                DropdownMenu(weekdayMenu, { weekdayMenu = false }) { weekdays.forEachIndexed { i, label -> DropdownMenuItem(text = { Text(label) }, onClick = { repository.preferenceAction { it.copy(timetableWeekdayFilter = i) }; weekdayMenu = false }) } }
            }
            analysis.changes.filter { selected.isEmpty() || canonicalClass(it.className) == selected }.forEach { row ->
                Text("${row.date} ${displayClass(row.className)} ${row.period}限　${row.type}")
                Text("変更前: ${row.before}\n変更後: ${row.after}\n教員: ${row.teacher}\n教室: ${row.room}\n備考: ${row.note}\n元の記載: ${row.raw}")
            }
            analysis.lessons.filter { (selected.isEmpty() || it.className == selected) && (kind != MaterialKind.TIMETABLE || state.settings.timetableWeekdayFilter == 0 || it.weekday == state.settings.timetableWeekdayFilter) }.forEach { lesson ->
                val names = state.mapping?.apply(lesson.names, lesson.className) ?: lesson.names
                Text("${displayClass(lesson.className)} ${lesson.date ?: "${lesson.weekday}曜日"} ${lesson.period}限")
                Text("科目: ${names.subjectFull.ifEmpty { names.subject }}\n教員: ${names.teacherFull.ifEmpty { names.teacher }}\n教室: ${names.roomFull.ifEmpty { names.room }}")
                lesson.time?.let { Text("時刻: $it") }; if (lesson.sourceText.isNotEmpty()) Text("元のセルの記載\n${lesson.sourceText}")
            }
        }

    } }, confirmButton = { TextButton(onClick = { detail = null }) { Text("閉じる") } }) } }
    if (warning) AlertDialog(onDismissRequest = { warning = false }, title = { Text("曜日を確認できないファイルです") }, text = { Text("日付と曜日が合わないか、曜日の計算結果が保存されていません。日付欄を基準に内容を表示しますが、正しい内容かは元ファイルで確認してください。前回の正常データは置き換えません。") }, confirmButton = { TextButton(onClick = { warning = false; detail?.let { kind -> repository.action { preview = repository.preview(kind, year.toIntOrNull() ?: schoolYear()) } } }) { Text("確認して表示") } }, dismissButton = { TextButton(onClick = { warning = false }) { Text("キャンセル") } })
    preview?.let { analysis -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("プレビュー（閲覧のみ）") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { analysis.changes.forEach { Text("${it.date} ${it.className} ${it.period}\n${it.before} → ${it.after}") } } }, confirmButton = { TextButton(onClick = { preview = null }) { Text("閉じる") } }) }
}
fun dateTime(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(schoolZone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
