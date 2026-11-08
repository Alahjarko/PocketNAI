package net.pocketnai.ui.connect

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.pocketnai.data.security.CredentialHint
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.domain.account.SavedAccount

/** Only public account metadata is frozen; never load/decrypt credentials for confirmation. */
internal data class NovelAiDisconnectTarget(
    val accounts: List<SavedAccount>,
    val activeId: String?,
    val hint: CredentialHint?,
    val connected: Boolean,
) {
    fun isCurrent(store: CredentialStore): Boolean = this == capture(store)

    val disconnectMessage: String
        get() = "将断开 NovelAI，并移除本机保存的全部 NovelAI 账号与凭据" +
            (if (accounts.isNotEmpty()) "（共 ${accounts.size} 个）" else "") +
            "。再次使用需重新添加令牌或登录。\n\n本机历史图片、收藏、生成草稿与已导出的图片会保留；不会注销服务端账号或撤销服务端令牌。"

    companion object {
        fun capture(store: CredentialStore) = NovelAiDisconnectTarget(
            accounts = store.listAccounts().sortedBy { it.id },
            activeId = store.activeAccountId(),
            hint = store.hint(),
            connected = store.hasCredential(),
        )
    }
}

/** A stale target fails closed. Dismissal, back and outside taps never invoke the action. */
@Composable
internal fun AccountDisconnectConfirmation(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Boolean,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
) {
    var targetChanged by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                if (targetChanged) "账号、密钥或任务状态已变化，未执行操作。请取消后重新确认。" else message,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                enabled = enabled && !targetChanged,
                onClick = {
                    if (onConfirm()) onDismiss() else targetChanged = true
                },
            ) {
                Text(confirmLabel, color = if (enabled && !targetChanged) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
