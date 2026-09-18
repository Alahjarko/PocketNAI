package net.pocketnai.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 图像系统分享工具类。
 *
 * 通过 FileProvider 获取只读内容 URI，拉起系统选择器（Intent.ACTION_SEND / ACTION_SEND_MULTIPLE）。
 * 支持单图与批量分享。
 */
object ImageShareManager {

    private const val MIME_TYPE_PNG = "image/png"

    /**
     * 分享单张图片。
     */
    fun shareSingle(context: Context, file: File, title: String? = null) {
        if (!file.isFile || file.length() == 0L) return

        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        } catch (_: IllegalArgumentException) {
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE_PNG
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!title.isNullOrBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, title)
            }
        }

        val chooser = Intent.createChooser(shareIntent, title ?: "分享图片").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * 批量分享多张图片。
     */
    fun shareMultiple(context: Context, files: List<File>, title: String? = null) {
        val validFiles = files.filter { it.isFile && it.length() > 0L }
        if (validFiles.isEmpty()) return

        if (validFiles.size == 1) {
            shareSingle(context, validFiles.first(), title)
            return
        }

        val authority = "${context.packageName}.fileprovider"
        val uris = ArrayList<Uri>(validFiles.size)
        for (file in validFiles) {
            try {
                uris.add(FileProvider.getUriForFile(context, authority, file))
            } catch (_: IllegalArgumentException) {
                // 跳过无法获取 Uri 的文件
            }
        }
        if (uris.isEmpty()) return

        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = MIME_TYPE_PNG
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!title.isNullOrBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, title)
            }
        }

        val chooser = Intent.createChooser(shareIntent, title ?: "批量分享图片").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
