package net.pocketnai.domain.billing

/**
 * 付费价格公式的策略位（规划 §5.5）。
 *
 * 把公式抽成接口而不是写死在 [AnlasCostCalculator] 里，是因为它**必然会被修正**：
 * 官方调整定价、或我们校准出更准的公式时，只换实现，不动判定流程。
 *
 * **没校准就把 [supports] 返回 false**，让上层给出"费用待确认"。
 * 猜一个数字比不给数字更糟：用户会按它做决定。
 */
interface PaidAnlasFormula {

    val version: String

    fun supports(context: AnlasPricingContext): Boolean

    /**
     * 返回**一次请求的总 Anlas**，不是单张价格。
     *
     * [billableImageCount] 由调用方传入而不是从 `params.sampleCount` 自己算：
     * "哪几张免费"是计费策略（[AnlasCostCalculator] 的免费规则），不是价格公式的职责。
     *
     * 只有经过校准的实现才允许返回非负整数；无法定价时返回负数。
     */
    fun calculateBatchTotal(context: AnlasPricingContext, billableImageCount: Int): Long

    /**
     * 与张数无关的附加费（参考图、Vibe 等）。
     *
     * 单独一个方法是因为它**不随免费单张而变化**：官方规则里附加费按请求的张数算，
     * 哪怕这张图的基础费用被 Opus 权益免掉了，5 Anlas 也照收（实测 407 → 402 即此）。
     */
    fun calculateSurcharge(context: AnlasPricingContext): Long
}

/**
 * 尚未校准的占位实现。
 *
 * 它存在的意义是让"还没有公式"这件事成为代码里的一个明确状态，
 * 而不是让调用方在缺少实现时抛异常或返回 0。
 */
object UncalibratedPaidAnlasFormula : PaidAnlasFormula {

    override val version: String = "uncalibrated"

    override fun supports(context: AnlasPricingContext): Boolean = false

    override fun calculateBatchTotal(
        context: AnlasPricingContext,
        billableImageCount: Int,
    ): Long = UNSUPPORTED

    override fun calculateSurcharge(context: AnlasPricingContext): Long = UNSUPPORTED

    /** 无法定价的哨兵，调用方必须先用 [supports] 过滤。 */
    const val UNSUPPORTED: Long = -1L
}
