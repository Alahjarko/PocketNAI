package net.pocketnai.data.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import net.pocketnai.domain.chat.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/** 图片独立存于私有目录，不占 Room 的 CursorWindow，也不暴露 FileProvider。 */
class ChatAttachmentStore(context: Context) {
    private val context = context.applicationContext
    private val root = File(context.filesDir, "chat-attachments")
    private val ownedPath = Regex("chat-attachments/[0-9a-f-]{36}\\.jpg")

    suspend fun importUri(uri: String): ChatAttachment = withContext(Dispatchers.IO) {
        if (Uri.parse(uri).scheme != "content") throw ChatFailure("请选择相册中的图片")
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { normalize(it) }
            ?: throw ChatFailure("无法读取所选图片")
    }

    suspend fun importGallery(relativePath: String): ChatAttachment = withContext(Dispatchers.IO) {
        val gallery = File(context.filesDir, "generations").canonicalFile
        val file = File(context.filesDir, relativePath).canonicalFile
        if (!file.path.startsWith(gallery.path + File.separator) || !file.isFile) throw ChatFailure("这张图库图片已不存在")
        file.inputStream().use { normalize(it) }
    }

    private fun normalize(input: InputStream): ChatAttachment {
        val original = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (original.size() + count > 20 * 1024 * 1024) throw ChatFailure("原图超过 20 MiB，请选择较小的图片")
            original.write(buffer, 0, count)
        }
        val bytes = original.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000)
            throw ChatFailure("图片无法解码或尺寸过大")
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ChatFailure("图片无法解码")
        val matrix = Matrix()
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(270f)
        }
        var rotated: Bitmap? = null
        var opaque: Bitmap? = null
        try {
            rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            opaque = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
            Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(rotated, 0f, 0f, null) }
            var encoded = ByteArray(0)
            for (quality in listOf(85, 70, 50, 30)) {
                encoded = ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                if (encoded.size <= MAX_IMAGE_BYTES) break
            }
            if (encoded.size > MAX_IMAGE_BYTES) throw ChatFailure("图片压缩后仍过大，请选择较小图片")
            val id = UUID.randomUUID().toString()
            root.mkdirs()
            val atomic = AtomicFile(File(root, "$id.jpg"))
            val output = atomic.startWrite()
            try { output.write(encoded); atomic.finishWrite(output) } catch (failure: Exception) { atomic.failWrite(output); throw failure }
            return ChatAttachment(id, "chat-attachments/$id.jpg", "image/jpeg", opaque.width, opaque.height)
        } finally {
            opaque?.recycle()
            if (rotated !== bitmap) rotated?.recycle()
            bitmap.recycle()
        }
    }

    /** 只在发送后读取/编码附件。缓存仅属于本次对话任务，进程历史里没有 data URL。 */
    suspend fun messages(entries: List<ChatEntry>, cache: MutableMap<String, String>): List<JsonObject> = withContext(Dispatchers.IO) {
        var totalBytes = 0L
        entries.map { entry ->
            val urls = entry.attachments.map { attachment ->
                val file = resolve(attachment)
                if (!file.isFile) throw ChatFailure("历史图片附件已丢失，请新建对话")
                totalBytes += file.length()
                if (file.length() > MAX_IMAGE_BYTES || totalBytes > 8L * 1024 * 1024) throw ChatFailure("对话图片上下文过大，请新建对话")
                cache.getOrPut(attachment.id) { "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }
            }
            ChatProtocol.withImages(entry.wire, urls)
        }
    }

    private fun resolve(attachment: ChatAttachment): File {
        if (!ownedPath.matches(attachment.relativePath)) throw ChatFailure("图片附件路径无效")
        return File(context.filesDir, attachment.relativePath)
    }
    suspend fun discard(attachment: ChatAttachment) = withContext(Dispatchers.IO) { resolve(attachment).delete(); Unit }
    companion object { const val MAX_IMAGE_BYTES = 512 * 1024 }
}
