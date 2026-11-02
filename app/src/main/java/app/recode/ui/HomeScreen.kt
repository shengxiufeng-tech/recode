@file:OptIn(ExperimentalMaterial3Api::class)

package app.recode.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.recode.R
import app.recode.RecodeApp
import app.recode.core.Format
import app.recode.core.Job
import app.recode.core.JobStatus
import app.recode.diag.CrashReporter
import app.recode.service.EncodeService

@Composable
fun HomeScreen(onPick: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as RecodeApp).graph
    val jobs by graph.jobs.jobs.collectAsState()
    val sorted = jobs.sortedWith(compareBy<Job> { statusOrder(it.status) }.thenByDescending { if (it.isActive) -it.createdAt else it.finishedAt })
    val running = jobs.any { it.status == JobStatus.RUNNING }
    val queued = jobs.count { it.status == JobStatus.QUEUED }
    var crash by remember { mutableStateOf(CrashReporter.pending(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recode", fontWeight = FontWeight.SemiBold) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "设置") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onPick, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("添加文件") })
        },
    ) { padding ->
        if (jobs.isEmpty() && crash == null) {
            EmptyState(Modifier.padding(padding), onPick)
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            crash?.let { report ->
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Recode 上次异常退出了", style = MaterialTheme.typography.titleSmall)
                            Text(report.lineSequence().take(3).joinToString("\n").take(300), style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = {
                                    runCatching { context.startActivity(CrashReporter.shareIntent(context, graph.diagnostics())) }
                                }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text(" 分享报告") }
                                TextButton(onClick = { CrashReporter.dismiss(context); crash = null }) { Text("忽略") }
                            }
                        }
                    }
                }
            }
            if (queued > 0 && !running) {
                item {
                    Card {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("队列中还有 $queued 个任务", Modifier.weight(1f))
                            Button(onClick = { EncodeService.start(context) }) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Text(" 继续")
                            }
                        }
                    }
                }
            }
            items(sorted, key = { it.id }) { job -> JobCard(job) }
            if (jobs.any { !it.isActive }) {
                item {
                    TextButton(onClick = { graph.jobs.clearFinished() }, modifier = Modifier.fillMaxWidth()) { Text("清除已结束的任务") }
                }
            }
        }
    }
}

private fun statusOrder(s: JobStatus) = when (s) {
    JobStatus.RUNNING -> 0
    JobStatus.QUEUED -> 1
    else -> 2
}

@Composable
private fun EmptyState(modifier: Modifier, onPick: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.ic_movie), null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("把视频变小、换格式、剪一段", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "支持骁龙硬件 H.264 / HEVC 编码，也可以用 x264、x265、AV1 软件编码获得更好画质。转码在后台进行，进度显示在通知栏。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onPick) { Text("选择视频或音频") }
    }
}

@Composable
private fun JobCard(job: Job) {
    val context = LocalContext.current
    val graph = (context.applicationContext as RecodeApp).graph
    var details by rememberSaveable(job.id) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.inputName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "→ ${job.outputName} · ${job.presetName}" + if (job.segments.isNotEmpty()) " · 剪切 ${job.segments.size} 段" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(job.status)
            }

            when (job.status) {
                JobStatus.RUNNING -> {
                    LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        listOfNotNull(
                            "${(job.progress * 100).toInt()}%",
                            job.etaMs.takeIf { it >= 0 }?.let { "剩余 ${Format.duration(it)}" },
                            Format.speed(job.speed).ifEmpty { null },
                            job.outputBytes.takeIf { it > 0 }?.let { Format.bytes(it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                JobStatus.DONE -> {
                    val saved = if (job.inputBytes > 0 && job.outputBytes > 0) {
                        val pct = (100 - job.outputBytes * 100 / job.inputBytes).toInt()
                        if (pct > 0) "，比原文件小 $pct%" else ""
                    } else ""
                    val took = if (job.finishedAt > job.startedAt && job.startedAt > 0) "，用时 ${Format.duration(job.finishedAt - job.startedAt)}" else ""
                    Text("${Format.bytes(job.outputBytes)}$saved$took", style = MaterialTheme.typography.bodySmall)
                }
                JobStatus.FAILED -> Text(job.error ?: "未知错误", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = if (details) 20 else 3)
                else -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (job.status) {
                    JobStatus.RUNNING, JobStatus.QUEUED -> OutlinedButton(onClick = { graph.controller.cancel(context, job.id) }) {
                        Icon(Icons.Filled.Close, null, Modifier.size(18.dp)); Text(" 取消")
                    }
                    JobStatus.DONE -> {
                        val uri = job.outputUri?.let(Uri::parse)
                        FilledTonalButton(onClick = { uri?.let { open(context, it, job) } }, enabled = uri != null) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text(" 打开")
                        }
                        OutlinedButton(onClick = { uri?.let { share(context, it, job) } }, enabled = uri != null) {
                            Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text(" 分享")
                        }
                    }
                    JobStatus.FAILED, JobStatus.CANCELLED -> OutlinedButton(onClick = {
                        graph.jobs.retry(job.id); EncodeService.start(context)
                    }) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Text(" 重试") }
                }
                Spacer(Modifier.weight(1f))
                if (job.command != null || job.logTail != null) {
                    TextButton(onClick = { details = !details }) { Text(if (details) "收起" else "详情") }
                }
                if (!job.isActive) {
                    IconButton(onClick = { graph.jobs.remove(job.id) }) { Icon(Icons.Filled.Delete, "删除记录") }
                }
            }

            if (details) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                    SelectionContainer {
                        Text(
                            buildString {
                                job.command?.let { append(it).append("\n\n") }
                                job.logTail?.let { append(it.takeLast(3000)) }
                            }.trim(),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(s: JobStatus) {
    val cs = MaterialTheme.colorScheme
    val (label, bg, fg) = when (s) {
        JobStatus.QUEUED -> Triple("排队中", cs.surfaceContainerHighest, cs.onSurfaceVariant)
        JobStatus.RUNNING -> Triple("转码中", cs.primaryContainer, cs.onPrimaryContainer)
        JobStatus.DONE -> Triple("完成", cs.secondaryContainer, cs.onSecondaryContainer)
        JobStatus.FAILED -> Triple("失败", cs.errorContainer, cs.onErrorContainer)
        JobStatus.CANCELLED -> Triple("已取消", cs.surfaceContainerHighest, cs.onSurfaceVariant)
    }
    Box { Pill(label, bg, fg) }
}

private fun open(context: Context, uri: Uri, job: Job) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, job.settings.container.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "没有可以打开 ${job.settings.container.label} 的应用", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, "文件已被移动或删除", Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, uri: Uri, job: Job) {
    val send = Intent(Intent.ACTION_SEND).setType(job.settings.container.mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
