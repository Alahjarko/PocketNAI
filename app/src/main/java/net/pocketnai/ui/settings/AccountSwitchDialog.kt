package net.pocketnai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.security.CredentialType
import net.pocketnai.data.security.StoredCredential
import net.pocketnai.domain.account.SavedAccount

@Composable
fun AccountSwitchDialog(
    credentialStore: CredentialStore,
    onAccountSwitched: () -> Unit,
    onDismiss: () -> Unit,
) {
    var accounts by remember { mutableStateOf(credentialStore.listAccounts()) }
    var addDialogOpen by remember { mutableStateOf(false) }
    var renamingAccount by remember { mutableStateOf<SavedAccount?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("账号管理与切换") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (accounts.isEmpty()) {
                    Text(
                        text = "暂无已保存账号，请添加第一个 NovelAI 账号。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    accounts.forEach { account ->
                        AccountItemCard(
                            account = account,
                            onSwitch = {
                                if (credentialStore.switchAccount(account.id)) {
                                    accounts = credentialStore.listAccounts()
                                    onAccountSwitched()
                                }
                            },
                            onRename = { renamingAccount = account },
                            onDelete = {
                                credentialStore.deleteAccount(account.id)
                                accounts = credentialStore.listAccounts()
                                onAccountSwitched()
                            },
                        )
                    }
                }

                OutlinedButton(
                    onClick = { addDialogOpen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("添加新账号", modifier = Modifier.padding(start = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        },
    )

    // 添加新账号弹窗
    if (addDialogOpen) {
        AddAccountDialog(
            onSave = { name, token ->
                credentialStore.saveAccount(
                    name = name,
                    credential = StoredCredential(token = token, type = CredentialType.PERSISTENT_API_TOKEN),
                )
                accounts = credentialStore.listAccounts()
                onAccountSwitched()
                addDialogOpen = false
            },
            onDismiss = { addDialogOpen = false },
        )
    }

    // 重命名账号弹窗
    renamingAccount?.let { target ->
        RenameAccountDialog(
            initialName = target.name,
            onConfirm = { newName ->
                credentialStore.renameAccount(target.id, newName)
                accounts = credentialStore.listAccounts()
                renamingAccount = null
            },
            onDismiss = { renamingAccount = null },
        )
    }
}

@Composable
private fun AccountItemCard(
    account: SavedAccount,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (account.isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = if (account.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = account.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = onRename, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "重命名", modifier = Modifier.size(14.dp))
                    }
                }

                if (account.isActive) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("当前激活") },
                        icon = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        ),
                    )
                } else {
                    OutlinedButton(
                        onClick = onSwitch,
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Text("切换")
                    }
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除账号",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Text(
                text = "凭据指纹: ${account.tokenFingerprint} · ${if (account.type == CredentialType.PERSISTENT_API_TOKEN) "Persistent Token" else "Session"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddAccountDialog(
    onSave: (name: String, token: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 NovelAI 账号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账号别名 (例如: 主号 Opus / 小号)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("Persistent API Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Token 由本机 Keystore 独立加密，绝不进入日志或普通存储。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, token) },
                enabled = token.isNotBlank(),
            ) {
                Text("保存并切换")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun RenameAccountDialog(
    initialName: String,
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名账号") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("账号名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
