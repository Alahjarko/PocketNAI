package net.pocketnai.domain.billing

/**
 * 账户类型（官方 `/user/subscription` 的 `accountType` 整数）。
 *
 * 取值来自官方网页前端 bundle 里的枚举定义（2026-09-14 反解）：
 * `RETAIL=0, B2B=1, SERVICE=2, SUPPORT=3, ADMIN=4`。
 *
 * 它之所以进入计费，是因为官方判断"这个账号是否算订阅用户"时，
 * 除了 `tier` 还看它 —— 内部账号（B2B/SERVICE/SUPPORT/ADMIN）一律视为有订阅权益。
 */
enum class AccountType(val rawValue: Int) {
    RETAIL(0),
    B2B(1),
    SERVICE(2),
    SUPPORT(3),
    ADMIN(4),
    ;

    /** 内部账号：官方前端把它们直接当成"有订阅"，不看 tier 与有效期。 */
    val grantsSubscription: Boolean
        get() = this != RETAIL

    companion object {
        fun from(raw: Int?): AccountType? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * 用户在本机手动指定的订阅等级（设置页）。
 *
 * 存在的理由很实际：服务端字段并不总是与用户实际买到的权益一致
 * （本机账号实测 `tier 0 / active false / accountType RETAIL`，但账号所有者
 * 在官网上确实能免费生成 V4.5）。**自动读取永远是默认**，手动值是兜底 ——
 * 猜错的后果是报价偏差，而报价偏差会直接改变用户点不点生成按钮。
 */
enum class SubscriptionOverride(val displayName: String) {
    /** 以服务端读数为准（默认）。 */
    AUTO("自动读取"),

    NONE("无订阅"),
    TABLET("Tablet"),
    SCROLL("Scroll"),
    OPUS("Opus"),
    ;

    companion object {
        fun fromNameOrDefault(name: String?): SubscriptionOverride =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** 订阅等级从哪里来，界面要如实说明。 */
enum class SubscriptionSource {
    /** 服务端 `/user/subscription`。 */
    REMOTE,

    /** 设置页里用户自己选的。 */
    MANUAL,
}

/**
 * 有效的订阅状态（等级 + 是否具备订阅权益）。
 *
 * 两个字段刻意分开：等级可能读不出来（[SubscriptionTier.Unknown]），
 * 但"是否有订阅"仍可由 [AccountType] 或有效期独立判定；
 * 反之内部账号可能根本没有 tier 却有完整权益。
 */
data class SubscriptionStatus(
    val tier: SubscriptionTier,
    /** 是否具备订阅权益。免费单张、订阅折扣等一切"订阅才有"的判断都看它。 */
    val subscribed: Boolean,
    val source: SubscriptionSource,
) {
    val isOpus: Boolean get() = subscribed && tier.isOpus

    companion object {
        /** 还没读到余额、也没手动指定时的初始状态：什么都不知道。 */
        val Unknown: SubscriptionStatus = SubscriptionStatus(
            tier = SubscriptionTier.Unknown(DefaultSubscriptionTierResolver.RAW_MISSING),
            subscribed = false,
            source = SubscriptionSource.REMOTE,
        )
    }
}

/**
 * 把"服务端读数 + 本机手动覆盖"合成一个有效订阅状态。
 *
 * ## 官方判据（2026-09-14 从官方网页前端 bundle 反解）
 * ```js
 * hasSubscription = accountType in {B2B, SERVICE, SUPPORT, ADMIN}
 *                || (expiresAt > now && tier > 0)
 * ```
 * 注意它**不看 `active`**，也**不看 `usage`**。这与早期版本"用 V5 使用额度当订阅旁证"
 * 的做法不同：额度只是 V5 Opus 的功能，用它反推订阅在"额度用尽/字段缺失"时会失真。
 *
 * ## 手动覆盖的优先级
 * 手动值直接取代服务端读数（包括 `subscribed`），因为用户是唯一能核对
 * "我到底有没有买"的人。覆盖为 [SubscriptionOverride.NONE] 也表达一个明确意图：
 * "别猜我有订阅，按量给我算"。**AUTO 才是默认**，手动是兜底。
 */
object SubscriptionStatusResolver {

    fun resolve(
        rawTier: Int?,
        accountType: Int?,
        expiresAtEpochSeconds: Long?,
        nowEpochSeconds: Long,
        override: SubscriptionOverride = SubscriptionOverride.AUTO,
        tierResolver: SubscriptionTierResolver = DefaultSubscriptionTierResolver,
    ): SubscriptionStatus {
        if (override != SubscriptionOverride.AUTO) {
            return SubscriptionStatus(
                tier = override.toTier(),
                subscribed = override != SubscriptionOverride.NONE,
                source = SubscriptionSource.MANUAL,
            )
        }

        val tier = tierResolver.resolve(rawTier)
        val internal = AccountType.from(accountType)?.grantsSubscription == true
        // `expiresAt` 是"权益到期时间"：已取消但仍在付费周期内的账号，它还在未来。
        val withinPaidPeriod = expiresAtEpochSeconds != null &&
            expiresAtEpochSeconds > nowEpochSeconds &&
            tier !is SubscriptionTier.None &&
            tier !is SubscriptionTier.Unknown

        return SubscriptionStatus(
            tier = tier,
            subscribed = internal || withinPaidPeriod,
            source = SubscriptionSource.REMOTE,
        )
    }

    private fun SubscriptionOverride.toTier(): SubscriptionTier = when (this) {
        SubscriptionOverride.AUTO -> SubscriptionTier.Unknown(DefaultSubscriptionTierResolver.RAW_MISSING)
        SubscriptionOverride.NONE -> SubscriptionTier.None
        SubscriptionOverride.TABLET -> SubscriptionTier.Tablet
        SubscriptionOverride.SCROLL -> SubscriptionTier.Scroll
        SubscriptionOverride.OPUS -> SubscriptionTier.Opus
    }
}
