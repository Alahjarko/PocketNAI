package net.pocketnai.domain.billing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 生成前后的余额差值（余额规划 §12.5）。
 *
 * 这个类的作用是**在说不清的时候说"说不清"**：余额上升、快照过期、生成未确认、
 * 前快照缺失，都必须落到 Ambiguous，而不是用 `coerceAtLeast(0)` 把异常抹成一个数字。
 */
class ObservedBalanceChangeTest {

    private fun balance(
        subscription: Long = 10_000,
        purchased: Long = 2_000,
        percent: Int? = 87,
        fetchedAt: Long = 0L,
    ) = SubscriptionBalance(
        rawTier = 3,
        active = true,
        expiresAtEpochSeconds = null,
        isGracePeriod = false,
        subscriptionAnlas = subscription,
        purchasedAnlas = purchased,
        v5UsageLimit = percent?.let {
            V5UsageLimit(rawPercent = it, isNegative = false, timeUntilNextPercentSeconds = null)
        },
        fetchedAtMillis = fetchedAt,
    )

    private fun calculate(
        before: SubscriptionBalance?,
        after: SubscriptionBalance?,
        expectedImageCount: Int = 1,
        completed: Boolean = true,
        beforeAgeMillis: Long? = null,
    ) = ObservedBalanceChange.calculate(
        before = before,
        after = after,
        expectedImageCount = expectedImageCount,
        generationCompleted = completed,
        beforeAgeMillis = beforeAgeMillis,
    )

    @Test
    fun `只消耗订阅 Anlas`() {
        val change = calculate(balance(), balance(subscription = 9_983)) as ObservedBalanceChange.AnlasDecreased

        assertThat(change.subscriptionSpent).isEqualTo(17L)
        assertThat(change.purchasedSpent).isEqualTo(0L)
        assertThat(change.totalSpent).isEqualTo(17L)
    }

    @Test
    fun `订阅池耗尽后继续消耗购买 Anlas`() {
        val before = balance(subscription = 20, purchased = 2_000)
        val after = balance(subscription = 0, purchased = 1_991)

        val change = calculate(before, after) as ObservedBalanceChange.AnlasDecreased

        assertThat(change.subscriptionSpent).isEqualTo(20L)
        assertThat(change.purchasedSpent).isEqualTo(9L)
        assertThat(change.totalSpent).isEqualTo(29L)
    }

    @Test
    fun `只消耗购买 Anlas`() {
        val change = calculate(
            balance(subscription = 0, purchased = 100),
            balance(subscription = 0, purchased = 95),
        ) as ObservedBalanceChange.AnlasDecreased

        assertThat(change.purchasedSpent).isEqualTo(5L)
    }

    @Test
    fun `Anlas 不变但 V5 百分比下降`() {
        val change = calculate(balance(percent = 87), balance(percent = 85))

        assertThat(change).isInstanceOf(ObservedBalanceChange.V5AllowanceChanged::class.java)
        assertThat((change as ObservedBalanceChange.V5AllowanceChanged).anlasSpent).isEqualTo(0L)
        assertThat(change.beforePercent).isEqualTo(87)
        assertThat(change.afterPercent).isEqualTo(85)
    }

    @Test
    fun `同时消耗 Anlas 与额度时以金额为准并带上额度变化`() {
        val change = calculate(
            balance(percent = 87),
            balance(subscription = 9_995, percent = 86),
        ) as ObservedBalanceChange.V5AllowanceChanged

        assertThat(change.anlasSpent).isEqualTo(5L)
        assertThat(change.afterPercent).isEqualTo(86)
    }

    @Test
    fun `两者都不变时只能说暂未观察到变化`() {
        val change = calculate(balance(), balance())

        assertThat(change).isInstanceOf(ObservedBalanceChange.NoVisibleChange::class.java)
        assertThat((change as ObservedBalanceChange.NoVisibleChange).mayBeDelayed).isTrue()
    }

    @Test
    fun `生成期间充值导致余额增加返回不确定`() {
        val change = calculate(balance(), balance(subscription = 12_000))

        assertThat((change as ObservedBalanceChange.Ambiguous).reason)
            .isEqualTo(AmbiguousBalanceReason.BALANCE_INCREASED_DURING_REQUEST)
    }

    @Test
    fun `缺少前快照返回不确定`() {
        assertThat((calculate(null, balance()) as ObservedBalanceChange.Ambiguous).reason)
            .isEqualTo(AmbiguousBalanceReason.MISSING_BEFORE_SNAPSHOT)
    }

    @Test
    fun `缺少后快照返回不确定`() {
        assertThat((calculate(balance(), null) as ObservedBalanceChange.Ambiguous).reason)
            .isEqualTo(AmbiguousBalanceReason.MISSING_AFTER_SNAPSHOT)
    }

    @Test
    fun `生成未确认时不宣称消费`() {
        val change = calculate(balance(), balance(subscription = 9_983), completed = false)

        assertThat((change as ObservedBalanceChange.Ambiguous).reason)
            .isEqualTo(AmbiguousBalanceReason.GENERATION_NOT_CONFIRMED)
    }

    @Test
    fun `前快照过旧时返回不确定`() {
        val change = calculate(
            balance(),
            balance(subscription = 9_983),
            beforeAgeMillis = ObservedBalanceChange.DEFAULT_MAX_PRE_SNAPSHOT_AGE_MS + 1,
        )

        assertThat((change as ObservedBalanceChange.Ambiguous).reason)
            .isEqualTo(AmbiguousBalanceReason.SNAPSHOT_TOO_OLD)
    }

    @Test
    fun `批量只给总变化和平均每张`() {
        val change = calculate(
            balance(),
            balance(subscription = 9_932),
            expectedImageCount = 4,
        ) as ObservedBalanceChange.AnlasDecreased

        assertThat(change.totalSpent).isEqualTo(68L)
        assertThat(change.averagePerRequestedImage).isEqualTo(17.0)
    }

    @Test
    fun `V5 整数百分比没变时不宣称完全免费`() {
        // percent 是整数，前后相同不代表没有消耗，可能只是取整后没变。
        val change = calculate(balance(percent = 50), balance(percent = 50))

        assertThat(change).isInstanceOf(ObservedBalanceChange.NoVisibleChange::class.java)
    }

    @Test
    fun `没有 V5 额度信息时只看 Anlas`() {
        val change = calculate(balance(percent = null), balance(percent = null, subscription = 9_990))

        assertThat((change as ObservedBalanceChange.AnlasDecreased).totalSpent).isEqualTo(10L)
    }

    @Test
    fun `额度从可用变为不可用也算发生了变化`() {
        val before = balance(percent = 1)
        val after = before.copy(
            v5UsageLimit = V5UsageLimit(rawPercent = 0, isNegative = true, timeUntilNextPercentSeconds = 0),
        )

        val change = calculate(before, after) as ObservedBalanceChange.V5AllowanceChanged

        assertThat(change.afterPercent).isEqualTo(0)
    }
}
