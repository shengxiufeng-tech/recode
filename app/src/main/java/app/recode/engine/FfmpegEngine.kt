package app.recode.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import app.recode.core.MediaInfo
import app.recode.core.ProbeParser
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

data class RunResult(val success: Boolean, val cancelled: Boolean, val returnCode: Int, val log: String)

data class Stats(val timeMs: Double, val speed: Double, val sizeBytes: Long, val fps: Double)

/** Thin coroutine wrapper around FFmpegKit. */
class FfmpegEngine(private val context: Context) {

    init {
        runCatching { FFmpegKitConfig.setSessionHistorySize(8) }
    }

    /** Runs FFmpeg synchronously on the IO dispatcher; for short probe commands. */
    suspend fun exec(args: List<String>): RunResult = withContext(Dispatchers.IO) {
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        val rc = session.returnCode
        RunResult(ReturnCode.isSuccess(rc), ReturnCode.isCancel(rc), rc?.value ?: -1, session.output ?: "")
    }

    suspend fun probe(uri: Uri): MediaInfo = withContext(Dispatchers.IO) {
        val first = runCatching { InputHandle.open(context, uri).use { probePath(it.path) } }
        first.getOrNull() ?: run {
            Log.w(TAG, "probe via fd failed, retrying with SAF", first.exceptionOrNull())
            probePath(InputHandle.saf(context, uri).path)
        }
    }

    fun probePath(path: String): MediaInfo {
        val session = FFprobeKit.executeWithArguments(
            arrayOf("-v", "error", "-print_format", "json", "-show_format", "-show_streams", path),
        )
        val out = session.output ?: ""
        val start = out.indexOf('{')
        val end = out.lastIndexOf('}')
        if (!ReturnCode.isSuccess(session.returnCode) || start < 0 || end <= start) {
            throw IllegalStateException(out.lines().lastOrNull { it.isNotBlank() } ?: "无法读取媒体信息")
        }
        val info = ProbeParser.parse(out.substring(start, end + 1))
        if (info.streams.isEmpty()) throw IllegalStateException("文件里没有可识别的音视频流")
        return info
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
