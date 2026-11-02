package app.recode.core

enum class IssueLevel { ERROR, WARNING }

data class Issue(val level: IssueLevel, val message: String)

/**
 * Checks a job before it is queued, so problems surface in the editor instead of as an FFmpeg
 * error minutes into an encode.
 */
object Validator {

    private val MP4_VIDEO_COPY = setOf("h264", "hevc", "av1", "vp9", "mpeg4", "mjpeg")
    private val MOV_VIDEO_COPY = MP4_VIDEO_COPY + setOf("prores", "dnxhd")
    private val WEBM_VIDEO_COPY = setOf("vp8", "vp9", "av1")
    private val MP4_AUDIO_COPY = setOf("aac", "mp3", "opus", "flac", "ac3", "eac3", "alac")
    private val WEBM_AUDIO_COPY = setOf("opus", "vorbis")

    fun validate(
        media: MediaInfo,
        settings: EncodeSettings,
        segments: List<TimeRange>,
        caps: Capabilities,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        fun err(m: String) { issues += Issue(IssueLevel.ERROR, m) }
        fun warn(m: String) { issues += Issue(IssueLevel.WARNING, m) }

        val c = settings.container
        val v = settings.video
        val a = settings.audio
        val src = media.video

        // ---- segments ----
        for (seg in segments) {
            if (seg.endMs <= seg.startMs) err("剪切片段的结束时间必须晚于开始时间")
            else if (media.durationMs > 0 && seg.startMs >= media.durationMs) err("剪切片段超出了视频时长")
        }
        val norm = CommandBuilder.normaliseSegments(segments, media.durationMs)
        if (segments.isNotEmpty() && norm.isEmpty() && segments.none { it.startMs <= 0 && it.endMs >= media.durationMs - 50 }) {
            err("剪切后没有剩余内容")
        }
        val multi = norm.size > 1

        // ---- what will be output ----
        val outVideo = !c.audioOnly && v.encoder != VideoEncoder.NONE && src != null
        val outAudio = a.encoder != AudioEncoder.NONE && media.hasAudio
        if (!outVideo && !outAudio) {
            err(if (c.audioOnly && !media.hasAudio) "源文件没有音轨，无法导出为 ${c.label}" else "没有可以输出的视频或音频")
        }
        if (!c.audioOnly && v.encoder != VideoEncoder.NONE && src == null && media.hasAudio) {
            warn("源文件没有视频画面，只会输出音频")
        }

        // ---- availability ----
        if (outVideo && !caps.isAvailable(v.encoder)) err("此设备不支持 ${v.encoder.label}")
        if (outAudio && !caps.isAvailable(a.encoder)) err("此设备的 FFmpeg 不包含 ${a.encoder.label} 编码器")

        // ---- multi-segment ----
        if (multi) {
            if (outVideo && v.encoder == VideoEncoder.COPY) err("多段剪辑需要重新编码视频，不能直接复制")
            if (outAudio && a.encoder == AudioEncoder.COPY) err("多段剪辑需要重新编码音频，不能直接复制")
        }

        // ---- container compatibility ----
        if (outVideo && src != null) {
            when (c) {
                Container.MP4, Container.MOV -> {
                    if (v.encoder == VideoEncoder.COPY) {
                        val ok = if (c == Container.MOV) MOV_VIDEO_COPY else MP4_VIDEO_COPY
                        if (src.codec !in ok) err("源视频编码 ${src.codec} 不能直接放进 ${c.label}，请重编码或改用 MKV")
                    }
                }
                Container.WEBM -> {
                    if (v.encoder == VideoEncoder.COPY) {
                        if (src.codec !in WEBM_VIDEO_COPY) err("WebM 只支持 VP8/VP9/AV1 视频，源视频是 ${src.codec}")
                    } else if (v.encoder.family != CodecFamily.VP9 && v.encoder.family != CodecFamily.AV1) {
                        err("WebM 只支持 VP9 或 AV1 视频编码")
                    }
                }
                else -> Unit
            }
        }
        if (outAudio) {
            val srcCodecs = (if (a.allTracks && !multi) media.audioStreams else media.audioStreams.take(1)).map { it.codec }
            val copy = a.encoder == AudioEncoder.COPY
            fun requireAudio(allowed: Set<AudioEncoder>, allowedCopy: (String) -> Boolean, what: String) {
                if (copy) {
                    val bad = srcCodecs.filterNot(allowedCopy)
                    if (bad.isNotEmpty()) err("源音频编码 ${bad.distinct().joinToString()} 不能直接放进 ${c.label}，$what")
                } else if (a.encoder !in allowed) {
                    err("${c.label} 不支持 ${a.encoder.label} 音频，$what")
                }
            }
            when (c) {
                Container.MP4 -> requireAudio(allowedAudio(c), { it in MP4_AUDIO_COPY }, "建议使用 AAC")
                Container.MOV -> requireAudio(allowedAudio(c), { it in MP4_AUDIO_COPY || it.startsWith("pcm_") }, "建议使用 AAC")
                Container.WEBM -> requireAudio(allowedAudio(c), { it in WEBM_AUDIO_COPY }, "请使用 Opus")
                Container.M4A -> requireAudio(allowedAudio(c), { it == "aac" || it == "alac" }, "请使用 AAC")
                Container.MP3 -> requireAudio(allowedAudio(c), { it == "mp3" }, "请使用 MP3")
                Container.FLAC -> requireAudio(allowedAudio(c), { it == "flac" }, "请使用 FLAC")
                Container.OGG -> requireAudio(allowedAudio(c), { it == "opus" || it == "vorbis" || it == "flac" }, "请使用 Opus")
                Container.WAV -> requireAudio(allowedAudio(c), { it.startsWith("pcm_") }, "请使用 PCM")
                Container.MKV -> Unit
            }
            if (c.audioOnly && (a.allTracks && media.audioStreams.size > 1)) {
                if (c != Container.MKV) warn("${c.label} 通常只能放一条音轨，播放器可能只识别第一条")
            }
        }

        // ---- quality / HDR ----
        if (outVideo && src != null && v.encoder.reencodes) {
            if (v.tenBit && v.encoder.hardware) warn("硬件编码目前只输出 8-bit")
            else if (v.tenBit && !caps.supportsTenBit(v.encoder)) warn("${v.encoder.label} 在此设备上不支持 10-bit，将使用 8-bit")
            if (src.isHdr) {
                if (v.hdrToSdr && !caps.canTonemap) warn("此 FFmpeg 不支持 HDR→SDR 色调映射，颜色可能偏灰")
                if (!v.hdrToSdr && !(v.tenBit && caps.supportsTenBit(v.encoder))) {
                    warn("源视频是 HDR，8-bit 输出又不做色调映射，颜色会发灰。建议打开 HDR→SDR")
                }
            }
            if (v.encoder.hardware && v.rateControl == RateControl.QUALITY) {
                warn("硬件编码器只支持码率模式，将自动选择码率")
            }
            if (v.encoder == VideoEncoder.AOM_AV1) warn("libaom 在手机上非常慢，建议用 SVT-AV1 或硬件编码")
            val fpsSrc = src.fps
            if (v.fps > 0 && fpsSrc > 0 && v.fps > fpsSrc + 0.5) warn("目标帧率高于源视频，只会重复帧，不会更流畅")
        }
        if (outVideo && v.encoder == VideoEncoder.COPY && norm.size == 1) {
            warn("直接复制时，剪切点会对齐到关键帧，开头可能多出零点几秒")
        }
        if (outAudio && a.normalize && a.encoder == AudioEncoder.COPY) {
            warn("直接复制音频时无法做响度标准化")
        }
        if (outAudio && a.encoder.reencodes) {
            val maxSrc = media.audioStreams.maxOfOrNull { it.channels } ?: 0
            val wanted = if (a.channels > 0) a.channels else maxSrc
            if (wanted > a.encoder.maxChannels) warn("${a.encoder.label} 最多 ${a.encoder.maxChannels} 声道，将自动缩混")
        }
        return issues.distinct()
    }

    fun hasErrors(issues: List<Issue>) = issues.any { it.level == IssueLevel.ERROR }

    /** Audio encoders each container can hold (re-encoding case). */
    fun allowedAudio(c: Container): Set<AudioEncoder> = when (c) {
        Container.MP4 -> setOf(AudioEncoder.AAC, AudioEncoder.MP3, AudioEncoder.OPUS, AudioEncoder.FLAC, AudioEncoder.AC3)
        Container.MOV -> setOf(AudioEncoder.AAC, AudioEncoder.MP3, AudioEncoder.AC3, AudioEncoder.PCM, AudioEncoder.FLAC)
        Container.WEBM -> setOf(AudioEncoder.OPUS)
        Container.M4A -> setOf(AudioEncoder.AAC)
        Container.MP3 -> setOf(AudioEncoder.MP3)
        Container.FLAC -> setOf(AudioEncoder.FLAC)
        Container.OGG -> setOf(AudioEncoder.OPUS)
        Container.WAV -> setOf(AudioEncoder.PCM)
        Container.MKV -> AudioEncoder.entries.filter { it.reencodes }.toSet()
    }
}
