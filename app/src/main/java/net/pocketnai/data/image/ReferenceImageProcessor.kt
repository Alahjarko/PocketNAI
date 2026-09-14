package net.pocketnai.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Hashing
import net.pocketnai.core.Outcome
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.domain.image.ImageGeometry
import net.pocketnai.domain.image.ImageTransform
import net.pocketnai.domain.image.PixelSize
import net.pocketnai.domain.image.PreparedReference
import net.pocketnai.domain.image.ReferenceImageEncoder
import net.pocketnai.domain.image.ReferenceImageImporter
import net.pocketnai.domain.image.ReferenceSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 参考图处理管线（《参考图功能规划书》5.3）。
 *
 * ## 职责划分：导入时只归一化，提交时才按当前尺寸裁剪
 * 导入（[importImage]）把用户选的图**原样保留**（只做降采样与统一 PNG），
 * 裁剪与缩放发生在提交时（[encodeBase64]）。这样做的原因是尺寸是可变的：
 * 用户在界面上一改 Resolution，起点图就要按新尺寸重新裁切。
 * 如果导入时就把图裁死，改尺寸时就必须回头找原始 URI 重新处理，
 * 而那个 URI 的授权在应用重启后未必还在 —— 草稿里存的参考图会因此失效。
 *
 * 代价是每次提交多一次解码与绘制。只有一张图、且在 IO 线程上，可以接受。
 *
 * ## 为什么这里薄
 * 所有几何计算都在 [ImageGeometry]（纯 Kotlin、有单元测试），本类只负责调用 Android 的
 * 解码 / 绘制 / 编码 API 并把失败翻译成错误码。这样"算错尺寸"这类最难查的问题不会藏在这一层。
 *
 * ## base64 的生命周期
 * [encodeBase64] 返回的字符串只允许用于构造当次请求体，不写数据库、不写日志、不长期驻留
 * （见 AGENTS.md 的安全约束）。
 *
 * ## 已知限制
 * 不处理 EXIF 旋转：相机拍摄的照片若带方向标记，这里会按原始像素方向处理。
 * 照片选择器返回的内容在多数设备上已经归一化，因此首版不做；若真机上发现方向不对，
 * 再引入 `androidx.exifinterface`。
 */
class ReferenceImageProcessor(
    context: Context,
    private val fileStore: GenerationFileStore,
) : ReferenceImageEncoder, ReferenceImageImporter {

    private val appContext = context.applicationContext

    override suspend fun import(source: ReferenceSource): Outcome<PreparedReference> =
        when (source) {
            is ReferenceSource.PickedUri -> importUri(Uri.parse(source.uri))
            is ReferenceSource.LocalPath -> importFile(fileStore.resolve(source.relativePath))
        }

    private suspend fun importUri(uri: Uri): Outcome<PreparedReference> = withContext(Dispatchers.IO) {
        storeNormalized({ factor -> decodeSampled(uri, factor) })
    }

    private suspend fun importFile(file: File): Outcome<PreparedReference> =
        withContext(Dispatchers.IO) {
            if (!file.isFile) {
                return@withContext Outcome.Failure(AppError.of(ErrorCode.REFERENCE_MISSING))
            }
            storeNormalized({ factor -> decodeSampled(file, factor) })
        }

    /** 按 [transform] 裁切并编码成请求体里的 base64。 */
    override suspend fun encodeBase64(
        relativePath: String,
        transform: ImageTransform,
    ): Outcome<String> = withContext(Dispatchers.IO) {
        val file = fileStore.resolve(relativePath)
        if (!file.isFile) {
            return@withContext Outcome.Failure(AppError.of(ErrorCode.REFERENCE_MISSING))
        }

        val decoded = decodeSampled(file, factor = 1)
            ?: return@withContext Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED))

        try {
            val rendered = render(decoded, transform)
                ?: return@withContext Outcome.Failure(
                    AppError.of(ErrorCode.REFERENCE_DECODE_FAILED),
                )
            val bytes = encodePng(rendered)
                ?: return@withContext Outcome.Failure(
                    AppError.of(ErrorCode.REFERENCE_DECODE_FAILED),
                )
            if (!ImageGeometry.isWithinReferenceBudget(bytes.size.toLong())) {
                return@withContext Outcome.Failure(AppError.of(ErrorCode.REFERENCE_TOO_LARGE))
            }
            Outcome.Success(Base64.encodeToString(bytes, Base64.NO_WRAP))
        } finally {
            decoded.recycle()
        }
    }

    // ---- 导入 ----

    /**
     * 解码 → 统一成 PNG → 落盘。超预算就降采样重来。
     *
     * 降采样重试而不是直接拒绝，是因为用户无法判断"这张图为什么太大"；
     * 多试一次的成本只是一次解码，而重试次数有上界，不会在病态输入上打转。
     */
    private inline fun storeNormalized(decode: (Int) -> Bitmap?): Outcome<PreparedReference> {
        var factor = 1
        for (attempt in 0..MAX_DOWNSAMPLE_ATTEMPTS) {
            val decoded = decode(factor)
                ?: return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED))

            val result = try {
                val bytes = encodePng(decoded)
                    ?: return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED))
                if (!ImageGeometry.isWithinReferenceBudget(bytes.size.toLong())) {
                    null
                } else {
                    val sha256 = Hashing.sha256(bytes)
                    fileStore.writeReference(sha256, bytes)
                    PreparedReference(
                        relativePath = fileStore.referenceRelativePath(sha256),
                        width = decoded.width,
                        height = decoded.height,
                        byteSize = bytes.size.toLong(),
                        sha256 = sha256,
                    )
                }
            } catch (e: IOException) {
                return Outcome.Failure(AppError.of(ErrorCode.STORAGE_FULL))
            } finally {
                decoded.recycle()
            }

            if (result != null) return Outcome.Success(result)
            factor *= 2
        }
        return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_TOO_LARGE))
    }

    // ---- 解码 ----

    private fun decodeSampled(uri: Uri, factor: Int): Bitmap? = try {
        val bounds = readBounds(uri) ?: return null
        val sample = maxOf(factor, ImageGeometry.downsampleFactor(bounds))
        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options(sample))
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        // URI 授权已失效（例如进程重启后旧授权被回收）。
        null
    }

    private fun decodeSampled(file: File, factor: Int): Bitmap? = try {
        val bounds = readBounds(file) ?: return null
        val sample = maxOf(factor, ImageGeometry.downsampleFactor(bounds))
        BitmapFactory.decodeFile(file.absolutePath, options(sample))
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun readBounds(uri: Uri): PixelSize? = try {
        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, options)
            sizeOf(options)
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun readBounds(file: File): PixelSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return sizeOf(options)
    }

    private fun sizeOf(options: BitmapFactory.Options): PixelSize? =
        if (options.outWidth > 0 && options.outHeight > 0) {
            PixelSize(options.outWidth, options.outHeight)
        } else {
            null
        }

    private fun options(sampleSize: Int) = BitmapFactory.Options().apply {
        inSampleSize = sampleSize.coerceAtLeast(1)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    // ---- 绘制与编码 ----

    private fun render(source: Bitmap, transform: ImageTransform): Bitmap? {
        val placement = ImageGeometry.placementFor(
            transform = transform,
            source = PixelSize(source.width, source.height),
        )
        val canvasSize = placement.canvas

        return try {
            val output = Bitmap.createBitmap(
                canvasSize.width,
                canvasSize.height,
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(output)
            // 先铺黑：Letterbox 留下的区域必须与官方要求一致（黑边），
            // 而 Cover 铺满整个画布，这一步等于没做。
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(
                source,
                Rect(
                    placement.source.x,
                    placement.source.y,
                    placement.source.x + placement.source.width,
                    placement.source.y + placement.source.height,
                ),
                Rect(
                    placement.destination.x,
                    placement.destination.y,
                    placement.destination.x + placement.destination.width,
                    placement.destination.y + placement.destination.height,
                ),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            output
        } catch (e: OutOfMemoryError) {
            // 画布与源图同时驻留内存，低端机上有可能撑不住。
            null
        }
    }

    /** 编码成 PNG 字节；压缩失败返回 null。 */
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

    private companion object {
        /** 超预算后最多再降采样两次（即最多解码三次），避免病态输入下的长时间重试。 */
        const val MAX_DOWNSAMPLE_ATTEMPTS = 2
    }
}
