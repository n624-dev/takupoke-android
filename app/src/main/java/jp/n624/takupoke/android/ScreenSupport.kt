package jp.n624.takupoke.android

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun AboutScreen(repository: AppRepository, state: AppState) {
    val context = LocalContext.current
    var document by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("アプリ情報", style = MaterialTheme.typography.titleMedium)
        Text("香川高専詫間キャンパスの学生向けに個人が開発・運営する非公式アプリです。")
        Text("バージョン: ${BuildConfig.VERSION_NAME}")
        Text("ビルド: ${BuildConfig.VERSION_CODE}")
        Button(onClick = { repository.action { repository.checkRelease() } }, enabled = !state.busy) { Text("アプリの更新を確認") }
        state.updateUrl?.let { url -> TextButton(onClick = { openLink(context, url, state.settings.inAppBrowser) }) { Text("配布ページを開く") } }
        Text("規約・プライバシー", style = MaterialTheme.typography.titleMedium)
        listOf("利用規約" to "terms.txt", "プライバシーポリシー" to "privacy.txt").forEach { (label, file) -> TextButton(onClick = { document = context.assets.open(file).bufferedReader().use { it.readText() } }) { Text(label) } }
        listOf("たくにんの利用規約" to "https://takuma-gakunin.n624.jp/terms", "たくにんのプライバシーポリシー" to "https://takuma-gakunin.n624.jp/privacy").forEach { (label, url) -> TextButton(onClick = { openLink(context, url, true) }) { Text(label) } }
        Text("問い合わせ・配布", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { openLink(context, "https://github.com/n624-dev/takupoke-android", state.settings.inAppBrowser) }) { Text("ソースコード") }
        TextButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:takupoke@n624.jp"))) } catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(context, "対応するアプリが見つかりませんでした。", android.widget.Toast.LENGTH_SHORT).show() } }) { Text("問い合わせ") }
        TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://github.com/n624-dev/takupoke-android/releases/latest"), "配布URLを共有")) }) { Text("配布URLを共有") }
        Text("ライセンス", style = MaterialTheme.typography.titleMedium)
        Text("オープンソースライセンス", style = MaterialTheme.typography.titleMedium)
        listOf("LiteRT-LM" to "litertlm", "Gson" to "gson", "ML Kit Text Recognition（日本語）" to "mlkit", "PDFBox-Android" to "pdfbox", "AndroidX" to "androidx", "Kotlin / kotlinx.serialization" to "kotlin", "OkHttp" to "okhttp", "Okio" to "okio", "Bouncy Castle" to "bouncycastle").forEach { (label, name) -> TextButton(onClick = { document = context.assets.open("license-$name.txt").bufferedReader().use { it.readText() } }) { Text(label) } }
    }
    document?.let { text ->
        val blocks = remember(text) { text.lineSequence().chunked(40).map { it.joinToString("\n") }.toList() }
        AlertDialog(onDismissRequest = { document = null }, text = { LazyColumn(Modifier.heightIn(max = 480.dp)) { items(blocks) { Text(it) } } }, confirmButton = { TextButton(onClick = { document = null }) { Text("閉じる") } })
    }
}
@Composable fun HelpScreen() {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(
            "はじめに" to "「設定」→「リンク・名称・授業時刻」で学校アカウントのデータを取得し、「時間割ファイル」で通常時間割のPDFと時間割変更のExcelファイルを選びます。「クラス」で表示するクラスを選びます。試験時間割・試験返却時間割のPDFは、手元にある場合に選んでください。元ファイルは変更しません。",
            "時間割を見る" to "ホームに今日の授業と行事を表示します。「時間割を見る」で今日を含む週を開きます。「前週」「翌週」で週を移動し、週の日付からカレンダーで移動先を選べます。「通常」「変更込み」で表示を切り替えます。授業を押すと詳細を確認できます。連続授業はまとめて表示します。時間割の下の変更一覧は「今日以降」「この週」「全件」で絞り込み、対象クラスを別に選べます。",
            "リンクを使う" to "一覧タブでリンクを押します。検索欄から名前を探せます。「設定」→「リンクの開き方」で、アプリ内かデフォルトのブラウザかを選びます。リンクを長押しして「お気に入りに追加」「色を変更」「非表示」を選びます。お気に入りはホームにも表示されます。「非表示のリンク」から「再表示」で戻せます。",
            "更新と通知" to "選択済みファイルは起動時・手動・バックグラウンドで確認します。「設定」→「通知」で「時間割変更」「試験・返却」のオン・オフを切り替えます。初回の取り込みは通知せず、その後の変更を通知します。実行時期はAndroidが決めます。ロック中などで保存データを読めない場合は見送ります。4月・10月の保存期間切り替え時に学校の非公開データとファイルのアクセス権を削除します。元ファイル、個人設定、公開学校行事は削除しません。",
            "困ったとき" to "OneDriveを開いて対象ファイルの同期状況を確認します。最新版が届く時期はOneDriveの同期状況によって異なります。読み取れない場合は「設定」→「時間割ファイル」で選び直します。解析に失敗した場合は「詳細を見る」でエラーを確認し、ファイルを確認した後「解析する」を押してください。前回の正常な結果があれば保持します。"
        ).forEach { (title, description) ->
            if (selected == null) OutlinedButton(onClick = { selected = title }, modifier = Modifier.fillMaxWidth()) { Text(title) }
            else if (selected == title) { TextButton(onClick = { selected = null }) { Text("使い方に戻る") }; Text(title, style = MaterialTheme.typography.titleMedium); Text(description) }
        }
    }
}
@Composable fun PdfScreen(file: File, updated: Boolean = false, close: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }; var count by remember { mutableIntStateOf(0) }; var bitmap by remember { mutableStateOf<Bitmap?>(null) }; var failed by remember { mutableStateOf(false) }
    LaunchedEffect(file, page) {
        bitmap = null; failed = false
        try { bitmap = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                count = renderer.pageCount
                renderer.openPage(page).use { p -> val width = 1600; val height = (width.toLong() * p.height / p.width).toInt().coerceIn(1, 3000); Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } }
            } }
        } } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize()) { Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text("前") }; Text("${page + 1}/$count", Modifier.padding(12.dp)); TextButton(onClick = { if (page + 1 < count) page++ }, enabled = page + 1 < count) { Text("次") }; TextButton(onClick = close) { Text("閉じる") }
        }
        if (updated) Text("PDFが更新されたため、新しい資料を表示しています。内容を再確認してください。", Modifier.padding(12.dp))
        if (failed) Text("保存済みPDFを表示できませんでした。")
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "保存済みPDF ${page + 1}ページ", Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) else if (!failed) CircularProgressIndicator()
    } } }
}
