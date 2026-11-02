package app.recode.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.recode.R
import app.recode.core.Format
import app.recode.core.Job
import app.recode.core.JobStatus
import app.recode.ui.MainActivity

object Notifications {
    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_DONE = "done"
    const val ID_PROGRESS = 1001
    const val ID_DONE = 1002
    private const val SEGMENT = 100

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "转码进度", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "正在进行的转码任务（实时通知）"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "任务完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "转码完成或失败时提醒"
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(context: Context, jobId: String?): PendingIntent = PendingIntent.getBroadcast(
        context, 1,
        Intent(context, CancelReceiver::class.java).putExtra(CancelReceiver.EXTRA_JOB, jobId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun preparing(context: Context): Notification =
        base(context, null)
            .setContentTitle("准备转码…")
            .setProgress(0, 0, true)
            .build()

    /**
     * A progress-centric notification. On Android 16+ it is requested as a promoted "Live Update":
     * one segment per task in the current batch, with a status-bar chip showing the percentage.
     */
    fun progress(context: Context, current: Job, batch: List<Job>): Notification {
        val index = batch.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        val pct = (current.progress * 100).toInt().coerceIn(0, 100)
        val parts = buildList {
            add("$pct%")
            if (current.etaMs >= 0) add("剩余 ${Format.duration(current.etaMs)}")
            if (current.speed > 0) add(Format.speed(current.speed))
        }
        val title = if (batch.size > 1) "转码中 ${index + 1}/${batch.size} · ${current.inputName}" else "转码中 · ${current.inputName}"

        val primary = ContextCompat.getColor(context, R.color.brand)
        val failed = ContextCompat.getColor(context, R.color.error)
        val pending = ContextCompat.getColor(context, R.color.brand_light)
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(
                batch.map { j ->
                    NotificationCompat.ProgressStyle.Segment(SEGMENT).setColor(
                        when (j.status) {
                            JobStatus.FAILED, JobStatus.CANCELLED -> failed
                            JobStatus.QUEUED -> pending
                            else -> primary
                        },
                    )
                },
            )
            .setProgressPoints(
                (1 until batch.size).map { NotificationCompat.ProgressStyle.Point(it * SEGMENT).setColor(primary) },
            )
            .setProgress(index * SEGMENT + pct)

        val builder = base(context, current.id)
            .setContentTitle(title)
            .setContentText(parts.joinToString(" · "))
            .setShortCriticalText("$pct%")
        if (Build.VERSION.SDK_INT >= 36) builder.setStyle(style)
        else builder.setProgress(100, pct, false)
        return builder.build()
    }

    private fun base(context: Context, jobId: String?): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_recode)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setRequestPromotedOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(0, "取消", cancelIntent(context, jobId))

    @SuppressLint("MissingPermission") // checked by canPost()
    fun finished(context: Context, batch: List<Job>) {
        if (batch.isEmpty() || !canPost(context)) return
        val done = batch.count { it.status == JobStatus.DONE }
        val failedCount = batch.count { it.status == JobStatus.FAILED }
        val cancelled = batch.count { it.status == JobStatus.CANCELLED }
        if (done == 0 && failedCount == 0) return // only cancellations: the user already knows
        val title = when {
            failedCount == 0 && batch.size == 1 -> "转码完成"
            failedCount == 0 -> "全部 $done 个任务已完成"
            done == 0 -> "转码失败"
            else -> "$done 个完成，$failedCount 个失败"
        }
        val text = if (batch.size == 1) {
            val j = batch.first()
            if (j.status == JobStatus.DONE) "${j.outputName} · ${Format.bytes(j.outputBytes)}" else (j.error ?: "").lineSequence().firstOrNull() ?: ""
        } else {
            batch.filter { it.status == JobStatus.DONE }.joinToString("、") { it.outputName }.take(200) +
                (if (cancelled > 0) "（$cancelled 个已取消）" else "")
        }
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_recode)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_DONE, n) }
    }

    @SuppressLint("MissingPermission") // checked by canPost()
    fun update(context: Context, n: Notification) {
        if (!canPost(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(ID_PROGRESS, n) }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
