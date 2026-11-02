package app.recode.core

/**
 * FFmpeg invocations used to discover device capabilities. Shared by the Android probe and the
 * desktop integration tests so both exercise exactly the same commands.
 */
object ProbePlan {

    // NOTE: informational commands (-version, -encoders, -filters) loop forever in the FFmpegKit
    // 8.1 build used on Android, so the app never runs them; they're only used by desktop tests.
    val ENCODERS = listOf("-hide_banner", "-encoders")
    val FILTERS = listOf("-hide_banner", "-filters")
    val VERSION = listOf("-version")

    /**
     * Every video encoder gets a real test encode: hardware ones can be compiled in yet unusable on a
     * given chip, and a tiny encode is the only reliable way to know.
     */
    val TESTED = VideoEncoder.entries.filter { it.reencodes }
    val TEN_BIT_CANDIDATES = listOf(VideoEncoder.X264, VideoEncoder.X265, VideoEncoder.VP9, VideoEncoder.SVT_AV1, VideoEncoder.AOM_AV1)

    private val SOURCE = listOf("-f", "lavfi", "-i", "color=c=gray:s=320x240:r=30:d=1")

    /** Audio encoders worth testing; COPY/NONE need no encoder. */
    val AUDIO_TESTED = AudioEncoder.entries.filter { it.reencodes }

    fun audioTest(e: AudioEncoder): List<String> {
        val args = mutableListOf("-hide_banner", "-nostdin", "-y", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", "0.2", "-c:a", e.ffName)
        if (e.usesBitrate) args += listOf("-b:a", "${e.defaultBitrateKbps}k")
        args += listOf("-f", "null", "-")
        return args
    }

    /** Runs the exact HDR→SDR chain the app uses on a frame tagged as PQ / BT.2020. */
    fun tonemapTest(): List<String> = listOf(
        "-hide_banner", "-nostdin", "-y", "-f", "lavfi", "-i", "color=c=gray:s=64x64:r=1:d=1", "-frames:v", "1",
        "-vf", (listOf("setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc", "format=yuv420p10le") + CommandBuilder.TONEMAP_CHAIN).joinToString(","),
        "-f", "null", "-",
    )

    /** A tiny encode with a built-in encoder; if even this fails, the probe cannot verify anything. */
    fun controlEncode(): List<String> =
        listOf("-hide_banner", "-nostdin", "-y") + SOURCE + listOf("-frames:v", "5", "-c:v", "mpeg4", "-f", "null", "-")

    fun testEncode(e: VideoEncoder, tenBit: Boolean): List<String> {
        val args = mutableListOf("-hide_banner", "-nostdin", "-y")
        args += SOURCE
        args += listOf("-frames:v", "10", "-c:v", e.ffName)
        when (e) {
            VideoEncoder.H264_HW, VideoEncoder.HEVC_HW, VideoEncoder.AV1_HW -> args += listOf("-b:v", "1000k", "-pix_fmt", "nv12")
            VideoEncoder.X264, VideoEncoder.X265 -> args += listOf("-preset", "ultrafast")
            VideoEncoder.VP9 -> args += listOf("-deadline", "realtime", "-cpu-used", "8")
            VideoEncoder.SVT_AV1 -> args += listOf("-preset", "12")
            VideoEncoder.AOM_AV1 -> args += listOf("-cpu-used", "8", "-usage", "realtime")
            else -> Unit
        }
        if (!e.hardware) args += listOf("-pix_fmt", if (tenBit) "yuv420p10le" else "yuv420p")
        if (e == VideoEncoder.X265) args += listOf("-x265-params", "log-level=error")
        args += listOf("-f", "null", "-")
        return args
    }
}
