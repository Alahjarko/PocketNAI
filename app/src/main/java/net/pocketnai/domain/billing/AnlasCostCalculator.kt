package net.pocketnai.domain.billing

import net.pocketnai.domain.model.GenerationFamily

/**
 * 把当前参数换算成费用状态（规划 §5.6，2026-09-14 按官方免费规则改版）。
 *
 * 判定顺序是固定的，改顺序会改变语义：
 *
 * 1. 参数校验 —— 非法参数直接 [GenerationCostEstimate.Unknown]；
 * 2. 免费单张 —— 官方规则算出的免费张数先扣掉；
 * 3. 剩余张数按已校准公式计价，再叠加与张数无关的附加费；
 * 4. 全额被免费覆盖时才是 [GenerationCostEstimate.Free] /
 *    [GenerationCostEstimate.UsesV5Allowance]。
 *
 * ## 这里不做什么
 * - 不判断"余额够不够"：余额是服务端事实，能不能生成由服务端 402 决定；
 * - 不因为算不出费用而阻止生成；
 * - 不把 V5 额度换算成 Anlas。
 */
class AnlasCostCalculator(
    private val paidFormula: PaidAnlasFormula = NovelAiPaidAnlasFormula(),
) {

    /** 当前定价策略的版本，供界面与诊断展示。 */
    val policyVersion: String get() = paidFormula.version

    fun estimate(context: AnlasPricingContext): GenerationCostEstimate {
        validateParameters(context)?.let { reason ->
            return GenerationCostEstimate.Unknown(paidFormula.version, reason)
        }

        val sampleCount = context.params.sampleCount
        val freeImages = freeImageCount(context)
        val billableImages = sampleCount - freeImages

        // 附加费与免费单张无关：Precise Reference 的 5 Anlas 照收（实测 407 → 402）。
        val surcharge = if (paidFormula.supports(context)) {
            paidFormula.calculateSurcharge(context)
        } else {
            UncalibratedPaidAnlasFormula.UNSUPPORTED
        }
        if (surcharge < 0) {
            return GenerationCostEstimate.Unknown(
                paidFormula.version,
                UnknownCostReason.PRICING_NOT_CALIBRATED,
            )
        }

        val baseTotal = if (billableImages <= 0) {
            0L
        } else {
            if (!paidFormula.supports(context)) {
                return GenerationCostEstimate.Unknown(
                    paidFormula.version,
                    UnknownCostReason.PRICING_NOT_CALIBRATED,
                )
            }
            val total = paidFormula.calculateBatchTotal(context, billableImages)
            if (total < 0) {
                // 参数合法但超出官方报价范围（面积过大 / 单张超过上限）。
                return GenerationCostEstimate.Unknown(
                    paidFormula.version,
                    UnknownCostReason.PRICING_NOT_CALIBRATED,
                )
            }
            total
        }

        if (billableImages <= 0 && surcharge == 0L) {
            return if (isV5AllowanceGeneration(context)) {
                GenerationCostEstimate.UsesV5Allowance(
                    policyVersion = paidFormula.version,
                    // 百分比消耗公式同样没有官方数值来源，因此不给数字。
                    estimatedPercentCost = null,
                )
            } else {
                GenerationCostEstimate.Free(
                    policyVersion = paidFormula.version,
                    reason = FreeReason.OPUS_FREE_IMAGE,
                )
            }
        }

        return GenerationCostEstimate.EstimatedAnlas(
            policyVersion = paidFormula.version,
            batchTotal = saturatingAdd(baseTotal, surcharge),
            imageCount = sampleCount,
            freeImageCount = freeImages,
        )
    }

    /**
     * 本次请求里有多少张是"不花 Anlas"的。
     *
     * ## 官方规则（2026-09-14 从官方前端 bundle 反解，2026-09-18 复核）
     * ```js
     * !characterRef && area <= 1024*1024 && steps <= 28
     *   && tier >= 3 && hasSubscription
     *   && !(isV5Model && usage.isNegative)
     * ```
     * 三条**容易被文档误导**的地方，都以代码为准：
     * - **没有"无基础图片"这一条**：带起点图的图生图同样免费（本机实测 407 → 407 印证）；
     * - **没有"至少 Normal 尺寸"这一条**，只有面积上界 1024²，因此比 Normal 更小的图也免费；
     * - **不是"整单免费"**：一次生成多张时只免掉 1 张，其余照价（官方前端就是 `n_samples -= 1`）。
     *
     * `characterRef` 看着像"Precise Reference 就不免费"，但它是个**死字段**：整个官方前端
     * bundle 里只有这一处**读**它，没有任何一处赋值，因此恒为 undefined、从不生效。
     * 与本机实测一致 —— Precise Reference 那次只扣了 5 Anlas 附加费（407 → 402），
     * 说明底图那张仍然免费。多角色提示词（`char_captions`）同理不进入判定，
     * 官方计价组装里也没有它的附加费项（技术决策记录 §30.2）。
     */
    private fun freeImageCount(context: AnlasPricingContext): Int {
        if (!context.hasSubscription) return 0
        if (!context.subscriptionTier.isOpus) return 0
        if (context.params.sampleCount < 1) return 0
        val area = context.params.size.width.toLong() * context.params.size.height.toLong()
        if (area > FREE_MAX_AREA) return 0
        if (context.params.steps > FREE_MAX_STEPS) return 0
        // V5 额度用尽（isNegative）后连"免费那张"也要按 Anlas 计。
        if (isV5AllowanceGeneration(context) && context.v5UsageLimit?.isNegative == true) return 0
        return 1
    }

    /** 这张免费图是走 V4.5 的免费权益，还是走 V5 的独立额度。 */
    private fun isV5AllowanceGeneration(context: AnlasPricingContext): Boolean =
        context.params.model.family == GenerationFamily.V5

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun validateParameters(context: AnlasPricingContext): UnknownCostReason? {
        val params = context.params
        if (params.sampleCount <= 0 ||
            params.steps <= 0 ||
            params.size.width <= 0 ||
            params.size.height <= 0
        ) {
            return UnknownCostReason.INVALID_PARAMETERS
        }
        return null
    }

    companion object {
        /** 官方免费单张的步数上界。 */
        const val FREE_MAX_STEPS: Int = 28

        /** 官方免费单张的面积上界：1024 × 1024。 */
        const val FREE_MAX_AREA: Long = 1024L * 1024L

        /**
         * 仓库真实生成的**安全护栏**：开发验证时固定用这个 Guidance。
         *
         * ⚠️ 它**不是**免费判定的条件（官方规则里没有 Guidance 这一项），
         * 只用于测试与探针脚本，避免实验时偏离已知免费的参数组合。
         */
        const val VERIFIED_FREE_GUIDANCE: Double = 7.0
    }
}
