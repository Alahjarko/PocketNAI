package net.pocketnai.domain.billing

/**
 * 订阅等级（规划 §5.2）。
 *
 * 官方 OpenAPI 只公开了整数 `tier`，没有在 schema 里给出枚举含义，
 * 因此整数映射必须集中在一处（[SubscriptionTierResolver]），
 * 不允许在别处散落 `tier == 3` 这类判断。
 */
sealed interface SubscriptionTier {
    data object None : SubscriptionTier
    data object Tablet : SubscriptionTier
    data object Scroll : SubscriptionTier
    data object Opus : SubscriptionTier

    /** 服务端给了我们不认识的取值。**不要**当成 None 或 Opus。 */
    data class Unknown(val rawValue: Int) : SubscriptionTier
}

interface SubscriptionTierResolver {
    fun resolve(rawTier: Int?, active: Boolean?): SubscriptionTier
}

/**
 * 默认的整数映射。
 *
 * ## 取值来源与核对状态
 * `0 = 无订阅 / 1 = Tablet / 2 = Scroll / 3 = Opus` 是社区与官方示例一致的解释
 * （OpenAPI 的响应示例用的是 `tier: 3` 且 `active: true`）。
 *
 * ⚠️ **仍未与用户自己的网页订阅名称做人工对照**（规划 §13 阶段 0 第 4 项）。
 * 因此这里对**未知整数**返回 [SubscriptionTier.Unknown]，绝不猜：
 * 猜错会让计价走进错误的免费/付费分支，而余额本身仍能正确显示。
 *
 * 另一个已有的教训：`tier == 0 && active == false` **不能**用来判断"不能生成图片"。
 * 按量购买 Anlas 但不订阅的账户就是这个表现，却能正常生成。
 */
object DefaultSubscriptionTierResolver : SubscriptionTierResolver {

    override fun resolve(rawTier: Int?, active: Boolean?): SubscriptionTier {
        // active 明确为 false 时，任何等级都按"当前没有生效订阅"处理。
        if (active == false) return SubscriptionTier.None
        return when (rawTier) {
            null -> SubscriptionTier.Unknown(RAW_MISSING)
            0 -> SubscriptionTier.None
            1 -> SubscriptionTier.Tablet
            2 -> SubscriptionTier.Scroll
            3 -> SubscriptionTier.Opus
            else -> SubscriptionTier.Unknown(rawTier)
        }
    }

    /** tier 字段缺失时用的哨兵值，只为诊断展示，不参与任何业务判断。 */
    const val RAW_MISSING: Int = -1
}
