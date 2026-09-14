package net.pocketnai.domain.billing

import net.pocketnai.domain.model.GenerationFamily
import kotlin.math.ceil

/**
 * 官方图片生成计价公式（2026-09-14 从官方网页前端 bundle 反解，版本 `novelai-web-2026-09-14`）。
 *
 * ## 基础费用
 * ```text
 * area = width × height            （下限 65536）
 * raw  = ceil(2.951823174884865e-6 × area + 5.753298233447344e-7 × area × steps)
 * raw *= smeaMultiplier            // 1.0 / 1.2 / 1.4
 * if (family == V5) raw *= 1.5     // V5 单价是 V4.5 的 1.5 倍
 * perImage = max(ceil(raw × strength), 2)   // strength 只在图生图/重绘时非 1
 * ```
 * 单张上限 140 Anlas：超过它官方前端不给数字（返回哨兵值），我们同样落"待确认"。
 *
 * 这个式子对 V4.5 与 V5 家族用的是**同一条**指数无关的线性式；官方前端里另有一条
 * 指数式子与三张采样器查表，只服务于 V1–V3 模型，与我们无关。
 *
 * ## 交叉验证
 * - 1024×1024 @ 28 步：`ceil(3.095 + 16.892) = 20`；
 * - 832×1216 @ 23 步：`ceil(2.986 + 13.387) = 17` —— 与社区长期流传的
 *   "默认一张约 17 Anlas"完全吻合，这也是我们判断反解正确的主要依据。
 *
 * ## 与官方前端的两处差异（都是"少发字段"造成的，不是公式差异）
 * 1. 官方前端总是显式发送 `sm` / `sm_dyn`（这四个模型的默认值都是 false），
 *    我们的请求不发这两个字段。服务端缺省是否等于 false 未实测，因此
 *    [AnlasPricingContext.smeaMultiplier] 由调用方按我们实际发出的字段给出（当前恒为 1.0）。
 * 2. 官方前端还会在 `char_ref`（多角色提示词）时取消免费单张；我们不做多角色提示词。
 */
class NovelAiPaidAnlasFormula : PaidAnlasFormula {

    override val version: String = VERSION

    override fun supports(context: AnlasPricingContext): Boolean =
        context.params.model.family in SUPPORTED_FAMILIES

    override fun calculateBatchTotal(
        context: AnlasPricingContext,
        billableImageCount: Int,
    ): Long {
        if (billableImageCount <= 0) return 0L
        val perImage = perImageCost(context) ?: return UNPRICED
        return saturatingMultiply(perImage, billableImageCount.toLong())
    }

    override fun calculateSurcharge(context: AnlasPricingContext): Long {
        val precise = saturatingMultiply(
            PRECISE_REFERENCE_ANLAS_PER_IMAGE,
            context.referenceImageCount.toLong() * context.params.sampleCount.toLong(),
        )
        val extraVibes = saturatingMultiply(
            VIBE_OVER_LIMIT_ANLAS,
            (context.vibeCount - FREE_VIBE_COUNT).coerceAtLeast(0).toLong(),
        )
        val encoding = saturatingMultiply(
            VIBE_ENCODE_ANLAS,
            context.uncachedVibeCount.toLong(),
        )
        return saturatingAdd(saturatingAdd(precise, extraVibes), encoding)
    }

    /** 单张费用；无法定价（面积/步数离谱、或超过官方上限）时返回 null。 */
    fun perImageCost(context: AnlasPricingContext): Long? {
        val width = context.params.size.width
        val height = context.params.size.height
        val steps = context.params.steps
        if (width <= 0 || height <= 0 || steps <= 0) return null
        // 官方前端认为"参数不对"的两条硬边界。
        if (steps > MAX_STEPS_PRICED) return null

        val area = (width.toLong() * height.toLong()).coerceAtLeast(MIN_AREA)
        if (area > MAX_AREA) return null

        var raw = ceil(
            AREA_COEFFICIENT * area + STEP_AREA_COEFFICIENT * area * steps,
        )
        raw *= context.smeaMultiplier
        if (context.params.model.family == GenerationFamily.V5) {
            raw *= V5_MULTIPLIER
        }

        val strength = context.strengthMultiplier.takeIf { it > 0.0 } ?: 1.0
        val perImage = ceil(raw * strength).toLong().coerceAtLeast(MIN_IMAGE_COST)
        if (perImage > MAX_IMAGE_COST) return null
        return perImage
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun saturatingMultiply(left: Long, right: Long): Long =
        if (left != 0L && right > Long.MAX_VALUE / left) Long.MAX_VALUE else left * right

    companion object {
        /** 公式版本号，会显示在费用详情里，便于日后追查"这个数字是哪版算的"。 */
        const val VERSION: String = "novelai-web-2026-09-14"

        private val SUPPORTED_FAMILIES = setOf(
            GenerationFamily.V4_5,
            GenerationFamily.V5,
        )

        /** 官方式子的两个系数，原样抄自前端 bundle，不要"化简"。 */
        const val AREA_COEFFICIENT: Double = 2951823174884865e-21
        const val STEP_AREA_COEFFICIENT: Double = 5753298233447344e-22

        /** V5 单价倍率。 */
        const val V5_MULTIPLIER: Double = 1.5

        /** 面积下限与上限（官方前端的 `g<65536→65536` 与参数校验）。 */
        const val MIN_AREA: Long = 65536L
        /**
         * 面积上限：与官方面板同源，因此直接引用尺寸约束里的那个常量，
         * 避免"能提交但不能报价"或反之的错位。
         */
        val MAX_AREA: Long = net.pocketnai.domain.model.SizeConstraints.OFFICIAL_MAX_TOTAL_PIXELS

        /** 官方前端认为"步数不合理"的边界。 */
        const val MAX_STEPS_PRICED: Int = 50

        /** 单张下限与上限（官方前端 `max(...,2)` 与 140 的报价上限）。 */
        const val MIN_IMAGE_COST: Long = 2L
        const val MAX_IMAGE_COST: Long = 140L

        /** Precise Reference：每张参考图、每张输出图 5 Anlas。 */
        const val PRECISE_REFERENCE_ANLAS_PER_IMAGE: Long = 5L

        /** Vibe：前 4 张免费。 */
        const val FREE_VIBE_COUNT: Int = 4

        /** Vibe：超出 4 张的部分每张 2 Anlas。 */
        const val VIBE_OVER_LIMIT_ANLAS: Long = 2L

        /** Vibe：把一张图编码成 `.vibe` 的一次性费用。 */
        const val VIBE_ENCODE_ANLAS: Long = 2L

        /** 无法定价的哨兵（与 [UncalibratedPaidAnlasFormula.UNSUPPORTED] 同值，含义一致）。 */
        const val UNPRICED: Long = -1L
    }
}
