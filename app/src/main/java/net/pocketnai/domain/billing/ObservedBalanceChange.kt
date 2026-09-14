package net.pocketnai.domain.billing

import kotlin.math.abs

/**
 * 生成前后各取一次余额，得到的**观察值**（规划 §9）。
 *
 * 它**不是账单**：同一账户可能同时在网页端或另一台设备消费、期间可能续订或充值、
 * V5 可能消耗额度而不是 Anlas、服务端更新可能有延迟、超时不代表服务端没有计费。
 * 因此文案统一叫"本次观察到的余额变化"，不叫"本次扣费"。
 */
sealed interface ObservedBalanceChange {

    data class AnlasDecreased(
        val subscriptionSpent: Long,
        val purchasedSpent: Long,
        val totalSpent: Long,
        val requestedImageCount: Int,
    ) : ObservedBalanceChange {
        val averagePerRequestedImage: Double?
            get() = requestedImageCount.takeIf { it > 0 }
                ?.let { totalSpent.toDouble() / it }
    }

    data class V5AllowanceChanged(
        val beforePercent: Int,
        val afterPercent: Int,
        val anlasSpent: Long,
    ) : ObservedBalanceChange

    /**
     * 没看到变化。
     *
     * ⚠️ [mayBeDelayed] 为真时文案必须是"暂未观察到余额变化"，
     * **不能**说成"本次完全免费"：V5 百分比是整数，前后相同不代表没有消耗，可能只是取整后没变。
     */
    data class NoVisibleChange(val mayBeDelayed: Boolean) : ObservedBalanceChange

    data class Ambiguous(val reason: AmbiguousBalanceReason) : ObservedBalanceChange

    companion object {
        /**
         * 计算观察值。
         *
         * 任何"说不清"的情况都返回 [Ambiguous] 而不是用 `coerceAtLeast(0)` 把异常抹平 ——
         * 余额在生成期间**增加**说明有续订、充值或别的设备在动这个账户，
         * 此时的差值不能当成这次生成的花费。
         */
        fun calculate(
            before: SubscriptionBalance?,
            after: SubscriptionBalance?,
            expectedImageCount: Int,
            generationCompleted: Boolean,
            beforeAgeMillis: Long? = null,
            maxPreSnapshotAgeMillis: Long = DEFAULT_MAX_PRE_SNAPSHOT_AGE_MS,
        ): ObservedBalanceChange {
            if (before == null) return Ambiguous(AmbiguousBalanceReason.MISSING_BEFORE_SNAPSHOT)
            if (after == null) return Ambiguous(AmbiguousBalanceReason.MISSING_AFTER_SNAPSHOT)
            if (!generationCompleted) {
                return Ambiguous(AmbiguousBalanceReason.GENERATION_NOT_CONFIRMED)
            }
            if (beforeAgeMillis != null && beforeAgeMillis > maxPreSnapshotAgeMillis) {
                return Ambiguous(AmbiguousBalanceReason.SNAPSHOT_TOO_OLD)
            }

            val subscriptionDelta = before.subscriptionAnlas - after.subscriptionAnlas
            val purchasedDelta = before.purchasedAnlas - after.purchasedAnlas

            // 任一池增加都说明期间发生了别的事情，差值不可解释为本次花费。
            if (subscriptionDelta < 0 || purchasedDelta < 0) {
                return Ambiguous(AmbiguousBalanceReason.BALANCE_INCREASED_DURING_REQUEST)
            }

            val subscriptionSpent = subscriptionDelta
            val purchasedSpent = purchasedDelta
            val totalSpent = saturatingAdd(subscriptionSpent, purchasedSpent)

            val beforePercent = before.v5UsageLimit?.rawPercent
            val afterPercent = after.v5UsageLimit?.rawPercent

            if (totalSpent > 0) {
                // 同时消耗了 Anlas 与额度时，以 Anlas 为准报告金额，并带上额度变化。
                return if (beforePercent != null && afterPercent != null && afterPercent < beforePercent) {
                    V5AllowanceChanged(
                        beforePercent = beforePercent,
                        afterPercent = afterPercent,
                        anlasSpent = totalSpent,
                    )
                } else {
                    AnlasDecreased(
                        subscriptionSpent = subscriptionSpent,
                        purchasedSpent = purchasedSpent,
                        totalSpent = totalSpent,
                        requestedImageCount = expectedImageCount,
                    )
                }
            }

            if (beforePercent != null && afterPercent != null && afterPercent < beforePercent) {
                return V5AllowanceChanged(
                    beforePercent = beforePercent,
                    afterPercent = afterPercent,
                    anlasSpent = 0,
                )
            }

            return NoVisibleChange(mayBeDelayed = true)
        }

        /** 前快照超过这个年龄就不再用来算差值 —— 期间太可能发生别的事情。 */
        const val DEFAULT_MAX_PRE_SNAPSHOT_AGE_MS: Long = 2 * 60 * 1000L

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

        /** 诊断用：快照里两个池的差异幅度，不参与业务判断。 */
        internal fun magnitude(before: SubscriptionBalance, after: SubscriptionBalance): Long =
            abs(before.totalAnlas - after.totalAnlas)
    }
}

enum class AmbiguousBalanceReason {
    MISSING_BEFORE_SNAPSHOT,
    MISSING_AFTER_SNAPSHOT,
    GENERATION_NOT_CONFIRMED,
    BALANCE_INCREASED_DURING_REQUEST,
    ACCOUNT_CHANGED,
    SNAPSHOT_TOO_OLD,
}
