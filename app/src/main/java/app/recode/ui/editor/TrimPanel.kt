@file:OptIn(ExperimentalMaterial3Api::class)

package app.recode.ui.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.recode.R
import app.recode.core.Format
import app.recode.core.MediaInfo
import app.recode.core.TimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

private const val THUMBS = 10

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrimPanel(
    uri: Uri,
    media: MediaInfo,
    segments: List<TimeRange>,
    onSegments: (List<TimeRange>) -> Unit,
    copyMode: Boolean,
) {
    val context = LocalContext.current
    val duration = media.durationMs.coerceAtLeast(1)
    val exo = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var selected by remember { mutableIntStateOf(0) }

    val currentSegments by rememberUpdatedState(segments)
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }
    LaunchedEffect(exo) {
        while (isActive) {
            position = exo.currentPosition.coerceIn(0, duration)
            // Stop at the end of the selected segment, which makes checking a cut point easy.
            val seg = currentSegments.getOrNull(selected)
            if (playing && seg != null && position >= seg.endMs) exo.pause()
            delay(if (playing) 50 else 200)
        }
    }
    fun seek(ms: Long) {
        val t = ms.coerceIn(0, duration)
        exo.seekTo(t)
        position = t
    }

    val thumbs by produceState<List<ImageBitmap>>(emptyList(), uri) {
        if (!media.hasVideo) return@produceState
        value = withContext(Dispatchers.IO) { loadThumbnails(context, uri, duration) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (media.hasVideo) {
            val v = media.video!!
            val ratio = if (v.displayHeight > 0) (v.displayWidth.toFloat() / v.displayHeight).coerceIn(0.4f, 2.4f) else 16f / 9
            Box(
                Modifier.fillMaxWidth().aspectRatio(if (ratio < 1f) 1f else ratio)
                    .clip(RoundedCornerShape(16.dp)).background(Color.Black),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            player = exo
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(onClick = {
                if (playing) exo.pause()
                else {
                    val seg = segments.getOrNull(selected)
                    if (seg != null && (position >= seg.endMs - 50 || position < seg.startMs)) seek(seg.startMs)
                    else if (position >= duration - 50) seek(0)
                    exo.play()
                }
            }) {
                if (playing) Icon(painterResource(R.drawable.ic_pause), "暂停")
                else Icon(Icons.Filled.PlayArrow, "播放")
            }
            Text("${Format.timecode(position)} / ${Format.timecode(duration)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            listOf(-1000L to "-1s", -100L to "-0.1", 100L to "+0.1", 1000L to "+1s").forEach { (d, label) ->
                TextButton(onClick = { exo.pause(); seek(position + d) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                    Text(label)
                }
            }
        }

        Filmstrip(thumbs, duration, segments, selected.coerceIn(0, (segments.size - 1).coerceAtLeast(0)), position, onSeek = { exo.pause(); seek(it) })

        if (segments.isEmpty()) {
            Text("导出完整文件。需要剪切时，添加一个片段。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = { onSegments(listOf(TimeRange(0, duration))); selected = 0 }) {
                Icon(painterResource(R.drawable.ic_cut), null)
                Text("  剪切片段")
            }
        } else {
            val sel = selected.coerceIn(0, segments.lastIndex)
            val seg = segments[sel]
            RangeSlider(
                value = seg.startMs.toFloat()..seg.endMs.toFloat(),
                onValueChange = { r ->
                    val ns = r.start.roundToLong()
                    val ne = r.endInclusive.roundToLong()
                    exo.pause()
                    if (ns != seg.startMs) seek(ns) else if (ne != seg.endMs) seek(ne)
                    onSegments(segments.toMutableList().also { it[sel] = TimeRange(ns, ne) })
                },
                valueRange = 0f..duration.toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    val ns = position.coerceAtMost(seg.endMs - 100)
                    onSegments(segments.toMutableList().also { it[sel] = TimeRange(ns.coerceAtLeast(0), seg.endMs) })
                }, modifier = Modifier.weight(1f)) { Text("起点 ${Format.timecode(seg.startMs)}") }
                OutlinedButton(onClick = {
                    val ne = position.coerceAtLeast(seg.startMs + 100)
                    onSegments(segments.toMutableList().also { it[sel] = TimeRange(seg.startMs, ne.coerceAtMost(duration)) })
                }, modifier = Modifier.weight(1f)) { Text("终点 ${Format.timecode(seg.endMs)}") }
            }
            Text("点按钮把起点/终点设为当前播放位置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                segments.forEachIndexed { i, s ->
                    InputChip(
                        selected = i == sel,
                        onClick = { selected = i; exo.pause(); seek(s.startMs) },
                        label = { Text("${i + 1}. ${Format.timecode(s.startMs)}–${Format.timecode(s.endMs)}") },
                    )
                }
                if (!copyMode) {
                    AssistChip(
                        onClick = {
                            val start = position.coerceIn(0, duration - 200)
                            val end = (start + 10_000).coerceAtMost(duration)
                            onSegments(segments + TimeRange(start, end))
                            selected = segments.size
                        },
                        label = { Text("添加片段") },
                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                    )
                }
            }
            // Explicit, full-size buttons: the old chip "×" was too small to hit reliably.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (segments.size > 1) {
                    OutlinedButton(onClick = {
                        val next = segments.toMutableList().also { it.removeAt(sel) }
                        selected = (sel - 1).coerceAtLeast(0)
                        onSegments(next)
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Close, null, Modifier.size(18.dp)); Text(" 删除片段 ${sel + 1}")
                    }
                }
                OutlinedButton(onClick = { selected = 0; onSegments(emptyList()) }, modifier = Modifier.weight(1f)) {
                    Text("取消剪切（导出完整）")
                }
            }
            val total = segments.sumOf { it.durationMs }
            Text(
                buildString {
                    append("输出时长 ${Format.timecode(total)}")
                    if (segments.size > 1) append(" · ${segments.size} 个片段将按时间顺序拼接")
                    if (copyMode) append(" · 直接复制模式只能剪一段，切点对齐关键帧")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Filmstrip(
    thumbs: List<ImageBitmap>,
    duration: Long,
    segments: List<TimeRange>,
    selected: Int,
    position: Long,
    onSeek: (Long) -> Unit,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val dim = Color.Black.copy(alpha = 0.55f)
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(duration) {
                detectTapGestures { o -> onSeek((o.x / size.width * duration).roundToLong()) }
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            thumbs.forEach { bmp ->
                Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            if (segments.isNotEmpty()) {
                // Dim everything outside the kept ranges.
                var cursor = 0f
                segments.sortedBy { it.startMs }.forEach { s ->
                    val x0 = s.startMs.toFloat() / duration * size.width
                    val x1 = s.endMs.toFloat() / duration * size.width
                    if (x0 > cursor) drawRect(dim, Offset(cursor, 0f), Size(x0 - cursor, size.height))
                    cursor = maxOf(cursor, x1)
                }
                if (cursor < size.width) drawRect(dim, Offset(cursor, 0f), Size(size.width - cursor, size.height))
                segments.getOrNull(selected)?.let { s ->
                    val x0 = s.startMs.toFloat() / duration * size.width
                    val x1 = s.endMs.toFloat() / duration * size.width
                    drawRect(highlight, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(2f), size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                }
            }
            val px = position.toFloat() / duration * size.width
            drawRect(Color.White, Offset(px - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        }
    }
}

private fun loadThumbnails(context: android.content.Context, uri: Uri, durationMs: Long): List<ImageBitmap> {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        (0 until THUMBS).mapNotNull { i ->
            val t = (durationMs * (i + 0.5) / THUMBS * 1000).toLong()
            runCatching {
                r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 108)
            }.getOrNull()?.let { bmp: Bitmap -> bmp.asImageBitmap() }
        }
    } catch (e: Exception) {
        emptyList()
    } finally {
        runCatching { r.release() }
    }
}
