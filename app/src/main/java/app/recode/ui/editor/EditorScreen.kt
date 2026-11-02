@file:OptIn(ExperimentalMaterial3Api::class)

package app.recode.ui.editor

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.recode.core.AudioEncoder
import app.recode.core.CommandBuilder
import app.recode.core.Container
import app.recode.core.CropAspect
import app.recode.core.Format
import app.recode.core.MediaInfo
import app.recode.core.RateControl
import app.recode.core.Resolution
import app.recode.core.Speed
import app.recode.core.StreamInfo
import app.recode.core.StreamType
import app.recode.core.SubtitleMode
import app.recode.core.VideoEncoder
import app.recode.engine.OutputWriter
import app.recode.ui.Choice
import app.recode.ui.Dropdown
import app.recode.ui.IntSlider
import app.recode.ui.IssueList
import app.recode.ui.Pill
import app.recode.ui.Section
import app.recode.ui.SwitchRow

@Composable
fun EditorScreen(uris: List<Uri>, vmKey: String, onBack: () -> Unit, onQueued: (Int) -> Unit) {
    val vm: EditorViewModel = viewModel(key = vmKey)
    LaunchedEffect(uris) { vm.load(uris) }
    var savePreset by remember { mutableStateOf(false) }

    val enqueue = {
        val n = vm.enqueue()
        if (n > 0) onQueued(n)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enqueue() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.sources.size > 1) "批量任务（${vm.sources.size} 个文件）" else "新任务") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = { savePreset = true }) {
                        Icon(Icons.Filled.Star, null, Modifier.size(18.dp))
                        Text(" 存为预设")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val preview = vm.preview()
                    Column(Modifier.weight(1f)) {
                        val est = vm.estimate()
                        Text(
                            if (est != null) "预计 ${Format.bytes(est)}" else "大小取决于画面复杂度",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (preview != null) {
                            Text(
                                buildString {
                                    append("时长 ${Format.duration(preview.outputDurationMs)}")
                                    if (preview.outputWidth > 0) append(" · ${preview.outputWidth}×${preview.outputHeight}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        enabled = vm.canEnqueue,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else enqueue()
                        },
                    ) { Text(if (vm.sources.size > 1) "全部加入队列" else "开始转码") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SourcesCard(vm) }
            val single = vm.single
            val media = single?.media
            if (single != null && media != null && media.durationMs > 0) {
                item {
                    Section("剪切") {
                        TrimPanel(
                            uri = single.uri, media = media, segments = vm.segments,
                            onSegments = { vm.segments = it },
                            copyMode = vm.settings.video.encoder == VideoEncoder.COPY || vm.settings.audio.encoder == AudioEncoder.COPY,
                        )
                    }
                }
            }
            item { PresetRow(vm) }
            item { OutputSection(vm) }
            if (!vm.settings.container.audioOnly) item { VideoSection(vm) }
            item { AudioSection(vm) }
            if (!vm.settings.container.audioOnly) item { ExtrasSection(vm) }
            item { ReviewSection(vm) }
        }
    }

    if (savePreset) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { savePreset = false },
            title = { Text("保存为预设") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("预设名称") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.saveAsPreset(name); savePreset = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { savePreset = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SourcesCard(vm: EditorViewModel) {
    Section(if (vm.sources.size > 1) "源文件" else "源文件信息") {
        vm.sources.forEach { src ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(src.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                when {
                    src.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  正在读取…", style = MaterialTheme.typography.bodySmall)
                    }
                    src.error != null -> Text(src.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    src.media != null -> MediaSummary(src.media, detailed = vm.sources.size == 1)
                }
            }
            if (vm.sources.size > 1 && src != vm.sources.last()) HorizontalDivider()
        }
    }
}

@Composable
private fun MediaSummary(m: MediaInfo, detailed: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val v = m.video
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(Format.duration(m.durationMs))
        if (m.sizeBytes > 0) Pill(Format.bytes(m.sizeBytes))
        if (v != null) {
            Pill("${v.displayWidth}×${v.displayHeight}")
            Pill(v.codec.uppercase() + if (v.bitDepth > 8) " ${v.bitDepth}-bit" else "")
            if (v.fps > 0) Pill(Format.fps(v.fps))
            if (v.isHdr) Pill(if (v.colorTransfer == "arib-std-b67") "HDR · HLG" else "HDR10", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        }
        m.audioStreams.firstOrNull()?.let { a -> Pill(a.codec.uppercase() + " " + channelsLabel(a.channels)) }
        if (m.audioStreams.size > 1) Pill("${m.audioStreams.size} 条音轨")
        if (m.subtitleStreams.isNotEmpty()) Pill("${m.subtitleStreams.size} 条字幕")
    }
    if (detailed) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(if (expanded) "收起详细信息" else "查看全部轨道")
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${m.formatLongName.ifEmpty { m.formatName }} · ${Format.bitrate(m.bitrate)}", style = MaterialTheme.typography.bodySmall)
                m.streams.forEach { s -> Text(describeStream(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

private fun channelsLabel(ch: Int) = when (ch) {
    1 -> "单声道"; 2 -> "立体声"; 6 -> "5.1"; 8 -> "7.1"; 0 -> ""; else -> "$ch 声道"
}

private fun describeStream(s: StreamInfo): String = "#${s.index} " + when (s.type) {
    StreamType.VIDEO -> if (s.isAttachedPic) "封面图 ${s.codec}" else
        "视频 ${s.codec}${s.profile?.let { " ($it)" } ?: ""} ${s.width}×${s.height} ${Format.fps(s.fps)} ${s.pixFmt ?: ""} ${s.colorTransfer ?: ""} ${if (s.rotation != 0) "旋转${s.rotation}°" else ""}"
    StreamType.AUDIO -> "音频 ${s.codec} ${channelsLabel(s.channels)} ${s.sampleRate} Hz ${Format.bitrate(s.bitrate)} ${s.language ?: ""}"
    StreamType.SUBTITLE -> "字幕 ${s.codec} ${s.language ?: ""} ${s.title ?: ""}"
    StreamType.DATA -> "数据 ${s.codec}"
    StreamType.ATTACHMENT -> "附件 ${s.codec}"
    StreamType.UNKNOWN -> "未知 ${s.codec}"
}.replace(Regex("\\s+"), " ").trim()

@Composable
private fun PresetRow(vm: EditorViewModel) {
    Section("预设") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.presets.forEach { p ->
                FilterChip(selected = vm.presetId == p.id, onClick = { vm.applyPreset(p) }, label = { Text(p.name) })
            }
        }
        val current = vm.presets.firstOrNull { it.id == vm.presetId }
        Text(
            current?.description?.ifEmpty { null } ?: if (current == null) "已自定义设置" else "我的预设",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OutputSection(vm: EditorViewModel) {
    val s = vm.settings
    Section("输出") {
        val hasVideo = vm.primaryMedia?.hasVideo != false
        Dropdown(
            "格式", s.container,
            Container.entries.map { Choice(it, it.label, enabled = it.audioOnly || hasVideo, hint = if (it.audioOnly) "仅音频" else null) },
            vm::setContainer,
        )
        val single = vm.single
        if (single != null) {
            val default = single.name.substringBeforeLast('.', single.name) + "_recode"
            OutlinedTextField(
                value = vm.customName, onValueChange = { vm.customName = it },
                label = { Text("文件名") }, placeholder = { Text(default) },
                suffix = { Text(".${s.container.extension}") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("保存到 ${OutputWriter.describe(s.container)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VideoSection(vm: EditorViewModel) {
    val s = vm.settings
    val v = s.video
    val caps = vm.caps
    val src = vm.primaryMedia?.video
    Section("视频") {
        Dropdown(
            "编码器", v.encoder,
            VideoEncoder.entries.map { e ->
                val ok = caps.isAvailable(e)
                Choice(
                    e, e.label, enabled = ok,
                    hint = when {
                        !ok -> "此设备不支持"
                        e.hardware -> "骁龙硬件编码 · 最快"
                        e == VideoEncoder.COPY -> "最快，画质无损，但不能裁剪或缩放"
                        e == VideoEncoder.X265 || e == VideoEncoder.SVT_AV1 -> "画质好、体积小、较慢"
                        else -> null
                    },
                )
            },
            vm::setVideoEncoder,
        )
        if (!v.encoder.reencodes) return@Section

        val range = v.encoder.qualityRange
        if (!v.encoder.hardware && range != null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RateControl.entries.forEachIndexed { i, rc ->
                    SegmentedButton(
                        selected = v.rateControl == rc,
                        onClick = { vm.video { it.copy(rateControl = rc) } },
                        shape = SegmentedButtonDefaults.itemShape(i, RateControl.entries.size),
                    ) { Text(rc.label) }
                }
            }
        }
        if (CommandBuilder.usesBitrate(v)) {
            val preview = vm.preview()
            val fps = if (v.fps > 0) v.fps.toDouble() else src?.fps ?: 30.0
            val auto = if (preview != null && preview.outputWidth > 0) CommandBuilder.autoVideoBitrateKbps(v.encoder.family, preview.outputWidth, preview.outputHeight, fps) else 0
            val options = listOf(0, 1000, 2000, 3000, 4000, 6000, 8000, 10_000, 12_000, 16_000, 20_000, 25_000, 35_000, 50_000, 80_000)
            Dropdown(
                "码率", v.bitrateKbps,
                options.map { k -> Choice(k, if (k == 0) "自动" + (if (auto > 0) "（约 ${mbps(auto)}）" else "") else mbps(k)) },
                { k -> vm.video { it.copy(bitrateKbps = k) } },
            )
        } else if (range != null) {
            IntSlider(
                "画质 (CRF)", v.quality.coerceIn(range), range, { q -> vm.video { it.copy(quality = q) } },
                hint = "数值越小画质越高、文件越大。推荐 ${v.encoder.defaultQuality} 左右",
            )
        }
        if (!v.encoder.hardware) {
            Dropdown("编码速度", v.speed, Speed.entries.map { Choice(it, it.label) }, { sp -> vm.video { it.copy(speed = sp) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(
                "分辨率", v.resolution,
                Resolution.entries.map { r ->
                    val short = src?.let { minOf(it.displayWidth, it.displayHeight) } ?: Int.MAX_VALUE
                    Choice(r, r.label, enabled = r == Resolution.SOURCE || r.shortSide < short, hint = if (r != Resolution.SOURCE && r.shortSide >= short) "不放大" else null)
                },
                { r -> vm.video { it.copy(resolution = r) } }, Modifier.weight(1f),
            )
            Dropdown(
                "帧率", v.fps,
                listOf(0, 24, 25, 30, 50, 60).map { Choice(it, if (it == 0) "保持原始" else "$it fps") },
                { f -> vm.video { it.copy(fps = f) } }, Modifier.weight(1f),
            )
        }
        Dropdown("画面裁剪", v.crop, CropAspect.entries.map { Choice(it, it.label) }, { c -> vm.video { it.copy(crop = c) } })
        Column {
            Text("旋转", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 90, 180, 270).forEach { deg ->
                    FilterChip(selected = v.rotate == deg, onClick = { vm.video { it.copy(rotate = deg) } }, label = { Text(if (deg == 0) "不旋转" else "$deg°") })
                }
            }
        }
        SwitchRow("水平翻转", v.flipHorizontal, { b -> vm.video { it.copy(flipHorizontal = b) } })
        if (v.encoder.supportsTenBit) {
            val ok = caps.supportsTenBit(v.encoder)
            SwitchRow("10-bit", v.tenBit && ok, { b -> vm.video { it.copy(tenBit = b) } },
                subtitle = if (ok) "减少色带，HDR 必需" else "此设备上的 ${v.encoder.label} 不支持 10-bit", enabled = ok)
        }
        if (src?.isHdr == true) {
            SwitchRow("HDR 转 SDR", v.hdrToSdr, { b -> vm.video { it.copy(hdrToSdr = b) } },
                subtitle = if (caps.canTonemap) "在普通屏幕和网站上颜色正常" else "此 FFmpeg 不支持色调映射", enabled = caps.canTonemap)
        }
    }
}

private fun mbps(kbps: Int): String = if (kbps >= 1000) {
    val m = kbps / 1000.0
    (if (m == Math.floor(m)) "${m.toInt()}" else String.format(java.util.Locale.US, "%.1f", m)) + " Mbps"
} else "$kbps kbps"

@Composable
private fun AudioSection(vm: EditorViewModel) {
    val a = vm.settings.audio
    val caps = vm.caps
    val media = vm.primaryMedia
    Section("音频") {
        if (media != null && !media.hasAudio) {
            Text("源文件没有音轨", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Section
        }
        Dropdown(
            "编码器", a.encoder,
            AudioEncoder.entries.map { e -> Choice(e, e.label, enabled = caps.isAvailable(e), hint = if (!caps.isAvailable(e)) "不可用" else null) },
            vm::setAudioEncoder,
        )
        if (!a.encoder.reencodes) {
            if (media != null && media.audioStreams.size > 1) {
                SwitchRow("保留全部音轨", a.allTracks, { b -> vm.audio { it.copy(allTracks = b) } }, subtitle = "共 ${media.audioStreams.size} 条")
            }
            return@Section
        }
        if (a.encoder.usesBitrate) {
            val opts = if (a.encoder == AudioEncoder.AC3) listOf(192, 256, 384, 448, 640) else listOf(64, 96, 128, 160, 192, 256, 320)
            Dropdown("码率", a.bitrateKbps, opts.map { Choice(it, "$it kbps") }, { b -> vm.audio { it.copy(bitrateKbps = b) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(
                "声道", a.channels,
                listOf(0, 1, 2, 6).map { Choice(it, if (it == 0) "保持原始" else channelsLabel(it), enabled = it <= a.encoder.maxChannels) },
                { c -> vm.audio { it.copy(channels = c) } }, Modifier.weight(1f),
            )
            Dropdown(
                "采样率", a.sampleRate,
                listOf(0, 44100, 48000).map { Choice(it, if (it == 0) "保持原始" else "$it Hz") },
                { r -> vm.audio { it.copy(sampleRate = r) } }, Modifier.weight(1f),
            )
        }
        SwitchRow("响度标准化", a.normalize, { b -> vm.audio { it.copy(normalize = b) } }, subtitle = "统一到 -16 LUFS，忽大忽小的视频更好听")
        if (media != null && media.audioStreams.size > 1) {
            SwitchRow("保留全部音轨", a.allTracks, { b -> vm.audio { it.copy(allTracks = b) } }, subtitle = "共 ${media.audioStreams.size} 条；关闭则只保留默认音轨")
        }
    }
}

@Composable
private fun ExtrasSection(vm: EditorViewModel) {
    val s = vm.settings
    Section("字幕与元数据") {
        if (vm.primaryMedia?.subtitleStreams?.isNotEmpty() == true) {
            Dropdown("字幕", s.subtitles, SubtitleMode.entries.map { Choice(it, it.label) }, { m -> vm.other { it.copy(subtitles = m) } })
        } else {
            Text("源文件没有字幕", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SwitchRow("保留元数据和章节", s.keepMetadata, { b -> vm.other { it.copy(keepMetadata = b) } }, subtitle = "拍摄时间、标题等信息")
    }
}

@Composable
private fun ReviewSection(vm: EditorViewModel) {
    val issues = vm.issues()
    val preview = vm.preview()
    var showCmd by rememberSaveable { mutableStateOf(false) }
    Section("检查") {
        if (issues.isEmpty()) {
            Text("设置没有问题", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        } else {
            IssueList(issues)
        }
        preview?.notes?.filter { n -> issues.none { it.message.contains(n) } }?.takeIf { it.isNotEmpty() }?.let { notes ->
            notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (preview != null) {
            TextButton(onClick = { showCmd = !showCmd }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showCmd) "隐藏 FFmpeg 命令" else "查看 FFmpeg 命令")
            }
            if (showCmd) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
                    SelectionContainer {
                        Text(preview.display(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
