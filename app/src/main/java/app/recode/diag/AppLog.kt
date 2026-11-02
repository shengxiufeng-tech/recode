package app.recode.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs to logcat and to a small rotating file in app storage, so diagnostics can be shared from the
 * app itself without ADB.
 */
object AppLog {
    private const val MAX_BYTES = 768 * 1024L
    private var file: File? = null
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "app.log")
    }

    val dir: File? get() = file?.parentFile

    fun i(tag: String, msg: String) { Log.i(tag, msg); write("I", tag, msg, null) }
    fun w(tag: String, msg: String, t: Throwable? = null) { Log.w(tag, msg, t); write("W", tag, msg, t) }
    fun e(tag: String, msg: String, t: Throwable? = null) { Log.e(tag, msg, t); write("E", tag, msg, t) }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val f = file ?: return
        val line = buildString {
            append(synchronized(fmt) { fmt.format(Date()) }).append(' ').append(level).append('/').append(tag)
            append(" [").append(Thread.currentThread().name).append("] ").append(msg).append('\n')
            if (t != null) append(stackTrace(t)).append('\n')
        }
        synchronized(lock) {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, "app.log.1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line)
            }
        }
    }

    /** The most recent log text, newest last, at most [maxChars] characters. */
    fun tail(maxChars: Int): String {
        val f = file ?: return ""
        val text = synchronized(lock) {
            val old = File(f.parentFile, "app.log.1")
            (if (old.exists()) old.readText() else "") + (if (f.exists()) f.readText() else "")
        }
        return if (text.length > maxChars) text.takeLast(maxChars) else text
    }

    fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
}
