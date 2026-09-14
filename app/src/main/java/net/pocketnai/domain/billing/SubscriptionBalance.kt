package net.pocketnai.domain.billing

/**
 * 账户余额（规划 §5.1）。
 *
 * 来源是只读接口 `GET /user/subscription`。字段名沿用服务端的历史命名，
 * 但领域模型一律用 [subscriptionAnlas] / [purchasedAnlas] ——
 * 界面上不要出现"Training Steps"这种历史遗留说法。
 *
 * ⚠️ 余额是**服务端事实**，可以准确展示；它不是账单，也不参与"能不能生成"的判断
 * （额度不足的权威结论始终是服务端返回 402）。
 */
data class SubscriptionBalance(
    /** 服务端原始的 tier 整数。语义由 [SubscriptionTierResolver] 解释，不要在这里判断。 */
    val rawTier: Int?,
    /**
     * 服务端原样返回的 `active`。
     *
     * ⚠️ **它不参与任何计费判断**：官方前端的订阅判据用的是
     * `accountType` 与 `expiresAt`（见 [SubscriptionStatusResolver]）。保留这个字段只为诊断展示。
     */
    val active: Boolean?,
    /** 账户类型。官方把它当作"内部账号＝有订阅"的判据之一。 */
    val accountType: Int?,
    val expiresAtEpochSeconds: Long?,
    val isGracePeriod: Boolean?,
    /** 订阅 Anlas。消费时先扣这一池。 */
    val subscriptionAnlas: Long,
    /** 购买 Anlas。订阅池用尽后才扣。 */
    val purchasedAnlas: Long,
    /** V5 Opus 的独立使用额度。账户没有这项时为空（合法情况，不是错误）。 */
    val v5UsageLimit: V5UsageLimit?,
    val fetchedAtMillis: Long,
) {
    /** 总余额。用饱和加法，避免异常大的值把结果绕成负数。 */
    val totalAnlas: Long
        get() = if (Long.MAX_VALUE - subscriptionAnlas < purchasedAnlas) {
            Long.MAX_VALUE
        } else {
            subscriptionAnlas + purchasedAnlas
        }
}

/**
 * V5 的独立使用额度（规划 §2.3）。
 *
 * **它不是 Anlas，绝不能与余额相加或换算。** 界面称它为"V5 免费额度"。
 */
data class V5UsageLimit(
    /** 保留服务端原值：OpenAPI 允许超过 100。 */
    val rawPercent: Int,
    /** 为真时当前不可用（官方定义）。 */
    val isNegative: Boolean,
    val timeUntilNextPercentSeconds: Long?,
) {
    val available: Boolean get() = !isNegative && rawPercent > 0

    /** 只供进度条渲染使用，不改变 [rawPercent]。 */
    val progressFraction: Float
        get() = rawPercent.coerceIn(0, 100) / 100f
}
