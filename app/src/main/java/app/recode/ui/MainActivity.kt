package app.recode.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import app.recode.ui.editor.EditorScreen

class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) shared.value = sharedUris(intent)
        setContent { RecodeTheme { App(shared) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUris(intent)?.let { shared.value = it }
    }

    /** Files shared to the app from a gallery or file manager. */
    private fun sharedUris(intent: Intent?): List<Uri>? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { listOf(it) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toList()
            Intent.ACTION_VIEW -> intent.data?.let { listOf(it) }
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }
}

@Composable
private fun App(shared: MutableState<List<Uri>?>) {
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf("home") }
    var editorUris by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var session by rememberSaveable { mutableIntStateOf(0) }

    fun openEditor(uris: List<Uri>) {
        editorUris = ArrayList(uris.map { it.toString() })
        session++
        route = "editor"
    }

    LaunchedEffect(shared.value) {
        shared.value?.let { openEditor(it); shared.value = null }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // Keep read access across restarts so queued jobs can still open their files later.
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        openEditor(uris)
    }

    BackHandler(enabled = route != "home") { route = "home" }

    when (route) {
        "editor" -> key(session) {
            EditorScreen(
                uris = editorUris.map(Uri::parse),
                vmKey = "editor-$session",
                onBack = { route = "home" },
                onQueued = { n ->
                    Toast.makeText(context, if (n > 1) "已加入 $n 个任务" else "已开始转码，进度见通知栏", Toast.LENGTH_SHORT).show()
                    route = "home"
                },
            )
        }
        "settings" -> SettingsScreen(onBack = { route = "home" })
        else -> HomeScreen(
            onPick = { picker.launch(arrayOf("video/*", "audio/*")) },
            onSettings = { route = "settings" },
        )
    }
}
