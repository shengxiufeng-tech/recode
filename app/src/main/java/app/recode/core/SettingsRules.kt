package app.recode.core

/**
 * Keeps settings coherent while the user edits them, so the editor rarely ends up in an invalid
 * combination (e.g. picking FLAC switches the container to FLAC, picking WebM switches to VP9/Opus).
 */
object SettingsRules {

    fun withContainer(s: EncodeSettings, c: Container, caps: Capabilities): EncodeSettings {
        var out = s.copy(container = c)
        if (c.audioOnly) {
            val audio = when (c) {
                Container.M4A -> AudioEncoder.AAC
                Container.MP3 -> AudioEncoder.MP3
                Container.FLAC -> AudioEncoder.FLAC
                Container.OGG -> AudioEncoder.OPUS
                Container.WAV -> AudioEncoder.PCM
                else -> s.audio.encoder
            }
            val keep = s.audio.encoder == AudioEncoder.COPY
            out = out.copy(
                video = out.video.copy(encoder = VideoEncoder.NONE),
                audio = if (keep) out.audio.copy(allTracks = false) else withAudioEncoderOnly(out.audio.copy(allTracks = false), audio),
                subtitles = SubtitleMode.DROP,
            )
            return out
        }
        if (s.container.audioOnly || out.video.encoder == VideoEncoder.NONE) {
            // Coming back from an audio-only format: bring video back.
            val v = Presets.adapt(Presets.builtIn.first().settings, caps).video
            out = out.copy(video = v, subtitles = SubtitleMode.KEEP)
            if (out.audio.encoder !in setOf(AudioEncoder.COPY, AudioEncoder.NONE)) {
                out = out.copy(audio = withAudioEncoderOnly(out.audio.copy(allTracks = true), AudioEncoder.AAC))
            }
        }
        if (c == Container.WEBM) {
            val v = out.video
            if (v.encoder.reencodes && v.encoder.family != CodecFamily.VP9 && v.encoder.family != CodecFamily.AV1) {
                val pick = listOf(VideoEncoder.VP9, VideoEncoder.SVT_AV1, VideoEncoder.AV1_HW).firstOrNull { caps.isAvailable(it) } ?: VideoEncoder.VP9
                out = out.copy(video = withVideoEncoderOnly(v, pick))
            }
            if (out.audio.encoder.reencodes && out.audio.encoder != AudioEncoder.OPUS) {
                out = out.copy(audio = withAudioEncoderOnly(out.audio, AudioEncoder.OPUS))
            }
        }
        if (out.audio.encoder.reencodes && out.audio.encoder !in Validator.allowedAudio(c)) {
            val pick = if (AudioEncoder.AAC in Validator.allowedAudio(c)) AudioEncoder.AAC else Validator.allowedAudio(c).first()
            out = out.copy(audio = withAudioEncoderOnly(out.audio, pick))
        }
        return out
    }

    fun withVideoEncoder(s: EncodeSettings, e: VideoEncoder): EncodeSettings {
        var out = s.copy(video = withVideoEncoderOnly(s.video, e))
        if (out.container == Container.WEBM && e.reencodes && e.family != CodecFamily.VP9 && e.family != CodecFamily.AV1) {
            out = out.copy(container = Container.MKV)
        }
        if (e != VideoEncoder.NONE && out.container.audioOnly) out = out.copy(container = Container.MP4)
        return out
    }

    fun withAudioEncoder(s: EncodeSettings, e: AudioEncoder): EncodeSettings {
        var out = s.copy(audio = withAudioEncoderOnly(s.audio, e))
        if (out.container.audioOnly) {
            val c = when (e) {
                AudioEncoder.AAC -> Container.M4A
                AudioEncoder.MP3 -> Container.MP3
                AudioEncoder.FLAC -> Container.FLAC
                AudioEncoder.OPUS -> Container.OGG
                AudioEncoder.PCM -> Container.WAV
                // No dedicated audio-only format for this codec: Matroska holds anything.
                else -> if (e.reencodes) Container.MKV else out.container
            }
            out = out.copy(container = c)
        } else if (e.reencodes && e !in Validator.allowedAudio(out.container)) {
            out = out.copy(container = if (e == AudioEncoder.PCM && out.container.isMp4Family) Container.MOV else Container.MKV)
        }
        return out
    }

    private fun withVideoEncoderOnly(v: VideoSettings, e: VideoEncoder): VideoSettings {
        val sameFamily = v.encoder.qualityRange == e.qualityRange
        val changed = v.encoder != e
        return v.copy(
            encoder = e,
            // Native presets/tunes/profiles are encoder-specific names.
            encoderPreset = if (changed && setOf(v.encoder, e) != setOf(VideoEncoder.X264, VideoEncoder.X265)) "" else v.encoderPreset,
            tune = if (changed) "" else v.tune,
            profile = if (changed) "" else v.profile,
            hwBitrateMode = if (e.hardware) v.hwBitrateMode else "",
            rateControl = if (e.hardware) RateControl.BITRATE else if (v.encoder.hardware) RateControl.QUALITY else v.rateControl,
            quality = if (sameFamily && e.qualityRange?.contains(v.quality) == true) v.quality else e.defaultQuality,
            tenBit = v.tenBit && e.supportsTenBit,
        )
    }

    private fun withAudioEncoderOnly(a: AudioSettings, e: AudioEncoder): AudioSettings =
        if (a.encoder == e) a else a.copy(encoder = e, bitrateKbps = if (e.usesBitrate) e.defaultBitrateKbps else 0)

    /** Output file name: "<base>.<ext>", with characters that file systems reject replaced. */
    fun outputName(inputName: String, suffix: String, c: Container, customBase: String? = null): String {
        val base = customBase?.takeIf { it.isNotBlank() } ?: (inputName.substringBeforeLast('.', inputName) + suffix)
        val clean = base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120).ifEmpty { "output" }
        return "$clean.${c.extension}"
    }
}
