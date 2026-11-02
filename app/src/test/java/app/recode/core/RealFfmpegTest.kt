package app.recode.core

import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs every command the app can generate through a real desktop FFmpeg.
 *
 * Enabled when FFMPEG_BIN and FFPROBE_BIN point at binaries (CI installs them); otherwise skipped.
 * Hardware MediaCodec encoders don't exist on desktop, so their commands are executed with
 * libx264 swapped in — this still validates mapping, filters, trimming and muxing.
 */
class RealFfmpegTest {
    private val ffmpeg = System.getenv("FFMPEG_BIN")
    private val ffprobe = System.getenv("FFPROBE_BIN")

    private data class Run(val code: Int, val output: String)

    private fun exec(bin: String, args: List<String>, timeoutSec: Long = 180): Run {
        val p = ProcessBuilder(listOf(bin) + args).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return Run(-1, "TIMEOUT\n$out")
        }
        reader.join(5000)
        return Run(p.exitValue(), out.toString())
    }

    private fun probe(path: String): MediaInfo {
        val r = exec(ffprobe!!, listOf("-v", "error", "-print_format", "json", "-show_format", "-show_streams", path))
        check(r.code == 0) { "ffprobe failed: ${r.output}" }
        return ProbeParser.parse(r.output.substring(r.output.indexOf('{')))
    }

    private fun swapHardware(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "-tag:v" && args.getOrNull(i + 1) == "hvc1" && args.any { it.endsWith("_mediacodec") }) { i += 2; continue }
            out += if (a.endsWith("_mediacodec")) "libx264" else a
            i++
        }
        return out
    }

    private class Scenario(
        val name: String,
        val input: String,
        val settings: EncodeSettings,
        val segments: List<TimeRange> = emptyList(),
        val durationTolerance: Long = 400,
        val check: (MediaInfo) -> String? = { null },
    )

    @Test
    fun everyScenarioProducesAPlayableFile() {
        if (ffmpeg == null || ffprobe == null) {
            println("RealFfmpegTest skipped: FFMPEG_BIN / FFPROBE_BIN not set")
            return
        }
        val dir = File(System.getProperty("java.io.tmpdir"), "recode-it-${System.nanoTime()}").apply { mkdirs() }
        println(exec(ffmpeg, ProbePlan.VERSION).output.lineSequence().first())

        // ---- fixtures ----
        val srt = File(dir, "subs.srt").apply {
            writeText("1\n00:00:00,500 --> 00:00:02,000\n你好，世界\n\n2\n00:00:03,000 --> 00:00:05,000\nSecond line\n")
        }
        val sdr = File(dir, "input.mp4").path
        exec(ffmpeg, listOf(
            "-hide_banner", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=6",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=6",
            "-f", "lavfi", "-i", "sine=frequency=220:sample_rate=44100:duration=6",
            "-i", srt.path,
            "-map", "0", "-map", "1", "-map", "2", "-map", "3",
            "-filter:a:1", "aformat=channel_layouts=5.1",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-c:a", "aac", "-c:s", "mov_text",
            "-disposition:a:0", "default", sdr,
        )).also { check(it.code == 0) { "fixture failed: ${it.output}" } }

        val hdr = File(dir, "hdr.mkv").path
        val hdrMade = exec(ffmpeg, listOf(
            "-hide_banner", "-y", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=3",
            "-vf", "format=yuv420p10le", "-c:v", "libx265", "-preset", "ultrafast",
            "-x265-params", "log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc",
            "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", hdr,
        )).code == 0

        // ---- capabilities, discovered exactly like on the device ----
        val encoders = Capabilities.parseNameTable(exec(ffmpeg, ProbePlan.ENCODERS).output)
        val filters = Capabilities.parseNameTable(exec(ffmpeg, ProbePlan.FILTERS).output)
        check(exec(ffmpeg, ProbePlan.controlEncode()).code == 0) { "control encode failed" }
        val tenBit = ProbePlan.TEN_BIT_CANDIDATES.filter { it.ffName in encoders && exec(ffmpeg, ProbePlan.testEncode(it, true)).code == 0 }.toSet()
        val desktopCaps = Capabilities("desktop", encoders, filters, emptyMap(), tenBit)
        // Pretend hardware encoders exist so their commands get generated (and then swapped for x264).
        val caps = desktopCaps.copy(verified = VideoEncoder.entries.filter { it.hardware }.associateWith { true })
        // The same per-encoder test encodes the app runs must succeed for every software encoder present.
        for (e in ProbePlan.TESTED.filter { !it.hardware && it.ffName in encoders }) {
            val r = exec(ffmpeg, ProbePlan.testEncode(e, false))
            check(r.code == 0) { "probe encode for ${e.ffName} failed: ${r.output.takeLast(800)}" }
        }
        println("encoders: ${VideoEncoder.entries.filter { desktopCaps.isAvailable(it) }}, tenBit=$tenBit, tonemap=${caps.canTonemap}")

        val sdrInfo = probe(sdr)
        check(sdrInfo.audioStreams.size == 2 && sdrInfo.subtitleStreams.size == 1) { "fixture streams: ${sdrInfo.streams}" }

        fun vcodec(expected: String): (MediaInfo) -> String? = { m ->
            val c = m.video?.codec
            if (c != expected) "video codec $c != $expected" else null
        }

        val scenarios = mutableListOf<Scenario>()
        for (p in Presets.builtIn) {
            scenarios += Scenario("preset ${p.id}", sdr, p.settings)
            if (p.settings.video.encoder.reencodes || p.settings.container.audioOnly) {
                scenarios += Scenario("preset ${p.id} + multi trim", sdr, p.settings, listOf(TimeRange(500, 1500), TimeRange(3000, 5000)))
            }
            scenarios += Scenario("preset ${p.id} + trim", sdr, p.settings, listOf(TimeRange(1000, 4000)),
                durationTolerance = if (p.settings.video.encoder == VideoEncoder.COPY) 1100 else 400)
        }
        val x264 = VideoSettings(encoder = VideoEncoder.X264, rateControl = RateControl.QUALITY, quality = 28, speed = Speed.FASTEST)
        scenarios += Scenario("rotate+crop+fps+flip", sdr,
            EncodeSettings(video = x264.copy(rotate = 90, crop = CropAspect.R4_5, fps = 24, flipHorizontal = true, resolution = Resolution.P480))) { m ->
            val v = m.video!!
            when {
                v.width != 480 || v.height != 600 -> "size ${v.width}x${v.height}"
                abs(v.fps - 24) > 0.1 -> "fps ${v.fps}"
                else -> null
            }
        }
        scenarios += Scenario("opus + loudnorm + 44.1k", sdr, EncodeSettings(container = Container.MKV, video = x264,
            audio = AudioSettings(encoder = AudioEncoder.OPUS, bitrateKbps = 96, normalize = true, sampleRate = 48000))) { m ->
            if (m.audioStreams.size != 2 || m.audioStreams.any { it.codec != "opus" || it.channels > 2 }) "audio ${m.audioStreams}" else null
        }
        scenarios += Scenario("webm vp9", sdr, EncodeSettings(container = Container.WEBM,
            video = VideoSettings(encoder = VideoEncoder.VP9, rateControl = RateControl.QUALITY, speed = Speed.FASTEST),
            audio = AudioSettings(encoder = AudioEncoder.OPUS)), check = vcodec("vp9"))
        scenarios += Scenario("mov + pcm", sdr, EncodeSettings(container = Container.MOV, video = x264,
            audio = AudioSettings(encoder = AudioEncoder.PCM, allTracks = false)))
        scenarios += Scenario("mp4 bitrate x265", sdr, EncodeSettings(container = Container.MP4,
            video = VideoSettings(encoder = VideoEncoder.X265, rateControl = RateControl.BITRATE, bitrateKbps = 1500, speed = Speed.FASTEST)), check = vcodec("hevc"))
        scenarios += Scenario("mkv keeps subs as copy", sdr, EncodeSettings(container = Container.MKV, video = x264)) { m ->
            if (m.subtitleStreams.size != 1) "subs ${m.subtitleStreams}" else null
        }
        scenarios += Scenario("svt-av1", sdr, EncodeSettings(container = Container.MKV,
            video = VideoSettings(encoder = VideoEncoder.SVT_AV1, rateControl = RateControl.QUALITY, speed = Speed.FASTEST, resolution = Resolution.P360),
            audio = AudioSettings(encoder = AudioEncoder.OPUS)), check = vcodec("av1"))
        for (c in listOf(Container.MP3 to AudioEncoder.MP3, Container.OGG to AudioEncoder.OPUS, Container.WAV to AudioEncoder.PCM, Container.FLAC to AudioEncoder.FLAC)) {
            scenarios += Scenario("audio ${c.first}", sdr, EncodeSettings(container = c.first, video = VideoSettings(encoder = VideoEncoder.NONE),
                audio = AudioSettings(encoder = c.second, allTracks = false), subtitles = SubtitleMode.DROP)) { m ->
                if (m.hasVideo || m.audioStreams.size != 1) "streams ${m.streams.map { it.codec }}" else null
            }
        }
        if (hdrMade) {
            scenarios += Scenario("hdr tonemap x264", hdr, EncodeSettings(video = x264.copy(hdrToSdr = true), audio = AudioSettings(encoder = AudioEncoder.NONE))) { m ->
                val v = m.video!!
                if (caps.canTonemap && (v.isHdr || v.colorTransfer != "bt709")) "transfer ${v.colorTransfer}" else null
            }
            scenarios += Scenario("hdr keep 10-bit x265", hdr, EncodeSettings(container = Container.MKV,
                video = VideoSettings(encoder = VideoEncoder.X265, rateControl = RateControl.QUALITY, hdrToSdr = false, tenBit = true, speed = Speed.FASTEST),
                audio = AudioSettings(encoder = AudioEncoder.NONE))) { m ->
                if (VideoEncoder.X265 in tenBit && m.video!!.bitDepth != 10) "bit depth ${m.video!!.bitDepth}" else null
            }
        }

        val failures = mutableListOf<String>()
        var ran = 0
        for ((i, sc) in scenarios.withIndex()) {
            val media = probe(sc.input)
            val enc = sc.settings.video.encoder
            if (!enc.hardware && !desktopCaps.isAvailable(enc) && !sc.settings.container.audioOnly) {
                println("SKIP ${sc.name}: ${enc.ffName} not in this ffmpeg"); continue
            }
            if (!desktopCaps.isAvailable(sc.settings.audio.encoder) && media.hasAudio) {
                println("SKIP ${sc.name}: ${sc.settings.audio.encoder.ffName} not in this ffmpeg"); continue
            }
            val issues = Validator.validate(media, sc.settings, sc.segments, caps)
            if (Validator.hasErrors(issues)) { failures += "${sc.name}: validator rejected: $issues"; continue }

            val out = File(dir, "out_$i.${sc.settings.container.extension}").path
            val built = CommandBuilder.build(BuildRequest(sc.input, out, media, sc.settings, sc.segments, caps))
            val args = swapHardware(built.args)
            val r = exec(ffmpeg, args)
            ran++
            if (r.code != 0) {
                failures += "${sc.name}: exit ${r.code}\n  cmd: ${built.display()}\n  ${r.output.lines().takeLast(12).joinToString("\n  ")}"
                continue
            }
            val res = runCatching { probe(out) }.getOrElse { failures += "${sc.name}: unreadable output: $it"; continue }
            val problems = mutableListOf<String>()
            if (abs(res.durationMs - built.outputDurationMs) > sc.durationTolerance) {
                problems += "duration ${res.durationMs} != ${built.outputDurationMs} (streams: " +
                    res.streams.joinToString { "${it.type}:${it.codec}=${it.durationMs}" } + ")"
            }
            if (built.outputWidth > 0) {
                val v = res.video
                if (v == null) problems += "no video stream"
                else if (v.displayWidth != built.outputWidth || v.displayHeight != built.outputHeight) {
                    problems += "size ${v.displayWidth}x${v.displayHeight} != ${built.outputWidth}x${built.outputHeight}"
                }
            }
            sc.check(res)?.let { problems += it }
            if (problems.isNotEmpty()) failures += "${sc.name}: ${problems.joinToString()}\n  cmd: ${built.display()}"
            else println("OK   ${sc.name}")
        }
        println("ran $ran scenarios, ${failures.size} failed")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
        assertTrue(ran >= 20, "too few scenarios ran ($ran)")
    }
}
