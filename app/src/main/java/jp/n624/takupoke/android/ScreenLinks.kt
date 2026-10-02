package jp.n624.takupoke.android

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import jp.n624.takupoke.core.*

fun openLink(context: android.content.Context, url: String, inApp: Boolean) {
    if (!validLink(url)) return
    try {
        if (inApp && Uri.parse(url).scheme?.equals("https", true) == true) CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(context, "対応するアプリが見つかりませんでした。", android.widget.Toast.LENGTH_SHORT).show() }
}
@Composable fun LinkButton(link: LinkItem, settings: Settings, modifier: Modifier = Modifier, edit: (() -> Unit)? = null, subtitle: String? = null) {
    val context = LocalContext.current
    val color = settings.linkColors[link.id] ?: link.color
    val palette = listOf(0xFF0369A1, 0xFF1D4ED8, 0xFF047857, 0xFF15803D, 0xFF92400E, 0xFF854D0E, 0xFFC2410C, 0xFFBE123C, 0xFFB91C1C, 0xFF4338CA, 0xFF7E22CE, 0xFFBE185D, 0xFF0F766E, 0xFF475569, 0xFF4B5563)
    val dark = listOf(0xFF7DD3FC, 0xFF93C5FD, 0xFF6EE7B7, 0xFF86EFAC, 0xFFFCD34D, 0xFFFDE047, 0xFFFDBA74, 0xFFFDA4AF, 0xFFFCA5A5, 0xFFA5B4FC, 0xFFD8B4FE, 0xFFF9A8D4, 0xFF5EEAD4, 0xFFCBD5E1, 0xFFD1D5DB)
    val chosen = if (isSystemInDarkTheme()) dark else palette
    val parsed = linkColorNames.indexOf(color).takeIf { it >= 0 }?.let { Color(chosen[it]) } ?: runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrNull()
    OutlinedCard(modifier = modifier.fillMaxWidth().combinedClickable(onClick = { openLink(context, link.href, settings.inAppBrowser) }, onLongClick = edit)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("↗", color = parsed ?: MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) { Text(link.label); subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (link.id in settings.favorites) Text("★", color = Color(0xFFB08900))
        }
    }
}
@Composable fun LinksScreen(state: AppState, account: () -> Unit, change: ((Settings) -> Settings) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }; var hidden by rememberSaveable { mutableStateOf(false) }; var editing by remember { mutableStateOf<LinkItem?>(null) }
    val all = state.links?.categories.orEmpty().sortedBy { it.sortOrder }
    val searching = LinkSearch.normalize(query).isNotEmpty()
    val categories = all.flatMap { category -> category.buttons.map { it.id to category.label } }.toMap()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("リンクを検索") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        TextButton(onClick = { hidden = !hidden }) { Text(if (hidden) "一覧" else "非表示のリンク") }
        val visible = all.flatMap { it.buttons.sortedBy { link -> link.sortOrder } }.filter { it.visible && if (hidden) it.id in state.settings.hidden else it.id !in state.settings.hidden }
        if (hidden) Text("非表示のリンク", style = MaterialTheme.typography.titleMedium)
        if (state.links == null && !hidden) {
            Text(if (state.busy) "一覧を取得中⋯" else "一覧はまだ取得されていません。")
            TextButton(onClick = account, enabled = !state.busy) { Text("リンク一覧を取得") }
        } else if (visible.isEmpty()) Text(if (hidden) "非表示のリンクはありません。" else "表示できるリンクがありません。")
        else if (!hidden && searching) {
            Text("検索結果", style = MaterialTheme.typography.titleMedium)
            if (visible.none { LinkSearch.score(it.searchTerms, query) >= 0 }) Text("該当するリンクがありません。")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (searching && !hidden) {
                val results = visible.filter { LinkSearch.score(it.searchTerms, query) >= 0 }.sortedByDescending { LinkSearch.score(it.searchTerms, query) }
                items(results, key = { it.id }) { link -> EditableLink(link, state.settings, { editing = link }, change, categories[link.id]) }
            } else all.forEach { category ->
                val values = category.buttons.filter { link -> link in visible && (!searching || LinkSearch.score(link.searchTerms, query) >= 0) }.sortedBy { it.sortOrder }
                if (values.isNotEmpty()) {
                    if (!hidden) item { Text(category.label, style = MaterialTheme.typography.titleMedium) }
                    items(values, key = { it.id }) { link ->
                        if (hidden) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(link.label, Modifier.weight(1f)); TextButton(onClick = { change { it.copy(hidden = it.hidden - link.id) } }) { Text("再表示") } }
                        else EditableLink(link, state.settings, { editing = link }, change)
                    }
                }
            }

        }
    }
    editing?.let { LinkEditDialog(it, state.settings, { editing = null }, change) }
}

@Composable fun LinkEditDialog(link: LinkItem, settings: Settings, close: () -> Unit, change: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = { close() }, title = { Text(link.label) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        if (Uri.parse(link.href).scheme?.equals("https", true) == true) TextButton(onClick = { openLink(context, link.href, !settings.inAppBrowser); close() }) { Text(if (settings.inAppBrowser) "デフォルトのブラウザで開く" else "アプリ内で開く") }
        TextButton(onClick = { change { it.copy(favorites = if (link.id in it.favorites) it.favorites - link.id else it.favorites + link.id) } }) { Text(if (link.id in settings.favorites) "お気に入りを解除" else "お気に入りに追加") }
        TextButton(onClick = { change { it.copy(hidden = it.hidden + link.id) }; close() }) { Text("非表示") }
        Text("色を変更")
        val labels = listOf("スカイ", "ブルー", "エメラルド", "グリーン", "アンバー", "イエロー", "オレンジ", "ローズ", "レッド", "インディゴ", "パープル", "ピンク", "ティール", "スレート", "グレー")
        linkColorNames.forEachIndexed { i, name -> TextButton(onClick = { change { it.copy(linkColors = it.linkColors + (link.id to name)) } }) { Text(labels[i]) } }
        if (link.id in settings.linkColors) TextButton(onClick = { change { it.copy(linkColors = it.linkColors - link.id) } }) { Text("既定色に戻す") }
    } }, confirmButton = { TextButton(onClick = { close() }) { Text("閉じる") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EditableLink(link: LinkItem, settings: Settings, edit: () -> Unit, change: ((Settings) -> Settings) -> Unit, subtitle: String? = null) {
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.StartToEnd -> change { it.copy(favorites = if (link.id in it.favorites) it.favorites - link.id else it.favorites + link.id) }
            SwipeToDismissBoxValue.EndToStart -> change { it.copy(hidden = it.hidden + link.id) }
            else -> Unit
        }
        false
    })
    SwipeToDismissBox(state = swipe, backgroundContent = { Row(Modifier.fillMaxSize().padding(12.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(if (link.id in settings.favorites) "お気に入りを解除" else "お気に入りに追加"); Text("非表示") } }) {
        LinkButton(link, settings, edit = edit, subtitle = subtitle)
    }
}
