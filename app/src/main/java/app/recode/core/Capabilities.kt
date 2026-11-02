package app.recode.core

/**
 * What the bundled FFmpeg build and this particular device can actually do.
 *
 * `encoders`/`filters` come from `ffmpeg -encoders` / `ffmpeg -filters`. Hardware (MediaCodec)
 * encoders are additionally verified with a tiny real encode, because being compiled in does not
 * mean the device's codec accepts our input.
 */
data class Capabilities(
    val ffmpegVersion: String,
    val encoders: Set<String>,
    val filters: Set<String>,
    /** Result of a real test encode, for encoders that were tested. */
    val verified: Map<VideoEncoder, Boolean> = emptyMap(),
    /** Encoders confirmed to accept 10-bit input. */
    val tenBit: Set<VideoEncoder> = emptySet(),
    val probedAt: Long = 0,
) {
    fun isAvailable(e: VideoEncoder): Boolean = when (e) {
        VideoEncoder.COPY, VideoEncoder.NONE -> true
        else -> verified[e] ?: (e.ffName in encoders)
    }

    fun isAvailable(a: AudioEncoder): Boolean = when (a) {
        AudioEncoder.COPY, AudioEncoder.NONE -> true
        else -> a.ffName in encoders
    }

    fun supportsTenBit(e: VideoEncoder): Boolean = e.supportsTenBit && e in tenBit

    val canTonemap: Boolean get() = "zscale" in filters && "tonemap" in filters

    val availableVideoEncoders: List<VideoEncoder> get() = VideoEncoder.entries.filter { isAvailable(it) }
    val availableAudioEncoders: List<AudioEncoder> get() = AudioEncoder.entries.filter { isAvailable(it) }

    companion object {
        /** Used before probing finishes and in tests: assume a full GPL desktop-like build. */
        val OPTIMISTIC = Capabilities(
            ffmpegVersion = "unknown",
            encoders = (VideoEncoder.entries.map { it.ffName } + AudioEncoder.entries.map { it.ffName })
                .filter { it.isNotEmpty() && it != "copy" }.toSet(),
            filters = setOf("zscale", "tonemap", "scale", "crop", "fps", "loudnorm", "concat"),
            tenBit = setOf(VideoEncoder.X264, VideoEncoder.X265, VideoEncoder.VP9, VideoEncoder.SVT_AV1, VideoEncoder.AOM_AV1),
        )

        /**
         * Parses the table printed by `ffmpeg -hide_banner -encoders` (or `-filters`).
         * Lines after the "------" separator look like: ` V....D libx264   libx264 H.264 ...`.
         */
        fun parseNameTable(output: String): Set<String> {
            val names = LinkedHashSet<String>()
            var inTable = false
            for (raw in output.lineSequence()) {
                val line = raw.trimEnd()
                if (!inTable) {
                    if (line.trim().startsWith("---")) inTable = true
                    continue
                }
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) names.add(parts[1])
            }
            if (!inTable) {
                // `-filters` has no separator line; flags are 3 chars, e.g. " TSC zscale   V->V  ..."
                for (raw in output.lineSequence()) {
                    val parts = raw.trim().split(Regex("\\s+"))
                    if (parts.size >= 3 && parts[2].contains("->")) names.add(parts[1])
                }
            }
            return names
        }

        fun parseVersion(output: String): String {
            val first = output.lineSequence().firstOrNull { it.startsWith("ffmpeg version") } ?: return "unknown"
            return first.removePrefix("ffmpeg version").trim().substringBefore(' ')
        }
    }
}
