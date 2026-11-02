package app.recode.core

import java.util.Locale
import kotlin.math.roundToLong

/** Smooths FFmpeg statistics into a progress fraction and a stable ETA. */
class ProgressTracker(private val totalMs: Long, private val startedAtMs: Long) {
    private var smoothedSpeed = 0.0
    var fraction: Float = 0f
        private set
    var etaMs: Long = -1
        private set
    var speed: Double = 0.0
        private set

    /** @param encodedMs output timestamp reached so far; @param reportedSpeed FFmpeg's "speed=" value. */
    fun update(encodedMs: Double, reportedSpeed: Double, nowMs: Long) {
        if (totalMs <= 0 || encodedMs < 0) return
        val f = (encodedMs / totalMs).coerceIn(0.0, 1.0).toFloat()
        // Never go backwards; FFmpeg occasionally reports a slightly earlier time.
        if (f > fraction) fraction = f
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(1)
        val measured = if (reportedSpeed > 0) reportedSpeed else encodedMs / elapsed
        smoothedSpeed = if (smoothedSpeed <= 0) measured else smoothedSpeed * 0.8 + measured * 0.2
        speed = smoothedSpeed
        val remainingMedia = (totalMs - encodedMs).coerceAtLeast(0.0)
        etaMs = if (smoothedSpeed > 0.001 && fraction > 0.002f) (remainingMedia / smoothedSpeed).roundToLong() else -1
    }
}

object Format {

    /** 3723400 → "1:02:03", 83400 → "1:23" */
    fun duration(ms: Long): String {
        if (ms < 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Timecode with tenths, used in the trim editor: "1:23.4", "1:02:03.4". */
    fun timecode(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val tenths = (safe % 1000) / 100
        return duration(safe) + "." + tenths
    }

    /** Parses "83.4", "1:23.4", "1:02:03.45" → milliseconds, or null if invalid. */
    fun parseTimecode(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size > 3) return null
        var total = 0.0
        for ((i, p) in parts.withIndex()) {
            val isLast = i == parts.lastIndex
            val v = (if (isLast) p.toDoubleOrNull() else p.toLongOrNull()?.toDouble()) ?: return null
            if (v < 0) return null
            if (!isLast && i > 0 && v >= 60) return null
            if (isLast && parts.size > 1 && v >= 60) return null
            total = total * 60 + v
        }
        return (total * 1000).roundToLong()
    }

    fun bytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun bitrate(bps: Long): String = when {
        bps <= 0 -> "—"
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        else -> String.format(Locale.US, "%d kbps", bps / 1000)
    }

    fun fps(f: Double): String = when {
        f <= 0 -> "—"
        kotlin.math.abs(f - f.roundToLong()) < 0.01 -> "${f.roundToLong()} fps"
        else -> String.format(Locale.US, "%.3f", f).trimEnd('0').trimEnd('.') + " fps"
    }

    fun speed(x: Double): String = if (x <= 0) "" else String.format(Locale.US, "%.2f×", x)
}
