package net.pocketnai.domain.inpaint

import kotlin.math.roundToInt

/** 蒙版上的一个点，坐标是**位图像素**（不是屏幕像素）。 */
data class MaskPoint(val x: Float, val y: Float)

/**
 * 一笔。橡皮与画笔用同一个类型，只靠 [isErase] 区分 ——
 * 这样撤销栈里只有一种东西，重放逻辑也只有一条路径。
 */
data class MaskStroke(
    val isErase: Boolean,
    /** 笔刷半径，单位是**位图像素**。 */
    val radius: Float,
    val points: List<MaskPoint>,
) {
    val isDrawable: Boolean get() = points.isNotEmpty() && radius > 0f
}

/**
 * 蒙版编辑器的几何换算（纯 Kotlin，可 JVM 测试）。
 *
 * ## 为什么单独抽出来
 * 这个模块最容易错、又最难看出来：**手指点的是屏幕，蒙版要的是位图坐标**。
 * 换算错一点，用户涂的是脸、重画的是背景，而程序不会报任何错。
 * 而且它天然是纯函数 —— 屏幕矩形、位图尺寸、缩放比进，位图坐标出。
 */
object MaskGeometry {

    /**
     * 一张位图按 `ContentScale.Fit` 放进视图后的摆放结果。
     *
     * [scale] 是位图 → 屏幕的缩放比；[offsetX]/[offsetY] 是位图左上角在视图里的位置。
     */
    data class FitTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
    ) {
        /** 屏幕坐标 → 位图坐标。结果可能落在位图外（手指滑出图片范围），调用方自行裁剪。 */
        fun toBitmap(x: Float, y: Float, bitmapWidth: Int, bitmapHeight: Int): MaskPoint =
            MaskPoint(
                x = ((x - offsetX) / scale).coerceIn(0f, bitmapWidth.toFloat()),
                y = ((y - offsetY) / scale).coerceIn(0f, bitmapHeight.toFloat()),
            )

        /** 位图坐标 → 屏幕坐标（画已完成的笔画时用）。 */
        fun toView(point: MaskPoint): MaskPoint =
            MaskPoint(x = point.x * scale + offsetX, y = point.y * scale + offsetY)

        /** 屏幕上的长度 → 位图上的长度。 */
        fun toBitmapLength(length: Float): Float = length / scale
    }

    /**
     * 按 `Fit`（完整放下、居中、留边）计算摆放。
     *
     * 与 `ImageGeometry.letterbox` 是同一个思路，但这里是**视图坐标**而不是像素画布，
     * 因此用 Float 并在 [FitTransform] 里保留精确的缩放比，避免反复取整累积误差。
     */
    fun fit(bitmapWidth: Int, bitmapHeight: Int, viewWidth: Float, viewHeight: Float): FitTransform {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) {
            return FitTransform(scale = 1f, offsetX = 0f, offsetY = 0f)
        }
        val scale = minOf(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
        return FitTransform(
            scale = scale,
            offsetX = (viewWidth - bitmapWidth * scale) / 2f,
            offsetY = (viewHeight - bitmapHeight * scale) / 2f,
        )
    }

    /**
     * 笔刷半径的滑块取值 → 位图像素半径。
     *
     * 滑块是**屏幕**上的手感值（太大或太小都不好用），因此要按缩放比换算成位图半径，
     * 否则同一张图在不同屏幕上涂出来的宽度会不一样。
     */
    fun bitmapRadius(viewRadius: Float, transform: FitTransform): Float =
        (viewRadius * transform.toBitmapLength(1f)).coerceAtLeast(1f)

    /**
     * 蒙版扩张：把每一笔的半径加上 [dilationPx]。
     *
     * 这是**数学上等价**于对蒙版做形态学膨胀的写法：圆头笔刷画出的形状是"笔画 ∪ 半径 r 的圆"
     * （Minkowski 和），把半径整体加上 N 就等于把整个蒙版膨胀 N 像素。
     * 好处是**实时预览不需要重算位图** —— 改滑块只是换个半径重放笔画。
     *
     * 橡皮**不参与**扩张：把橡皮也放大等于把涂抹区域缩小，那与"扩张蒙版"的字面意思相反，
     * 用户会以为控件坏了。
     */
    fun effectiveRadius(stroke: MaskStroke, dilationPx: Float): Float =
        if (stroke.isErase) stroke.radius else stroke.radius + dilationPx.coerceAtLeast(0f)

    /** 蒙版是否为空（没有任何有效笔画，或全部被橡皮擦掉时调用方另判）。 */
    fun isEmpty(strokes: List<MaskStroke>): Boolean =
        strokes.none { it.isDrawable && !it.isErase }

    /** 从笔画起点到终点的距离，用于判断"同一个点被重复记录"这类退化输入。 */
    fun strokeLength(stroke: MaskStroke): Float {
        var total = 0f
        for (i in 1 until stroke.points.size) {
            val dx = stroke.points[i].x - stroke.points[i - 1].x
            val dy = stroke.points[i].y - stroke.points[i - 1].y
            total += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return total
    }

    /** 位图像素半径 → 显示用的整数（界面上的"48 px"就是它）。 */
    fun displayRadius(bitmapRadius: Float): Int = bitmapRadius.roundToInt()
}
