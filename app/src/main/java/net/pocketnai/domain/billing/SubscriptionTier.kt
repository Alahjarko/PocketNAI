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

/** 按官方客户端的判据：`tier >= 3` 即 Opus。免费单张权益的门槛就是这一条。 */
val SubscriptionTier.isOpus: Boolean get() = this is SubscriptionTier.Opus

/**
 * 整数的唯一映射点。
 *
 * ## 取值来源
 * `0 = 无订阅 / 1 = Tablet / 2 = Scroll / 3 = Opus` —— 2026-09-14 从
 * **官方网页前端 bundle 反解出的枚举**（`subscription.tier` 与定价表一一对应），
 * 不再只是社区解读。
 *
 * ## 为什么不再看 `active`
 * 官方前端的"是否算订阅"判据（`accountType ∈ {B2B,SERVICE,SUPPORT,ADMIN}` 或
 * `expiresAt > now && tier > 0`）**完全没有用 `active`**，`tier` 的映射也与它无关。
 * 早期版本把 `active == false` 一律读成"无订阅"，那会让"已取消但仍在有效期内"的
 * 账号被误判成未订阅（官方文档明确说取消后权益保留到付费周期结束）。
 * 因此这里只映射 `tier`，是否具备订阅权益交给 [SubscriptionStatusResolver] 判断。
 */
interface SubscriptionTierResolver {
    fun resolve(rawTier: Int?): SubscriptionTier
}

object DefaultSubscriptionTierResolver : SubscriptionTierResolver {

    override fun resolve(rawTier: Int?): SubscriptionTier = when (rawTier) {
        null -> SubscriptionTier.Unknown(RAW_MISSING)
        0 -> SubscriptionTier.None
        1 -> SubscriptionTier.Tablet
        2 -> SubscriptionTier.Scroll
        3 -> SubscriptionTier.Opus
        else -> SubscriptionTier.Unknown(rawTier)
    }

    /** tier 字段缺失时用的哨兵值，只为诊断展示，不参与任何业务判断。 */
    const val RAW_MISSING: Int = -1
}
