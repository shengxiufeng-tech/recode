package app.recode.core

import kotlin.math.abs
import kotlin.math.roundToInt

enum class StreamType { VIDEO, AUDIO, SUBTITLE, DATA, ATTACHMENT, UNKNOWN }

data class StreamInfo(
    val index: Int,
    val type: StreamType,
    val codec: String,
    val codecLongName: String = "",
    val profile: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val pixFmt: String? = null,
    val fps: Double = 0.0,
    val bitrate: Long = 0,
    val channels: Int = 0,
    val channelLayout: String? = null,
    val sampleRate: Int = 0,
    val language: String? = null,
    val title: String? = null,
    val isAttachedPic: Boolean = false,
    val isDefault: Boolean = false,
    /** Rotation that a player applies on display, normalised to 0/90/180/270. */
    val rotation: Int = 0,
    val colorTransfer: String? = null,
    val colorPrimaries: String? = null,
    val colorSpace: String? = null,
    val bitDepth: Int = 8,
    val durationMs: Long = 0,
) {
    /** Size after the display rotation is applied (FFmpeg auto-rotates by default). */
    val displayWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight: Int get() = if (rotation == 90 || rotation == 270) width else height

    val isHdr: Boolean get() = colorTransfer == "smpte2084" || colorTransfer == "arib-std-b67"

    val isTextSubtitle: Boolean
        get() = codec in TEXT_SUB_CODECS

    companion object {
        val TEXT_SUB_CODECS = setOf("subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text", "tx3g")
    }
}

data class MediaInfo(
    val durationMs: Long,
    val formatName: String,
    val formatLongName: String = "",
    val sizeBytes: Long = 0,
    val bitrate: Long = 0,
    val streams: List<StreamInfo>,
    val title: String? = null,
) {
    /** The main video stream: first real video stream that is not cover art. */
    val video: StreamInfo? get() = streams.firstOrNull { it.type == StreamType.VIDEO && !it.isAttachedPic }
    val audioStreams: List<StreamInfo> get() = streams.filter { it.type == StreamType.AUDIO }
    val subtitleStreams: List<StreamInfo> get() = streams.filter { it.type == StreamType.SUBTITLE }
    val coverArt: StreamInfo? get() = streams.firstOrNull { it.type == StreamType.VIDEO && it.isAttachedPic }
    val hasVideo: Boolean get() = video != null
    val hasAudio: Boolean get() = audioStreams.isNotEmpty()
}

/** Parses `ffprobe -print_format json -show_format -show_streams` output. */
object ProbeParser {

    fun parse(json: String): MediaInfo {
        val root = Json.parse(json).obj()
        val format = root["format"].obj()
        val streams = root["streams"].arr().map { parseStream(it.obj()) }

        val formatDurationMs = secondsToMs(format.double("duration"))
        val durationMs = if (formatDurationMs > 0) formatDurationMs
        else streams.maxOfOrNull { it.durationMs } ?: 0L

        return MediaInfo(
            durationMs = durationMs,
            formatName = format.str("format_name") ?: "",
            formatLongName = format.str("format_long_name") ?: "",
            sizeBytes = format.long("size") ?: 0,
            bitrate = format.long("bit_rate") ?: 0,
            streams = streams,
            title = format["tags"].obj().let { it.str("title") ?: it.str("TITLE") },
        )
    }

    private fun parseStream(s: Map<String, Any?>): StreamInfo {
        val type = when (s.str("codec_type")) {
            "video" -> StreamType.VIDEO
            "audio" -> StreamType.AUDIO
            "subtitle" -> StreamType.SUBTITLE
            "data" -> StreamType.DATA
            "attachment" -> StreamType.ATTACHMENT
            else -> StreamType.UNKNOWN
        }
        val tags = s["tags"].obj()
        val disposition = s["disposition"].obj()
        val pixFmt = s.str("pix_fmt")
        return StreamInfo(
            index = s.int("index") ?: 0,
            type = type,
            codec = s.str("codec_name") ?: "unknown",
            codecLongName = s.str("codec_long_name") ?: "",
            profile = s.str("profile"),
            width = s.int("width") ?: 0,
            height = s.int("height") ?: 0,
            pixFmt = pixFmt,
            fps = parseRate(s.str("avg_frame_rate")).takeIf { it > 0 } ?: parseRate(s.str("r_frame_rate")),
            bitrate = s.long("bit_rate") ?: tags.long("BPS") ?: 0,
            channels = s.int("channels") ?: 0,
            channelLayout = s.str("channel_layout"),
            sampleRate = s.int("sample_rate") ?: 0,
            language = tags.str("language"),
            title = tags.str("title") ?: tags.str("handler_name")?.takeIf { type == StreamType.SUBTITLE },
            isAttachedPic = (disposition.int("attached_pic") ?: 0) == 1,
            isDefault = (disposition.int("default") ?: 0) == 1,
            rotation = parseRotation(s, tags),
            colorTransfer = s.str("color_transfer"),
            colorPrimaries = s.str("color_primaries"),
            colorSpace = s.str("color_space"),
            bitDepth = s.int("bits_per_raw_sample")?.takeIf { it in 8..16 } ?: bitDepthFromPixFmt(pixFmt),
            durationMs = secondsToMs(s.double("duration"))
                .takeIf { it > 0 } ?: parseTagDuration(tags.str("DURATION")),
        )
    }

    internal fun parseRate(r: String?): Double {
        if (r.isNullOrBlank()) return 0.0
        val parts = r.split('/')
        return if (parts.size == 2) {
            val n = parts[0].toDoubleOrNull() ?: return 0.0
            val d = parts[1].toDoubleOrNull() ?: return 0.0
            if (d == 0.0) 0.0 else n / d
        } else r.toDoubleOrNull() ?: 0.0
    }

    private fun parseRotation(s: Map<String, Any?>, tags: Map<String, Any?>): Int {
        // Newer FFmpeg reports rotation in the display matrix side data (counter-clockwise, e.g. -90).
        val sideRotation = s["side_data_list"].arr()
            .map { it.obj() }
            .firstNotNullOfOrNull { sd -> sd.double("rotation") }
        val raw = sideRotation ?: tags.double("rotate") ?: 0.0
        return normaliseRotation(raw)
    }

    internal fun normaliseRotation(raw: Double): Int {
        val r = ((raw.roundToInt() % 360) + 360) % 360
        // Snap to the nearest quarter turn.
        return listOf(0, 90, 180, 270, 360).minBy { abs(it - r) } % 360
    }

    private fun bitDepthFromPixFmt(pixFmt: String?): Int {
        if (pixFmt == null) return 8
        return when {
            pixFmt.contains("p010") || pixFmt.contains("10le") || pixFmt.contains("10be") -> 10
            pixFmt.contains("12le") || pixFmt.contains("12be") -> 12
            else -> 8
        }
    }

    private fun secondsToMs(sec: Double?): Long = if (sec == null || sec.isNaN() || sec < 0) 0 else (sec * 1000).toLong()

    /** Matroska stores per-stream duration as a tag like "00:01:02.345000000". */
    internal fun parseTagDuration(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val parts = s.trim().split(':')
        if (parts.size != 3) return 0
        val h = parts[0].toLongOrNull() ?: return 0
        val m = parts[1].toLongOrNull() ?: return 0
        val sec = parts[2].toDoubleOrNull() ?: return 0
        return h * 3_600_000 + m * 60_000 + (sec * 1000).toLong()
    }
}
