package net.pocketnai.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Hashing
import net.pocketnai.core.Outcome
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.domain.image.ImageGeometry
import net.pocketnai.domain.image.PreparedReference
import net.pocketnai.domain.image.PixelSize
import net.pocketnai.domain.inpaint.MaskConvention
import net.pocketnai.domain.inpaint.MaskGeometry
import net.pocketnai.domain.inpaint.MaskPoint
import net.pocketnai.domain.inpaint.MaskStroke
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 把笔画渲染成提交给服务端的蒙版 PNG（局部重绘规划书 §5.2）。
 *
 * ## 硬边，没有抗锯齿；提交前对齐隐空间网格
 * 蒙版是"涂了 / 没涂"的二值语义。抗锯齿会在边界产生一圈半透明像素，
 * 而服务端怎么解释这些像素是未知的。因此画笔一律 `isAntiAlias = false`，
 * 落盘前再做一次 8×8 网格对齐（见 [snapToLatentGrid]）—— 否则任意精度的
 * 边界经服务端下采样后会产生"半涂半不涂"的隐空间边缘格，模型在那里
 * 发明过渡材质（用户实测的"白色不明材质边界"就是这么来的）。
 * 编辑器的实时预览保持平滑（差距最多半格 4px），网格对齐只作用于落盘的提交图。
 *
 * ## 扩张就是"半径加大"
 * 见 [MaskGeometry.effectiveRadius]：圆头笔刷的 Minkowski 和等价于形态学膨胀，
 * 所以扩张不需要真的对位图做卷积，改半径重画一遍即可 —— 这也是界面上能实时预览的原因。
 *
 * ## 约定可翻转
 * 涂抹区域画成白色还是留成透明，取决于 [MaskConvention]（探针确认前按白色实现）。
 */
class MaskImageProcessor(
    context: Context,
    private val fileStore: GenerationFileStore,
    private val convention: MaskConvention = MaskConvention.CURRENT,
) {

    private val appContext = context.applicationContext

    /** 渲染并落盘。返回的路径与尺寸可直接当作参考图入库。 */
    fun render(
        strokes: List<MaskStroke>,
        size: PixelSize,
        dilationPx: Float,
    ): Outcome<PreparedReference> {
        if (size.width <= 0 || size.height <= 0) {
            return Outcome.Failure(AppError.of(ErrorCode.INVALID_PARAMS))
        }

        var bitmap: Bitmap? = null
        return try {
            bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            paintStrokes(bitmap, strokes, dilationPx)
            // 提交前把蒙版对齐到隐空间网格（8×8 像素一格）。编辑器里的实时预览
            // 仍是平滑的；落盘的这份才是提交图，必须与服务端的下采样对齐。
            val output = snapToLatentGrid(bitmap)
            if (output !== bitmap) {
                bitmap.recycle()
                bitmap = output
            }

            val bytes = ByteArrayOutputStream().use { buffer ->
                if (!output.compress(Bitmap.CompressFormat.PNG, 100, buffer)) {
                    return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED))
                }
                buffer.toByteArray()
            }
            if (!ImageGeometry.isWithinReferenceBudget(bytes.size.toLong())) {
                return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_TOO_LARGE))
            }

            val sha256 = Hashing.sha256(bytes)
            fileStore.writeReference(sha256, bytes)
            Outcome.Success(
                PreparedReference(
                    relativePath = fileStore.referenceRelativePath(sha256),
                    width = size.width,
                    height = size.height,
                    byteSize = bytes.size.toLong(),
                    sha256 = sha256,
                ),
            )
        } catch (e: OutOfMemoryError) {
            Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED, detail = "蒙版画布过大"))
        } catch (e: IOException) {
            Outcome.Failure(AppError.of(ErrorCode.STORAGE_FULL, detail = e.message.orEmpty()))
        } finally {
            bitmap?.recycle()
        }
    }

    private fun paintStrokes(bitmap: Bitmap, strokes: List<MaskStroke>, dilationPx: Float) {
        val canvas = Canvas(bitmap)

        // 背景：白色约定下背景是黑的；透明约定下背景是不透明的，涂抹处再用 CLEAR 擦成透明。
        val background = when (convention) {
            MaskConvention.PAINTED_IS_WHITE -> Color.BLACK
            MaskConvention.PAINTED_IS_TRANSPARENT -> Color.WHITE
        }
        canvas.drawColor(background)

        val paint = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        strokes.forEach { stroke ->
            if (!stroke.isDrawable) return@forEach
            paint.strokeWidth = MaskGeometry.effectiveRadius(stroke, dilationPx) * 2f
            applyStyle(paint, stroke.isErase)

            val path = Path()
            val first = stroke.points.first()
            path.moveTo(first.x, first.y)
            if (stroke.points.size == 1) {
                // 单点：用一段极短的线配合圆头端点画出圆点。
                path.lineTo(first.x + 0.01f, first.y)
            } else {
                stroke.points.drop(1).forEach { point -> path.lineTo(point.x, point.y) }
            }
            canvas.drawPath(path, paint)
        }
    }

    /**
     * 画笔与橡皮各自"涂成什么"。
     *
     * 两种约定下正好互为镜像：白色约定里画笔给白色、橡皮给黑色；
     * 透明约定里画笔把像素擦成透明、橡皮再用不透明色补回来。
     */
    private fun applyStyle(paint: Paint, isErase: Boolean) {
        // 用 xfermode 而不是 Paint.blendMode：后者是 API 29+，本项目 minSdk 是 26。
        when (convention) {
            MaskConvention.PAINTED_IS_WHITE -> {
                paint.xfermode = null
                paint.color = if (isErase) Color.BLACK else Color.WHITE
            }

            MaskConvention.PAINTED_IS_TRANSPARENT -> {
                paint.color = Color.WHITE
                paint.xfermode = if (isErase) {
                    null
                } else {
                    PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            }
        }
    }

    /**
     * 把蒙版对齐到 8×8 隐空间网格：最近邻缩到 1/8 再最近邻放回。
     *
     * 这是官方前端蒙版管线的等价物（它们提交前先把蒙版量化到 1/8）：
     * 服务端在隐空间（分辨率的 1/8）解释蒙版，我们的任意精度边界经服务端
     * 下采样后会产生"半涂半不涂"的边缘格，模型就在那圈里发明过渡材质
     * （2026-09-14 用户实测：蒙版边缘生成一坨白色不明材质）。
     * 预先把每个格子定死成纯黑或纯白，无论服务端怎么下采样，看到的都是确定值。
     *
     * 输入是硬边二值图（无抗锯齿），最近邻缩放保持二值，不需要再阈值化。
     * 尺寸不能被 8 整除时原样返回（生成尺寸都是 64 的倍数，这只是防御）。
     */
    private fun snapToLatentGrid(source: Bitmap): Bitmap {
        if (source.width % LATENT_CELL != 0 || source.height % LATENT_CELL != 0) {
            return source
        }
        val cells = Bitmap.createScaledBitmap(
            source,
            source.width / LATENT_CELL,
            source.height / LATENT_CELL,
            false,
        )
        val snapped = Bitmap.createScaledBitmap(cells, source.width, source.height, false)
        cells.recycle()
        return snapped
    }

    /** 便于诊断：把笔画渲染成一张仅用于界面预览的位图（不落盘）。 */
    fun renderPreview(
        strokes: List<MaskStroke>,
        size: PixelSize,
        dilationPx: Float,
    ): Bitmap? = try {
        Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            paintStrokes(bitmap, strokes, dilationPx)
        }
    } catch (e: OutOfMemoryError) {
        null
    }

    companion object {
        /** 编辑器里笔刷半径的可选范围（位图像素）。 */
        val BRUSH_RANGE: ClosedFloatingPointRange<Float> = 4f..160f

        /** 蒙版扩张的可选范围（位图像素）。上限对应官方那句"涂太靠边会泄漏，把蒙版扩大一些"。 */
        val DILATION_RANGE: ClosedFloatingPointRange<Float> = 0f..24f

        /** 隐空间网格的边长（像素）：蒙版按它对齐，与官方前端一致。 */
        private const val LATENT_CELL = 8
    }
}

/** 仅供界面显示：把位图坐标点四舍五入，避免累积浮点误差。 */
internal fun MaskPoint.rounded(): MaskPoint =
    MaskPoint(kotlin.math.round(x), kotlin.math.round(y))
