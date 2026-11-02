package app.recode.data

import android.content.Context
import app.recode.diag.AppLog
import app.recode.core.Codec
import app.recode.core.EncodeSettings
import app.recode.core.Job
import app.recode.core.JobStatus
import app.recode.core.Json
import app.recode.core.Preset
import app.recode.core.Presets
import app.recode.core.obj
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

/** Writes via a temp file + rename so a crash mid-write never corrupts saved state. */
internal fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        writeText(text)
        tmp.delete()
    }
}

class JobRepository(dir: File) {
    private val file = File(dir, "jobs.json")
    private val lock = Any()
    private val _jobs = MutableStateFlow(load())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()
    private var lastSave = 0L

    private fun load(): List<Job> {
        val list = runCatching { if (file.exists()) Codec.jobs(file.readText()) else emptyList() }
            .onFailure { AppLog.e("JobRepository", "load failed", it) }
            .getOrDefault(emptyList())
        // A job that was running when the process died gets another go.
        return list.map { if (it.status == JobStatus.RUNNING) it.copy(status = JobStatus.QUEUED, progress = 0f) else it }
    }

    private fun persist(list: List<Job>, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 4000) return
        lastSave = now
        runCatching { file.writeAtomically(Codec.jobs(list)) }
            .onFailure { AppLog.e("JobRepository", "save failed", it) }
    }

    fun get(id: String): Job? = _jobs.value.firstOrNull { it.id == id }

    fun add(jobs: List<Job>) = synchronized(lock) {
        _jobs.update { it + jobs }
        persist(_jobs.value, true)
    }

    /** @param durable false for frequent progress ticks (persisted at most every few seconds). */
    fun update(id: String, durable: Boolean = true, transform: (Job) -> Job) = synchronized(lock) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
        persist(_jobs.value, durable)
    }

    fun remove(id: String) = synchronized(lock) {
        _jobs.update { list -> list.filterNot { it.id == id } }
        persist(_jobs.value, true)
    }

    fun clearFinished() = synchronized(lock) {
        _jobs.update { list -> list.filter { it.isActive } }
        persist(_jobs.value, true)
    }

    fun nextQueued(): Job? = _jobs.value.firstOrNull { it.status == JobStatus.QUEUED }

    fun retry(id: String) = update(id) {
        it.copy(status = JobStatus.QUEUED, progress = 0f, error = null, logTail = null, etaMs = -1, speed = 0.0, outputUri = null)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

class PresetRepository(dir: File) {
    private val file = File(dir, "presets.json")
    private val _user = MutableStateFlow(runCatching { if (file.exists()) Codec.presets(file.readText()) else emptyList() }.getOrDefault(emptyList()))
    val user: StateFlow<List<Preset>> = _user.asStateFlow()

    val all: List<Preset> get() = Presets.builtIn + _user.value

    fun save(name: String, description: String, settings: EncodeSettings): Preset {
        val p = Preset("user-" + JobRepository.newId(), name.trim().ifEmpty { "我的预设" }, description, false, settings)
        _user.update { it + p }
        file.writeAtomically(Codec.presets(_user.value))
        return p
    }

    fun delete(id: String) {
        _user.update { list -> list.filterNot { it.id == id } }
        file.writeAtomically(Codec.presets(_user.value))
    }
}

/** Small key-value settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("recode", Context.MODE_PRIVATE)

    var lastSettings: EncodeSettings?
        get() = sp.getString("lastSettings", null)?.let { s -> runCatching { Codec.settings(Json.parse(s).obj()) }.getOrNull() }
        set(v) { sp.edit().putString("lastSettings", v?.let { Json.stringify(Codec.settings(it)) }).apply() }

    var lastPresetId: String?
        get() = sp.getString("lastPresetId", null)
        set(v) { sp.edit().putString("lastPresetId", v).apply() }

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", false)
        set(v) { sp.edit().putBoolean("keepScreenOn", v).apply() }

    var notifiedPromotion: Boolean
        get() = sp.getBoolean("notifiedPromotion", false)
        set(v) { sp.edit().putBoolean("notifiedPromotion", v).apply() }
}
