package app.recode.core

/** Encoder-native option lists shown in the editor, so users see the real names they know. */
object EncoderOptions {

    data class Option(val value: String, val label: String)

    private val X26X_PRESETS = listOf("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow")

    /** Presets for the encoder; the first entry ("") means "follow the speed setting". */
    fun presets(e: VideoEncoder): List<Option> = when (e) {
        VideoEncoder.X264, VideoEncoder.X265 -> X26X_PRESETS.map { Option(it, it + if (it == "medium") "（默认）" else "") }
        VideoEncoder.VP9 -> (0..8).map { Option(it.toString(), "cpu-used $it" + when (it) { 0 -> "（最慢）"; 8 -> "（最快，实时）"; else -> "" }) }
        VideoEncoder.SVT_AV1 -> (0..13).map { Option(it.toString(), "preset $it" + when (it) { 0 -> "（最慢）"; 13 -> "（最快）"; 10 -> "（默认）"; else -> "" }) }
        VideoEncoder.AOM_AV1 -> (0..8).map { Option(it.toString(), "cpu-used $it" + when (it) { 0 -> "（最慢）"; 8 -> "（最快）"; else -> "" }) }
        else -> emptyList()
    }

    fun tunes(e: VideoEncoder): List<Option> = when (e) {
        VideoEncoder.X264 -> listOf(
            Option("film", "film 电影"), Option("animation", "animation 动画"), Option("grain", "grain 保留颗粒"),
            Option("stillimage", "stillimage 静态画面"), Option("fastdecode", "fastdecode 快速解码"), Option("zerolatency", "zerolatency 低延迟"),
        )
        VideoEncoder.X265 -> listOf(
            Option("animation", "animation 动画"), Option("grain", "grain 保留颗粒"),
            Option("fastdecode", "fastdecode 快速解码"), Option("zerolatency", "zerolatency 低延迟"),
        )
        else -> emptyList()
    }

    fun profiles(e: VideoEncoder): List<Option> = when (e) {
        VideoEncoder.X264 -> listOf(Option("baseline", "Baseline（老设备）"), Option("main", "Main"), Option("high", "High"))
        else -> emptyList()
    }

    fun hwBitrateModes(e: VideoEncoder): List<Option> =
        if (e.hardware) listOf(Option("vbr", "VBR 可变码率"), Option("cbr", "CBR 恒定码率")) else emptyList()

    val KEYFRAME_SECONDS = listOf(0, 1, 2, 5, 10)
}
