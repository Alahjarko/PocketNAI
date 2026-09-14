package net.pocketnai.domain.billing

/**
 * 一次生成的费用预估结果（规划 §5.4）。
 *
 * 刻意做成四态而不是一个整数：**"算不出来"必须是一等公民**。
 * 定价未经校准、参数超出官方报价范围时，界面显示"费用待确认"，
 * 而不是给一个看起来精确、实际上可能错的数字。
 */
sealed interface GenerationCostEstimate {

    val policyVersion: String

    /** 可以明确判断 Anlas 为 0（订阅权益覆盖了本次全部张数）。 */
    data class Free(
        override val policyVersion: String,
        val reason: FreeReason,
    ) : GenerationCostEstimate

    /** 预计不花 Anlas，但会消耗 V5 的独立使用额度。 */
    data class UsesV5Allowance(
        override val policyVersion: String,
        /** 没有官方公式时为空；界面只用它显示"将消耗 V5 免费额度"。 */
        val estimatedPercentCost: Double?,
    ) : GenerationCostEstimate

    /** 按已校准规则算出的预计 Anlas（[batchTotal] 是**整批**费用，不是单张）。 */
    data class EstimatedAnlas(
        override val policyVersion: String,
        val batchTotal: Long,
        val imageCount: Int,
        /**
         * 这批里被订阅权益免掉了几张。
         *
         * 需要展示：Opus 一次生成 4 张时只免 1 张，界面若不说明，
         * 用户会以为"4 张也该全免费"而怀疑报价错了。
         */
        val freeImageCount: Int = 0,
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
    /**
     * 官方免费单张规则：账号带订阅（Opus）+ 面积 ≤ 1024² + Steps ≤ 28。
     *
     * 免费额度是**订阅权益**，只买 Anlas、没有生效订阅的账号会正常扣费。
     * 规则细节见 [AnlasCostCalculator.freeImageCount]。
     */
    OPUS_FREE_IMAGE,
}

enum class UnknownCostReason {
    /** 公式没有覆盖这组参数（超出官方报价范围），或定价尚未校准。 */
    PRICING_NOT_CALIBRATED,
    INVALID_PARAMETERS,
}
