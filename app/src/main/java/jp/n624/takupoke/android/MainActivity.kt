package jp.n624.takupoke.android

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.browser.customtabs.CustomTabsIntent
import jp.n624.takupoke.core.MaterialKind

class MainActivity : ComponentActivity() {
    private val repository get() = (application as TakupokeApplication).repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            var pendingKind by rememberSaveable { mutableStateOf<String?>(null) }
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val kind = pendingKind?.let(MaterialKind::valueOf); pendingKind = null
                val uri = result.data?.data
                if (result.resultCode == RESULT_OK && kind != null && uri != null) repository.action { repository.select(kind, uri, result.data!!.flags) }
            }
            val permission = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            TakupokeUi(repository, pick = { kind ->
                pendingKind = kind.name
                picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (kind == MaterialKind.CHANGES) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
            }, login = { CustomTabsIntent.Builder().build().launchUrl(this, repository.auth.begin()) }, notifyPermission = { if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) })
        }
        handleCallback(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleCallback(intent) }
    private fun handleCallback(intent: Intent) { intent.data?.takeIf { it.scheme == "jp.n624.takupoke.android" }?.let { uri -> repository.action(queued = true) { repository.finishAuth(uri) }; intent.data = null } }
    override fun onResume() { super.onResume(); repository.foreground(true); repository.action { repository.activate() } }
    override fun onPause() { repository.foreground(false); super.onPause() }
    override fun onStop() { (application as TakupokeApplication).schedule(); super.onStop() }
}
