@file:OptIn(ExperimentalMaterial3Api::class)

package app.recode.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.recode.RecodeApp
import app.recode.core.AudioEncoder
import app.recode.core.VideoEncoder
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as RecodeApp).graph
    val caps by graph.caps.caps.collectAsState()
    val userPresets by graph.presets.user.collectAsState()
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("通知") {
                    // Called reflectively: the API is new in Android 16 and absent from older SDK stubs.
                    val promoted = Build.VERSION.SDK_INT >= 36 && runCatching {
                        val nm = context.getSystemService(NotificationManager::class.java)
                        NotificationManager::class.java.getMethod("canPostPromotedNotifications").invoke(nm) as Boolean
                    }.getOrDefault(false)
                    Text(
                        when {
                            Build.VERSION.SDK_INT < 36 -> "转码进度显示在普通通知里。Android 16 及以上会显示为实时通知（状态栏胶囊 + 分段进度条）。"
                            promoted -> "实时通知已开启：转码时状态栏会显示进度胶囊。"
                            else -> "实时通知目前被关闭了，转码进度只会以普通通知显示。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = { openNotificationSettings(context) }) { Text("通知设置") }
                }
            }
            item {
                Section("此设备的编码能力") {
                    val c = caps
                    if (c == null || probing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  正在检测…")
                        }
                    } else {
                        Text("FFmpeg ${c.ffmpegVersion} · ${Build.MANUFACTURER} ${Build.MODEL} · ${socModel()}", style = MaterialTheme.typography.bodySmall)
                        VideoEncoder.entries.filter { it.reencodes }.forEach { e ->
                            CapRow(e.label + if (c.supportsTenBit(e)) " · 10-bit" else "", c.isAvailable(e))
                        }
                        AudioEncoder.entries.filter { it.reencodes }.forEach { e -> CapRow("音频 · ${e.label}", c.isAvailable(e)) }
                        CapRow("HDR → SDR 色调映射", c.canTonemap)
                    }
                    OutlinedButton(onClick = {
                        probing = true
                        scope.launch { runCatching { graph.caps.load(force = true) }; probing = false }
                    }, enabled = !probing) { Text("重新检测") }
                }
            }
            item {
                Section("我的预设") {
                    if (userPresets.isEmpty()) {
                        Text("在编辑页点「存为预设」即可保存常用设置。", style = MaterialTheme.typography.bodyMedium)
                    }
                    userPresets.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, Modifier.weight(1f))
                            IconButton(onClick = { graph.presets.delete(p.id) }) { Icon(Icons.Filled.Delete, "删除") }
                        }
                    }
                }
            }
            item {
                Section("关于") {
                    Text(
                        "Recode 使用 FFmpeg（通过 FFmpegKit）进行转码。本应用包含 GPL 授权的 x264、x265 等组件，因此整体以 GPL-3.0 发布。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("输出文件保存在「影片/Recode」或「音乐/Recode」。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun socModel(): String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""

@Composable
private fun CapRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Clear, null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
        Text("  $label", style = MaterialTheme.typography.bodyMedium, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

private fun openNotificationSettings(context: Context) {
    val pkg = context.packageName
    val candidates = buildList {
        if (Build.VERSION.SDK_INT >= 36) {
            add(Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
        }
        add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }
    for (i in candidates) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}
