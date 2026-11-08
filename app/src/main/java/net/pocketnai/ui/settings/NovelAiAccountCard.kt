package net.pocketnai.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pocketnai.data.repo.BalanceState
import net.pocketnai.domain.billing.SubscriptionStatus
import net.pocketnai.ui.billing.AccountUsageContent
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.connect.AccountDisconnectConfirmation
import net.pocketnai.ui.connect.NovelAiDisconnectTarget

/** 令牌和用量是同一账户组的上下两段，不再散落为独立设置项或等级弹窗。 */
@Composable
internal fun NovelAiAccountCard(
    connected: Boolean,
    accountName: String?,
    fingerprint: String?,
    state: BalanceState,
    status: SubscriptionStatus,
    onManage: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
) {
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    if (showDisconnectConfirm) {
        val credentialStore = LocalAppContainer.current.credentialStore
        val target = remember { NovelAiDisconnectTarget.capture(credentialStore) }
        AccountDisconnectConfirmation(
            title = "断开 NovelAI 连接？",
            message = target.disconnectMessage,
            confirmLabel = "确认断开",
            onConfirm = {
                if (target.isCurrent(credentialStore)) {
                    onDisconnect()
                    true
                } else false
            },
            onDismiss = { showDisconnectConfirm = false },
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (connected) accountName ?: "NovelAI" else "未连接 NovelAI", style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (connected) Text(fingerprint?.let { "已连接 · $it" } ?: "已连接",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onManage) { Text("切换令牌") }
                    TextButton(onClick = {
                        if (connected) showDisconnectConfirm = true
                        else onConnect()
                    }) { Text(if (connected) "断开连接" else "连接") }
                }
            }
        }
        Surface(shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            AccountUsageContent(state, status, onRefresh, Modifier.fillMaxWidth().padding(16.dp), connected)
        }
    }
}
