package app.recode.engine

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import app.recode.core.Capabilities
import app.recode.core.Codec
import app.recode.core.ProbePlan
import app.recode.core.VideoEncoder
import app.recode.diag.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Discovers what this device's FFmpeg can do (once per app version / OS build) and caches it.
 *
 * The test encodes run in a separate process ([ProbeService], ":probe"): a vendor codec that crashes
 * in native code then only takes down that process. A marker file records which encoder was being
 * tested, so a restarted probe skips it and marks it unusable.
 */
class CapabilityRepository(private val context: Context) {

    private val dir = context.filesDir
    private val file = File(dir, "capabilities.json")
    private val keyFile = File(dir, "capabilities.key")
    private val markerFile = File(dir, "capabilities.testing")
    private val badFile = File(dir, "capabilities.crashed")
    private val statusFile = File(dir, "capabilities.status")
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
            return "$version|${Build.FINGERPRINT}|v4"
        }

    private fun readCache(): Capabilities? = runCatching {
        if (keyFile.exists() && keyFile.readText() == cacheKey) Codec.caps(file.readText()) else null
    }.getOrNull()

    // ---------------------------------------------------------------- main process

    suspend fun load(force: Boolean = false): Capabilities = mutex.withLock {
        if (!force) {
            _caps.value?.let { return it }
            readCache()?.let { _caps.value = it; return it }
        } else {
            badFile.delete()
        }
        val result = try {
            runProbeProcess()
        } finally {
            _status.value = null
        }
        _caps.value = result
        result
    }

    private suspend fun runProbeProcess(): Capabilities {
        file.delete(); keyFile.delete(); statusFile.delete()
        val started = System.currentTimeMillis()
        val deadline = started + 4 * 60_000
        var restarts = 0
        var deadSince = 0L
        startProbeService()
        while (System.currentTimeMillis() < deadline) {
            delay(400)
            _status.value = runCatching { statusFile.readText().ifBlank { null } }.getOrNull() ?: "正在启动检测…"
            readCache()?.let {
                AppLog.i(TAG, "probe finished in ${System.currentTimeMillis() - started}ms after $restarts restarts")
                return it
            }
            if (probeProcessAlive()) {
                deadSince = 0
            } else {
                val now = System.currentTimeMillis()
                if (deadSince == 0L) deadSince = now
                else if (now - deadSince > 3000) {
                    if (restarts >= 5) break
                    restarts++
                    AppLog.w(TAG, "probe process died (marker=${runCatching { markerFile.readText() }.getOrNull()}); restart #$restarts")
                    startProbeService()
                    deadSince = 0
                }
            }
        }
        AppLog.e(TAG, "probe did not finish; using fallback")
        val bad = readBad()
        return Capabilities.OPTIMISTIC.copy(
            ffmpegVersion = "检测未完成",
            verified = ProbePlan.TESTED.filter { it.hardware || it in bad }.associateWith { false },
            tenBit = emptySet(),
            probedAt = System.currentTimeMillis(),
        )
    }

    private fun startProbeService() {
        runCatching { context.startService(Intent(context, ProbeService::class.java)) }
            .onFailure { AppLog.e(TAG, "cannot start probe service", it) }
    }

    private fun probeProcessAlive(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        val name = context.packageName + ":probe"
        return am.runningAppProcesses?.any { it.processName == name } == true
    }

    // ---------------------------------------------------------------- probe process

    private fun setStatus(s: String) {
        runCatching { statusFile.writeText(s) }
    }

    private fun readBad(): Set<VideoEncoder> = runCatching {
        badFile.readLines().mapNotNull { n -> VideoEncoder.entries.firstOrNull { it.name == n.trim() } }.toSet()
    }.getOrDefault(emptySet())

    /** Runs in the ":probe" process. */
    suspend fun probeAndSave(engine: FfmpegEngine) {
        val crashed = readBad().toMutableSet()
        markerFile.takeIf { it.exists() }?.readText()?.trim()?.let { name ->
            VideoEncoder.entries.firstOrNull { it.name == name }?.let {
                AppLog.w(TAG, "previous probe died while testing $it; marking unusable")
                crashed += it
                badFile.writeText(crashed.joinToString("\n") { e -> e.name })
            }
            markerFile.delete()
        }

        setStatus("读取 FFmpeg 信息…")
        // `ffmpeg -version/-encoders/-filters` loop forever in this FFmpegKit build, so everything is
        // established with tiny real runs instead.
        val version = runCatching { com.arthenica.ffmpegkit.FFmpegKitConfig.getFFmpegVersion() }.getOrNull() ?: "unknown"
        AppLog.i(TAG, "ffmpeg $version; crashed before=$crashed")

        val canVerify = engine.exec(ProbePlan.controlEncode()).success
        AppLog.i(TAG, "control encode ok=$canVerify")
        val verified = mutableMapOf<VideoEncoder, Boolean>()
        val tenBit = mutableSetOf<VideoEncoder>()
        val encoders = mutableSetOf<String>()
        val filters = mutableSetOf("scale", "crop", "fps", "concat", "loudnorm", "aresample", "transpose", "hflip", "vflip")
        if (canVerify) {
            // Software encoders first: if a hardware codec takes the process down, the restart has less to redo.
            for (e in ProbePlan.TESTED.sortedBy { it.hardware }) {
                val ok = e !in crashed && test(engine, e, tenBit = false)
                verified[e] = ok
                if (ok) encoders += e.ffName
            }
            for (e in ProbePlan.TEN_BIT_CANDIDATES) {
                if (verified[e] == true && test(engine, e, tenBit = true)) tenBit += e
            }
            for (a in ProbePlan.AUDIO_TESTED) {
                setStatus("测试音频 ${a.label}…")
                if (engine.exec(ProbePlan.audioTest(a), timeoutMs = 8_000).success) encoders += a.ffName
            }
            setStatus("测试 HDR 色调映射…")
            if (engine.exec(ProbePlan.tonemapTest(), timeoutMs = 8_000).success) filters += setOf("zscale", "tonemap")
        } else {
            AppLog.w(TAG, "control encode failed; assuming a full build without hardware encoders")
            encoders += Capabilities.OPTIMISTIC.encoders
            filters += Capabilities.OPTIMISTIC.filters
            ProbePlan.TESTED.filter { it.hardware || it in crashed }.forEach { verified[it] = false }
        }
        AppLog.i(TAG, "verified=$verified tenBit=$tenBit encoders=$encoders filters=$filters")
        val caps = Capabilities(version, encoders, filters, verified, tenBit, System.currentTimeMillis())
        file.writeText(Codec.caps(caps))
        keyFile.writeText(cacheKey) // written last: its presence means the result is complete
        setStatus("")
    }

    private suspend fun test(engine: FfmpegEngine, e: VideoEncoder, tenBit: Boolean): Boolean {
        setStatus("测试 ${e.label}${if (tenBit) " 10-bit" else ""}…")
        markerFile.writeText(e.name)
        return try {
            // Hardware codecs get longer: some take a few seconds to spin up the first time.
            engine.exec(ProbePlan.testEncode(e, tenBit), timeoutMs = if (e.hardware) 12_000 else 8_000).success
        } finally {
            markerFile.delete()
        }
    }

    companion object {
        private const val TAG = "Capabilities"
    }
}

/** Hosts the capability probe in its own process (see manifest: android:process=":probe"). */
class ProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        AppLog.i("ProbeService", "starting probe in pid ${Process.myPid()}")
        scope.launch {
            try {
                CapabilityRepository(applicationContext).probeAndSave(FfmpegEngine(applicationContext))
            } catch (t: Throwable) {
                AppLog.e("ProbeService", "probe crashed", t)
            } finally {
                stopSelf()
                // Free FFmpeg's native memory and any stuck codec threads.
                delay(300)
                Process.killProcess(Process.myPid())
            }
        }
        return START_NOT_STICKY
    }
}
