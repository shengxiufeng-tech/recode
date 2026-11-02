package app.recode.core

/**
 * JSON mapping for persisted state. Every reader tolerates missing or unknown values and falls back
 * to defaults, so data written by older versions of the app keeps loading.
 */
object Codec {

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    // ---- settings ----

    fun video(v: VideoSettings): Map<String, Any?> = mapOf(
        "encoder" to v.encoder.name,
        "rateControl" to v.rateControl.name,
        "quality" to v.quality,
        "bitrateKbps" to v.bitrateKbps,
        "speed" to v.speed.name,
        "resolution" to v.resolution.name,
        "fps" to v.fps,
        "tenBit" to v.tenBit,
        "hdrToSdr" to v.hdrToSdr,
        "crop" to v.crop.name,
        "rotate" to v.rotate,
        "flipHorizontal" to v.flipHorizontal,
        "encoderPreset" to v.encoderPreset,
        "tune" to v.tune,
        "profile" to v.profile,
        "fpsMode" to v.fpsMode.name,
        "keyframeSec" to v.keyframeSec,
        "hwBitrateMode" to v.hwBitrateMode,
    )

    fun video(m: Map<String, Any?>): VideoSettings {
        val d = VideoSettings()
        return VideoSettings(
            encoder = enumOf(m.str("encoder"), d.encoder),
            rateControl = enumOf(m.str("rateControl"), d.rateControl),
            quality = m.int("quality") ?: d.quality,
            bitrateKbps = m.int("bitrateKbps") ?: d.bitrateKbps,
            speed = enumOf(m.str("speed"), d.speed),
            resolution = enumOf(m.str("resolution"), d.resolution),
            fps = m.int("fps") ?: d.fps,
            tenBit = m.bool("tenBit") ?: d.tenBit,
            hdrToSdr = m.bool("hdrToSdr") ?: d.hdrToSdr,
            crop = enumOf(m.str("crop"), d.crop),
            rotate = m.int("rotate") ?: d.rotate,
            flipHorizontal = m.bool("flipHorizontal") ?: d.flipHorizontal,
            encoderPreset = m.str("encoderPreset") ?: d.encoderPreset,
            tune = m.str("tune") ?: d.tune,
            profile = m.str("profile") ?: d.profile,
            fpsMode = enumOf(m.str("fpsMode"), d.fpsMode),
            keyframeSec = m.int("keyframeSec") ?: d.keyframeSec,
            hwBitrateMode = m.str("hwBitrateMode") ?: d.hwBitrateMode,
        )
    }

    fun audio(a: AudioSettings): Map<String, Any?> = mapOf(
        "encoder" to a.encoder.name,
        "bitrateKbps" to a.bitrateKbps,
        "channels" to a.channels,
        "sampleRate" to a.sampleRate,
        "normalize" to a.normalize,
        "allTracks" to a.allTracks,
    )

    fun audio(m: Map<String, Any?>): AudioSettings {
        val d = AudioSettings()
        return AudioSettings(
            encoder = enumOf(m.str("encoder"), d.encoder),
            bitrateKbps = m.int("bitrateKbps") ?: d.bitrateKbps,
            channels = m.int("channels") ?: d.channels,
            sampleRate = m.int("sampleRate") ?: d.sampleRate,
            normalize = m.bool("normalize") ?: d.normalize,
            allTracks = m.bool("allTracks") ?: d.allTracks,
        )
    }

    fun settings(s: EncodeSettings): Map<String, Any?> = mapOf(
        "container" to s.container.name,
        "video" to video(s.video),
        "audio" to audio(s.audio),
        "subtitles" to s.subtitles.name,
        "keepMetadata" to s.keepMetadata,
    )

    fun settings(m: Map<String, Any?>): EncodeSettings {
        val d = EncodeSettings()
        return EncodeSettings(
            container = enumOf(m.str("container"), d.container),
            video = video(m["video"].obj()),
            audio = audio(m["audio"].obj()),
            subtitles = enumOf(m.str("subtitles"), d.subtitles),
            keepMetadata = m.bool("keepMetadata") ?: d.keepMetadata,
        )
    }

    // ---- presets ----

    fun preset(p: Preset): Map<String, Any?> = mapOf(
        "id" to p.id,
        "name" to p.name,
        "description" to p.description,
        "settings" to settings(p.settings),
    )

    fun preset(m: Map<String, Any?>): Preset? {
        val id = m.str("id") ?: return null
        return Preset(
            id = id,
            name = m.str("name") ?: "未命名",
            description = m.str("description") ?: "",
            builtIn = false,
            settings = settings(m["settings"].obj()),
        )
    }

    fun presets(list: List<Preset>): String = Json.stringify(list.map { preset(it) })

    fun presets(json: String): List<Preset> = runCatching {
        Json.parse(json).arr().mapNotNull { preset(it.obj()) }
    }.getOrDefault(emptyList())

    // ---- jobs ----

    fun job(j: Job): Map<String, Any?> = mapOf(
        "id" to j.id,
        "inputUri" to j.inputUri,
        "inputName" to j.inputName,
        "outputName" to j.outputName,
        "settings" to settings(j.settings),
        "segments" to j.segments.map { listOf(it.startMs, it.endMs) },
        "presetName" to j.presetName,
        "status" to j.status.name,
        "progress" to j.progress.toDouble(),
        "outputUri" to j.outputUri,
        "outputBytes" to j.outputBytes,
        "inputBytes" to j.inputBytes,
        "error" to j.error,
        "logTail" to j.logTail,
        "command" to j.command,
        "createdAt" to j.createdAt,
        "startedAt" to j.startedAt,
        "finishedAt" to j.finishedAt,
    )

    fun job(m: Map<String, Any?>): Job? {
        val id = m.str("id") ?: return null
        val input = m.str("inputUri") ?: return null
        return Job(
            id = id,
            inputUri = input,
            inputName = m.str("inputName") ?: "",
            outputName = m.str("outputName") ?: "output",
            settings = settings(m["settings"].obj()),
            segments = m["segments"].arr().mapNotNull { seg ->
                val pair = seg.arr()
                val a = (pair.getOrNull(0) as? Number)?.toLong()
                val b = (pair.getOrNull(1) as? Number)?.toLong()
                if (a != null && b != null) TimeRange(a, b) else null
            },
            presetName = m.str("presetName") ?: "",
            status = enumOf(m.str("status"), JobStatus.QUEUED),
            progress = (m.double("progress") ?: 0.0).toFloat(),
            outputUri = m.str("outputUri"),
            outputBytes = m.long("outputBytes") ?: 0,
            inputBytes = m.long("inputBytes") ?: 0,
            error = m.str("error"),
            logTail = m.str("logTail"),
            command = m.str("command"),
            createdAt = m.long("createdAt") ?: 0,
            startedAt = m.long("startedAt") ?: 0,
            finishedAt = m.long("finishedAt") ?: 0,
        )
    }

    fun jobs(list: List<Job>): String = Json.stringify(list.map { job(it) })

    fun jobs(json: String): List<Job> = runCatching {
        Json.parse(json).arr().mapNotNull { job(it.obj()) }
    }.getOrDefault(emptyList())

    // ---- capabilities cache ----

    fun caps(c: Capabilities): String = Json.stringify(
        mapOf(
            "ffmpegVersion" to c.ffmpegVersion,
            "encoders" to c.encoders.toList(),
            "filters" to c.filters.toList(),
            "verified" to c.verified.mapKeys { it.key.name },
            "tenBit" to c.tenBit.map { it.name },
            "probedAt" to c.probedAt,
        ),
    )

    fun caps(json: String): Capabilities? = runCatching {
        val m = Json.parse(json).obj()
        Capabilities(
            ffmpegVersion = m.str("ffmpegVersion") ?: "unknown",
            encoders = m["encoders"].arr().mapNotNull { it as? String }.toSet(),
            filters = m["filters"].arr().mapNotNull { it as? String }.toSet(),
            verified = m["verified"].obj().mapNotNull { (k, v) ->
                val e = VideoEncoder.entries.firstOrNull { it.name == k } ?: return@mapNotNull null
                (v as? Boolean)?.let { e to it }
            }.toMap(),
            tenBit = m["tenBit"].arr().mapNotNull { n -> VideoEncoder.entries.firstOrNull { it.name == n } }.toSet(),
            probedAt = m.long("probedAt") ?: 0,
        )
    }.getOrNull()
}
