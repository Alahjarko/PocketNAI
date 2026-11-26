package net.pocketnai.domain.image

import net.pocketnai.domain.model.ImageSizePreset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.abs

/** 像素尺寸。domain 层不依赖 Android，因此自带一个最小的表示。 */
data class PixelSize(val width: Int, val height: Int) {

    val aspectRatio: Double get() = width.toDouble() / height.toDouble()

    val totalPixels: Long get() = width.toLong() * height.toLong()

    val longestSide: Int get() = max(width, height)
}

/**
 * 输出尺寸 → 像素尺寸。
 *
 * 两者字面上都是宽高，但语义不同：`ImageSizePreset` 是"用户选的那个档位"，
 * [PixelSize] 是"要绘制的画布"。显式转换让调用点无法把两者混用。
 */
fun ImageSizePreset.toPixelSize(): PixelSize = PixelSize(width, height)

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

    /** 与底图 Cover 共用裁切坐标，蒙版最近邻缩放后对齐 8px 网格，禁止柔化提交图。 */
    data class MaskCover(val target: PixelSize) : ImageTransform

    /** 完整放进 [canvas]，不足处留黑。用于已确定画布尺寸的场合。 */
    data class Letterbox(val canvas: PixelSize) : ImageTransform

    /**
     * 按官方 V4.5 要求补齐到与源图方向对应的画布（1024×1536 / 1536×1024 / 1472×1472）。
     *
     * 单独成一个变体，是因为"选哪块画布"取决于源图的方向，而方向要解码之后才知道 ——
     * 调用方在导入时还不掌握这个信息，只能把决策交给处理管线。
     */
    data object DirectorCanvas : ImageTransform

    /**
     * 按官方 V5 `bucketCharacterReferences` 规则匹配对数宽高比最近的 97 个分桶之一，
     * 等比覆盖缩放并居中裁切，不填充黑边（2026-11-25 核对官方前端 `2s4lj3fycctfh.js`）。
     */
    data object DirectorBucket : ImageTransform
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

    /** Precise Reference V4.5 官方要求的三种画布（规划书 3.3，来自 OpenAPI 的字段说明）。 */
    val DIRECTOR_PORTRAIT: PixelSize = PixelSize(1024, 1536)
    val DIRECTOR_LANDSCAPE: PixelSize = PixelSize(1536, 1024)
    val DIRECTOR_SQUARE: PixelSize = PixelSize(1472, 1472)

    /** Precise Reference V5 官方使用的 97 个分辨率分桶（核对自 `2s4lj3fycctfh.js` 模块 589161）。 */
    val DIRECTOR_BUCKETS: List<PixelSize> = listOf(
        PixelSize(256, 4096), PixelSize(256, 4032), PixelSize(256, 3968), PixelSize(256, 3904),
        PixelSize(256, 3840), PixelSize(256, 3776), PixelSize(256, 3712), PixelSize(256, 3648),
        PixelSize(256, 3584), PixelSize(256, 3520), PixelSize(256, 3456), PixelSize(256, 3392),
        PixelSize(256, 3328), PixelSize(320, 3264), PixelSize(320, 3200), PixelSize(320, 3136),
        PixelSize(320, 3072), PixelSize(320, 3008), PixelSize(320, 2944), PixelSize(320, 2880),
        PixelSize(320, 2816), PixelSize(320, 2752), PixelSize(384, 2688), PixelSize(384, 2624),
        PixelSize(384, 2560), PixelSize(384, 2496), PixelSize(384, 2432), PixelSize(384, 2368),
        PixelSize(448, 2304), PixelSize(448, 2240), PixelSize(448, 2176), PixelSize(448, 2112),
        PixelSize(512, 2048), PixelSize(512, 1984), PixelSize(512, 1920), PixelSize(512, 1856),
        PixelSize(576, 1792), PixelSize(576, 1728), PixelSize(576, 1664), PixelSize(640, 1600),
        PixelSize(640, 1536), PixelSize(704, 1472), PixelSize(704, 1408), PixelSize(768, 1344),
        PixelSize(768, 1280), PixelSize(832, 1216), PixelSize(896, 1152), PixelSize(960, 1088),
        PixelSize(1024, 1024), PixelSize(1088, 960), PixelSize(1152, 896), PixelSize(1216, 832),
        PixelSize(1280, 768), PixelSize(1344, 768), PixelSize(1408, 704), PixelSize(1472, 704),
        PixelSize(1536, 640), PixelSize(1600, 640), PixelSize(1664, 576), PixelSize(1728, 576),
        PixelSize(1792, 576), PixelSize(1856, 512), PixelSize(1920, 512), PixelSize(1984, 512),
        PixelSize(2048, 512), PixelSize(2112, 448), PixelSize(2176, 448), PixelSize(2240, 448),
        PixelSize(2304, 448), PixelSize(2368, 384), PixelSize(2432, 384), PixelSize(2496, 384),
        PixelSize(2560, 384), PixelSize(2624, 384), PixelSize(2688, 384), PixelSize(2752, 320),
        PixelSize(2816, 320), PixelSize(2880, 320), PixelSize(2944, 320), PixelSize(3008, 320),
        PixelSize(3072, 320), PixelSize(3136, 320), PixelSize(3200, 320), PixelSize(3264, 320),
        PixelSize(3328, 256), PixelSize(3392, 256), PixelSize(3456, 256), PixelSize(3520, 256),
        PixelSize(3584, 256), PixelSize(3648, 256), PixelSize(3712, 256), PixelSize(3776, 256),
        PixelSize(3840, 256), PixelSize(3904, 256), PixelSize(3968, 256), PixelSize(4032, 256),
        PixelSize(4096, 256),
    )

    /** 按放置方式给出完整方案。 */
    fun placementFor(transform: ImageTransform, source: PixelSize): ImagePlacement =
        when (transform) {
            is ImageTransform.Cover -> centerCrop(source, transform.target)
            is ImageTransform.MaskCover -> centerCrop(source, transform.target)
            is ImageTransform.Letterbox -> letterbox(source, transform.canvas)
            ImageTransform.DirectorCanvas -> directorPlacement(source)
            ImageTransform.DirectorBucket -> directorBucketPlacement(source)
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

    /** 官网 V4.5 选择长宽比绝对差最小的画布，等距时保留列表前项。 */
    fun directorCanvas(source: PixelSize): PixelSize =
        listOf(DIRECTOR_PORTRAIT, DIRECTOR_LANDSCAPE, DIRECTOR_SQUARE)
            .minBy { abs(it.aspectRatio - source.aspectRatio) }

    /** 官网 V5 按对数长宽比绝对差最小匹配 97 个分桶之一，等距时保留前项。 */
    fun directorBucket(source: PixelSize): PixelSize {
        val logAspect = kotlin.math.ln(
            max(source.width, 1).toDouble() / max(source.height, 1).toDouble(),
        )
        var best = DIRECTOR_BUCKETS.first()
        var bestDiff = Double.POSITIVE_INFINITY
        for (bucket in DIRECTOR_BUCKETS) {
            val diff = abs(logAspect - kotlin.math.ln(bucket.width.toDouble() / bucket.height.toDouble()))
            if (diff < bestDiff) {
                bestDiff = diff
                best = bucket
            }
        }
        return best
    }

    /** 根据模型策略返回参考图提交时的实际目标尺寸。 */
    fun directorTargetSize(source: PixelSize, useBucket: Boolean): PixelSize =
        if (useBucket) directorBucket(source) else directorCanvas(source)

    /** Precise Ref (V4.5) 小图也要放大填满一边，否则角色会缩在大黑框中，损失有效占比。 */
    private fun directorPlacement(source: PixelSize): ImagePlacement {
        val canvas = directorCanvas(source)
        val wider = source.aspectRatio > canvas.aspectRatio
        val width = if (wider) canvas.width else (canvas.height * source.aspectRatio).roundToInt()
        val height = if (wider) (canvas.width / source.aspectRatio).roundToInt() else canvas.height
        val fittedWidth = width.coerceIn(1, canvas.width)
        val fittedHeight = height.coerceIn(1, canvas.height)
        return ImagePlacement(
            source = PixelRegion(0, 0, source.width, source.height),
            canvas = canvas,
            destination = PixelRegion(
                ((canvas.width - fittedWidth) / 2.0).roundToInt(),
                ((canvas.height - fittedHeight) / 2.0).roundToInt(),
                fittedWidth, fittedHeight,
            ),
        )
    }

    /**
     * Precise Ref (V5) 分桶放置：对齐官方 `2s4lj3fycctfh.js` 模块 134691 `v(e)`。
     * 先按 `max(bw/w, bh/h)` 缩放到 `(rw, rh)`，再从 `(floor((rw-bw)/2), floor((rh-bh)/2))` 居中裁出 `bw × bh`。
     */
    private fun directorBucketPlacement(source: PixelSize): ImagePlacement {
        val bucket = directorBucket(source)
        val w = max(source.width, 1)
        val h = max(source.height, 1)
        if (w == bucket.width && h == bucket.height) {
            return ImagePlacement(
                source = PixelRegion(0, 0, w, h),
                canvas = bucket,
                destination = PixelRegion(0, 0, bucket.width, bucket.height),
            )
        }
        val scale = max(bucket.width.toDouble() / w, bucket.height.toDouble() / h)
        val rw = max(kotlin.math.floor(w * scale + 0.5).toInt(), bucket.width)
        val rh = max(kotlin.math.floor(h * scale + 0.5).toInt(), bucket.height)
        val left = (rw - bucket.width) / 2
        val top = (rh - bucket.height) / 2
        return ImagePlacement(
            source = PixelRegion(0, 0, source.width, source.height),
            canvas = bucket,
            destination = PixelRegion(-left, -top, rw, rh),
        )
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
}
