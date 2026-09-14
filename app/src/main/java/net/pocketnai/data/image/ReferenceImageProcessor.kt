package net.pocketnai.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** 一张已经处理好、落盘并可以入库的参考图。 */
data class PreparedReference(
    val relativePath: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
)

/**
 * 参考图处理管线（《参考图功能规划书》5.3）。
 *
 * 做四件事：解码（带降采样）、按 [ImageTransform] 变换、编码 PNG、写入内容寻址的
 * `files/references/`。**不做 base64** —— 那一步在发请求时按需做，避免把几 MB 的
 * 字符串长期留在内存里。
 *
 * ## 为什么这里薄
 * 所有几何计算都在 [ImageGeometry]（纯 Kotlin、有单元测试），本类只负责调用 Android 的
 * 解码/绘制/编码 API 并把失败翻译成错误码。这样"算错尺寸"这类最难查的问题不会藏在这一层。
 *
 * ## 已知限制
 * 不处理 EXIF 旋转：相机拍摄的照片若带方向标记，这里会按原始像素方向处理。
 * 照片选择器返回的内容在多数设备上已经归一化，因此首版不做；若真机上发现图片方向不对，
 * 再引入 `androidx.exifinterface` 处理。
 */
class ReferenceImageProcessor(
    context: Context,
    private val fileStore: GenerationFileStore,
) {

    private val appContext = context.applicationContext

    /** 从相册 / 文件选择器返回的 URI 导入。 */
    suspend fun prepare(uri: Uri, transform: ImageTransform): Outcome<PreparedReference> =
        withContext(Dispatchers.IO) {
            prepareWithRetry({ factor -> decodeSampled(uri, factor) }, transform)
        }

    /** 从应用私有目录里已有的文件导入（"从历史选图"）。 */
    suspend fun prepare(file: File, transform: ImageTransform): Outcome<PreparedReference> =
        withContext(Dispatchers.IO) {
            if (!file.isFile) {
                return@withContext Outcome.Failure(AppError.of(ErrorCode.REFERENCE_MISSING))
            }
            prepareWithRetry({ factor -> decodeSampled(file, factor) }, transform)
        }

    /**
     * 解码 → 变换 → 编码 → 落盘，超预算就降采样重来。
     *
     * 降采样重试而不是直接拒绝，是因为用户无法判断"这张图为什么太大"；
     * 多试一次的成本只是一次解码，而重试次数有上界，不会在病态输入上打转。
     */
    private inline fun prepareWithRetry(
        decode: (Int) -> Bitmap?,
        transform: ImageTransform,
    ): Outcome<PreparedReference> {
        var factor = 1
        for (attempt in 0..MAX_DOWNSAMPLE_ATTEMPTS) {
            val decoded = decode(factor)
                ?: return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED))

            val rendered = renderAndStore(decoded, transform)
            decoded.recycle()

            when (rendered) {
                is Rendered.Success -> return Outcome.Success(rendered.reference)
                is Rendered.Failed -> return Outcome.Failure(AppError.of(rendered.code))
                Rendered.OverBudget -> factor *= 2
            }
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

    // ---- 变换 + 编码 + 落盘 ----

    private fun renderAndStore(source: Bitmap, transform: ImageTransform): Rendered {
        val placement = ImageGeometry.placementFor(
            transform = transform,
            source = PixelSize(source.width, source.height),
        )
        val canvasSize = placement.canvas

        var output: Bitmap? = null
        return try {
            output = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            // 先铺黑：Letterbox 留下的区域必须是黑色（官方对 Precise Reference 的要求），
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

            val bytes = ByteArrayOutputStream().use { buffer ->
                output.compress(Bitmap.CompressFormat.PNG, 100, buffer)
                buffer.toByteArray()
            }

            if (!ImageGeometry.isWithinReferenceBudget(bytes.size.toLong())) {
                return Rendered.OverBudget
            }

            val sha256 = Hashing.sha256(bytes)
            fileStore.writeReference(sha256, bytes)
            Rendered.Success(
                PreparedReference(
                    relativePath = fileStore.referenceRelativePath(sha256),
                    width = canvasSize.width,
                    height = canvasSize.height,
                    byteSize = bytes.size.toLong(),
                    sha256 = sha256,
                ),
            )
        } catch (e: OutOfMemoryError) {
            // 画布与源图同时驻留内存，低端机上有可能撑不住。
            Rendered.Failed(ErrorCode.REFERENCE_DECODE_FAILED)
        } catch (e: IOException) {
            Rendered.Failed(ErrorCode.STORAGE_FULL)
        } finally {
            output?.recycle()
        }
    }

    private sealed interface Rendered {
        data class Success(val reference: PreparedReference) : Rendered

        /** 编码后超出体积预算，调用方应降采样重试。 */
        data object OverBudget : Rendered

        data class Failed(val code: ErrorCode) : Rendered
    }

    private companion object {
        /** 超预算后最多再降采样两次（即最多解码三次），避免病态输入下的长时间重试。 */
        const val MAX_DOWNSAMPLE_ATTEMPTS = 2
    }
}
