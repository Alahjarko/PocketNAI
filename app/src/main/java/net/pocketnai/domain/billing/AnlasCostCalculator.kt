package net.pocketnai.domain.billing

import net.pocketnai.domain.model.GenerationFamily
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ResolutionTier

/**
 * 把当前参数换算成费用状态（规划 §5.6）。
 *
 * 判定顺序是固定的，改顺序会改变语义：
 *
 * 1. 参数校验 —— 非法参数直接 [GenerationCostEstimate.Unknown]；
 * 2. 已验证的免费规则 —— 只有官方规则或实测确认的组合才进来；
 * 3. V5 额度分支 —— 符合条件且额度可用时**不花 Anlas**，但要提醒会消耗额度；
 * 4. 付费公式 —— 只有校准过的公式才会给出数字，否则"费用待确认"。
 *
 * ## 这里不做什么
 * - 不判断"余额够不够"：余额是服务端事实，能不能生成由服务端 402 决定；
 * - 不因为算不出费用而阻止生成；
 * - 不把 V5 额度换算成 Anlas。
 */
class AnlasCostCalculator(
    private val paidFormula: PaidAnlasFormula = UncalibratedPaidAnlasFormula,
) {

    /** 当前定价策略的版本，供界面与诊断展示。 */
    val policyVersion: String get() = paidFormula.version

    fun estimate(context: AnlasPricingContext): GenerationCostEstimate {
        validateParameters(context)?.let { reason ->
            return GenerationCostEstimate.Unknown(paidFormula.version, reason)
        }

        // 免费判定放在"订阅等级是否已知"之前：实测确认的那条规则只依赖参数，
        // 不依赖订阅等级。这样余额还没读回来时按钮上也不会从"费用待确认"跳成"免费"。
        val freeReason = verifiedFreeReasonOrNull(context)
        val referenceSurcharge = referenceSurchargeOf(context.referenceImageCount)

        if (referenceSurcharge > 0) {
            // 参考图附加费是**已知的确定值**，但它建立在"基础生成费用"之上：
            // 基础费用未知时不能给出总数（只报那 5 点会让用户以为总共只要 5）。
            val baseTotal = baseBatchTotalOrNull(context, freeReason)
                ?: return GenerationCostEstimate.Unknown(
                    paidFormula.version,
                    UnknownCostReason.PRICING_NOT_CALIBRATED,
                )
            return GenerationCostEstimate.EstimatedAnlas(
                policyVersion = paidFormula.version,
                batchTotal = saturatingAdd(baseTotal, referenceSurcharge),
                imageCount = context.params.sampleCount,
            )
        }

        freeReason?.let { reason ->
            return GenerationCostEstimate.Free(paidFormula.version, reason)
        }

        if (context.subscriptionTier is SubscriptionTier.Unknown) {
            return GenerationCostEstimate.Unknown(
                paidFormula.version,
                UnknownCostReason.UNKNOWN_SUBSCRIPTION_TIER,
            )
        }

        if (isEligibleV5UsageGeneration(context)) {
            val usage = context.v5UsageLimit
                ?: return GenerationCostEstimate.Unknown(
                    paidFormula.version,
                    UnknownCostReason.V5_ALLOWANCE_STATE_UNKNOWN,
                )
            if (usage.available) {
                return GenerationCostEstimate.UsesV5Allowance(
                    policyVersion = paidFormula.version,
                    // 百分比消耗公式同样未经校准，因此不给数字。
                    estimatedPercentCost = null,
                )
            }
            // 额度不可用（用完或 isNegative）时继续走 Anlas 计价 —— 与官方说明一致。
        }

        if (!paidFormula.supports(context)) {
            return GenerationCostEstimate.Unknown(
                paidFormula.version,
                UnknownCostReason.PRICING_NOT_CALIBRATED,
            )
        }

        val total = paidFormula.calculateBatchTotal(context)
        if (total < 0) {
            return GenerationCostEstimate.Unknown(
                paidFormula.version,
                UnknownCostReason.INVALID_PARAMETERS,
            )
        }
        return GenerationCostEstimate.EstimatedAnlas(
            policyVersion = paidFormula.version,
            batchTotal = total,
            imageCount = context.params.sampleCount,
        )
    }

    /**
     * 基础生成费用（不含参考图附加费）。算不出来时返回 null。
     *
     * 免费判定、V5 额度（视为 0 Anlas 的基础费用）、已校准的付费公式三条路径都汇总到这里，
     * 附加费只在这个结果上加一次，避免"带参考图的免费组合"被各处各判一遍。
     */
    private fun baseBatchTotalOrNull(
        context: AnlasPricingContext,
        freeReason: FreeReason?,
    ): Long? {
        if (freeReason != null) return 0L
        if (context.subscriptionTier is SubscriptionTier.Unknown) return null
        if (isEligibleV5UsageGeneration(context) &&
            context.v5UsageLimit?.available == true
        ) {
            // 基础生成由 V5 额度覆盖，不产生 Anlas。
            return 0L
        }
        if (!paidFormula.supports(context)) return null
        val total = paidFormula.calculateBatchTotal(context)
        return total.takeIf { it >= 0 }
    }

    /** 参考图附加费：每张固定值，与模型无关。 */
    private fun referenceSurchargeOf(count: Int): Long =
        if (count <= 0) 0L else saturatingMultiply(REFERENCE_IMAGE_SURCHARGE_ANLAS, count.toLong())

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun saturatingMultiply(left: Long, right: Long): Long =
        if (left != 0L && right > Long.MAX_VALUE / left) Long.MAX_VALUE else left * right

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

    /**
     * 已验证的免费规则。返回 null 表示"不能判定为免费"，**不等于要收费**。
     *
     * 两条规则的差别不只是账户条件，**形状条件也不同**：
     * - 官方规则（Opus）明确要求"无基础图片"，因此只覆盖纯 T2I；
     * - 实测规则来自本仓库开发账号的观测，它**包含带起点图的情形** ——
     *   起点图那部分不免费，但会以固定的参考图附加费单独计价（见
     *   [REFERENCE_IMAGE_SURCHARGE_ANLAS]），所以基础费用仍按 0 计。
     */
    private fun verifiedFreeReasonOrNull(context: AnlasPricingContext): FreeReason? {
        if (context.params.sampleCount != 1) return null
        if (context.resolutionTier != ResolutionTier.NORMAL) return null
        if (context.params.steps > FREE_MAX_STEPS) return null
        if (context.params.model.family != GenerationFamily.V4_5) return null

        // 官方规则：Opus + 纯文生图 + 无基础图。
        if (context.subscriptionTier is SubscriptionTier.Opus &&
            context.generationKind == GenerationKind.TEXT_TO_IMAGE &&
            !context.hasBaseImage
        ) {
            return FreeReason.OPUS_V45_ELIGIBLE
        }

        // 实测规则：只覆盖被观测过的那个组合 —— Curated 模型、Guidance 恰好 7.0、
        // 至多一张起点图（Vibe / Precise Reference 未被观测过，不在此列）。
        val isVerifiedShape = context.params.model == ImageModel.V4_5_CURATED &&
            context.params.guidance == VERIFIED_FREE_GUIDANCE &&
            context.referenceImageCount <= 1 &&
            when (context.generationKind) {
                GenerationKind.TEXT_TO_IMAGE -> true
                GenerationKind.IMAGE_TO_IMAGE -> true
                else -> false
            }
        if (isVerifiedShape) return FreeReason.OTHER_VERIFIED_RULE

        return null
    }

    /** V5 走独立的额度池，必须先判断额度状态，不能套用 V4.5 的免费规则。 */
    private fun isEligibleV5UsageGeneration(context: AnlasPricingContext): Boolean {
        if (context.generationKind != GenerationKind.TEXT_TO_IMAGE || context.hasBaseImage) {
            return false
        }
        if (context.params.sampleCount != 1) return false
        if (context.resolutionTier != ResolutionTier.NORMAL) return false
        if (context.params.steps > FREE_MAX_STEPS) return false
        if (context.params.model.family != GenerationFamily.V5) return false
        return context.subscriptionTier is SubscriptionTier.Opus
    }

    companion object {
        /** 官方免费条件的 Steps 上界。 */
        const val FREE_MAX_STEPS: Int = 28

        /**
         * 开发账号实测免费组合里的 Guidance。
         *
         * 官方说明没有把 Guidance 列为免费条件，但仓库的真实生成安全护栏里它是 7.0，
         * 而"免费"这件事只在这个值上被观测过，所以判定时要求它相等。
         */
        const val VERIFIED_FREE_GUIDANCE: Double = 7.0

        /**
         * 每张参考图的固定附加费。
         *
         * 来源：账号所有者确认的规则 —— **每加一张参考图多加 5 Anlas，与模型无关**，
         * 因此它是一条与基础生成费用正交的加价，加在基础费用之上。
         *
         * ⚠️ 这条规则尚未与官方网页的费用标签对照过（余额规划 §10 的校准纪律）。
         * 它已经被实现并会显示在生成按钮上，因此**必须用余额观测反向验证一次**：
         * 生成前后各读一次余额，差值应当是 5 的整数倍（单张参考图就是 5）。
         * 若观测结果不是这样，改这一个常量即可 —— 判定流程不依赖它的具体数值。
         */
        const val REFERENCE_IMAGE_SURCHARGE_ANLAS: Long = 5L
    }
}
