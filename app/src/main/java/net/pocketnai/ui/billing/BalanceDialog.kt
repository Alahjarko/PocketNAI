package net.pocketnai.ui.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import net.pocketnai.data.repo.BalanceState
import net.pocketnai.domain.billing.SubscriptionStatus

/** 生成面板的紧凑账户浮层，与设置内联账户卡共用真实读数。 */
@Composable
fun BalanceDetailDialog(
    state: BalanceState,
    subscriptionStatus: SubscriptionStatus = SubscriptionStatus.Unknown,
    onRefresh: () -> Unit,
    onViewLedger: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NovelAI 账户") },
        text = { AccountUsageContent(state, subscriptionStatus, onRefresh) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onViewLedger?.let { action -> TextButton(onClick = action) { Text("消耗统计") } }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
    )
}
