package net.pocketnai.domain.image

/**
 * 分辨率规划：把"用户想要的最终尺寸"换算成"要提交给 NovelAI 的画布尺寸"以及"收到图之后的裁切"。
 *
 * ## 为什么需要单独一层
 * 以前一个 `width × height` 同时承担三种含义（界面输入、请求参数、最终文件尺寸），
 * 在预设尺寸下三者恰好相同，所以看不出问题。**自定义尺寸把这个巧合打破了**：
 * NovelAI 的画布边长必须是 64 的倍数，而用户想要的是 `1920×1080` 这类规格。
 *
 * 官方的做法（2026-09-14 从官方前端 bundle 反解并核对）：
 * - `dimensionStep = 64`（函数 `$d`，四个模型都是 64）；
 * - 输入框失焦与提交前各归一化一次，取**最近的** 64 倍数（`sD`：`floor` 与 `ceil`
 *   谁近取谁）。所以官网输入 `1920×1080` 会提交 `1920×1088`，并**不会**裁回 1080；
 * - 面积上限 `3,145,728 = 3 × 1024²`（`Dk`：宽高存在、`steps ≤ 50`、面积不超上限）。
 *
 * 我们比官方多走一步：`1920×1088` 生成完之后居中裁掉 8 px 得到真正的 `1920×1080`。
 * 只多生成 0.74% 的面积，而且是**不采样的裁切**（缩放会带来一次额外的重采样）。
 */
object ResolutionPlanner {

    /** 官方画布边长步长。 */
    const val STEP: Int = 64

    /** 官方面积上限：`3 × 1024 × 1024`。 */
    const val MAX_TOTAL_PIXELS: Long = 3_145_728L

    /** 官方输入框下限（步长同为 64）。 */
    const val MIN_DIMENSION: Int = 64

    /**
     * 本地安全上限（**不是** NovelAI 的限制）。
     *
     * 官方只按面积约束，理论上允许极端长条。我们不跟：超大画布在解码、缩略图与
     * GPU 纹理上都可能出问题，而这些问题在本机表现为"某张图打不开"，很难归因。
     * 2048 足够覆盖 1920×1080 / 1088×1920 这类真实需求。
     */
    const val LOCAL_MAX_DIMENSION: Int = 2048

    /**
     * 规划结果。
     *
     * [generationSize] 是提交给服务端的画布，[targetSize] 是用户要的最终尺寸；
     * [crop] 为空表示两者相同、不需要裁切。
     */
    data class Plan(
        val targetSize: PixelSize,
        val generationSize: PixelSize,
        val crop: PixelRegion?,
    ) {
        /** 是否需要在收到图之后裁切。 */
        val needsCrop: Boolean get() = crop != null

        /** 裁掉的总高度 / 总宽度，用于界面上"上下各裁 N px"这类说明。 */
        val croppedWidth: Int get() = generationSize.width - targetSize.width
        val croppedHeight: Int get() = generationSize.height - targetSize.height
    }

    sealed interface Result {
        data class Success(val plan: Plan) : Result

        data class Failure(val reason: Reason) : Result
    }

    enum class Reason {
        /** 目标任一边小于 64。 */
        SIDE_TOO_SMALL,

        /** 对齐后的画布超过官方面积上限。 */
        AREA_TOO_LARGE,

        /** 对齐后的画布超过我们的本地安全上限。 */
        SIDE_TOO_LARGE,
    }

    /**
     * 规划一次生成。
     *
     * [exactOutput] 的两种含义：
     * - `true`（"精确最终尺寸"）：画布**向上**对齐，多出来的边**居中裁掉**，最终文件等于
     *   [targetSize]。这是 PocketNAI 推荐给用户的模式，因为输入框里写的就是拿到的尺寸。
     * - `false`（仿官方）：画布取**最近的** 64 倍数，不裁切 —— 输入 `1920×1080`
     *   最终文件是 `1920×1088`，与官网行为一致。
     *
     * 注意向上取整而不是"取最近"：用户要 `1050×1050` 时，最近值是 `1024×1024`，
     * 比目标**小**，根本没有像素可裁。这是精确模式必须与官方分道扬镳的地方。
     */
    fun plan(targetSize: PixelSize, exactOutput: Boolean): Result {
        if (targetSize.width < MIN_DIMENSION || targetSize.height < MIN_DIMENSION) {
            return Result.Failure(Reason.SIDE_TOO_SMALL)
        }

        val generation = if (exactOutput) {
            PixelSize(ceilToStep(targetSize.width), ceilToStep(targetSize.height))
        } else {
            PixelSize(nearestStep(targetSize.width), nearestStep(targetSize.height))
        }

        // 本地安全上限：宁可明说"这个尺寸我们不支持"，也不发一个会拖垮解码的请求。
        if (generation.width > LOCAL_MAX_DIMENSION || generation.height > LOCAL_MAX_DIMENSION) {
            return Result.Failure(Reason.SIDE_TOO_LARGE)
        }

        val area = generation.width.toLong() * generation.height.toLong()
        if (area > MAX_TOTAL_PIXELS) {
            return Result.Failure(Reason.AREA_TOO_LARGE)
        }

        val crop = if (exactOutput && generation != targetSize) {
            centeredCrop(generation, targetSize)
        } else {
            null
        }

        return Result.Success(
            Plan(targetSize = targetSize, generationSize = generation, crop = crop),
        )
    }

    /**
     * 居中裁切的范围：从 [canvas] 里取 [target] 大小的一块。
     *
     * 多出来的**奇数**像素固定多给右下 1 px（左上取 `floor`）。规则确定，
     * 所以"同 Seed + 同参数"重现时不会出现偏移一像素这种查不出来的差异。
     *
     * 这是裁切规则的**唯一定义处**：规划器与落盘后处理都调它，
     * 两处各写一遍迟早会不一致，而那种不一致表现为"输出的图比说的尺寸大一点"。
     */
    fun centeredCrop(canvas: PixelSize, target: PixelSize): PixelRegion = PixelRegion(
        x = (canvas.width - target.width) / 2,
        y = (canvas.height - target.height) / 2,
        width = target.width,
        height = target.height,
    )

    /**
     * 预设尺寸的规划：预设本来就是 64 对齐的，因此不产生裁切。
     *
     * 预设**不走**面积/边长校验 —— 它们是官方给出的组合，合法性由 `ModelCatalog` 保证；
     * 万一某个预设超出上限，那是目录数据的问题，应该在目录测试里暴露，
     * 而不是让用户点一下按钮才收到一句"尺寸不支持"。
     */
    fun planForPreset(size: PixelSize): Plan =
        Plan(targetSize = size, generationSize = size, crop = null)

    /** 向上对齐到 64 的倍数。 */
    fun ceilToStep(value: Int): Int = ((value + STEP - 1) / STEP) * STEP

    /**
     * 对齐到最近的 64 倍数。
     *
     * 与官方前端 `sD` 完全一致，包括**距离相同时取较大值**这个细节
     * （官方写的是 `e-a < n-e ? a : n`，相等时走 `n`）。
     */
    fun nearestStep(value: Int): Int {
        val lower = (value / STEP) * STEP
        val upper = lower + STEP
        return if (value - lower < upper - value) lower else upper
    }
}
