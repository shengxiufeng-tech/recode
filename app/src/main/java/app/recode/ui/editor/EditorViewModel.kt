package app.recode.ui.editor

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.recode.RecodeApp
import app.recode.core.AudioEncoder
import app.recode.core.AudioSettings
import app.recode.core.BuildRequest
import app.recode.core.BuildResult
import app.recode.core.Capabilities
import app.recode.core.CommandBuilder
import app.recode.core.Container
import app.recode.core.EncodeSettings
import app.recode.core.Issue
import app.recode.core.IssueLevel
import app.recode.core.Job
import app.recode.core.MediaInfo
import app.recode.core.Preset
import app.recode.core.Presets
import app.recode.core.SettingsRules
import app.recode.core.TimeRange
import app.recode.core.Validator
import app.recode.core.VideoEncoder
import app.recode.core.VideoSettings
import app.recode.data.JobRepository
import app.recode.service.EncodeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Source(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val media: MediaInfo? = null,
    val error: String? = null,
    val loading: Boolean = true,
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as RecodeApp).graph

    val sources = mutableStateListOf<Source>()
    var settings by mutableStateOf(EncodeSettings())
        private set
    var presetId by mutableStateOf<String?>(null)
        private set
    var segments by mutableStateOf<List<TimeRange>>(emptyList())
    var customName by mutableStateOf("")
    var caps by mutableStateOf(graph.caps.current)
        private set
    private var initialised = false

    val presets: List<Preset> get() = graph.presets.all
    val single: Source? get() = sources.singleOrNull()
    val primaryMedia: MediaInfo? get() = sources.firstNotNullOfOrNull { it.media }

    fun load(uris: List<Uri>) {
        if (initialised) return
        initialised = true
        val resolver = getApplication<Application>().contentResolver
        uris.forEach { uri ->
            var name = uri.lastPathSegment ?: "video"
            var size = 0L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            sources += Source(uri, name, size)
        }
        // Start from the last used settings (or the first preset), adapted to what this device supports.
        val last = graph.prefs.lastSettings
        val lastPreset = graph.prefs.lastPresetId?.let { id -> presets.firstOrNull { it.id == id } }
        settings = Presets.adapt(last ?: Presets.builtIn.first().settings, caps)
        presetId = if (last == null) Presets.builtIn.first().id else lastPreset?.id?.takeIf { lastPreset.settings == last }

        viewModelScope.launch {
            caps = graph.caps.load()
            settings = Presets.adapt(settings, caps)
        }
        sources.indices.forEach { i ->
            viewModelScope.launch {
                val src = sources[i]
                val result = runCatching { withContext(Dispatchers.IO) { graph.engine.probe(src.uri) } }
                sources[i] = src.copy(
                    media = result.getOrNull(),
                    error = result.exceptionOrNull()?.let { it.message ?: "无法读取此文件" },
                    loading = false,
                )
                if (result.isSuccess && i == 0) onFirstMediaLoaded(result.getOrThrow())
            }
        }
    }

    private fun onFirstMediaLoaded(m: MediaInfo) {
        // Audio files get an audio preset by default.
        if (!m.hasVideo && !settings.container.audioOnly) {
            applyPreset(presets.first { it.id == "audio-aac" })
        }
    }

    fun applyPreset(p: Preset) {
        presetId = p.id
        settings = Presets.adapt(p.settings, caps)
    }

    private fun edit(s: EncodeSettings) {
        settings = s
        presetId = null
    }

    fun setContainer(c: Container) = edit(SettingsRules.withContainer(settings, c, caps))
    fun setVideoEncoder(e: VideoEncoder) = edit(SettingsRules.withVideoEncoder(settings, e))
    fun setAudioEncoder(e: AudioEncoder) = edit(SettingsRules.withAudioEncoder(settings, e))
    fun video(t: (VideoSettings) -> VideoSettings) = edit(settings.copy(video = t(settings.video)))
    fun audio(t: (AudioSettings) -> AudioSettings) = edit(settings.copy(audio = t(settings.audio)))
    fun other(t: (EncodeSettings) -> EncodeSettings) = edit(t(settings))

    /** Issues for every loaded source (prefixed with the file name when there are several). */
    fun issues(): List<Issue> {
        val out = mutableListOf<Issue>()
        val many = sources.size > 1
        for (src in sources) {
            if (src.error != null) {
                out += Issue(IssueLevel.ERROR, (if (many) "${src.name}：" else "") + "无法读取：${src.error}")
                continue
            }
            val m = src.media ?: continue
            val segs = if (many) emptyList() else segments
            Validator.validate(m, settings, segs, caps).forEach {
                out += if (many) it.copy(message = "${src.name}：${it.message}") else it
            }
        }
        return out.distinct()
    }

    fun preview(): BuildResult? {
        val m = primaryMedia ?: return null
        val name = outputName(sources.first())
        return runCatching {
            CommandBuilder.build(BuildRequest("input", name, m, settings, if (sources.size > 1) emptyList() else segments, caps))
        }.getOrNull()
    }

    fun estimate(): Long? {
        val r = preview() ?: return null
        val m = primaryMedia ?: return null
        return CommandBuilder.estimateBytes(settings, m, r.outputDurationMs, r.outputWidth, r.outputHeight)
    }

    fun outputName(src: Source): String =
        SettingsRules.outputName(src.name, "_recode", settings.container, if (sources.size == 1) customName else null)

    val canEnqueue: Boolean
        get() = sources.isNotEmpty() && sources.none { it.loading } && sources.any { it.media != null } &&
            !Validator.hasErrors(issues())

    /** Adds one job per readable source and starts the encoder service. Returns the number queued. */
    fun enqueue(): Int {
        val now = System.currentTimeMillis()
        val presetName = presetId?.let { id -> presets.firstOrNull { it.id == id }?.name } ?: "自定义"
        val jobs = sources.filter { it.media != null }.mapIndexed { i, src ->
            Job(
                id = JobRepository.newId(),
                inputUri = src.uri.toString(),
                inputName = src.name,
                outputName = outputName(src),
                settings = settings,
                segments = if (sources.size == 1) CommandBuilder.normaliseSegments(segments, src.media!!.durationMs) else emptyList(),
                presetName = presetName,
                inputBytes = src.sizeBytes,
                createdAt = now + i,
            )
        }
        if (jobs.isEmpty()) return 0
        graph.jobs.add(jobs)
        graph.prefs.lastSettings = settings
        graph.prefs.lastPresetId = presetId
        EncodeService.start(getApplication())
        return jobs.size
    }

    fun saveAsPreset(name: String) {
        val p = graph.presets.save(name, "", settings)
        presetId = p.id
    }
}
