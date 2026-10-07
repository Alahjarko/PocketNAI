package net.pocketnai.ui.billing

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SubscriptionExpiryText(val summary: String, val expiresAt: String?, val pending: Boolean)

/** 使用服务端 Unix 秒与绝对时间差，不从手动指定的等级推断日期。 */
internal fun formatSubscriptionExpiry(
    expiresAtEpochSeconds: Long?,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): SubscriptionExpiryText {
    val expiry = expiresAtEpochSeconds?.takeIf { it in 1..253402300799L }
        ?: return SubscriptionExpiryText("到期时间未知", null, false)
    val date = Instant.ofEpochSecond(expiry).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val seconds = expiry - nowMillis / 1000L
    if (seconds <= 0) return SubscriptionExpiryText("已到期", date, false)
    val days = seconds / 86400
    val hours = seconds % 86400 / 3600
    val minutes = seconds % 3600 / 60
    val remaining = when {
        days > 0 -> "$days 天" + if (hours > 0) " $hours 小时" else ""
        hours > 0 -> "$hours 小时" + if (minutes > 0) " $minutes 分钟" else ""
        minutes > 0 -> "$minutes 分钟"
        else -> "不到 1 分钟"
    }
    return SubscriptionExpiryText("剩余 $remaining", date, true)
}
