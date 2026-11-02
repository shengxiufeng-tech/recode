package app.recode.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {
    @Test
    fun roundTripNestedValues() {
        val value = mapOf(
            "a" to 1L, "b" to listOf(true, false, null), "c" to "引号\"与\\反斜杠\n换行",
            "d" to mapOf("x" to 1.5), "e" to emptyList<Any>(),
        )
        val text = Json.stringify(value)
        assertEquals(value, Json.parse(text))
    }

    @Test
    fun parsesUnicodeEscapesAndNumbers() {
        val m = Json.parse("""{"s":"中文","n":-12,"f":3.5e2,"big":12345678901234}""").obj()
        assertEquals("中文", m["s"])
        assertEquals(-12L, m["n"])
        assertEquals(350.0, m["f"])
        assertEquals(12345678901234L, m["big"])
    }

    @Test
    fun rejectsTrailingGarbage() {
        val r = runCatching { Json.parse("{} x") }
        assertTrue(r.isFailure)
    }
}

class ProbeParserTest {
    @Test
    fun phoneRecording() {
        val m = ProbeParser.parse(Fixtures.PHONE_HDR_PORTRAIT)
        assertEquals(12345L, m.durationMs)
        assertEquals("Trip!", m.title)
        val v = assertNotNull(m.video)
        assertEquals("hevc", v.codec)
        assertEquals(270, v.rotation)
        assertEquals(2160, v.displayWidth)
        assertEquals(3840, v.displayHeight)
        assertTrue(v.isHdr)
        assertEquals(10, v.bitDepth)
        assertEquals(29.97, v.fps, 0.01)
        assertEquals(1, m.audioStreams.size)
        assertEquals(2, m.audioStreams[0].channels)
        assertEquals(48000, m.audioStreams[0].sampleRate)
        val sub = m.subtitleStreams.single()
        assertTrue(sub.isTextSubtitle)
        assertEquals("中文 \"简体\"", sub.title)
        assertEquals(StreamType.DATA, m.streams[3].type)
    }

    @Test
    fun mkvTagsAndCoverArt() {
        val m = ProbeParser.parse(Fixtures.MKV_51)
        // No format duration: falls back to the longest stream DURATION tag.
        assertEquals(42 * 60_000L + 10_125, m.durationMs)
        assertEquals(0, m.video!!.index)
        assertEquals(5, m.coverArt!!.index)
        assertEquals(9_000_000, m.video!!.bitrate)
        assertFalse(m.video!!.isHdr)
        assertEquals(2, m.audioStreams.size)
    }

    @Test
    fun rotationNormalisation() {
        assertEquals(90, ProbeParser.normaliseRotation(90.0))
        assertEquals(270, ProbeParser.normaliseRotation(-90.0))
        assertEquals(180, ProbeParser.normaliseRotation(-180.0))
        assertEquals(0, ProbeParser.normaliseRotation(359.0))
        assertEquals(0, ProbeParser.normaliseRotation(0.0))
    }
}

class CommandBuilderTest {
    private val phone = ProbeParser.parse(Fixtures.PHONE_HDR_PORTRAIT)
    private val mkv = ProbeParser.parse(Fixtures.MKV_51)

    private fun build(media: MediaInfo, s: EncodeSettings, segs: List<TimeRange> = emptyList(), caps: Capabilities = Capabilities.OPTIMISTIC) =
        CommandBuilder.build(BuildRequest("IN", "OUT", media, s, segs, caps))

    private fun List<String>.valueAfter(flag: String): String? = indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    @Test
    fun hardwareHevcPortrait1080() {
        val r = build(phone, Presets.builtIn.first { it.id == "hw-hevc-1080" }.settings)
        val a = r.args
        assertEquals(1080, r.outputWidth)
        assertEquals(1920, r.outputHeight)
        assertEquals("hevc_mediacodec", a.valueAfter("-c:v"))
        assertEquals("nv12", a.valueAfter("-pix_fmt"))
        assertEquals("hvc1", a.valueAfter("-tag:v"))
        val vf = a.valueAfter("-vf")!!
        assertTrue(vf.startsWith("scale=1080:1920,zscale=t=linear"), vf)
        assertTrue(vf.endsWith("format=yuv420p"), vf)
        assertEquals("bt709", a.valueAfter("-color_trc"))
        // Video, audio and the text subtitle are mapped explicitly; the data track is not.
        assertEquals(listOf("0:0", "0:1", "0:2"), a.indices.filter { a[it] == "-map" }.map { a[it + 1] })
        assertEquals("copy", a.valueAfter("-c:s:0")) // mov_text already fits MP4
        assertEquals("+faststart", a.valueAfter("-movflags"))
        assertEquals("mp4", a.valueAfter("-f"))
        assertEquals("OUT", a.last())
        assertEquals(12345L, r.outputDurationMs)
        // Bitrate auto-selected for HEVC 1080x1920 @ 29.97.
        val br = a.valueAfter("-b:v")!!.removeSuffix("k").toInt()
        assertTrue(br in 3000..5000, "bitrate $br")
        assertEquals("60", a.valueAfter("-g"))
    }

    @Test
    fun noUpscaleAndEvenDimensions() {
        val g = CommandBuilder.geometry(1279, 719, VideoSettings(resolution = Resolution.P1080))
        assertEquals(1278 to 718, g.width to g.height)
        assertEquals(listOf("scale=1278:718"), g.filters)
        val g2 = CommandBuilder.geometry(1280, 720, VideoSettings(resolution = Resolution.P1080))
        assertTrue(g2.filters.isEmpty())
    }

    @Test
    fun rotateCropScaleOrder() {
        val g = CommandBuilder.geometry(1920, 1080, VideoSettings(rotate = 90, crop = CropAspect.R1_1, resolution = Resolution.P720))
        assertEquals(listOf("transpose=1", "crop=1080:1080", "scale=720:720"), g.filters)
        assertEquals(720, g.width)
        val g2 = CommandBuilder.geometry(1920, 1080, VideoSettings(crop = CropAspect.R9_16))
        assertEquals(listOf("crop=608:1080"), g2.filters)
    }

    @Test
    fun singleSegmentCopyUsesInputSeek() {
        val s = Presets.builtIn.first { it.id == "remux" }.settings
        val r = build(mkv, s, listOf(TimeRange(60_000, 90_500)))
        val a = r.args
        val iIdx = a.indexOf("-i")
        assertEquals("60.000", a.valueAfter("-ss"))
        assertEquals("0", a[a.lastIndexOf("-ss") + 1])
        assertEquals("1", a.valueAfter("-copypriorss:v"))
        assertTrue(a.indexOf("-ss") < iIdx)
        assertEquals("30.500", a.valueAfter("-t"))
        assertEquals(2, a.count { it == "-t" }) // input and output side
        assertEquals("make_zero", a.valueAfter("-avoid_negative_ts"))
        assertEquals("copy", a.valueAfter("-c:v"))
        // cover art (stream 5) is not mapped as the main video
        assertEquals("0:0", a.valueAfter("-map"))
        // Both subtitle tracks fit in MKV.
        assertEquals("copy", a.valueAfter("-c:s:1"))
        assertEquals(30_500L, r.outputDurationMs)
        assertTrue(r.notes.any { it.contains("关键帧") })
    }

    @Test
    fun multiSegmentConcatGraph() {
        val s = EncodeSettings(
            container = Container.MP4,
            video = VideoSettings(encoder = VideoEncoder.X264, rateControl = RateControl.QUALITY, quality = 22, resolution = Resolution.P720),
            audio = AudioSettings(encoder = AudioEncoder.AAC, normalize = true),
        )
        val r = build(mkv, s, listOf(TimeRange(10_000, 20_000), TimeRange(30_000, 35_000)))
        val a = r.args
        assertEquals(2, a.count { it == "-i" })
        assertEquals(
            "[0:0]setpts=PTS-STARTPTS[v0];[0:1]asetpts=PTS-STARTPTS[a0];" +
                "[1:0]setpts=PTS-STARTPTS[v1];[1:1]asetpts=PTS-STARTPTS[a1];" +
                "[v0][a0][v1][a1]concat=n=2:v=1:a=1[vcat][acat];" +
                "[vcat]scale=1280:720[vout];[acat]loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[aout]",
            a.valueAfter("-filter_complex"),
        )
        assertEquals(listOf("[vout]", "[aout]"), a.indices.filter { a[it] == "-map" }.map { a[it + 1] })
        assertFalse("-vf" in a)
        assertFalse("-af" in a)
        assertEquals("22", a.valueAfter("-crf"))
        assertEquals("6", a.valueAfter("-ac:a:0").let { it ?: "6" }) // AAC keeps 5.1
        assertEquals(15_000L, r.outputDurationMs)
        assertTrue("-sn" in a)
    }

    @Test
    fun opusDownmixesSurround() {
        val s = EncodeSettings(container = Container.MKV, video = VideoSettings(encoder = VideoEncoder.COPY),
            audio = AudioSettings(encoder = AudioEncoder.OPUS, allTracks = false))
        val a = build(mkv, s).args
        assertEquals("libopus", a.valueAfter("-c:a:0"))
        assertEquals("2", a.valueAfter("-ac:a:0"))
        assertEquals("160k", a.valueAfter("-b:a:0"))
    }

    @Test
    fun audioOnlyExport() {
        val s = Presets.builtIn.first { it.id == "audio-aac" }.settings
        val r = build(phone, s)
        val a = r.args
        assertTrue("-vn" in a)
        assertTrue("-sn" in a)
        assertEquals("ipod", a.valueAfter("-f"))
        assertEquals("256k", a.valueAfter("-b:a:0"))
        assertEquals(0, r.outputWidth)
    }

    @Test
    fun tenBitFallsBackWhenUnsupported() {
        val caps = Capabilities.OPTIMISTIC.copy(tenBit = emptySet())
        val s = Presets.builtIn.first { it.id == "x265-quality" }.settings
        val r = build(mkv, s, caps = caps)
        assertEquals("yuv420p", r.args.valueAfter("-pix_fmt"))
        assertTrue(r.notes.any { it.contains("10-bit") })
        val r2 = build(mkv, s)
        assertEquals("yuv420p10le", r2.args.valueAfter("-pix_fmt"))
        assertEquals("24", r2.args.valueAfter("-crf"))
    }

    @Test
    fun nativeEncoderOptions() {
        val s = EncodeSettings(video = VideoSettings(encoder = VideoEncoder.X264, rateControl = RateControl.QUALITY,
            encoderPreset = "slow", tune = "animation", profile = "high", fpsMode = FpsMode.VFR, keyframeSec = 5))
        val a = build(mkv, s).args
        assertEquals("slow", a.valueAfter("-preset"))
        assertEquals("animation", a.valueAfter("-tune"))
        assertEquals("high", a.valueAfter("-profile:v"))
        assertEquals("vfr", a.valueAfter("-fps_mode"))
        assertEquals("120", a.valueAfter("-g")) // 23.976 fps × 5 s
        // Unknown values are ignored rather than passed to FFmpeg.
        val bad = build(mkv, s.copy(video = s.video.copy(encoderPreset = "warp9", tune = "bogus", profile = "main10"))).args
        assertEquals("fast", bad.valueAfter("-preset"))
        assertFalse("-tune" in bad)
        assertFalse("-profile:v" in bad)
        // 10-bit x264 is forced to High 10.
        val ten = build(mkv, s.copy(video = s.video.copy(tenBit = true, profile = "baseline"))).args
        assertEquals("high10", ten.valueAfter("-profile:v"))
        // VP9 switches deadline with cpu-used.
        val vp9 = build(mkv, EncodeSettings(container = Container.WEBM, video = VideoSettings(encoder = VideoEncoder.VP9, encoderPreset = "7"), audio = AudioSettings(encoder = AudioEncoder.OPUS))).args
        assertEquals("realtime", vp9.valueAfter("-deadline"))
        assertEquals("7", vp9.valueAfter("-cpu-used"))
        // Hardware rate-control mode.
        val hw = build(phone, EncodeSettings(video = VideoSettings(encoder = VideoEncoder.HEVC_HW, hwBitrateMode = "cbr"))).args
        assertEquals("cbr", hw.valueAfter("-bitrate_mode"))
    }

    @Test
    fun switchingEncoderResetsNativeOptions() {
        val s = EncodeSettings(video = VideoSettings(encoder = VideoEncoder.X264, encoderPreset = "slow", tune = "film", profile = "high"))
        val x265 = SettingsRules.withVideoEncoder(s, VideoEncoder.X265).video
        assertEquals("slow", x265.encoderPreset) // same preset vocabulary
        assertEquals("", x265.tune)
        assertEquals("", SettingsRules.withVideoEncoder(s, VideoEncoder.SVT_AV1).video.encoderPreset)
    }

    @Test
    fun segmentNormalisation() {
        val n = CommandBuilder.normaliseSegments(
            listOf(TimeRange(5000, 8000), TimeRange(1000, 3000), TimeRange(2500, 4000), TimeRange(9000, 9050)), 10_000,
        )
        assertEquals(listOf(TimeRange(1000, 4000), TimeRange(5000, 8000)), n)
        assertTrue(CommandBuilder.normaliseSegments(listOf(TimeRange(0, 10_000)), 10_000).isEmpty())
        assertEquals(listOf(TimeRange(9000, 10_000)), CommandBuilder.normaliseSegments(listOf(TimeRange(9000, 20_000)), 10_000))
    }

    @Test
    fun displayQuotesArguments() {
        val r = CommandBuilder.build(BuildRequest("/x/my file.mp4", "OUT", phone, EncodeSettings()))
        assertTrue(r.display().contains("'/x/my file.mp4'"))
    }

    @Test
    fun estimateForBitrateMode() {
        val s = EncodeSettings(video = VideoSettings(encoder = VideoEncoder.H264_HW, bitrateKbps = 8000), audio = AudioSettings(bitrateKbps = 192))
        val bytes = CommandBuilder.estimateBytes(s, phone, 10_000, 1080, 1920)!!
        assertEquals((8000 + 192) * 1000L / 8 * 10, bytes)
        assertNull(CommandBuilder.estimateBytes(EncodeSettings(video = VideoSettings(encoder = VideoEncoder.X264, rateControl = RateControl.QUALITY)), phone, 10_000, 1, 1))
    }
}

class ValidatorTest {
    private val phone = ProbeParser.parse(Fixtures.PHONE_HDR_PORTRAIT)
    private val mkv = ProbeParser.parse(Fixtures.MKV_51)
    private val caps = Capabilities.OPTIMISTIC

    private fun errors(m: MediaInfo, s: EncodeSettings, segs: List<TimeRange> = emptyList(), c: Capabilities = caps) =
        Validator.validate(m, s, segs, c).filter { it.level == IssueLevel.ERROR }

    @Test
    fun builtInPresetsAreValidForPhoneVideo() {
        for (p in Presets.builtIn) {
            assertTrue(errors(phone, p.settings).isEmpty(), "${p.id}: ${errors(phone, p.settings)}")
        }
    }

    @Test
    fun webmNeedsVp9OrAv1() {
        val s = EncodeSettings(container = Container.WEBM, video = VideoSettings(encoder = VideoEncoder.X264), audio = AudioSettings(encoder = AudioEncoder.OPUS))
        assertTrue(errors(phone, s).any { it.message.contains("WebM") })
        val ok = s.copy(video = VideoSettings(encoder = VideoEncoder.VP9))
        assertTrue(errors(phone, ok).isEmpty())
    }

    @Test
    fun multiSegmentCannotCopy() {
        val s = Presets.builtIn.first { it.id == "remux" }.settings
        val e = errors(mkv, s, listOf(TimeRange(0, 1000), TimeRange(5000, 6000)))
        assertEquals(2, e.size)
    }

    @Test
    fun copyIncompatibleAudioIntoM4a() {
        val s = EncodeSettings(container = Container.M4A, video = VideoSettings(encoder = VideoEncoder.NONE), audio = AudioSettings(encoder = AudioEncoder.COPY))
        assertTrue(errors(mkv, s).isNotEmpty()) // eac3 + aac
        assertTrue(errors(mkv, s.copy(audio = s.audio.copy(allTracks = false))).isNotEmpty()) // default track is eac3
        assertTrue(errors(phone, s).isEmpty()) // aac
    }

    @Test
    fun missingEncoderIsAnError() {
        val c = caps.copy(verified = mapOf(VideoEncoder.HEVC_HW to false))
        assertTrue(errors(phone, Presets.builtIn.first().settings, c = c).isNotEmpty())
        val adapted = Presets.adapt(Presets.builtIn.first().settings, c)
        assertEquals(VideoEncoder.X265, adapted.video.encoder)
        assertTrue(errors(phone, adapted, c = c).isEmpty())
    }

    @Test
    fun hdrWithoutTonemapWarns() {
        val s = EncodeSettings(video = VideoSettings(encoder = VideoEncoder.X264, hdrToSdr = false))
        val w = Validator.validate(phone, s, emptyList(), caps).filter { it.level == IssueLevel.WARNING }
        assertTrue(w.any { it.message.contains("HDR") })
    }

    @Test
    fun badSegment() {
        assertTrue(errors(phone, EncodeSettings(), listOf(TimeRange(5000, 4000))).isNotEmpty())
        assertTrue(errors(phone, EncodeSettings(), listOf(TimeRange(20_000, 30_000))).isNotEmpty())
    }
}

class CodecTest {
    @Test
    fun jobRoundTrip() {
        val job = Job(
            id = "j1", inputUri = "content://x/y", inputName = "a.mp4", outputName = "a_recode.mp4",
            settings = Presets.builtIn[2].settings, segments = listOf(TimeRange(1, 2), TimeRange(3, 4)),
            presetName = "高画质", status = JobStatus.FAILED, progress = 0.5f, outputUri = null,
            outputBytes = 10, inputBytes = 20, error = "boom\n\"x\"", logTail = "log", command = "ffmpeg -i",
            createdAt = 1, startedAt = 2, finishedAt = 3,
        )
        val back = Codec.jobs(Codec.jobs(listOf(job))).single()
        assertEquals(job, back)
    }

    @Test
    fun presetRoundTripAndGarbage() {
        val p = Presets.builtIn[0].copy(builtIn = false, id = "u1", name = "我的")
        assertEquals(listOf(p), Codec.presets(Codec.presets(listOf(p))))
        assertTrue(Codec.presets("not json").isEmpty())
        // Unknown enum values fall back to defaults instead of crashing.
        val s = Codec.settings(mapOf("container" to "AVI", "video" to mapOf("encoder" to "H266")))
        assertEquals(EncodeSettings(), s)
    }

    @Test
    fun capsRoundTrip() {
        val c = Capabilities.OPTIMISTIC.copy(verified = mapOf(VideoEncoder.HEVC_HW to true, VideoEncoder.AV1_HW to false), probedAt = 99)
        assertEquals(c, Codec.caps(Codec.caps(c)))
    }
}

class CapabilitiesTest {
    @Test
    fun parsesEncoderTable() {
        val names = Capabilities.parseNameTable(Fixtures.ENCODERS_OUTPUT)
        assertEquals(setOf("libx264", "h264_mediacodec", "hevc_mediacodec", "libx265", "aac", "libopus", "mov_text"), names)
    }

    @Test
    fun parsesFilterTable() {
        val names = Capabilities.parseNameTable(Fixtures.FILTERS_OUTPUT)
        assertEquals(setOf("abench", "crop", "zscale", "tonemap", "loudnorm"), names)
    }

    @Test
    fun parsesVersion() {
        assertEquals("n8.1.2", Capabilities.parseVersion("ffmpeg version n8.1.2 Copyright (c) 2000-2026\nbuilt with clang"))
    }
}

class FormatTest {
    @Test
    fun timecodes() {
        assertEquals("1:02:03", Format.duration(3_723_400))
        assertEquals("1:23", Format.duration(83_400))
        assertEquals("1:23.4", Format.timecode(83_400))
        assertEquals(83_400L, Format.parseTimecode("1:23.4"))
        assertEquals(3_723_450L, Format.parseTimecode("1:02:03.45"))
        assertEquals(12_500L, Format.parseTimecode("12.5"))
        assertNull(Format.parseTimecode("1:75"))
        assertNull(Format.parseTimecode("abc"))
        assertNull(Format.parseTimecode(""))
    }

    @Test
    fun sizes() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.5 MB", Format.bytes(1_572_864))
        assertEquals("29.97 fps", Format.fps(29.97002997))
        assertEquals("30 fps", Format.fps(30.0))
        assertEquals("8.0 Mbps", Format.bitrate(8_000_000))
    }
}

class ProgressTrackerTest {
    @Test
    fun monotonicWithEta() {
        val t = ProgressTracker(totalMs = 100_000, startedAtMs = 0)
        t.update(10_000.0, 2.0, 5_000)
        assertEquals(0.1f, t.fraction, 0.001f)
        assertEquals(45_000L, t.etaMs)
        t.update(9_000.0, 2.0, 6_000)
        assertEquals(0.1f, t.fraction, 0.001f)
        t.update(100_000.0, 2.0, 50_000)
        assertEquals(1f, t.fraction)
        assertEquals(0L, t.etaMs)
    }
}

class SettingsRulesTest {
    private val caps = Capabilities.OPTIMISTIC
    private val phone = ProbeParser.parse(Fixtures.PHONE_HDR_PORTRAIT)

    private fun valid(s: EncodeSettings) =
        assertTrue(!Validator.hasErrors(Validator.validate(phone, s, emptyList(), caps)), "${s} -> ${Validator.validate(phone, s, emptyList(), caps)}")

    @Test
    fun everyContainerChoiceStaysValid() {
        for (start in Presets.builtIn) {
            for (c in Container.entries) {
                val s = SettingsRules.withContainer(start.settings, c, caps)
                assertEquals(c, s.container)
                // Stream copy depends on the source codecs; the validator explains those cases to the user.
                if (s.video.encoder == VideoEncoder.COPY || s.audio.encoder == AudioEncoder.COPY) continue
                valid(s)
            }
        }
    }

    @Test
    fun everyEncoderChoiceStaysValid() {
        for (start in Presets.builtIn) {
            for (e in VideoEncoder.entries) {
                val s = SettingsRules.withVideoEncoder(start.settings, e)
                assertEquals(e, s.video.encoder)
                if (e == VideoEncoder.NONE && s.audio.encoder == AudioEncoder.NONE) continue
                if (e == VideoEncoder.COPY || s.audio.encoder == AudioEncoder.COPY) continue
                valid(s)
            }
            for (a in AudioEncoder.entries) {
                val s = SettingsRules.withAudioEncoder(start.settings, a)
                assertEquals(a, s.audio.encoder)
                if (a == AudioEncoder.NONE && (s.video.encoder == VideoEncoder.NONE || s.container.audioOnly)) continue
                if (a == AudioEncoder.COPY || s.video.encoder == VideoEncoder.COPY) continue
                valid(s)
            }
        }
    }

    @Test
    fun qualityResetsBetweenScales() {
        val s = SettingsRules.withVideoEncoder(EncodeSettings(video = VideoSettings(encoder = VideoEncoder.X264, rateControl = RateControl.QUALITY, quality = 20)), VideoEncoder.SVT_AV1)
        assertEquals(35, s.video.quality)
        val hw = SettingsRules.withVideoEncoder(s, VideoEncoder.HEVC_HW)
        assertEquals(RateControl.BITRATE, hw.video.rateControl)
        assertEquals(RateControl.QUALITY, SettingsRules.withVideoEncoder(hw, VideoEncoder.X265).video.rateControl)
    }

    @Test
    fun outputNames() {
        assertEquals("clip_recode.mkv", SettingsRules.outputName("clip.mp4", "_recode", Container.MKV))
        assertEquals("a_b_.mp4", SettingsRules.outputName("x.mov", "", Container.MP4, "a/b?"))
        assertEquals("noext_recode.mp4", SettingsRules.outputName("noext", "_recode", Container.MP4))
    }
}
