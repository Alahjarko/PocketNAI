package net.pocketnai.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import net.pocketnai.core.Hashing
import net.pocketnai.data.network.ZipImageExtractor
import net.pocketnai.domain.image.PixelRegion
import net.pocketnai.domain.image.PixelSize
import net.pocketnai.domain.metadata.PngTextChunks
import net.pocketnai.domain.metadata.PngTextWriter
import net.pocketnai.domain.metadata.PocketNaiOutputMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 生成结果的后处理：按自定义分辨率把画布裁成用户要的最终尺寸。
 *
 * ## 为什么在落盘之前做
 * 历史目录里的文件、数据库里的宽高、缩略图缓存与相册导出都以落盘那一刻的文件为准。
 * 先落盘再裁等于要把"已提交"的东西改一遍，而数据库事务、哈希与文件三者的一致性
 * 都要重新保证一次 —— 越晚改越容易留下不一致。
 *
 * ## 不裁切时一个字节都不动
 * 没有裁切请求时直接返回原列表：不重新编码 PNG，因此 SHA-256、体积与压缩痕迹
 * 都与服务端产物完全一致（预设尺寸的历史记录因此完全不受新功能影响）。
 *
 * ## 元数据必须保住
 * 裁切要解码再编码，而 `Bitmap.compress(PNG)` 会丢掉原图的 `tEXt`。
 * 因此这里先把白名单里的 NovelAI 文本块读出来，裁完再写回，并补一条
 * `pocketnai_output` 说明"这张图被本地裁过、原始画布多大"。
 * 不这么做的话，元数据导入功能会在用户裁过一次之后静默失效。
 *
 * ## 内存
 * 逐张处理并及时回收：四张 2 MP 的图同时驻留就是 ~32 MB 位图内存，
 * 低端机上足够触发 OOM。
 */
class OutputImageProcessor {

    /**
     * 对每张图应用裁切。
     *
     * [crop] 为空表示不裁切（返回原列表）；裁不出来（文件坏了）时**保留原图**并如实返回，
     * 不抛异常 —— 一次裁切失败不该让整批生成作废。
     */
    fun apply(
        images: List<ZipImageExtractor.ExtractedImage>,
        crop: PixelRegion?,
        canvas: PixelSize?,
    ): List<ZipImageExtractor.ExtractedImage> {
        if (crop == null || canvas == null) return images
        return images.map { image -> cropImage(image, crop, canvas) ?: image }
    }

    private fun cropImage(
        image: ZipImageExtractor.ExtractedImage,
        crop: PixelRegion,
        canvas: PixelSize,
    ): ZipImageExtractor.ExtractedImage? {
        val original = try {
            image.file.readBytes()
        } catch (e: IOException) {
            return null
        }

        val decoded = decode(image.file) ?: return null
        // 边界防护：服务端返回的尺寸与我们要裁的范围必须自洽，否则宁可不裁。
        if (crop.x + crop.width > decoded.width || crop.y + crop.height > decoded.height) {
            decoded.recycle()
            return null
        }

        val cropped = try {
            Bitmap.createBitmap(decoded, crop.x, crop.y, crop.width, crop.height)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (cropped == null) {
            decoded.recycle()
            return null
        }

        return try {
            val encoded = encodePng(cropped) ?: return null
            val withMetadata = PngTextWriter.withTextChunks(
                png = encoded,
                chunks = preservedChunks(original) + outputChunk(crop, canvas),
            )
            val bytes = withMetadata
            writeAtomically(image.file, bytes)
            // 裁切不改变像素值，但重新编码后字节数一定变，哈希必须重算。
            image.copy(
                byteSize = bytes.size.toLong(),
                sha256 = Hashing.sha256(bytes),
                width = cropped.width,
                height = cropped.height,
            )
        } catch (e: IOException) {
            null
        } finally {
            cropped.recycle()
            if (cropped !== decoded) decoded.recycle()
        }
    }

    /** 只保留白名单里的 NovelAI 文本块，其余（含未知工具的块）一律不带走。 */
    private fun preservedChunks(original: ByteArray): List<PngTextChunks.TextChunk> =
        PngTextWriter.readTextChunks(original).filter { chunk ->
            PocketNaiOutputMetadata.normalizeKeyword(chunk.keyword) in
                PocketNaiOutputMetadata.PRESERVED_KEYWORDS
        }

    private fun outputChunk(crop: PixelRegion, canvas: PixelSize): PngTextChunks.TextChunk =
        PngTextChunks.TextChunk(
            keyword = PocketNaiOutputMetadata.KEYWORD,
            text = PocketNaiOutputMetadata.Entry(
                outputWidth = crop.width,
                outputHeight = crop.height,
                generationWidth = canvas.width,
                generationHeight = canvas.height,
                cropX = crop.x,
                cropY = crop.y,
            ).encode(),
        )

    private fun decode(file: File): Bitmap? = try {
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun encodePng(bitmap: Bitmap): ByteArray? = try {
        ByteArrayOutputStream().use { buffer ->
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)) {
                buffer.toByteArray()
            } else {
                null
            }
        }
    } catch (e: OutOfMemoryError) {
        null
    }

    /** 先写 `.part` 再改名：与文件存储层同一套"不留半个文件"的做法。 */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val staging = File(target.parentFile, "${target.name}.crop.part")
        staging.outputStream().buffered().use { it.write(bytes) }
        if (!staging.renameTo(target)) {
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }
    }
}
