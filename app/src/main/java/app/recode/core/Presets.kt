package app.recode.core

object Presets {

    val builtIn: List<Preset> = listOf(
        Preset(
            "hw-hevc-1080", "快速 · HEVC 1080p", "骁龙硬件编码，速度最快，体积约为 H.264 的一半", true,
            EncodeSettings(
                container = Container.MP4,
                video = VideoSettings(encoder = VideoEncoder.HEVC_HW, rateControl = RateControl.BITRATE, resolution = Resolution.P1080),
                audio = AudioSettings(encoder = AudioEncoder.AAC, bitrateKbps = 160),
            ),
        ),
        Preset(
            "hw-h264-1080", "兼容 · H.264 1080p", "硬件编码，几乎所有设备和网站都能播放", true,
            EncodeSettings(
                container = Container.MP4,
                video = VideoSettings(encoder = VideoEncoder.H264_HW, rateControl = RateControl.BITRATE, resolution = Resolution.P1080),
                audio = AudioSettings(encoder = AudioEncoder.AAC, bitrateKbps = 160),
            ),
        ),
        Preset(
            "x265-quality", "高画质 · x265", "软件编码，同体积画质最好，但比硬件慢很多", true,
            EncodeSettings(
                container = Container.MKV,
                video = VideoSettings(
                    encoder = VideoEncoder.X265, rateControl = RateControl.QUALITY, quality = 24,
                    speed = Speed.BALANCED, tenBit = true,
                ),
                audio = AudioSettings(encoder = AudioEncoder.OPUS, bitrateKbps = 160),
            ),
        ),
        Preset(
            "x264-share", "分享 · H.264 720p", "体积小，适合发消息或上传", true,
            EncodeSettings(
                container = Container.MP4,
                video = VideoSettings(
                    encoder = VideoEncoder.X264, rateControl = RateControl.QUALITY, quality = 24,
                    speed = Speed.FAST, resolution = Resolution.P720,
                ),
                audio = AudioSettings(encoder = AudioEncoder.AAC, bitrateKbps = 128, channels = 2),
            ),
        ),
        Preset(
            "av1-archive", "归档 · AV1", "SVT-AV1 软件编码，体积最小，速度较慢", true,
            EncodeSettings(
                container = Container.MKV,
                video = VideoSettings(
                    encoder = VideoEncoder.SVT_AV1, rateControl = RateControl.QUALITY, quality = 32,
                    speed = Speed.FAST, tenBit = true,
                ),
                audio = AudioSettings(encoder = AudioEncoder.OPUS, bitrateKbps = 128),
            ),
        ),
        Preset(
            "remux", "只换封装", "不重编码，几秒完成，画质无损", true,
            EncodeSettings(
                container = Container.MKV,
                video = VideoSettings(encoder = VideoEncoder.COPY),
                audio = AudioSettings(encoder = AudioEncoder.COPY),
            ),
        ),
        Preset(
            "audio-aac", "提取音频 · AAC", "导出 M4A 音频", true,
            EncodeSettings(
                container = Container.M4A,
                video = VideoSettings(encoder = VideoEncoder.NONE),
                audio = AudioSettings(encoder = AudioEncoder.AAC, bitrateKbps = 256, allTracks = false),
                subtitles = SubtitleMode.DROP,
            ),
        ),
        Preset(
            "audio-flac", "提取音频 · FLAC", "无损音频", true,
            EncodeSettings(
                container = Container.FLAC,
                video = VideoSettings(encoder = VideoEncoder.NONE),
                audio = AudioSettings(encoder = AudioEncoder.FLAC, allTracks = false),
                subtitles = SubtitleMode.DROP,
            ),
        ),
    )

    /** Swaps encoders the device lacks for the closest available one, keeping the rest of the preset. */
    fun adapt(settings: EncodeSettings, caps: Capabilities): EncodeSettings {
        val v = settings.video
        if (caps.isAvailable(v.encoder)) return settings
        val fallback = when (v.encoder.family) {
            CodecFamily.HEVC -> listOf(VideoEncoder.HEVC_HW, VideoEncoder.X265, VideoEncoder.H264_HW, VideoEncoder.X264)
            CodecFamily.H264 -> listOf(VideoEncoder.H264_HW, VideoEncoder.X264, VideoEncoder.HEVC_HW)
            CodecFamily.AV1 -> listOf(VideoEncoder.SVT_AV1, VideoEncoder.AV1_HW, VideoEncoder.AOM_AV1, VideoEncoder.X265)
            CodecFamily.VP9 -> listOf(VideoEncoder.VP9, VideoEncoder.SVT_AV1)
            CodecFamily.OTHER -> listOf(VideoEncoder.X264)
        }.firstOrNull { caps.isAvailable(it) } ?: return settings
        val container = if (settings.container == Container.WEBM && fallback.family !in setOf(CodecFamily.VP9, CodecFamily.AV1)) Container.MKV else settings.container
        return settings.copy(
            container = container,
            video = v.copy(
                encoder = fallback,
                rateControl = if (fallback.hardware) RateControl.BITRATE else v.rateControl,
                quality = if (fallback.qualityRange?.contains(v.quality) == true) v.quality else fallback.defaultQuality,
            ),
        )
    }
}
