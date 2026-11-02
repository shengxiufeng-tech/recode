package app.recode.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records why the app died last time — Java exceptions via an uncaught-exception handler, native
 * crashes / ANRs / system kills via ApplicationExitInfo — and builds a shareable diagnostics file.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val PREFS = "crash"

    private fun reportFile(context: Context) = File(File(context.filesDir, "logs").apply { mkdirs() }, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = "Java 崩溃 · 线程 ${thread.name} · ${now()}\n\n${AppLog.stackTrace(error)}"
                AppLog.e(TAG, "uncaught exception on ${thread.name}", error)
                reportFile(app).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
        runCatching { collectExitReasons(app) }.onFailure { AppLog.w(TAG, "exit reasons failed", it) }
    }

    /** Native crashes and ANRs can't be caught in-process; Android 11+ tells us about them on the next start. */
    private fun collectExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(ActivityManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong("lastExitTs", 0)
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        val newest = infos.maxOfOrNull { it.timestamp } ?: return
        prefs.edit().putLong("lastExitTs", newest).apply()
        val bad = infos.filter {
            it.timestamp > lastSeen && it.reason in setOf(
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            )
        }.sortedByDescending { it.timestamp }
        val info = bad.firstOrNull() ?: return
        val kind = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生代码崩溃 (native)"
            ApplicationExitInfo.REASON_ANR -> "无响应 (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统结束"
            else -> "资源占用过多被系统结束"
        }
        val sb = StringBuilder()
        sb.append("$kind · ${fmt(info.timestamp)}\n")
        sb.append("description: ${info.description}\nstatus: ${info.status} importance: ${info.importance} pss: ${info.pss}KB rss: ${info.rss}KB\n")
        if (info.reason == ApplicationExitInfo.REASON_ANR) {
            runCatching { info.traceInputStream?.bufferedReader()?.use { sb.append("\n").append(it.readText().take(60_000)) } }
        }
        // Keep the Java stack trace if the uncaught handler already wrote one for this crash.
        val existing = reportFile(context).takeIf { it.exists() }?.readText()
        val text = if (info.reason == ApplicationExitInfo.REASON_CRASH && existing != null) existing + "\n\n" + sb else sb.toString()
        reportFile(context).writeText(text)
        AppLog.w(TAG, "previous exit: $kind ${info.description}")
    }

    fun pending(context: Context): String? = reportFile(context).takeIf { it.exists() }?.readText()

    fun dismiss(context: Context) { reportFile(context).delete() }

    /** Writes a diagnostics bundle and returns a share intent for it. */
    fun shareIntent(context: Context, extra: String): Intent {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val text = buildString {
            append("Recode 诊断信息 · ${now()}\n")
            append("app ${pkg?.versionName} (${pkg?.longVersionCode}) ${context.packageName}\n")
            append("device ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (Build.VERSION.SDK_INT >= 31) append(" · SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            append("\nabi ${Build.SUPPORTED_ABIS.joinToString()}\n\n")
            pending(context)?.let { append("===== 上次异常退出 =====\n").append(it).append("\n\n") }
            append("===== 状态 =====\n").append(extra).append("\n\n")
            append("===== 应用日志 =====\n").append(AppLog.tail(400_000))
        }
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "recode-diagnostics.txt").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Recode 诊断信息")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "分享诊断信息").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun now() = fmt(System.currentTimeMillis())
    private fun fmt(ts: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))
}
