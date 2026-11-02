package app.recode.engine

import android.content.Context
import android.os.Build
import android.util.Log
import app.recode.core.Capabilities
import app.recode.core.Codec
import app.recode.core.ProbePlan
import app.recode.core.VideoEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Discovers what this device's FFmpeg can do (once per app version / OS build) and caches it.
 */
class CapabilityRepository(private val context: Context, private val engine: FfmpegEngine) {

    private val file = File(context.filesDir, "capabilities.json")
    private val keyFile = File(context.filesDir, "capabilities.key")
    private val mutex = Mutex()
    private val _caps = MutableStateFlow<Capabilities?>(null)
    val caps: StateFlow<Capabilities?> = _caps.asStateFlow()

    /** Current capabilities, or an optimistic guess while the probe is still running. */
    val current: Capabilities get() = _caps.value ?: Capabilities.OPTIMISTIC

    private val cacheKey: String
        get() {
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            }.getOrDefault(0L)
            return "$version|${Build.FINGERPRINT}|v2"
        }

    suspend fun load(force: Boolean = false): Capabilities = mutex.withLock {
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
        val probed = probe()
        _caps.value = probed
        runCatching {
            file.writeText(Codec.caps(probed))
            keyFile.writeText(cacheKey)
        }
        probed
    }

    private suspend fun probe(): Capabilities {
        val version = Capabilities.parseVersion(engine.exec(ProbePlan.VERSION).log)
        // If the listing can't be captured for some reason, fall back to assuming a full build
        // rather than disabling every encoder; real test encodes below still catch missing ones.
        val encoders = Capabilities.parseNameTable(engine.exec(ProbePlan.ENCODERS).log)
            .ifEmpty { Capabilities.OPTIMISTIC.encoders }
        val filters = Capabilities.parseNameTable(engine.exec(ProbePlan.FILTERS).log)
            .ifEmpty { Capabilities.OPTIMISTIC.filters }
        Log.i(TAG, "ffmpeg $version, ${encoders.size} encoders, ${filters.size} filters")

        val canVerify = engine.exec(ProbePlan.controlEncode()).success
        val verified = mutableMapOf<VideoEncoder, Boolean>()
        val tenBit = mutableSetOf<VideoEncoder>()
        if (canVerify) {
            for (e in ProbePlan.TESTED) {
                verified[e] = e.ffName in encoders && engine.exec(ProbePlan.testEncode(e, false)).success
            }
            for (e in ProbePlan.TEN_BIT_CANDIDATES) {
                if (e.ffName in encoders && engine.exec(ProbePlan.testEncode(e, true)).success) tenBit += e
            }
        } else {
            Log.w(TAG, "control encode failed; trusting the encoder list")
        }
        Log.i(TAG, "verified=$verified tenBit=$tenBit")
        return Capabilities(version, encoders, filters, verified, tenBit, System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "Capabilities"
    }
}
