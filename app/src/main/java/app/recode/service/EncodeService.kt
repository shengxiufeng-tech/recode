package app.recode.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.recode.RecodeApp
import app.recode.core.BuildRequest
import app.recode.core.CommandBuilder
import app.recode.core.Job
import app.recode.core.JobStatus
import app.recode.core.MediaInfo
import app.recode.core.ProgressTracker
import app.recode.core.Validator
import app.recode.core.IssueLevel
import app.recode.engine.FfmpegEngine
import app.recode.engine.InputHandle
import app.recode.engine.OutputWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lets the UI and the notification action cancel jobs without talking to the service directly. */
class EncodeController {
    @Volatile var currentJobId: String? = null
    @Volatile internal var cancelCurrent: (() -> Unit)? = null

    fun cancel(context: Context, jobId: String?) {
        val graph = (context.applicationContext as RecodeApp).graph
        val id = jobId ?: currentJobId ?: return
        if (id == currentJobId) cancelCurrent?.invoke()
        else graph.jobs.update(id) { if (it.status == JobStatus.QUEUED) it.copy(status = JobStatus.CANCELLED) else it }
    }
}

class CancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as RecodeApp).graph.controller.cancel(context, intent.getStringExtra(EXTRA_JOB))
    }

    companion object {
        const val EXTRA_JOB = "job"
    }
}

/**
 * Foreground service that works through the job queue one task at a time.
 * Progress is pushed to the repository (UI) and to a live progress notification.
 */
class EncodeService : Service() {

    private val graph by lazy { (application as RecodeApp).graph }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: kotlinx.coroutines.Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val batch = LinkedHashSet<String>()
    @Volatile private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with startForeground().
        goForeground()
        if (worker?.isActive != true) {
            worker = scope.launch { runQueue() }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val running = graph.controller.currentJobId?.let { graph.jobs.get(it) }
        val n = if (running != null) Notifications.progress(this, running, batchJobs()) else Notifications.preparing(this)
        ServiceCompat.startForeground(this, Notifications.ID_PROGRESS, n, type)
    }

    private suspend fun runQueue() {
        acquireWakeLock()
        try {
            while (true) {
                val job = graph.jobs.nextQueued() ?: break
                batch += job.id
                val encode = scope.launch { process(job) }
                graph.controller.currentJobId = job.id
                graph.controller.cancelCurrent = { encode.cancel() }
                encode.join()
                graph.controller.currentJobId = null
                graph.controller.cancelCurrent = null
            }
        } finally {
            releaseWakeLock()
            val finished = batch.mapNotNull { graph.jobs.get(it) }
            batch.clear()
            withContext(NonCancellable + Dispatchers.Main) {
                ServiceCompat.stopForeground(this@EncodeService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                Notifications.finished(this@EncodeService, finished)
                stopSelf()
            }
        }
    }

    private suspend fun process(job: Job) {
        val repo = graph.jobs
        val startedAt = System.currentTimeMillis()
        repo.update(job.id) {
            it.copy(status = JobStatus.RUNNING, startedAt = startedAt, progress = 0f, etaMs = -1, speed = 0.0, error = null, logTail = null)
        }
        val workDir = File(cacheDir, "work").apply { mkdirs() }
        val tmp = File(workDir, "${job.id}.${job.settings.container.extension}")
        try {
            val caps = graph.caps.load()
            val uri = Uri.parse(job.inputUri)
            val input = runCatching { InputHandle.open(this, uri) }
                .getOrElse { throw IllegalStateException("无法打开源文件，可能已被删除或移动：${it.message}") }
            input.use { handle ->
                val (media, path) = withContext(Dispatchers.IO) { probeWithFallback(handle, uri) }
                val issues = Validator.validate(media, job.settings, job.segments, caps)
                issues.firstOrNull { it.level == IssueLevel.ERROR }?.let { throw IllegalStateException(it.message) }

                tmp.delete()
                val built = CommandBuilder.build(BuildRequest(path, tmp.path, media, job.settings, job.segments, caps))
                val estimate = CommandBuilder.estimateBytes(job.settings, media, built.outputDurationMs, built.outputWidth, built.outputHeight)
                    ?: media.sizeBytes
                // Need room for the temp file plus the published copy.
                if (workDir.usableSpace < estimate * 2 + 200L * 1024 * 1024) {
                    throw IllegalStateException("存储空间可能不足，预计需要约 ${app.recode.core.Format.bytes(estimate * 2)}")
                }
                repo.update(job.id) { it.copy(command = built.display(), inputBytes = media.sizeBytes) }
                Log.i(TAG, built.display())

                val tracker = ProgressTracker(built.outputDurationMs, SystemClock.elapsedRealtime())
                var lastUi = 0L
                var lastNotif = 0L
                val result = graph.engine.run(built.args) { st ->
                    val now = SystemClock.elapsedRealtime()
                    tracker.update(st.timeMs, st.speed, now)
                    if (now - lastUi >= 300) {
                        lastUi = now
                        repo.update(job.id, durable = false) {
                            if (it.status != JobStatus.RUNNING) it
                            else it.copy(progress = tracker.fraction, etaMs = tracker.etaMs, speed = tracker.speed, outputBytes = st.sizeBytes)
                        }
                    }
                    if (now - lastNotif >= 1000) {
                        lastNotif = now
                        repo.get(job.id)?.let { j -> Notifications.update(this, Notifications.progress(this, j, batchJobs())) }
                    }
                }

                when {
                    result.success -> {
                        if (!tmp.exists() || tmp.length() == 0L) throw IllegalStateException("FFmpeg 没有生成输出文件")
                        repo.update(job.id) { it.copy(progress = 1f, etaMs = 0) }
                        val outUri = withContext(Dispatchers.IO) {
                            OutputWriter.publish(this@EncodeService, tmp, job.outputName, job.settings.container)
                        }
                        repo.update(job.id) {
                            it.copy(
                                status = JobStatus.DONE, outputUri = outUri.toString(), outputBytes = tmp.length(),
                                finishedAt = System.currentTimeMillis(), etaMs = 0,
                            )
                        }
                    }
                    result.cancelled -> markCancelled(job.id)
                    else -> repo.update(job.id) {
                        it.copy(
                            status = JobStatus.FAILED,
                            error = FfmpegEngine.summariseError(result.log).ifBlank { "FFmpeg 返回错误码 ${result.returnCode}" },
                            logTail = result.log.takeLast(6000),
                            finishedAt = System.currentTimeMillis(),
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { markCancelled(job.id) }
        } catch (e: Throwable) {
            Log.e(TAG, "job ${job.id} failed", e)
            repo.update(job.id) {
                it.copy(status = JobStatus.FAILED, error = e.message ?: e.javaClass.simpleName, finishedAt = System.currentTimeMillis())
            }
        } finally {
            tmp.delete()
        }
    }

    /** Probes via /proc/self/fd first; falls back to FFmpegKit's SAF bridge for providers that need it. */
    private fun probeWithFallback(handle: InputHandle, uri: Uri): Pair<MediaInfo, String> =
        try {
            graph.engine.probePath(handle.path) to handle.path
        } catch (e: Exception) {
            Log.w(TAG, "fd probe failed, trying SAF", e)
            val probePath = InputHandle.saf(this, uri).path
            val media = graph.engine.probePath(probePath)
            // SAF parameters are single-use, so the encode gets a fresh one.
            media to InputHandle.saf(this, uri).path
        }

    private fun markCancelled(id: String) {
        graph.jobs.update(id) {
            if (timedOut) it.copy(status = JobStatus.FAILED, error = "系统限制了后台处理时长（每天 6 小时），任务已停止", finishedAt = System.currentTimeMillis())
            else it.copy(status = JobStatus.CANCELLED, finishedAt = System.currentTimeMillis(), etaMs = -1)
        }
    }

    private fun batchJobs(): List<Job> {
        val all = graph.jobs.jobs.value
        val inBatch = batch.mapNotNull { id -> all.firstOrNull { it.id == id } }
        val queued = all.filter { it.status == JobStatus.QUEUED && it.id !in batch }
        return inBatch + queued
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Recode:encode").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /** Android 15+ caps media-processing foreground services at 6 hours per day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timeout")
        timedOut = true
        scope.cancel()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EncodeService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EncodeService::class.java))
        }
    }
}
