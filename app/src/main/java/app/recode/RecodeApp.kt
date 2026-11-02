package app.recode

import android.app.Application
import app.recode.data.JobRepository
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
    val caps = CapabilityRepository(app, engine)
    val controller = EncodeController()
}

class RecodeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
        // Probe codecs in the background so the editor knows what the device supports.
        graph.scope.launch { runCatching { graph.caps.load() } }
        // Clean up temp files left behind by a killed encode.
        graph.scope.launch(Dispatchers.IO) {
            runCatching { java.io.File(cacheDir, "work").listFiles()?.forEach { it.delete() } }
        }
    }
}
