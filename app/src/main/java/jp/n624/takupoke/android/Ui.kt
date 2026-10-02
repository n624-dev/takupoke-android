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
                        0 -> HomeScreen(state, { page = "account" }, { page = "classes" }, { selectedLesson = it }, { todayRequest = java.util.UUID.randomUUID().toString(); tab = 2 }, { transform -> settings(transform) })
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
fun dateTime(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(schoolZone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
