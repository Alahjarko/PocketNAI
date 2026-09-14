package net.pocketnai.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import net.pocketnai.BuildConfig
import net.pocketnai.R
import net.pocketnai.data.repo.BalanceRefreshReason
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.billing.BalanceDetailDialog
import net.pocketnai.ui.billing.compactLabel

@Composable
fun SettingsScreen(onRequestConnect: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    repository = container.generationRepository,
                    settingsStore = container.settingsStore,
                    credentialStore = container.credentialStore,
                    sessionState = container.sessionState,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val streamingEnabled by viewModel.streamingPreviewEnabled.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()

    // 余额与生成页共用同一个仓库实例，不另建一份网络状态或缓存（余额规划 §11.4）。
    val balanceState by container.accountBalanceRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        // ---- 连接状态 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (connected) {
                        stringResource(R.string.connect_connected)
                    } else {
                        stringResource(R.string.generate_no_token)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "PocketNAI 只用你自己的 Persistent API Token 直连 NovelAI，" +
                        "不经过任何第三方服务器，也不保存你的邮箱或密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRequestConnect) {
                        Text(stringResource(R.string.connect_title))
                    }
                    if (connected) {
                        OutlinedButton(onClick = viewModel::disconnect) {
                            Text(stringResource(R.string.connect_delete))
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // ---- 账户余额 ----
        BalanceSection(
            state = balanceState,
            connected = connected,
            onRefresh = {
                // 手动刷新无视缓存；余额刷新失败不影响生成，也不会覆盖生成错误。
                scope.launch {
                    container.accountBalanceRepository.refresh(
                        reason = BalanceRefreshReason.USER_REQUESTED,
                        force = true,
                    )
                }
            },
        )

        HorizontalDivider()

        // ---- 外观 ----
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.settings_appearance),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.settings_theme_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(stringResource(mode.labelRes())) },
                    )
                }
            }
        }

        HorizontalDivider()

        // ---- 流式预览 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_streaming_preview),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_streaming_preview_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = streamingEnabled,
                onCheckedChange = viewModel::setStreamingPreviewEnabled,
            )
        }

        HorizontalDivider()

        // ---- 存储 ----
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.settings_storage),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "私有历史占用 ${formatBytes(state.usedBytes)}。" +
                    "这些图片保存在应用私有目录，删除应用数据会一并清除；" +
                    "已经保存到系统相册的副本不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::runMaintenance) {
                    Text(stringResource(R.string.settings_clear_orphans))
                }
                OutlinedButton(onClick = viewModel::refreshUsage) {
                    Text("刷新占用")
                }
            }
        }

        state.maintenanceMessage?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = message, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::dismissMaintenanceMessage) {
                        Text(stringResource(R.string.action_confirm))
                    }
                }
            }
        }

        HorizontalDivider()

        Text(
            text = "${stringResource(R.string.settings_version)} ${BuildConfig.VERSION_NAME} " +
                "(build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "首次发布只支持文生图（T2I）与四个 V4.5 / V5 模型。" +
                "Img2Img、Inpaint、Vibe Transfer 等能力不在首版范围内。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/**
 * 账户余额区块（余额规划 §11.4）。
 *
 * 这里是**只读展示 + 手动刷新**：余额是账户级状态，购买与充值都在官方网页完成，
 * 应用不提供任何花钱的入口。
 */
@Composable
private fun BalanceSection(
    state: net.pocketnai.data.repo.BalanceState,
    connected: Boolean,
    onRefresh: () -> Unit,
) {
    var dialogOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.balance_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = if (!connected) {
                stringResource(R.string.balance_never_loaded)
            } else {
                state.compactLabel() ?: stringResource(R.string.balance_never_loaded)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dialogOpen = true }, enabled = connected) {
                Text(stringResource(R.string.balance_title))
            }
            OutlinedButton(onClick = onRefresh, enabled = connected) {
                Text(stringResource(R.string.balance_refresh))
            }
        }
        Text(
            text = "余额由 NovelAI 返回，购买与充值请到官方网页操作。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (dialogOpen) {
        BalanceDetailDialog(
            state = state,
            onRefresh = onRefresh,
            onDismiss = { dialogOpen = false },
        )
    }
}

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
