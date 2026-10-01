package jp.n624.takupoke.android

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun AboutScreen(repository: AppRepository, state: AppState) {
    val context = LocalContext.current
    var document by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("たくポケ Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
        Text("時間割・変更・学校行事を確認する非公式アプリです。学校の公式サービスではありません。重要な予定は必ず学校の原資料で確認してください。")
        Button(onClick = { repository.action { repository.checkRelease() } }, enabled = !state.busy) { Text("アプリの更新を確認") }
        state.updateUrl?.let { url -> TextButton(onClick = { openLink(context, url, state.settings.inAppBrowser) }) { Text("配布ページを開く") } }
        TextButton(onClick = { openLink(context, "https://github.com/n624-dev/takupoke-android", state.settings.inAppBrowser) }) { Text("ソースコード・お問い合わせ") }
        listOf("プライバシー" to "privacy.txt", "利用上の注意" to "terms.txt").forEach { (label, file) -> TextButton(onClick = { document = context.assets.open(file).bufferedReader().use { it.readText() } }) { Text(label) } }
        Text("オープンソースライセンス", style = MaterialTheme.typography.titleMedium)
        listOf("PDFBox-Android" to "pdfbox", "AndroidX" to "androidx", "Kotlin / kotlinx.serialization" to "kotlin", "OkHttp" to "okhttp", "Okio" to "okio", "Bouncy Castle" to "bouncycastle").forEach { (label, name) -> TextButton(onClick = { document = context.assets.open("license-$name.txt").bufferedReader().use { it.readText() } }) { Text(label) } }
    }
    document?.let { text -> AlertDialog(onDismissRequest = { document = null }, text = { Text(text, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { document = null }) { Text("閉じる") } }) }
}
@Composable fun HelpScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(
            "データの設定" to "設定から通常時間割PDF・時間割変更XLSX・試験PDF・試験返却PDFを選択し、クラスを設定します。ファイルの選択はAndroid標準の画面で行います。元ファイルは変更しません。",
            "ホーム・リンク一覧" to "ホームに今日の授業とおすすめを表示します。一覧で検索・お気に入り・非表示・色を設定できます。リンクはアプリ内ブラウザまたは外部アプリで開きます。",
            "時間割" to "週を切り替え、変更反映の有無、5日・7日表示、文字サイズを選べます。授業をタップすると元の記載・名称・変更の詳細を確認できます。",
            "ファイルの更新" to "選択したファイルを起動時・手動・バックグラウンドで確認します。クラウドの更新がAndroidに届くまで遅れる場合があります。同じファイルの再取得でも変わらない場合は、選び直して原資料を確認してください。",
            "保存と通知" to "4月・10月の保存期間切り替え時に学校の非公開データとファイルのアクセス権を削除します。元ファイル、個人設定、公開学校行事は削除しません。通知には授業名などの内容を含めません。"
        ).forEach { (title, description) -> Text(title, style = MaterialTheme.typography.titleMedium); Text(description) }
    }
}
@Composable fun PdfScreen(file: File, close: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }; var count by remember { mutableIntStateOf(0) }; var bitmap by remember { mutableStateOf<Bitmap?>(null) }; var failed by remember { mutableStateOf(false) }
    LaunchedEffect(file, page) {
        bitmap = null; failed = false
        try { bitmap = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                count = renderer.pageCount
                renderer.openPage(page).use { p -> val width = 1600; val height = (width.toLong() * p.height / p.width).toInt().coerceIn(1, 3000); Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } }
            } }
        } } catch (_: Exception) { failed = true }
    }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize()) { Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text("前") }; Text("${page + 1}/$count", Modifier.padding(12.dp)); TextButton(onClick = { if (page + 1 < count) page++ }, enabled = page + 1 < count) { Text("次") }; TextButton(onClick = close) { Text("閉じる") }
        }
        if (failed) Text("保存済みPDFを表示できませんでした。")
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "保存済みPDF ${page + 1}ページ", Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) else if (!failed) CircularProgressIndicator()
    } } }
}
