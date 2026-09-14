package net.pocketnai.domain.image

import net.pocketnai.domain.model.SizeConstraints
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 像素尺寸。domain 层不依赖 Android，因此自带一个最小的表示。 */
data class PixelSize(val width: Int, val height: Int) {

    val aspectRatio: Double get() = width.toDouble() / height.toDouble()

    val totalPixels: Long get() = width.toLong() * height.toLong()

    val longestSide: Int get() = max(width, height)
}

/** 图片上的一块矩形区域，(x, y) 为左上角。 */
data class PixelRegion(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 一次「把源图放进画布」的完整方案。
 *
 * [source] 是从源图上取的哪一块，[destination] 是画到画布的哪个位置，[canvas] 是输出尺寸。
 * 两者分开表达，是因为两种放置方式对"保留什么"的取舍正好相反：
 * 图生图要铺满画布（裁掉多余边缘），参考图要完整保留（画布上留黑边）。
 */
data class ImagePlacement(
    val source: PixelRegion,
    val canvas: PixelSize,
    val destination: PixelRegion,
)

/** 参考图在提交前要做成什么形状。 */
sealed interface ImageTransform {

    /** 铺满 [target]，保持比例、裁剪多余边缘。用于 Image2Img。 */
    data class Cover(val target: PixelSize) : ImageTransform

    /** 完整放进 [canvas]，不足处留黑。用于 Precise Reference。 */
    data class Letterbox(val canvas: PixelSize) : ImageTransform
}

/**
 * 参考图的几何计算（《参考图功能规划书》5.3）。
 *
 * 这里只有整数与浮点运算，没有 Android 依赖，因此可以在 JVM 单元测试里逐条断言。
 * 之所以把它单独抽出来：算错的结果不会崩溃，而是**提交一个服务端拒绝的请求**
 * （尺寸不是 64 的倍数、总像素超限、图与 width/height 不一致），
 * 那种问题在真机上表现为一句笼统的"参数无效"，非常难查。
 */
object ImageGeometry {

    /**
     * 解码时的单边上限。
     *
     * 用户从相册选的可能是一张几千万像素的照片，直接解码会 OOM。
     * 先按这个上限降采样，再缩放到目标尺寸 —— 中间多一次缩放，代价远小于崩溃。
     */
    const val MAX_DECODE_DIMENSION: Int = 4096

    /** 单张参考图编码后的体积上限（base64 之前）。 */
    const val MAX_REFERENCE_FILE_BYTES: Long = 8L * 1024 * 1024

    /** 正方形判定带：长宽比落在 [1/CANVAS_SQUARE_BAND, CANVAS_SQUARE_BAND] 内视为方图。 */
    private const val CANVAS_SQUARE_BAND = 1.1

    /** Precise Reference 官方要求的三种画布（规划书 3.3，来自 OpenAPI 的字段说明）。 */
    val DIRECTOR_PORTRAIT: PixelSize = PixelSize(1024, 1536)
    val DIRECTOR_LANDSCAPE: PixelSize = PixelSize(1536, 1024)
    val DIRECTOR_SQUARE: PixelSize = PixelSize(1472, 1472)

    /** 按放置方式给出完整方案。 */
    fun placementFor(transform: ImageTransform, source: PixelSize): ImagePlacement =
        when (transform) {
            is ImageTransform.Cover -> centerCrop(source, transform.target)
            is ImageTransform.Letterbox -> letterbox(source, transform.canvas)
        }

    /**
     * 铺满画布：按比例放大到刚好覆盖 [canvas]，居中裁掉溢出的部分。
     *
     * 不做拉伸是有意的 —— 拉伸会改变人物比例，而用户选的源图里内容就是他的意图。
     */
    fun centerCrop(source: PixelSize, canvas: PixelSize): ImagePlacement {
        val scale = max(
            canvas.width.toDouble() / source.width,
            canvas.height.toDouble() / source.height,
        )
        val visibleWidth = (canvas.width / scale).roundToInt().coerceIn(1, source.width)
        val visibleHeight = (canvas.height / scale).roundToInt().coerceIn(1, source.height)

        return ImagePlacement(
            source = PixelRegion(
                x = (source.width - visibleWidth) / 2,
                y = (source.height - visibleHeight) / 2,
                width = visibleWidth,
                height = visibleHeight,
            ),
            canvas = canvas,
            destination = PixelRegion(0, 0, canvas.width, canvas.height),
        )
    }

    /**
     * 完整保留：按比例缩小到能放进 [canvas]，居中摆放，周围留空（由调用方填黑）。
     *
     * 只在需要缩小时才缩，不放大小图 —— 放大不会增加任何信息，只会让参考变糊。
     */
    fun letterbox(source: PixelSize, canvas: PixelSize): ImagePlacement {
        val scale = min(
            canvas.width.toDouble() / source.width,
            canvas.height.toDouble() / source.height,
        )
        // 源图比画布小时 scale > 1，此处刻意不用它：宁可留黑也不放大。
        val effective = min(scale, 1.0)
        val width = (source.width * effective).roundToInt().coerceIn(1, canvas.width)
        val height = (source.height * effective).roundToInt().coerceIn(1, canvas.height)

        return ImagePlacement(
            source = PixelRegion(0, 0, source.width, source.height),
            canvas = canvas,
            destination = PixelRegion(
                x = (canvas.width - width) / 2,
                y = (canvas.height - height) / 2,
                width = width,
                height = height,
            ),
        )
    }

    /** Precise Reference 的三选一画布：竖图 / 横图 / 方图。 */
    fun directorCanvas(source: PixelSize): PixelSize {
        val ratio = source.aspectRatio
        return when {
            ratio >= CANVAS_SQUARE_BAND -> DIRECTOR_LANDSCAPE
            ratio <= 1.0 / CANVAS_SQUARE_BAND -> DIRECTOR_PORTRAIT
            else -> DIRECTOR_SQUARE
        }
    }

    /**
     * 按源图比例算出一个该模型能接受的尺寸（边长是步长的整数倍、不超上限）。
     *
     * **只缩不放**：源图比上限小的时候保持原尺寸的取整结果，不放大 ——
     * 放大不会增加信息，却会让用户以为"这张图的细节够用"。
     */
    fun nearestLegalSize(source: PixelSize, constraints: SizeConstraints): PixelSize {
        val step = constraints.dimensionStep
        if (step <= 0) return source

        var scale = 1.0
        if (source.longestSide > constraints.maxDimension) {
            scale = constraints.maxDimension.toDouble() / source.longestSide
        }
        val scaledPixels = source.totalPixels * scale * scale
        if (scaledPixels > constraints.maxTotalPixels) {
            scale *= sqrt(constraints.maxTotalPixels.toDouble() / scaledPixels)
        }

        var width = floorToStep((source.width * scale).roundToInt(), step)
            .coerceIn(constraints.minDimension, constraints.maxDimension)
        var height = floorToStep((source.height * scale).roundToInt(), step)
            .coerceIn(constraints.minDimension, constraints.maxDimension)

        // 兜底：极端长宽比（例如 4000×100）下，取整后仍可能超过总像素上限。
        // 每次把较长的一边收一格，最多收 MAX_SHRINK_STEPS 次。
        var guard = 0
        while (width.toLong() * height > constraints.maxTotalPixels && guard < MAX_SHRINK_STEPS) {
            guard++
            if (width >= height) {
                if (width - step < constraints.minDimension) break
                width -= step
            } else {
                if (height - step < constraints.minDimension) break
                height -= step
            }
        }

        return PixelSize(width, height)
    }

    /**
     * 解码时用的降采样倍数（2 的幂，与 `BitmapFactory.Options.inSampleSize` 的语义一致）。
     *
     * 取"能让长边落到 [maxDimension] 以内的**最小** 2 的幂"，因此解码结果的单边落在
     * `(maxDimension / 2, maxDimension]` 区间：既不会 OOM，也不会把图缩得比需要的更小。
     *
     * 注意判据方向：要的是"除以 factor 之后**不超过**上限"，
     * 写成"除以 factor 之后仍不小于上限"会让 8000px 的图算出 factor = 1，解码时直接 OOM。
     */
    fun downsampleFactor(source: PixelSize, maxDimension: Int = MAX_DECODE_DIMENSION): Int {
        if (maxDimension <= 0) return 1
        var factor = 1
        while (source.longestSide / factor > maxDimension) {
            factor *= 2
        }
        return factor
    }

    /** base64 编码后的字符数（标准字母表、无换行）。请求体预算按它计算。 */
    fun base64Length(byteCount: Long): Long = ((byteCount + 2) / 3) * 4

    /** 编码后的单张参考图是否还在上传预算内。 */
    fun isWithinReferenceBudget(encodedBytes: Long): Boolean =
        encodedBytes <= MAX_REFERENCE_FILE_BYTES

    private fun floorToStep(value: Int, step: Int): Int = (value / step) * step

    /** 收缩兜底的最大迭代次数，避免病态输入下的长循环。 */
    private const val MAX_SHRINK_STEPS = 64
}
