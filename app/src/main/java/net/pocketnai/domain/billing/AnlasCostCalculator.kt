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

        // Vibe Transfer 自身的价格还没有任何依据（账号所有者只确认了 Precise Reference 的附加费），
        // 因此它一律"待确认"，不因为基础费用免费就说成免费。
        if (context.generationKind == GenerationKind.VIBE_TRANSFER) {
            return GenerationCostEstimate.Unknown(
                paidFormula.version,
                UnknownCostReason.PRICING_NOT_CALIBRATED,
            )
        }

        val surcharge = referenceSurchargeOf(context)
        if (surcharge > 0) {
            // 附加费是**已知的确定值**，但它建立在"基础生成费用"之上：
            // 基础费用未知时不能给出总数（只报那 5 点会让用户以为总共只要 5）。
            val baseTotal = baseBatchTotalOrNull(context, freeReason)
                ?: return GenerationCostEstimate.Unknown(
                    paidFormula.version,
                    UnknownCostReason.PRICING_NOT_CALIBRATED,
                )
            return GenerationCostEstimate.EstimatedAnlas(
                policyVersion = paidFormula.version,
                batchTotal = saturatingAdd(baseTotal, surcharge),
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

    /**
     * 参考图附加费：**只有 Precise Reference 收费**，每张固定值。
     *
     * 账号所有者确认的规则（2026-09-14 更正）：
     * - Image2Img **不收费**（V4.5 与 V5 都免费，实测也印证了这一点）；
     * - Precise Reference 每张 +5 Anlas；
     * - Vibe Transfer 的价格未确认（调用方在更早的分支把它判成"待确认"）。
     *
     * 与"按参考图张数收费"的写法相比，这里刻意按**生成类型**区分：
     * 加的是"这个功能"的钱，不是"多一张图"的钱 —— 图生图也带图，但它不额外收费。
     */
    private fun referenceSurchargeOf(context: AnlasPricingContext): Long {
        if (context.generationKind != GenerationKind.PRECISE_REFERENCE) return 0L
        val count = context.referenceImageCount
        if (count <= 0) return 0L
        return saturatingMultiply(PRECISE_REFERENCE_SURCHARGE_ANLAS, count.toLong())
    }

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
     * 免费判定。返回 null 表示"不能判定为免费"，**不等于要收费**。
     *
     * ## 先决条件：账号必须有订阅（Opus）
     * 免费额度是**订阅权益**，不是"买了 Anlas"就有的。只买积分、没有生效订阅的账号
     * 会正常扣费，因此这里必须确认账号确实带订阅，否则一律不宣称免费。
     *
     * ## 怎么判断"带订阅"
     * 服务端的 `tier` / `active` 两个字段**不足以判断**：本机实测账号读数是
     * `tier 0 / active false`，但它既有 V5 使用额度、生成也确实不扣费
     * （图生图 407 → 407；Precise Reference 只扣了附加的 5）。
     * 因此把"具备 V5 使用额度"当作订阅的旁证 —— 官方文档说明该额度是 V5 Opus 的功能，
     * 没有订阅的账号不会有它。
     *
     * ## 形状条件来自官方说明
     * 单张、Normal 范围、Steps ≤ 28、V4.5 家族。
     * **刻意不要求"无基础图片"**：官方说明里有这一条，但本机实测与它相矛盾
     * （带起点图的图生图没有扣费），而观测证据比文档复述更硬。
     * Precise Reference 另有每张 5 Anlas 的附加费，由 [referenceSurchargeOf] 单独加上。
     */
    private fun verifiedFreeReasonOrNull(context: AnlasPricingContext): FreeReason? {
        if (!hasSubscriptionBenefit(context)) return null
        if (context.params.sampleCount != 1) return null
        if (context.resolutionTier != ResolutionTier.NORMAL) return null
        if (context.params.steps > FREE_MAX_STEPS) return null
        if (context.params.model.family != GenerationFamily.V4_5) return null
        if (!isFreeEligibleKind(context.generationKind)) return null
        return FreeReason.OPUS_V45_ELIGIBLE
    }

    /**
     * 账号是否带订阅权益。
     *
     * 两条任一成立即可：等级字段直接读成 Opus，或账户带回 V5 使用额度
     * （官方文档说明那是 V5 Opus 的功能，无订阅账户不会有）。
     */
    private fun hasSubscriptionBenefit(context: AnlasPricingContext): Boolean =
        context.subscriptionTier is SubscriptionTier.Opus || context.v5UsageLimit != null

    /** 免费判定覆盖哪些生成类型。Vibe 的价格未确认，因此不在此列（一律"待确认"）。 */
    private fun isFreeEligibleKind(kind: GenerationKind): Boolean = when (kind) {
        GenerationKind.TEXT_TO_IMAGE -> true
        // 起点图/蒙版都只有一张，多于一张说明请求本身不合法，不在此判免费。
        GenerationKind.IMAGE_TO_IMAGE -> true
        GenerationKind.INPAINT -> true
        GenerationKind.PRECISE_REFERENCE -> true
        GenerationKind.VIBE_TRANSFER -> false
        GenerationKind.OTHER -> false
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
         * 仓库真实生成的**安全护栏**：开发验证时固定用这个 Guidance。
         *
         * ⚠️ 它**不是**免费判定的条件（官方说明里没有 Guidance 这一项），
         * 只用于测试与探针脚本，避免实验时偏离已知免费的参数组合。
         */
        const val VERIFIED_FREE_GUIDANCE: Double = 7.0

        /**
         * Precise Reference 每张参考图的固定附加费。
         *
         * 来源：账号所有者确认 —— **只有 Precise Reference 收费，每张 5 Anlas**。
         * Image2Img 不收费（V4.5 与 V5 都是），Vibe Transfer 的价格未确认。
         *
         * ⚠️ 这条规则尚未与官方网页的费用标签对照过（余额规划 §10 的校准纪律）。
         * 它会被显示在生成按钮上，因此必须用余额观测反向验证一次：
         * 生成前后各读一次余额，差值应当是 5 的整数倍。改这一个常量即可，
         * 判定流程不依赖它的数值。
         */
        const val PRECISE_REFERENCE_SURCHARGE_ANLAS: Long = 5L

        /** 与模型无关：附加费按"功能"而不是按"模型"计。 */
        const val REFERENCE_IMAGE_SURCHARGE_ANLAS: Long = PRECISE_REFERENCE_SURCHARGE_ANLAS
    }
}
