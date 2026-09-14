package net.pocketnai.domain.billing

/**
 * 付费价格公式的策略位（规划 §5.5）。
 *
 * 把公式抽成接口而不是写死在 [AnlasCostCalculator] 里，是因为它**必然会被修正**：
 * 官方调整定价、或我们校准出更准的公式时，只换实现，不动判定流程。
 *
 * ## 进入实现的门槛（规划 §10.4）
 * 只有同时满足下列条件，[supports] 才允许对某组参数返回 `true`：
 * 公式能解释全部校准样本、交叉验证样本与网页标签完全一致、取整方向已确认、
 * 批量总价已确认、模型差异已确认、免费边界已确认，并写有版本与核对日期。
 *
 * **没通过就把 [supports] 返回 false**，让上层给出"费用待确认"。
 * 猜一个数字比不给数字更糟：用户会按它做决定。
 */
interface PaidAnlasFormula {

    val version: String

    fun supports(context: AnlasPricingContext): Boolean

    /**
     * 返回**一次请求的总 Anlas**，不是单张价格。
     *
     * 只有经过校准的实现才允许返回整数。
     */
    fun calculateBatchTotal(context: AnlasPricingContext): Long
}

/**
 * 尚未校准的占位实现（规划 §13 阶段 3 之前的默认状态）。
 *
 * 它存在的意义是让"还没有公式"这件事成为代码里的一个明确状态，
 * 而不是让调用方在缺少实现时抛异常或返回 0。
 */
object UncalibratedPaidAnlasFormula : PaidAnlasFormula {

    override val version: String = "uncalibrated"

    override fun supports(context: AnlasPricingContext): Boolean = false

    override fun calculateBatchTotal(context: AnlasPricingContext): Long =
        throw UnsupportedOperationException(
            "定价公式尚未校准：supports() 返回 false 时不应调用 calculateBatchTotal()",
        )
}
