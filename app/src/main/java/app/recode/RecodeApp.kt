package app.recode

import android.app.Application
import app.recode.data.JobRepository
import app.recode.diag.AppLog
import app.recode.diag.CrashReporter
import app.recode.data.PresetRepository
import app.recode.data.Prefs
import app.recode.engine.CapabilityRepository
import app.recode.engine.FfmpegEngine
import app.recode.service.EncodeController
import app.recode.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val jobs = JobRepository(app.filesDir)
    val presets = PresetRepository(app.filesDir)
    val prefs = Prefs(app)
    val engine = FfmpegEngine(app)
    val caps = CapabilityRepository(app)
    val controller = EncodeController()

    /** Snapshot of app state for the diagnostics file. */
    fun diagnostics(): String = buildString {
        append("probe status: ${caps.status.value}\n")
        append("capabilities: ").append(caps.caps.value?.let { app.recode.core.Codec.caps(it) } ?: "(not ready)").append("\n\n")
        jobs.jobs.value.takeLast(10).forEach { j ->
            append("job ${j.id.take(8)} ${j.status} ${(j.progress * 100).toInt()}% ${j.inputName} -> ${j.outputName}\n")
            j.error?.let { append("  error: ").append(it).append("\n") }
            j.command?.let { append("  ").append(it).append("\n") }
            j.logTail?.let { append("  log: ").append(it.takeLast(2000)).append("\n") }
        }
    }
}

class RecodeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // The capability probe runs in its own ":probe" process; keep that process minimal.
        val isProbe = Application.getProcessName().endsWith(":probe")
        AppLog.init(this, if (isProbe) "probe.log" else "app.log")
        if (isProbe) return
        CrashReporter.install(this)
        AppLog.i("RecodeApp", "start ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} API ${android.os.Build.VERSION.SDK_INT}")
        graph = AppGraph(this)
        Notifications.createChannels(this)
        // Probe codecs in the background so the editor knows what the device supports.
        graph.scope.launch {
            runCatching { graph.caps.load() }.onFailure { AppLog.e("RecodeApp", "capability probe failed", it) }
        }
        // Clean up temp files left behind by a killed encode.
        graph.scope.launch(Dispatchers.IO) {
            runCatching { java.io.File(cacheDir, "work").listFiles()?.forEach { it.delete() } }
        }
    }
}
