package app.recode.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.recode.core.MediaInfo
import app.recode.core.ProbeParser
import app.recode.diag.AppLog
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSessionCompleteCallback
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import kotlin.coroutines.resume

/** A content:// URI opened in a form FFmpeg can read. Close it after FFmpeg is done. */
class InputHandle private constructor(
    val path: String,
    private val pfd: ParcelFileDescriptor?,
) : Closeable {
    override fun close() {
        runCatching { pfd?.close() }
    }

    companion object {
        /**
         * Opens the URI and exposes it through `/proc/self/fd/N`. FFmpeg reopens that path, which gives it
         * an independent, seekable descriptor for regular files — and the same path can be used for
         * several inputs (multi-segment trims seek each input separately).
         */
        fun open(context: Context, uri: Uri): InputHandle {
            if (uri.scheme == "file") return InputHandle(uri.path!!, null)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("无法打开文件")
            return InputHandle("/proc/self/fd/${pfd.fd}", pfd)
        }

        /** FFmpegKit's own Storage Access Framework bridge; single use, kept as a fallback. */
        fun saf(context: Context, uri: Uri): InputHandle =
            InputHandle(FFmpegKitConfig.getSafParameterForRead(context, uri), null)
    }
}

data class RunResult(val success: Boolean, val cancelled: Boolean, val returnCode: Int, val log: String, val timedOut: Boolean = false)

data class Stats(val timeMs: Double, val speed: Double, val sizeBytes: Long, val fps: Double)

/**
 * Coroutine wrapper around FFmpegKit.
 *
 * Every call is asynchronous with a timeout, so a codec that hangs inside native code can never
 * freeze the app. Short commands (probing, capability checks) are serialised: they are cheap, and
 * running several FFmpeg/FFprobe instances at once in one process is the riskiest thing FFmpegKit does.
 */
class FfmpegEngine(private val context: Context) {

    private val shortLock = Mutex()

    init {
        runCatching {
            FFmpegKitConfig.setSessionHistorySize(16)
            FFmpegKitConfig.setAsyncConcurrencyLimit(16)
        }.onFailure { AppLog.e(TAG, "FFmpegKit init failed", it) }
    }

    /** Runs a short FFmpeg command and returns its full output. */
    suspend fun exec(args: List<String>, timeoutMs: Long = 15_000): RunResult = shortLock.withLock {
        val started = System.currentTimeMillis()
        var id = -1L
        val r = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val session = FFmpegKit.executeWithArgumentsAsync(
                    args.toTypedArray(),
                    FFmpegSessionCompleteCallback { s ->
                        val rc = s.returnCode
                        if (cont.isActive) cont.resume(RunResult(ReturnCode.isSuccess(rc), ReturnCode.isCancel(rc), rc?.value ?: -1, s.output ?: ""))
                    },
                )
                id = session.sessionId
                cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
            }
        }
        val took = System.currentTimeMillis() - started
        if (r == null) {
            AppLog.w(TAG, "timeout after ${took}ms (session $id): ${args.joinToString(" ")}")
            RunResult(false, false, -1, "timeout", timedOut = true)
        } else {
            AppLog.i(TAG, "rc=${r.returnCode} in ${took}ms: ${args.joinToString(" ").take(160)}")
            r
        }
    }

    suspend fun probe(uri: Uri): MediaInfo {
        val first = runCatching { InputHandle.open(context, uri).use { probePath(it.path) } }
        return first.getOrNull() ?: run {
            AppLog.w(TAG, "probe via fd failed, retrying with SAF", first.exceptionOrNull())
            probePath(InputHandle.saf(context, uri).path)
        }
    }

    suspend fun probePath(path: String, timeoutMs: Long = 30_000): MediaInfo = shortLock.withLock {
        val started = System.currentTimeMillis()
        val args = arrayOf("-v", "error", "-print_format", "json", "-show_format", "-show_streams", path)
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                val session = FFprobeKit.executeWithArgumentsAsync(
                    args,
                    FFprobeSessionCompleteCallback { s ->
                        if (cont.isActive) cont.resume(ReturnCode.isSuccess(s.returnCode) to (s.output ?: ""))
                    },
                )
                cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
            }
        } ?: throw IllegalStateException("读取媒体信息超时")
        val (ok, out) = result
        AppLog.i(TAG, "ffprobe ok=$ok ${out.length} chars in ${System.currentTimeMillis() - started}ms")
        val start = out.indexOf('{')
        val end = out.lastIndexOf('}')
        if (!ok || start < 0 || end <= start) {
            AppLog.w(TAG, "ffprobe failed: ${out.takeLast(1000)}")
            throw IllegalStateException(out.lines().lastOrNull { it.isNotBlank() } ?: "无法读取媒体信息")
        }
        val info = ProbeParser.parse(out.substring(start, end + 1))
        if (info.streams.isEmpty()) throw IllegalStateException("文件里没有可识别的音视频流")
        AppLog.i(TAG, "probed: ${info.formatName} ${info.durationMs}ms streams=${info.streams.map { "${it.type}:${it.codec}" }}")
        info
    }

    /**
     * Runs an encode. Cancelling the calling coroutine cancels the FFmpeg session.
     * [onStats] is called from FFmpegKit's callback thread.
     */
    suspend fun run(args: List<String>, onStats: (Stats) -> Unit): RunResult =
        suspendCancellableCoroutine { cont ->
            val tail = ArrayDeque<String>()
            val session = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                FFmpegSessionCompleteCallback { s ->
                    val rc = s.returnCode
                    val log = synchronized(tail) { tail.joinToString("") }
                    AppLog.i(TAG, "encode finished rc=${rc?.value} in ${s.duration}ms")
                    if (cont.isActive) {
                        cont.resume(RunResult(ReturnCode.isSuccess(rc), ReturnCode.isCancel(rc), rc?.value ?: -1, log))
                    }
                },
                LogCallback { log ->
                    val msg = log.message ?: return@LogCallback
                    synchronized(tail) {
                        tail.addLast(msg)
                        while (tail.size > 200) tail.removeFirst()
                    }
                },
                StatisticsCallback { st ->
                    onStats(Stats(st.time.toDouble(), st.speed.toDouble(), st.size.toLong(), st.videoFps.toDouble()))
                },
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }

    companion object {
        private const val TAG = "FfmpegEngine"

        /** Picks the lines worth showing to a user out of an FFmpeg log tail. */
        fun summariseError(log: String): String {
            val lines = log.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val interesting = lines.filter { l ->
                listOf("error", "invalid", "failed", "not supported", "unsupported", "no such", "cannot", "unable", "incompatible", "too many", "no space")
                    .any { l.contains(it, ignoreCase = true) }
            }
            return (interesting.ifEmpty { lines }).takeLast(4).joinToString("\n").take(600)
        }
    }
}
