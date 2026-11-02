package app.recode.engine

import android.content.Context
import android.os.Build
import app.recode.core.Capabilities
import app.recode.core.Codec
import app.recode.core.ProbePlan
import app.recode.core.VideoEncoder
import app.recode.diag.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Discovers what this device's FFmpeg can do (once per app version / OS build) and caches it.
 *
 * Each test encode has a timeout, and a marker file is written before it runs: if the process dies
 * during a test (a native crash inside a vendor codec), the next start skips that encoder instead of
 * crashing again.
 */
class CapabilityRepository(private val context: Context, private val engine: FfmpegEngine) {

    private val file = File(context.filesDir, "capabilities.json")
    private val keyFile = File(context.filesDir, "capabilities.key")
    private val markerFile = File(context.filesDir, "capabilities.testing")
    private val badFile = File(context.filesDir, "capabilities.crashed")
    private val mutex = Mutex()
    private val _caps = MutableStateFlow<Capabilities?>(null)
    val caps: StateFlow<Capabilities?> = _caps.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    /** Human-readable progress of a running probe, null when idle. */
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Current capabilities, or an optimistic guess while the probe is still running. */
    val current: Capabilities get() = _caps.value ?: Capabilities.OPTIMISTIC

    private val cacheKey: String
        get() {
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            }.getOrDefault(0L)
            return "$version|${Build.FINGERPRINT}|v3"
        }

    suspend fun load(force: Boolean = false): Capabilities = mutex.withLock {
        if (force) badFile.delete()
        if (!force) {
            _caps.value?.let { return it }
            val cached = runCatching {
                if (keyFile.readText() == cacheKey) Codec.caps(file.readText()) else null
            }.getOrNull()
            if (cached != null) {
                _caps.value = cached
                return cached
            }
        }
        val probed = try {
            probe()
        } catch (e: Throwable) {
            AppLog.e(TAG, "probe failed", e)
            // Never leave the app waiting forever: fall back to the encoder list we could get.
            Capabilities.OPTIMISTIC.copy(ffmpegVersion = "检测失败：${e.javaClass.simpleName}", verified = ProbePlan.TESTED.associateWith { false } + mapOf(VideoEncoder.X264 to true), probedAt = System.currentTimeMillis())
        } finally {
            _status.value = null
        }
        _caps.value = probed
        runCatching {
            file.writeText(Codec.caps(probed))
            keyFile.writeText(cacheKey)
        }
        probed
    }

    private suspend fun probe(): Capabilities {
        // An encoder that was being tested when the process died last time is treated as broken.
        val crashed = readCrashed().toMutableSet()
        markerFile.takeIf { it.exists() }?.readText()?.trim()?.let { name ->
            VideoEncoder.entries.firstOrNull { it.name == name }?.let {
                AppLog.w(TAG, "previous probe died while testing $it; marking unusable")
                crashed += it
                badFile.writeText(crashed.joinToString("\n") { e -> e.name })
            }
            markerFile.delete()
        }

        _status.value = "读取 FFmpeg 信息…"
        val version = Capabilities.parseVersion(engine.exec(ProbePlan.VERSION).log)
        // If the listing can't be captured for some reason, fall back to assuming a full build
        // rather than disabling every encoder; real test encodes below still catch missing ones.
        val encoders = Capabilities.parseNameTable(engine.exec(ProbePlan.ENCODERS).log)
            .ifEmpty { AppLog.w(TAG, "empty encoder list"); Capabilities.OPTIMISTIC.encoders }
        val filters = Capabilities.parseNameTable(engine.exec(ProbePlan.FILTERS).log)
            .ifEmpty { AppLog.w(TAG, "empty filter list"); Capabilities.OPTIMISTIC.filters }
        AppLog.i(TAG, "ffmpeg $version, ${encoders.size} encoders, ${filters.size} filters; crashed before=$crashed")

        val canVerify = engine.exec(ProbePlan.controlEncode()).success
        val verified = mutableMapOf<VideoEncoder, Boolean>()
        val tenBit = mutableSetOf<VideoEncoder>()
        if (canVerify) {
            for (e in ProbePlan.TESTED) {
                verified[e] = e !in crashed && e.ffName in encoders && test(e, tenBit = false)
            }
            for (e in ProbePlan.TEN_BIT_CANDIDATES) {
                if (verified[e] == true && test(e, tenBit = true)) tenBit += e
            }
        } else {
            AppLog.w(TAG, "control encode failed; trusting the encoder list")
            crashed.forEach { verified[it] = false }
        }
        AppLog.i(TAG, "verified=$verified tenBit=$tenBit")
        return Capabilities(version, encoders, filters, verified, tenBit, System.currentTimeMillis())
    }

    private suspend fun test(e: VideoEncoder, tenBit: Boolean): Boolean {
        _status.value = "测试 ${e.label}${if (tenBit) " 10-bit" else ""}…"
        markerFile.writeText(e.name)
        return try {
            // Hardware codecs get longer: some take a few seconds to spin up the first time.
            engine.exec(ProbePlan.testEncode(e, tenBit), timeoutMs = if (e.hardware) 12_000 else 8_000).success
        } finally {
            markerFile.delete()
        }
    }

    private fun readCrashed(): Set<VideoEncoder> = runCatching {
        badFile.readLines().mapNotNull { n -> VideoEncoder.entries.firstOrNull { it.name == n.trim() } }.toSet()
    }.getOrDefault(emptySet())

    companion object {
        private const val TAG = "Capabilities"
    }
}
