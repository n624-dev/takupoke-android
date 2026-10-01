package jp.n624.takupoke.android

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import jp.n624.takupoke.core.*

fun openLink(context: android.content.Context, url: String, inApp: Boolean) {
    if (!validLink(url)) return
    try {
        if (inApp && Uri.parse(url).scheme == "https") CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(context, "対応するアプリが見つかりませんでした。", android.widget.Toast.LENGTH_SHORT).show() }
}
@Composable fun LinkButton(link: LinkItem, settings: Settings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val color = settings.linkColors[link.id] ?: link.color
    val palette = listOf(0xFF0369A1, 0xFF1D4ED8, 0xFF047857, 0xFF15803D, 0xFF92400E, 0xFF854D0E, 0xFFC2410C, 0xFFBE123C, 0xFFB91C1C, 0xFF4338CA, 0xFF7E22CE, 0xFFBE185D, 0xFF0F766E, 0xFF475569, 0xFF4B5563)
    val parsed = linkColorNames.indexOf(color).takeIf { it >= 0 }?.let { Color(palette[it]) } ?: runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrNull()
    OutlinedButton(onClick = { openLink(context, link.href, settings.inAppBrowser) }, modifier = modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = parsed ?: MaterialTheme.colorScheme.primary)) { Text(link.label) }
}
@Composable fun LinksScreen(state: AppState, change: ((Settings) -> Settings) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }; var mode by rememberSaveable { mutableIntStateOf(0) }; var editing by remember { mutableStateOf<LinkItem?>(null) }
    val all = state.links?.categories.orEmpty().sortedBy { it.sortOrder }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("リンクを検索") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row { listOf("すべて", "お気に入り", "非表示").forEachIndexed { i, label -> FilterChip(mode == i, { mode = i }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp)) } }
        if (all.isEmpty()) Text("設定の「リンク・名称・授業時刻」から学校アカウントで取得してください。")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            all.forEach { category ->
                val values = category.buttons.filter { link -> link.visible && when (mode) { 1 -> link.id in state.settings.favorites && link.id !in state.settings.hidden; 2 -> link.id in state.settings.hidden; else -> link.id !in state.settings.hidden } && (query.isBlank() || key(link.label + " " + link.searchTerms + " " + link.searchAliases.joinToString(" ")).contains(key(query))) }.sortedBy { it.sortOrder }
                if (values.isNotEmpty()) { item { Text(category.label, style = MaterialTheme.typography.titleMedium) }; items(values, key = { it.id }) { link -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { LinkButton(link, state.settings, Modifier.weight(1f)); TextButton(onClick = { editing = link }) { Text("編集") } } } }
            }
        }
    }
    editing?.let { link -> AlertDialog(onDismissRequest = { editing = null }, title = { Text(link.label) }, text = { Column {
        Toggle("お気に入り", link.id in state.settings.favorites) { v -> change { it.copy(favorites = if (v) it.favorites + link.id else it.favorites - link.id) } }
        Toggle("一覧で非表示", link.id in state.settings.hidden) { v -> change { it.copy(hidden = if (v) it.hidden + link.id else it.hidden - link.id) } }
        Text("色")
        listOf("blue", "green", "yellow", "orange", "red", "pink", "purple").forEachIndexed { i, name -> TextButton(onClick = { change { it.copy(linkColors = it.linkColors + (link.id to name)) } }) { Text(colorNames[i]) } }
        TextButton(onClick = { change { it.copy(linkColors = it.linkColors - link.id) } }) { Text("元の色に戻す") }
    } }, confirmButton = { TextButton(onClick = { editing = null }) { Text("閉じる") } }) }
}
