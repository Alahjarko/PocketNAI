package net.pocketnai.domain.billing

/**
 * 一次生成的费用预估结果（规划 §5.4）。
 *
 * 刻意做成四态而不是一个整数：**"算不出来"必须是一等公民**。
 * 定价未经校准、订阅等级不认识、参数不支持时，界面显示"费用待确认"，
 * 而不是给一个看起来精确、实际上可能错的数字。
 */
sealed interface GenerationCostEstimate {

    val policyVersion: String

    /** 可以明确判断 Anlas 为 0。 */
    data class Free(
        override val policyVersion: String,
        val reason: FreeReason,
    ) : GenerationCostEstimate

    /** 预计不花 Anlas，但会消耗 V5 的独立使用额度。 */
    data class UsesV5Allowance(
        override val policyVersion: String,
        /** 没有可靠公式时为空；界面只用它显示"将消耗 V5 免费额度"。 */
        val estimatedPercentCost: Double?,
    ) : GenerationCostEstimate

    /** 按已校准规则算出的预计 Anlas（[batchTotal] 是**整批**费用，不是单张）。 */
    data class EstimatedAnlas(
        override val policyVersion: String,
        val batchTotal: Long,
        val imageCount: Int,
    ) : GenerationCostEstimate {
        /** 平均每张。批量时允许是小数，不伪造逐张整数。 */
        val averagePerImage: Double
            get() = if (imageCount > 0) batchTotal.toDouble() / imageCount else 0.0
    }

    /** 不能可靠报价。 */
    data class Unknown(
        override val policyVersion: String,
        val reason: UnknownCostReason,
    ) : GenerationCostEstimate
}

enum class FreeReason {
    /** 官方规则：Opus 且满足单张 / Normal / Steps ≤ 28 / 无基础图。 */
    OPUS_V45_ELIGIBLE,

    /**
     * 本仓库开发账号实测确认的免费组合（见 [AnlasCostCalculator] 的常量说明）。
     *
     * 与上一条分开，是因为它**不是**官方公开规则，而是针对特定账户观测到的事实；
     * 一旦余额观测显示该组合在扣费，就应该删掉这条规则。
     */
    OTHER_VERIFIED_RULE,
}

enum class UnknownCostReason {
    /** 定价公式尚未用官方网页的费用标签校准。 */
    PRICING_NOT_CALIBRATED,
    UNKNOWN_SUBSCRIPTION_TIER,
    UNSUPPORTED_MODEL,
    UNSUPPORTED_GENERATION_KIND,
    INVALID_PARAMETERS,
    /** 该走 V5 额度分支，但账户没有可读的 usage 状态。 */
    V5_ALLOWANCE_STATE_UNKNOWN,
    V5_ALLOWANCE_TOO_LOW_TO_CONFIRM,
}
