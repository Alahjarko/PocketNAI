package net.pocketnai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
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
import net.pocketnai.domain.proxy.ProxyMode
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.messageRes
import net.pocketnai.ui.update.UpdateCheckDialog
import net.pocketnai.ui.update.UpdateViewModel

/**
 * 设置页：顶部账号卡片 + 一个分组列表。
 *
 * 列表化之前，每个设置项都是"标题 + 当前值 + 一排按钮 + 整段说明散文"直接铺在页面上，
 * 设置页读起来像一篇文档（2026-09-21 界面减负）。现在的约定：
 * 每项一行（标题 + 当前值 + ›），点进去是对话框，说明散文只住在对话框里。
 * 纯开关项（流式预览）与"行上就要能拨"的开关（代理）保留行内 Switch。
 */
@Composable
fun SettingsScreen(
    onRequestConnect: () -> Unit,
    onOpenStatistics: () -> Unit,
    updateViewModel: UpdateViewModel,
    onOpenChatSettings: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    repository = container.generationRepository,
                    settingsStore = container.settingsStore,
                    credentialStore = container.credentialStore,
                    sessionState = container.sessionState,
                    accountBalanceRepository = container.accountBalanceRepository,
                    accountAdder = container.accountAdder,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val addAccountUi by viewModel.addAccountUi.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val subscriptionStatus by viewModel.subscriptionStatus.collectAsStateWithLifecycle()

    // 余额与生成页共用同一个仓库实例，不另建一份网络状态或缓存（余额规划 §11.4）。
    val balanceState by container.accountBalanceRepository.state.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val chatEnabled by container.settingsStore.chatEnabled.collectAsStateWithLifecycle()
    val artistLabEnabled by container.settingsStore.artistLabEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var accountSwitchDialogOpen by remember { mutableStateOf(false) }
    var proxyDialogOpen by remember { mutableStateOf(false) }
    var storageDialogOpen by remember { mutableStateOf(false) }
    var updateDialogOpen by remember { mutableStateOf(false) }
    var cleanupImagesDialogOpen by remember { mutableStateOf(false) }

    val proxySettings by container.proxyStore.settings.collectAsStateWithLifecycle()
    val proxyUsedBytes by container.proxyStore.usedBytesToday.collectAsStateWithLifecycle()

    // 手动刷新无视缓存；余额刷新失败不影响生成，也不会覆盖生成错误。
    val refreshBalance: () -> Unit = {
        scope.launch {
            container.accountBalanceRepository.refresh(
                reason = BalanceRefreshReason.USER_REQUESTED,
                force = true,
            )
        }
    }

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

        net.pocketnai.ui.openai.OpenAiImageSettingsRow()

        Text("NovelAI 账户", style = MaterialTheme.typography.titleSmall)
        val activeAccount = remember(connected, accountSwitchDialogOpen) {
            container.credentialStore.listAccounts().firstOrNull { it.isActive }
        }
        NovelAiAccountCard(
            connected = connected,
            accountName = activeAccount?.name,
            fingerprint = activeAccount?.tokenFingerprint,
            state = balanceState,
            status = subscriptionStatus,
            onManage = { accountSwitchDialogOpen = true },
            onConnect = onRequestConnect,
            onDisconnect = viewModel::disconnect,
            onRefresh = refreshBalance,
        )
        ColorModeCard(themeMode, viewModel::setThemeMode)
        val sfwMode by container.settingsStore.sfwMode.collectAsStateWithLifecycle()
        SfwModeCard(sfwMode, container.settingsStore::setSfwMode)

        // ---- 设置项分组列表 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingsRow(
                    title = stringResource(R.string.settings_balance_ledger),
                    icon = Icons.Default.QueryStats,
                    subtitle = stringResource(R.string.settings_ledger_subtitle),
                    onClick = onOpenStatistics,
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.proxy_section_title),
                    icon = Icons.Default.Language,
                    subtitle = proxySummaryLabel(
                        enabled = proxySettings.enabled,
                        mode = proxySettings.mode,
                        host = proxySettings.host,
                        port = proxySettings.port,
                        usedBytesToday = proxyUsedBytes,
                    ),
                    onClick = { proxyDialogOpen = true },
                    trailing = {
                        Switch(
                            checked = proxySettings.enabled,
                            onCheckedChange = {
                                container.proxyStore.update(proxySettings.copy(enabled = it))
                            },
                        )
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = "实验性对话",
                    icon = Icons.Default.ChatBubbleOutline,
                    subtitle = if (chatEnabled) "已在导航栏显示对话" else "开启后在导航栏显示对话",
                    onClick = { container.settingsStore.setChatEnabled(!chatEnabled) },
                    trailing = { Switch(chatEnabled, container.settingsStore::setChatEnabled) },
                )
                SettingsDivider()
                SettingsRow(
                    title = "连接与人格",
                    icon = Icons.Default.Hub,
                    subtitle = "模型服务、人格与工具",
                    onClick = onOpenChatSettings,
                )
                SettingsDivider()
                SettingsRow(
                    title = "抽卡实验室（实验）",
                    icon = Icons.Default.Palette,
                    subtitle = "随机画师串 · 固定种子 · 收藏与批次清理",
                    onClick = { container.settingsStore.setArtistLabEnabled(!artistLabEnabled) },
                    trailing = { Switch(artistLabEnabled, container.settingsStore::setArtistLabEnabled) },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_storage),
                    icon = Icons.Default.Storage,
                    subtitle = "私有历史占用 ${formatBytes(state.usedBytes)}",
                    onClick = { storageDialogOpen = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.update_section_title),
                    icon = Icons.Default.SystemUpdate,
                    subtitle = "${stringResource(R.string.settings_version)} " +
                        "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                    onClick = { updateDialogOpen = true },
                )
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
    }

    if (accountSwitchDialogOpen) {
        AccountSwitchDialog(
            credentialStore = container.credentialStore,
            addAccountUi = addAccountUi,
            onAddWithToken = viewModel::addAccountWithToken,
            onAddWithLogin = viewModel::addAccountWithLogin,
            onDismissAddError = viewModel::dismissAddAccountError,
            onAccountSwitched = viewModel::onAccountSwitched,
            onDismiss = { accountSwitchDialogOpen = false },
        )
    }

    if (proxyDialogOpen) {
        AlertDialog(
            onDismissRequest = { proxyDialogOpen = false },
            title = { Text(stringResource(R.string.proxy_section_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.proxy_section_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.proxy_enable_label),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = proxySettings.enabled,
                            onCheckedChange = {
                                container.proxyStore.update(proxySettings.copy(enabled = it))
                            },
                        )
                    }
                    if (proxySettings.enabled) {
                        ProxyConfigContent(
                            settings = proxySettings,
                            onSettingsChange = container.proxyStore::update,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { proxyDialogOpen = false }) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }

    if (storageDialogOpen) {
        AlertDialog(
            onDismissRequest = { storageDialogOpen = false },
            title = { Text(stringResource(R.string.settings_storage)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "私有历史占用 ${formatBytes(state.usedBytes)}。" +
                            "这些图片保存在应用私有目录，删除应用数据会一并清除；" +
                            "已经保存到系统相册的副本不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                storageDialogOpen = false
                                cleanupImagesDialogOpen = true
                            },
                        ) {
                            Text("清理生成图片")
                        }
                        OutlinedButton(onClick = viewModel::refreshUsage) {
                            Text("刷新占用")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { storageDialogOpen = false }) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }


    if (updateDialogOpen) {
        UpdateCheckDialog(
            currentVersionName = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE,
            state = updateState,
            onCheck = updateViewModel::checkManually,
            onDismiss = { updateDialogOpen = false },
        )
    }

    if (cleanupImagesDialogOpen) {
        CleanupImagesDialog(
            onConfirm = { deleteFavorites ->
                viewModel.cleanupImages(deleteFavorites)
            },
            onDismiss = { cleanupImagesDialogOpen = false },
        )
    }
}

/**
 * 设置项行：标题 + 当前值，点进去看详情。
 * [trailing] 为空时是引导箭头；有开关等控件时传控件。
 */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    SettingsNavigationRow(title, subtitle, icon, onClick, modifier, enabled, trailing = trailing)

}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun proxySummaryLabel(
    enabled: Boolean,
    mode: ProxyMode,
    host: String,
    port: Int,
    usedBytesToday: Long,
): String = when {
    !enabled -> stringResource(R.string.settings_proxy_summary_off)
    mode == ProxyMode.PUBLIC ->
        stringResource(R.string.proxy_mode_public) + " · " +
            stringResource(R.string.proxy_usage_today, formatBytes(usedBytesToday))

    else ->
        stringResource(R.string.proxy_mode_custom) +
            if (host.isNotBlank()) " · $host:$port" else ""
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
