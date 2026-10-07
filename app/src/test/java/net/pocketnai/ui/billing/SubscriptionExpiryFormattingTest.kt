package net.pocketnai.ui.billing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SubscriptionExpiryFormattingTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun seconds(value: String) = Instant.parse(value).epochSecond
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun `Unix秒显示本地到期日期以及天小时剩余量`() {
        val result = formatSubscriptionExpiry(seconds("2026-10-24T13:15:00Z"), millis("2026-10-07T10:15:00Z"), shanghai)
        assertThat(result.expiresAt).isEqualTo("2026-10-24 21:15")
        assertThat(result.summary).isEqualTo("剩余 17 天 3 小时")
        assertThat(result.pending).isTrue()
    }

    @Test fun `不到一天显示小时分钟不把零天误认为已经到期`() {
        val now = millis("2026-10-07T10:15:00Z")
        assertThat(formatSubscriptionExpiry(seconds("2026-10-07T13:35:00Z"), now, shanghai).summary)
            .isEqualTo("剩余 3 小时 20 分钟")
        assertThat(formatSubscriptionExpiry(seconds("2026-10-07T10:35:00Z"), now, shanghai).summary)
            .isEqualTo("剩余 20 分钟")
    }

    @Test fun `最后一分钟与到期边界正确区分`() {
        val expiry = seconds("2026-10-07T10:15:00Z")
        assertThat(formatSubscriptionExpiry(expiry, (expiry - 1) * 1000, shanghai).summary).isEqualTo("剩余 不到 1 分钟")
        val reached = formatSubscriptionExpiry(expiry, expiry * 1000, shanghai)
        assertThat(reached.summary).isEqualTo("已到期")
        assertThat(reached.pending).isFalse()
        assertThat(formatSubscriptionExpiry(expiry, (expiry + 60) * 1000, shanghai).expiresAt).isEqualTo(reached.expiresAt)
    }

    @Test fun `缺失零负值及毫秒形态异常不伪造到期日期`() {
        val now = millis("2026-10-07T10:15:00Z")
        listOf(null, 0L, -1L, Long.MAX_VALUE, millis("2026-10-24T13:15:00Z")).forEach { raw ->
            val result = formatSubscriptionExpiry(raw, now, shanghai)
            assertThat(result.summary).isEqualTo("到期时间未知")
            assertThat(result.expiresAt).isNull()
            assertThat(result.pending).isFalse()
        }
    }

    @Test fun `跨夏令时仍按绝对剩余时长计算`() {
        val result = formatSubscriptionExpiry(seconds("2026-03-29T08:00:00Z"), millis("2026-03-28T08:00:00Z"), ZoneId.of("Europe/Berlin"))
        assertThat(result.summary).isEqualTo("剩余 1 天")
        assertThat(result.expiresAt).isEqualTo("2026-03-29 10:00")
    }
}
