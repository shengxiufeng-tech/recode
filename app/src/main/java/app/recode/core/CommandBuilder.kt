package app.recode.core

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class BuildRequest(
    /** Path FFmpeg can open, e.g. `/proc/self/fd/57` or `saf:12.mp4`. One entry is reused per segment input. */
    val inputPath: String,
    val outputPath: String,
    val media: MediaInfo,
    val settings: EncodeSettings,
    val segments: List<TimeRange> = emptyList(),
    val caps: Capabilities = Capabilities.OPTIMISTIC,
)

data class BuildResult(
    val args: List<String>,
    val outputDurationMs: Long,
    val outputWidth: Int,
    val outputHeight: Int,
    val notes: List<String>,
) {
    /** Human-readable command for logs ("ffmpeg ..."), with simple shell quoting. */
    fun display(): String = "ffmpeg " + args.joinToString(" ") { a ->
        if (a.isEmpty() || a.any { it.isWhitespace() || it in "'\"[];|&()" }) "'" + a.replace("'", "'\\''") + "'" else a
    }
}

/** Output geometry after rotate → crop → scale. */
data class Geometry(val width: Int, val height: Int, val filters: List<String>)

object CommandBuilder {

    fun build(req: BuildRequest): BuildResult {
        val s = req.settings
        val media = req.media
        val notes = mutableListOf<String>()
        val segments = normaliseSegments(req.segments, media.durationMs)
        val multi = segments.size > 1
        val single = segments.singleOrNull()

        val srcVideo = media.video
        val wantVideo = !s.container.audioOnly && s.video.encoder != VideoEncoder.NONE && srcVideo != null
        val reencodeVideo = wantVideo && s.video.encoder.reencodes

        val audioIn = selectAudio(media, s.audio, multi, notes)
        val wantAudio = s.audio.encoder != AudioEncoder.NONE && audioIn.isNotEmpty()

        val subsOut = if (wantVideo && !multi && s.subtitles == SubtitleMode.KEEP) selectSubtitles(media, s.container, notes) else emptyList()
        if (multi && s.subtitles == SubtitleMode.KEEP && media.subtitleStreams.isNotEmpty()) {
            notes += "多段剪辑时字幕会被移除"
        }

        val args = mutableListOf("-hide_banner", "-nostdin", "-y")

        // ---- inputs ----
        if (multi) {
            for (seg in segments) {
                args += listOf("-ss", sec(seg.startMs), "-t", sec(seg.durationMs), "-i", req.inputPath)
            }
        } else {
            if (single != null) {
                args += listOf("-ss", sec(single.startMs), "-t", sec(single.durationMs))
            }
            args += listOf("-i", req.inputPath)
        }

        // ---- video filter chain ----
        var outW = srcVideo?.displayWidth ?: 0
        var outH = srcVideo?.displayHeight ?: 0
        val vFilters = mutableListOf<String>()
        var tonemapped = false
        var pixFmt: String? = null
        if (reencodeVideo && srcVideo != null) {
            val geo = geometry(srcVideo.displayWidth, srcVideo.displayHeight, s.video)
            outW = geo.width
            outH = geo.height
            vFilters += geo.filters
            if (srcVideo.isHdr && s.video.hdrToSdr) {
                if (req.caps.canTonemap) {
                    vFilters += TONEMAP_CHAIN
                    tonemapped = true
                } else {
                    notes += "此 FFmpeg 不支持 HDR 色调映射，输出颜色可能偏灰"
                }
            }
            if (s.video.fps > 0) vFilters += "fps=${s.video.fps}"
            pixFmt = pixelFormat(s.video, req.caps, tonemapped, notes)
        } else if (wantVideo && hasVideoEdits(s.video)) {
            notes += "直接复制视频时，裁剪、缩放、旋转和帧率设置不会生效"
        }

        // ---- audio filter chain ----
        val aFilters = mutableListOf<String>()
        if (wantAudio && s.audio.encoder.reencodes && s.audio.normalize) {
            val sr = if (s.audio.sampleRate > 0) s.audio.sampleRate else audioIn.first().sampleRate.takeIf { it > 0 } ?: 48000
            aFilters += "loudnorm=I=-16:TP=-1.5:LRA=11"
            aFilters += "aresample=$sr"
        }

        // ---- mapping ----
        if (multi) {
            val graph = StringBuilder()
            val vIdx = srcVideo?.index
            val aIdx = audioIn.firstOrNull()?.index
            val labels = StringBuilder()
            segments.indices.forEach { k ->
                if (wantVideo && vIdx != null) {
                    graph.append("[$k:$vIdx]setpts=PTS-STARTPTS[v$k];")
                    labels.append("[v$k]")
                }
                if (wantAudio && aIdx != null) {
                    graph.append("[$k:$aIdx]asetpts=PTS-STARTPTS[a$k];")
                    labels.append("[a$k]")
                }
            }
            val v = if (wantVideo) 1 else 0
            val a = if (wantAudio) 1 else 0
            graph.append(labels).append("concat=n=${segments.size}:v=$v:a=$a")
            if (wantVideo) graph.append("[vcat]")
            if (wantAudio) graph.append("[acat]")
            var vOut = "[vcat]"
            var aOut = "[acat]"
            if (wantVideo && vFilters.isNotEmpty()) {
                graph.append(";[vcat]").append(vFilters.joinToString(",")).append("[vout]")
                vOut = "[vout]"
            }
            if (wantAudio && aFilters.isNotEmpty()) {
                graph.append(";[acat]").append(aFilters.joinToString(",")).append("[aout]")
                aOut = "[aout]"
            }
            args += listOf("-filter_complex", graph.toString())
            if (wantVideo) args += listOf("-map", vOut)
            if (wantAudio) args += listOf("-map", aOut)
        } else {
            if (wantVideo) args += listOf("-map", "0:${srcVideo!!.index}")
            if (wantAudio) audioIn.forEach { args += listOf("-map", "0:${it.index}") }
            subsOut.forEach { args += listOf("-map", "0:${it.first.index}") }
            if (vFilters.isNotEmpty()) args += listOf("-vf", vFilters.joinToString(","))
            if (aFilters.isNotEmpty()) args += listOf("-af", aFilters.joinToString(","))
        }

        // ---- video codec ----
        if (wantVideo) {
            args += videoCodecArgs(s, srcVideo!!, outW, outH, pixFmt, notes)
            if (tonemapped) {
                args += listOf("-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709")
            }
        } else {
            args += "-vn"
        }

        // ---- audio codec ----
        if (wantAudio) {
            audioIn.forEachIndexed { i, src -> args += audioCodecArgs(i, src, s.audio, notes) }
            if (s.audio.encoder.reencodes && s.audio.sampleRate > 0 && aFilters.isEmpty()) {
                args += listOf("-ar", s.audio.sampleRate.toString())
            }
        } else {
            args += "-an"
        }

        // ---- subtitles ----
        if (subsOut.isNotEmpty()) {
            subsOut.forEachIndexed { i, (_, codec) -> args += listOf("-c:s:$i", codec) }
        } else {
            args += "-sn"
        }
        args += listOf("-dn")

        // ---- metadata & muxer ----
        if (s.keepMetadata && !multi) {
            args += listOf("-map_metadata", "0", "-map_chapters", "0")
        } else {
            args += listOf("-map_metadata", if (s.keepMetadata) "0" else "-1", "-map_chapters", "-1")
        }
        if (single != null && (!reencodeVideo || !s.audio.encoder.reencodes)) {
            args += listOf("-avoid_negative_ts", "make_zero")
        }
        args += listOf("-max_muxing_queue_size", "4096")
        if (s.container.isMp4Family) args += listOf("-movflags", "+faststart")
        args += listOf("-f", s.container.muxer, req.outputPath)

        val outDuration = if (segments.isEmpty()) media.durationMs else segments.sumOf { it.durationMs }
        if (single != null && !reencodeVideo && wantVideo) notes += "直接复制时，剪切点会对齐到最近的关键帧"

        return BuildResult(args, outDuration, if (wantVideo) outW else 0, if (wantVideo) outH else 0, notes.distinct())
    }

    // ------------------------------------------------------------------

    /** Sorts, clamps, merges overlapping ranges and drops tiny ones. A single full-length range becomes "no trim". */
    fun normaliseSegments(segments: List<TimeRange>, durationMs: Long): List<TimeRange> {
        if (segments.isEmpty()) return emptyList()
        val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val clamped = segments
            .map { TimeRange(it.startMs.coerceIn(0, limit), it.endMs.coerceIn(0, limit)) }
            .filter { it.durationMs >= MIN_SEGMENT_MS }
            .sortedBy { it.startMs }
        val merged = mutableListOf<TimeRange>()
        for (r in clamped) {
            val last = merged.lastOrNull()
            if (last != null && r.startMs <= last.endMs) {
                merged[merged.lastIndex] = TimeRange(last.startMs, max(last.endMs, r.endMs))
            } else merged += r
        }
        if (merged.size == 1 && durationMs > 0 && merged[0].startMs <= 0 && merged[0].endMs >= durationMs - 50) {
            return emptyList()
        }
        return merged
    }

    fun geometry(srcW: Int, srcH: Int, v: VideoSettings): Geometry {
        val filters = mutableListOf<String>()
        var w = srcW
        var h = srcH
        when (((v.rotate % 360) + 360) % 360) {
            90 -> { filters += "transpose=1"; w = srcH; h = srcW }
            180 -> { filters += "hflip"; filters += "vflip" }
            270 -> { filters += "transpose=2"; w = srcH; h = srcW }
        }
        if (v.flipHorizontal) filters += "hflip"

        if (v.crop != CropAspect.NONE && w > 0 && h > 0) {
            val target = v.crop.w.toDouble() / v.crop.h
            val current = w.toDouble() / h
            val (cw, ch) = if (current > target) even((h * target).roundToInt()) to even(h) else even(w) to even((w / target).roundToInt())
            if (cw != w || ch != h) {
                filters += "crop=$cw:$ch"
                w = cw; h = ch
            }
        }

        val short = minOf(w, h)
        val target = v.resolution.shortSide
        if (target in 1 until short) {
            val (nw, nh) = if (w <= h) {
                even(target) to even((h.toDouble() * target / w).roundToInt())
            } else {
                even((w.toDouble() * target / h).roundToInt()) to even(target)
            }
            filters += "scale=$nw:$nh"
            w = nw; h = nh
        } else if (w % 2 != 0 || h % 2 != 0) {
            // Most encoders (and every hardware encoder) need even dimensions.
            val nw = even(w); val nh = even(h)
            filters += "scale=$nw:$nh"
            w = nw; h = nh
        }
        return Geometry(w, h, filters)
    }

    fun autoVideoBitrateKbps(family: CodecFamily, width: Int, height: Int, fps: Double): Int {
        val f = if (fps <= 0) 30.0 else fps.coerceAtMost(120.0)
        val bpp = when (family) {
            CodecFamily.H264 -> 0.10
            CodecFamily.HEVC -> 0.065
            CodecFamily.AV1 -> 0.05
            CodecFamily.VP9 -> 0.06
            CodecFamily.OTHER -> 0.10
        }
        val kbps = width.toDouble() * height * f * bpp / 1000.0
        return ((kbps / 100).roundToInt() * 100).coerceIn(300, 120_000)
    }

    fun effectiveVideoBitrate(s: VideoSettings, w: Int, h: Int, fps: Double): Int =
        if (s.bitrateKbps > 0) s.bitrateKbps else autoVideoBitrateKbps(s.encoder.family, w, h, fps)

    /** True when the encoder will run in bitrate mode (hardware encoders always do). */
    fun usesBitrate(v: VideoSettings): Boolean =
        v.encoder.hardware || v.encoder.qualityRange == null || v.rateControl == RateControl.BITRATE

    private fun hasVideoEdits(v: VideoSettings) =
        v.crop != CropAspect.NONE || v.resolution != Resolution.SOURCE || v.rotate % 360 != 0 || v.flipHorizontal || v.fps > 0

    private fun pixelFormat(v: VideoSettings, caps: Capabilities, tonemapped: Boolean, notes: MutableList<String>): String {
        if (v.encoder.hardware) {
            if (v.tenBit) notes += "硬件编码目前只输出 8-bit"
            return "nv12"
        }
        if (v.tenBit) {
            if (caps.supportsTenBit(v.encoder)) return "yuv420p10le"
            notes += "${v.encoder.label} 在此设备上不支持 10-bit，已改用 8-bit"
        }
        return "yuv420p"
    }

    private fun videoCodecArgs(
        s: EncodeSettings,
        src: StreamInfo,
        w: Int,
        h: Int,
        pixFmt: String?,
        notes: MutableList<String>,
    ): List<String> {
        val v = s.video
        val e = v.encoder
        val a = mutableListOf("-c:v", e.ffName)
        if (e == VideoEncoder.COPY) {
            if (s.container.isMp4Family && src.codec == "hevc") a += listOf("-tag:v", "hvc1")
            return a
        }
        val fps = if (v.fps > 0) v.fps.toDouble() else src.fps
        val bitrate = effectiveVideoBitrate(v, w, h, fps)
        val q = (v.quality.takeIf { e.qualityRange?.contains(it) == true } ?: e.defaultQuality).toString()
        val bitrateMode = usesBitrate(v)
        if (e.hardware && v.rateControl == RateControl.QUALITY) notes += "硬件编码器使用码率模式（${bitrate} kbps）"

        when (e) {
            VideoEncoder.H264_HW, VideoEncoder.HEVC_HW, VideoEncoder.AV1_HW -> {
                a += listOf("-b:v", "${bitrate}k")
                // Keyframe roughly every 2 seconds.
                if (fps > 0) a += listOf("-g", (fps * 2).roundToInt().coerceAtLeast(1).toString())
            }
            VideoEncoder.X264 -> {
                a += listOf("-preset", x26xPreset(v.speed))
                a += if (bitrateMode) listOf("-b:v", "${bitrate}k") else listOf("-crf", q)
            }
            VideoEncoder.X265 -> {
                a += listOf("-preset", x26xPreset(v.speed))
                a += if (bitrateMode) listOf("-b:v", "${bitrate}k") else listOf("-crf", q)
                a += listOf("-x265-params", "log-level=error")
            }
            VideoEncoder.VP9 -> {
                val (deadline, cpu) = when (v.speed) {
                    Speed.FASTEST -> "realtime" to 8
                    Speed.FAST -> "good" to 5
                    Speed.BALANCED -> "good" to 4
                    Speed.SLOW -> "good" to 2
                    Speed.SLOWEST -> "good" to 1
                }
                a += listOf("-deadline", deadline, "-cpu-used", cpu.toString(), "-row-mt", "1")
                a += if (bitrateMode) listOf("-b:v", "${bitrate}k") else listOf("-crf", q, "-b:v", "0")
            }
            VideoEncoder.SVT_AV1 -> {
                val preset = when (v.speed) {
                    Speed.FASTEST -> 12; Speed.FAST -> 10; Speed.BALANCED -> 8; Speed.SLOW -> 6; Speed.SLOWEST -> 4
                }
                a += listOf("-preset", preset.toString())
                a += if (bitrateMode) listOf("-b:v", "${bitrate}k") else listOf("-crf", q)
            }
            VideoEncoder.AOM_AV1 -> {
                val cpu = when (v.speed) {
                    Speed.FASTEST -> 8; Speed.FAST -> 7; Speed.BALANCED -> 6; Speed.SLOW -> 5; Speed.SLOWEST -> 4
                }
                a += listOf("-cpu-used", cpu.toString(), "-row-mt", "1")
                a += if (bitrateMode) listOf("-b:v", "${bitrate}k") else listOf("-crf", q, "-b:v", "0")
            }
            VideoEncoder.COPY, VideoEncoder.NONE -> Unit
        }
        if (pixFmt != null) a += listOf("-pix_fmt", pixFmt)
        if (s.container.isMp4Family && e.family == CodecFamily.HEVC) a += listOf("-tag:v", "hvc1")
        return a
    }

    private fun x26xPreset(speed: Speed) = when (speed) {
        Speed.FASTEST -> "ultrafast"
        Speed.FAST -> "veryfast"
        Speed.BALANCED -> "fast"
        Speed.SLOW -> "medium"
        Speed.SLOWEST -> "slow"
    }

    private fun audioCodecArgs(i: Int, src: StreamInfo, a: AudioSettings, notes: MutableList<String>): List<String> {
        val e = a.encoder
        val out = mutableListOf("-c:a:$i", e.ffName)
        if (!e.reencodes) return out
        if (e.usesBitrate) {
            val br = if (a.bitrateKbps > 0) a.bitrateKbps else e.defaultBitrateKbps
            out += listOf("-b:a:$i", "${br}k")
        }
        val srcCh = src.channels
        val wanted = if (a.channels > 0) a.channels else srcCh
        val ch = if (wanted > e.maxChannels) {
            notes += "${e.label} 最多支持 ${e.maxChannels} 声道，已自动缩混"
            e.maxChannels
        } else wanted
        if (ch > 0 && (a.channels > 0 || ch != srcCh)) out += listOf("-ac:a:$i", ch.toString())
        return out
    }

    private fun selectAudio(media: MediaInfo, a: AudioSettings, multi: Boolean, notes: MutableList<String>): List<StreamInfo> {
        val all = media.audioStreams
        if (all.isEmpty() || a.encoder == AudioEncoder.NONE) return emptyList()
        val primary = all.firstOrNull { it.isDefault } ?: all.first()
        if (multi) {
            if (all.size > 1) notes += "多段剪辑只保留主音轨"
            return listOf(primary)
        }
        return if (a.allTracks) all else listOf(primary)
    }

    /** Returns subtitle streams to keep together with the codec each should be written as. */
    private fun selectSubtitles(media: MediaInfo, c: Container, notes: MutableList<String>): List<Pair<StreamInfo, String>> {
        val result = mutableListOf<Pair<StreamInfo, String>>()
        var dropped = 0
        for (sub in media.subtitleStreams) {
            val codec: String? = when (c) {
                Container.MKV -> when (sub.codec) {
                    "mov_text", "tx3g", "text" -> "srt"
                    "webvtt", "subrip", "ass", "ssa", "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle" -> "copy"
                    else -> null
                }
                Container.MP4, Container.MOV -> if (sub.isTextSubtitle) {
                    if (sub.codec == "mov_text") "copy" else "mov_text"
                } else null
                Container.WEBM -> if (sub.isTextSubtitle) {
                    if (sub.codec == "webvtt") "copy" else "webvtt"
                } else null
                else -> null
            }
            if (codec != null) result += sub to codec else dropped++
        }
        if (dropped > 0) notes += "有 $dropped 条字幕无法放入 ${c.label}，已移除"
        return result
    }

    private fun even(x: Int): Int = (x - x % 2).coerceAtLeast(2)

    fun sec(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)

    const val MIN_SEGMENT_MS = 100L

    val TONEMAP_CHAIN = listOf(
        "zscale=t=linear:npl=100",
        "format=gbrpf32le",
        "zscale=p=bt709",
        "tonemap=tonemap=hable:desat=0",
        "zscale=t=bt709:m=bt709:r=tv",
        "format=yuv420p",
    )

    /** Rough output size when running in bitrate mode; null for quality modes. */
    fun estimateBytes(s: EncodeSettings, media: MediaInfo, durationMs: Long, outW: Int, outH: Int): Long? {
        if (durationMs <= 0) return null
        var kbps = 0L
        val v = media.video
        if (!s.container.audioOnly && v != null) {
            when {
                s.video.encoder == VideoEncoder.NONE -> Unit
                s.video.encoder == VideoEncoder.COPY -> kbps += (v.bitrate.takeIf { it > 0 } ?: return null) / 1000
                usesBitrate(s.video) -> kbps += effectiveVideoBitrate(
                    s.video, outW, outH, if (s.video.fps > 0) s.video.fps.toDouble() else v.fps,
                )
                else -> return null
            }
        }
        val tracks = if (s.audio.allTracks) media.audioStreams else media.audioStreams.take(1)
        for (t in tracks) {
            kbps += when {
                s.audio.encoder == AudioEncoder.NONE -> 0
                s.audio.encoder == AudioEncoder.COPY -> t.bitrate / 1000
                s.audio.encoder.usesBitrate -> (if (s.audio.bitrateKbps > 0) s.audio.bitrateKbps else s.audio.encoder.defaultBitrateKbps).toLong()
                else -> (t.sampleRate.takeIf { it > 0 } ?: 48000) * 16L * (t.channels.takeIf { it > 0 } ?: 2) / 1000 / (if (s.audio.encoder == AudioEncoder.FLAC) 2 else 1)
            }
        }
        return (kbps * 1000 / 8.0 * durationMs / 1000.0).roundToLong()
    }
}
