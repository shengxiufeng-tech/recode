package app.recode.core

/** Video codec family, used for container compatibility checks. */
enum class CodecFamily { H264, HEVC, AV1, VP9, OTHER }

enum class VideoEncoder(
    val ffName: String,
    val label: String,
    val family: CodecFamily,
    val hardware: Boolean,
    /** Quality (CRF-like) range and default; null when the encoder has no quality mode. */
    val qualityRange: IntRange?,
    val defaultQuality: Int,
    val supportsTenBit: Boolean,
) {
    COPY("copy", "直接复制（不重编码）", CodecFamily.OTHER, false, null, 0, false),
    H264_HW("h264_mediacodec", "H.264 · 硬件", CodecFamily.H264, true, null, 0, false),
    HEVC_HW("hevc_mediacodec", "HEVC · 硬件", CodecFamily.HEVC, true, null, 0, false),
    AV1_HW("av1_mediacodec", "AV1 · 硬件", CodecFamily.AV1, true, null, 0, false),
    X264("libx264", "H.264 · x264 软件", CodecFamily.H264, false, 0..51, 23, true),
    X265("libx265", "HEVC · x265 软件", CodecFamily.HEVC, false, 0..51, 26, true),
    VP9("libvpx-vp9", "VP9 · libvpx 软件", CodecFamily.VP9, false, 0..63, 32, true),
    SVT_AV1("libsvtav1", "AV1 · SVT-AV1 软件", CodecFamily.AV1, false, 0..63, 35, true),
    AOM_AV1("libaom-av1", "AV1 · libaom 软件", CodecFamily.AV1, false, 0..63, 32, true),
    NONE("", "移除视频", CodecFamily.OTHER, false, null, 0, false);

    val reencodes: Boolean get() = this != COPY && this != NONE
}

enum class AudioEncoder(
    val ffName: String,
    val label: String,
    val defaultBitrateKbps: Int,
    /** Lossless / PCM encoders ignore bitrate. */
    val usesBitrate: Boolean,
    val maxChannels: Int,
) {
    COPY("copy", "直接复制", 0, false, 64),
    AAC("aac", "AAC", 160, true, 8),
    OPUS("libopus", "Opus", 128, true, 2),
    MP3("libmp3lame", "MP3", 192, true, 2),
    AC3("ac3", "AC-3 (杜比数字)", 448, true, 6),
    FLAC("flac", "FLAC 无损", 0, false, 8),
    PCM("pcm_s16le", "PCM 16-bit (WAV)", 0, false, 8),
    NONE("", "移除音频", 0, false, 0);

    val reencodes: Boolean get() = this != COPY && this != NONE
}

enum class Container(
    val muxer: String,
    val extension: String,
    val mime: String,
    val label: String,
    val audioOnly: Boolean,
) {
    MP4("mp4", "mp4", "video/mp4", "MP4", false),
    MKV("matroska", "mkv", "video/x-matroska", "MKV", false),
    MOV("mov", "mov", "video/quicktime", "MOV", false),
    WEBM("webm", "webm", "video/webm", "WebM", false),
    M4A("ipod", "m4a", "audio/mp4", "M4A (AAC)", true),
    MP3("mp3", "mp3", "audio/mpeg", "MP3", true),
    FLAC("flac", "flac", "audio/flac", "FLAC", true),
    OGG("ogg", "ogg", "audio/ogg", "OGG (Opus)", true),
    WAV("wav", "wav", "audio/wav", "WAV", true);

    val isMp4Family: Boolean get() = this == MP4 || this == MOV || this == M4A
}

enum class RateControl(val label: String) { QUALITY("恒定画质"), BITRATE("目标码率") }

enum class Speed(val label: String) {
    FASTEST("极快"), FAST("快"), BALANCED("均衡"), SLOW("慢"), SLOWEST("极慢");
}

/** Target size expressed as the length of the short side, so portrait clips are handled sensibly. */
enum class Resolution(val shortSide: Int, val label: String) {
    SOURCE(0, "保持原始"),
    P2160(2160, "4K (2160p)"),
    P1440(1440, "2K (1440p)"),
    P1080(1080, "1080p"),
    P720(720, "720p"),
    P540(540, "540p"),
    P480(480, "480p"),
    P360(360, "360p");
}

enum class CropAspect(val w: Int, val h: Int, val label: String) {
    NONE(0, 0, "不裁剪"),
    R16_9(16, 9, "16:9"),
    R9_16(9, 16, "9:16 竖屏"),
    R1_1(1, 1, "1:1"),
    R4_3(4, 3, "4:3"),
    R3_4(3, 4, "3:4"),
    R4_5(4, 5, "4:5"),
    R21_9(21, 9, "21:9");
}

enum class SubtitleMode(val label: String) { KEEP("保留（自动转换格式）"), DROP("移除") }

/** How output timestamps are produced. Phone recordings are usually variable frame rate. */
enum class FpsMode(val label: String, val ffValue: String?) {
    AUTO("自动", null),
    CFR("恒定帧率 (CFR)", "cfr"),
    VFR("可变帧率 (VFR)", "vfr"),
}

data class VideoSettings(
    val encoder: VideoEncoder = VideoEncoder.HEVC_HW,
    val rateControl: RateControl = RateControl.BITRATE,
    val quality: Int = 26,
    /** 0 = automatic, derived from the output resolution and frame rate. */
    val bitrateKbps: Int = 0,
    val speed: Speed = Speed.BALANCED,
    val resolution: Resolution = Resolution.SOURCE,
    /** 0 = keep source frame rate. */
    val fps: Int = 0,
    val tenBit: Boolean = false,
    val hdrToSdr: Boolean = true,
    val crop: CropAspect = CropAspect.NONE,
    /** Clockwise rotation in degrees: 0, 90, 180, 270. */
    val rotate: Int = 0,
    val flipHorizontal: Boolean = false,
    /** Encoder-native preset (x264 "slow", SVT-AV1 "8", …); empty = derived from [speed]. */
    val encoderPreset: String = "",
    /** x264/x265 -tune; empty = none. */
    val tune: String = "",
    /** x264 -profile:v; empty = encoder default. */
    val profile: String = "",
    val fpsMode: FpsMode = FpsMode.AUTO,
    /** Keyframe interval in seconds; 0 = encoder default (2 s for hardware). */
    val keyframeSec: Int = 0,
    /** MediaCodec rate control ("vbr", "cbr", "cq"); empty = device default. */
    val hwBitrateMode: String = "",
)

data class AudioSettings(
    val encoder: AudioEncoder = AudioEncoder.AAC,
    val bitrateKbps: Int = 160,
    /** 0 = keep source channel count. */
    val channels: Int = 0,
    /** 0 = keep source sample rate. */
    val sampleRate: Int = 0,
    val normalize: Boolean = false,
    val allTracks: Boolean = true,
)

data class EncodeSettings(
    val container: Container = Container.MP4,
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val subtitles: SubtitleMode = SubtitleMode.KEEP,
    val keepMetadata: Boolean = true,
)

data class TimeRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

data class Preset(
    val id: String,
    val name: String,
    val description: String,
    val builtIn: Boolean,
    val settings: EncodeSettings,
)

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class Job(
    val id: String,
    val inputUri: String,
    val inputName: String,
    val outputName: String,
    val settings: EncodeSettings,
    val segments: List<TimeRange> = emptyList(),
    val presetName: String = "",
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Float = 0f,
    val speed: Double = 0.0,
    val etaMs: Long = -1,
    val outputUri: String? = null,
    val outputBytes: Long = 0,
    val inputBytes: Long = 0,
    val error: String? = null,
    val logTail: String? = null,
    val command: String? = null,
    val createdAt: Long = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
) {
    val isActive: Boolean get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING
}
