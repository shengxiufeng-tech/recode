package app.recode.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.recode.diag.AppLog
import app.recode.core.Container
import java.io.File

/**
 * Publishes a finished encode to shared storage (Movies/Recode or Music/Recode) via MediaStore,
 * so no storage permission is needed and the file shows up in the gallery / music apps right away.
 */
object OutputWriter {
    private const val TAG = "OutputWriter"
    const val FOLDER = "Recode"

    fun publish(context: Context, source: File, displayName: String, container: Container): Uri {
        val attempts = buildList {
            if (container.audioOnly) {
                add(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_MUSIC}/$FOLDER")
            } else {
                add(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            }
            // Downloads accepts any MIME type; used if the media collection rejects this one (e.g. MKV on some builds).
            add(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
        }
        var last: Exception? = null
        for ((collection, relPath) in attempts) {
            try {
                return write(context, collection, relPath, source, displayName, container.mime)
            } catch (e: Exception) {
                AppLog.w(TAG, "publish to $collection failed", e)
                last = e
            }
        }
        throw last ?: IllegalStateException("无法保存文件")
    }

    private fun write(context: Context, collection: Uri, relPath: String, source: File, name: String, mime: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore 拒绝了 $mime")
        try {
            resolver.openOutputStream(uri, "w").use { out ->
                requireNotNull(out) { "无法写入输出文件" }
                source.inputStream().use { it.copyTo(out, 1 shl 20) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Human-readable location for the UI. */
    fun describe(container: Container): String =
        if (container.audioOnly) "音乐/$FOLDER" else "影片/$FOLDER"
}
