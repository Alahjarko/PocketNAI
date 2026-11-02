package net.pocketnai.ui.billing

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.pocketnai.data.repo.BalanceState
import net.pocketnai.domain.billing.SubscriptionStatus
import net.pocketnai.domain.billing.SubscriptionTier
import net.pocketnai.ui.motion.WavyQuotaBar

/** 设置账户卡和生成面板共用同一份真实权益与余额展示。 */
@Composable
internal fun AccountUsageContent(
    state: BalanceState,
    status: SubscriptionStatus,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    connected: Boolean = true,
) {
    val balance = state.knownBalance
    val allowance = balance?.v5AllowanceFor(status)
    val digits = TextStyle(fontFeatureSettings = "tnum")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("会员等级", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when {
                    !connected -> "未连接"
                    balance == null -> "未读取"
                    status.tier is SubscriptionTier.Unknown && !status.subscribed -> "等级未知"
                    !status.subscribed -> "无有效订阅"
                    status.tier == SubscriptionTier.Opus -> "Opus"
                    status.tier == SubscriptionTier.Scroll -> "Scroll"
                    status.tier == SubscriptionTier.Tablet -> "Tablet"
                    else -> "会员"
                }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("剩余 Anlas", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(balance?.totalAnlas?.let(::formatAnlas) ?: "—", style = MaterialTheme.typography.titleLarge.merge(digits),
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onRefresh, enabled = connected && state !is BalanceState.Loading,
                modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Refresh, "刷新账户用量") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("V5 限额", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when {
                state is BalanceState.Loading && balance == null -> "读取中"
                allowance == null -> "未提供"
                allowance.isNegative -> "暂不可用"
                else -> "${allowance.rawPercent}%"
            }, style = MaterialTheme.typography.labelMedium.merge(digits), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WavyQuotaBar(allowance?.let { if (it.isNegative) 0f else it.progressFraction }, Modifier.fillMaxWidth())
        if (state is BalanceState.RefreshFailed || (state is BalanceState.Available && state.stale)) {
            Text(if (balance != null) "显示上次读数" else "暂时无法读取，点击刷新重试",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
