package net.pocketnai.data.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import java.io.File
import java.io.IOException

/**
 * 把私有目录里的 PNG 复制到系统相册（规划书 4.4）。
 *
 * 语义要点：“保存”是**复制**，不是移动。PocketNAI 私有历史保持不变；
 * 反之，删除私有历史也不会删除用户已经保存到相册的副本。
 */
class MediaStoreExporter(private val context: Context) {

    suspend fun export(source: File, displayName: String): Outcome<Uri> =
        withContext(Dispatchers.IO) {
            if (!source.isFile) {
                return@withContext Outcome.Failure(
                    AppError.of(ErrorCode.SAVE_TO_GALLERY_FAILED, detail = "源文件不存在: ${source.name}"),
                )
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    exportViaMediaStore(source, displayName)
                } else {
                    exportViaPublicDirectory(source, displayName)
                }
            } catch (e: IOException) {
                Outcome.Failure(
                    AppError.of(ErrorCode.SAVE_TO_GALLERY_FAILED, detail = e.message.orEmpty()),
                )
            } catch (e: SecurityException) {
                Outcome.Failure(
                    AppError.of(ErrorCode.SAVE_TO_GALLERY_FAILED, detail = "缺少写入相册的权限"),
                )
            }
        }

    /** Android 10+：走 MediaStore，不需要存储权限。 */
    private fun exportViaMediaStore(source: File, displayName: String): Outcome<Uri> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return Outcome.Failure(
                AppError.of(ErrorCode.SAVE_TO_GALLERY_FAILED, detail = "MediaStore 拒绝创建条目"),
            )

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: run {
                resolver.delete(uri, null, null)
                return Outcome.Failure(
                    AppError.of(ErrorCode.SAVE_TO_GALLERY_FAILED, detail = "无法打开 MediaStore 输出流"),
                )
            }
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Outcome.Success(uri)
    }

    /**
     * Android 9 及以下：写入公共 Pictures 目录后通知媒体扫描。
     * 这里必须自己处理重名，不覆盖用户已有的文件（规划书 5.2）。
     */
    private fun exportViaPublicDirectory(source: File, displayName: String): Outcome<Uri> {
        val albumDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_NAME,
        )
        if (!albumDir.exists() && !albumDir.mkdirs()) {
            return Outcome.Failure(
                AppError.of(ErrorCode.SAVE_TO_GALLERY_FAILED, detail = "无法创建相册目录"),
            )
        }

        val target = uniqueFile(albumDir, displayName)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(MIME_PNG),
            null,
        )
        return Outcome.Success(Uri.fromFile(target))
    }

    /** 重名时追加 ` (2)`、` (3)`，绝不覆盖。 */
    private fun uniqueFile(directory: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "png")
        var candidate = File(directory, displayName)
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index).$extension")
            index++
        }
        return candidate
    }

    private companion object {
        const val ALBUM_NAME = "PocketNAI"
        const val MIME_PNG = "image/png"
    }
}
