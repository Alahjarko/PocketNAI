package net.pocketnai.ui.settings

import androidx.annotation.StringRes
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
import net.pocketnai.domain.billing.SubscriptionOverride
import net.pocketnai.domain.billing.SubscriptionSource
import net.pocketnai.domain.billing.SubscriptionStatus
import net.pocketnai.domain.billing.SubscriptionTier
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.billing.AnlasLedgerDialog
import net.pocketnai.ui.billing.BalanceDetailDialog
import net.pocketnai.ui.billing.compactLabel
import net.pocketnai.ui.common.messageRes
import net.pocketnai.ui.update.UpdateViewModel

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

        var accountSwitchDialogOpen by remember { mutableStateOf(false) }
        var ledgerDialogOpen by remember { mutableStateOf(false) }

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

        if (ledgerDialogOpen) {
            AnlasLedgerDialog(
                ledgerRepository = container.anlasLedgerRepository,
                onDismiss = { ledgerDialogOpen = false },
            )
        }

        // ---- 连接状态 ----
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

        HorizontalDivider()

        // ---- 账户余额 ----
        BalanceSection(
            state = balanceState,
            connected = connected,
            subscriptionStatus = subscriptionStatus,
            onRefresh = {
                // 手动刷新无视缓存；余额刷新失败不影响生成，也不会覆盖生成错误。
                scope.launch {
                    container.accountBalanceRepository.refresh(
                        reason = BalanceRefreshReason.USER_REQUESTED,
                        force = true,
                    )
                }
            },
            onViewLedger = { ledgerDialogOpen = true },
        )

        HorizontalDivider()

        // ---- 订阅等级 ----
        SubscriptionSection(
            override = subscriptionOverride,
            status = subscriptionStatus,
            onOverrideChange = viewModel::setSubscriptionOverride,
        )

        HorizontalDivider()

        // ---- 网络代理（公益节点 / 自定义） ----
        val proxySettings by container.proxyStore.settings.collectAsStateWithLifecycle()
        val proxyUsedBytes by container.proxyStore.usedBytesToday.collectAsStateWithLifecycle()
        ProxySection(
            settings = proxySettings,
            usedBytesToday = proxyUsedBytes,
            nodeCount = container.publicProxyNodes.all().size,
            onSettingsChange = container.proxyStore::update,
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
            var cleanupImagesDialogOpen by remember { mutableStateOf(false) }

            if (cleanupImagesDialogOpen) {
                CleanupImagesDialog(
                    onConfirm = { deleteFavorites ->
                        viewModel.cleanupImages(deleteFavorites)
                    },
                    onDismiss = { cleanupImagesDialogOpen = false },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { cleanupImagesDialogOpen = true }) {
                    Text("清理生成图片")
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

        // ---- 检查更新 ----
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.update_section_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "${stringResource(R.string.settings_version)} ${BuildConfig.VERSION_NAME} " +
                    "(build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_update_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}

internal fun formatBytes(bytes: Long): String {
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
    subscriptionStatus: SubscriptionStatus,
    onRefresh: () -> Unit,
    onViewLedger: () -> Unit,
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
            OutlinedButton(onClick = onViewLedger) {
                Text("消耗流水")
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
            subscriptionStatus = subscriptionStatus,
            onRefresh = onRefresh,
            onViewLedger = {
                dialogOpen = false
                onViewLedger()
            },
            onDismiss = { dialogOpen = false },
        )
    }
}

/**
 * 订阅等级区块。
 *
 * 存在的原因：服务端读数不一定与用户实际买到的权益一致（本机账号实测
 * `tier 0 / active false / accountType RETAIL`），而订阅等级直接决定
 * "这次生成免不免费、报多少 Anlas"。自动读取是默认，手动指定是兜底 ——
 * 与其让报价静默算错，不如让用户能一句话纠正它。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionSection(
    override: SubscriptionOverride,
    status: SubscriptionStatus,
    onOverrideChange: (SubscriptionOverride) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "订阅等级",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = buildString {
                append("当前：")
                append(if (status.subscribed) status.tier.displayName() else "无订阅")
                append(when (status.source) {
                    SubscriptionSource.REMOTE -> "（读取自 NovelAI）"
                    SubscriptionSource.MANUAL -> "（本机手动指定）"
                })
                append("。订阅等级决定 Opus 免费单张与计价方式。")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
