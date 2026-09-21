package net.pocketnai.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import net.pocketnai.domain.billing.SubscriptionOverride
import net.pocketnai.domain.billing.SubscriptionSource
import net.pocketnai.domain.billing.SubscriptionStatus
import net.pocketnai.domain.billing.SubscriptionTier
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.domain.proxy.ProxyMode
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.billing.AnlasLedgerDialog
import net.pocketnai.ui.billing.BalanceDetailDialog
import net.pocketnai.ui.billing.compactLabel
import net.pocketnai.ui.common.messageRes
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
    updateViewModel: UpdateViewModel,
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
    val streamingEnabled by viewModel.streamingPreviewEnabled.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val subscriptionOverride by viewModel.subscriptionOverride.collectAsStateWithLifecycle()
    val subscriptionStatus by viewModel.subscriptionStatus.collectAsStateWithLifecycle()

    // 余额与生成页共用同一个仓库实例，不另建一份网络状态或缓存（余额规划 §11.4）。
    val balanceState by container.accountBalanceRepository.state.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var accountSwitchDialogOpen by remember { mutableStateOf(false) }
    var balanceDialogOpen by remember { mutableStateOf(false) }
    var ledgerDialogOpen by remember { mutableStateOf(false) }
    var subscriptionDialogOpen by remember { mutableStateOf(false) }
    var proxyDialogOpen by remember { mutableStateOf(false) }
    var themeDialogOpen by remember { mutableStateOf(false) }
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

        // ---- 账号（保留卡片：这是身份区，不是普通设置项） ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val activeAccount = remember(connected, accountSwitchDialogOpen) {
                    container.credentialStore.listAccounts().firstOrNull { it.isActive }
                }

                Text(
                    text = if (connected) {
                        "已连接 ${activeAccount?.let { "· ${it.name} (${it.tokenFingerprint})" }.orEmpty()}"
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
                    OutlinedButton(onClick = { accountSwitchDialogOpen = true }) {
                        Text("切换/管理账号")
                    }
                    if (connected) {
                        OutlinedButton(onClick = viewModel::disconnect) {
                            Text(stringResource(R.string.connect_delete))
                        }
                    } else {
                        OutlinedButton(onClick = onRequestConnect) {
                            Text(stringResource(R.string.connect_title))
                        }
                    }
                }
            }
        }

        // ---- 设置项分组列表 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingsRow(
                    title = stringResource(R.string.balance_title),
                    subtitle = if (!connected) {
                        stringResource(R.string.balance_never_loaded)
                    } else {
                        balanceState.compactLabel() ?: stringResource(R.string.balance_never_loaded)
                    },
                    onClick = { balanceDialogOpen = true },
                    enabled = connected,
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_balance_ledger),
                    subtitle = stringResource(R.string.settings_ledger_subtitle),
                    onClick = { ledgerDialogOpen = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_subscription_title),
                    subtitle = subscriptionSummary(subscriptionStatus),
                    onClick = { subscriptionDialogOpen = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.proxy_section_title),
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
                    title = stringResource(R.string.settings_appearance),
                    subtitle = stringResource(themeMode.labelRes()),
                    onClick = { themeDialogOpen = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_streaming_preview),
                    subtitle = stringResource(R.string.settings_streaming_preview_desc),
                    onClick = { viewModel.setStreamingPreviewEnabled(!streamingEnabled) },
                    trailing = {
                        Switch(
                            checked = streamingEnabled,
                            onCheckedChange = viewModel::setStreamingPreviewEnabled,
                        )
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.settings_storage),
                    subtitle = "私有历史占用 ${formatBytes(state.usedBytes)}",
                    onClick = { storageDialogOpen = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.update_section_title),
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

    if (balanceDialogOpen) {
        BalanceDetailDialog(
            state = balanceState,
            subscriptionStatus = subscriptionStatus,
            onRefresh = refreshBalance,
            onViewLedger = {
                balanceDialogOpen = false
                ledgerDialogOpen = true
            },
            onDismiss = { balanceDialogOpen = false },
        )
    }

    if (ledgerDialogOpen) {
        AnlasLedgerDialog(
            ledgerRepository = container.anlasLedgerRepository,
            onDismiss = { ledgerDialogOpen = false },
        )
    }

    if (subscriptionDialogOpen) {
        SubscriptionDialog(
            override = subscriptionOverride,
            status = subscriptionStatus,
            onOverrideChange = viewModel::setSubscriptionOverride,
            onDismiss = { subscriptionDialogOpen = false },
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
                            usedBytesToday = proxyUsedBytes,
                            nodeCount = container.publicProxyNodes.all().size,
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

    if (themeDialogOpen) {
        AlertDialog(
            onDismissRequest = { themeDialogOpen = false },
            title = { Text(stringResource(R.string.settings_appearance)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            },
            confirmButton = {
                TextButton(onClick = { themeDialogOpen = false }) {
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
        AlertDialog(
            onDismissRequest = { updateDialogOpen = false },
            title = { Text(stringResource(R.string.update_section_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${stringResource(R.string.settings_version)} " +
                            "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.settings_update_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = updateViewModel::checkManually,
                        enabled = !updateState.checking,
                    ) {
                        Text(
                            stringResource(
                                if (updateState.checking) {
                                    R.string.update_checking
                                } else {
                                    R.string.update_check_action
                                },
                            ),
                        )
                    }
                    when {
                        updateState.upToDateNotice -> Text(
                            text = stringResource(R.string.update_up_to_date),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        updateState.error != null -> Text(
                            text = stringResource(updateState.error!!.messageRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { updateDialogOpen = false }) {
                    Text(stringResource(R.string.action_done))
                }
            },
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = trailing ?: {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun subscriptionSummary(status: SubscriptionStatus): String = buildString {
    append(if (status.subscribed) status.tier.displayName() else "无订阅")
    append(
        when (status.source) {
            SubscriptionSource.REMOTE -> "（读取自 NovelAI）"
            SubscriptionSource.MANUAL -> "（本机手动指定）"
        },
    )
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

/**
 * 订阅等级对话框。
 *
 * 手动指定存在的原因：服务端读数不一定与用户实际买到的权益一致（本机账号实测
 * `tier 0 / active false / accountType RETAIL`），而订阅等级直接决定
 * "这次生成免不免费、报多少 Anlas"。自动读取是默认，手动指定是兜底 ——
 * 与其让报价静默算错，不如让用户能一句话纠正它。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionDialog(
    override: SubscriptionOverride,
    status: SubscriptionStatus,
    onOverrideChange: (SubscriptionOverride) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_subscription_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = buildString {
                        append("当前：")
                        append(subscriptionSummary(status))
                        append("。订阅等级决定 Opus 免费单张与计价方式。")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 五个选项在窄屏上一行放不下，用 FlowRow 换行而不是横向滚动：
                // 全部可见才谈得上"选哪个"，藏起来的选项等于不存在。
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubscriptionOverride.entries.forEach { option ->
                        FilterChip(
                            selected = override == option,
                            onClick = { onOverrideChange(option) },
                            label = { Text(option.displayName) },
                        )
                    }
                }
                Text(
                    text = "默认「自动读取」。只有当服务端读不到、或你确认读数不对时才需要手动指定；" +
                        "手动值会直接决定生成按钮上的报价。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "关于「20% 优惠」：官方定价页上的 20% 是购买 Anlas 时的折扣" +
                        "（Anlas Purchase Discount：充值时少花美元），" +
                        "不是生成扣费打八折。因此这里的报价不打折 —— " +
                        "打折会把实际扣费报少。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        },
    )
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun SubscriptionTier.displayName(): String = when (this) {
    SubscriptionTier.None -> "无订阅"
    SubscriptionTier.Tablet -> "Tablet"
    SubscriptionTier.Scroll -> "Scroll"
    SubscriptionTier.Opus -> "Opus"
    is SubscriptionTier.Unknown -> "未知（读数 $rawValue）"
}

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
