package net.pocketnai.ui.settings

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import net.pocketnai.ui.common.ShortcutCheatsheetDialog
import net.pocketnai.ui.update.UpdateCheckDialog
import net.pocketnai.ui.update.UpdateViewModel

private enum class SettingsCategory(
    val label: String,
    val icon: ImageVector,
) {
    ACCOUNTS("账号与生图服务", Icons.Default.AccountCircle),
    FEATURES("功能与键鼠", Icons.Default.Tune),
    APPEARANCE("外观与显示", Icons.Default.ColorLens),
    DATA_ABOUT("数据与关于", Icons.Default.Storage),
}

/**
 * 设置页：
 * - 窄屏（< 720.dp）：单列分组列表，保持既有轻量布局；
 * - 宽屏（>= 720.dp）：左侧 220.dp 分类导航 + 右侧可滚动详情页。
 */
@Composable
fun SettingsScreen(
    onRequestConnect: () -> Unit,
    onOpenStatistics: () -> Unit,
    updateViewModel: UpdateViewModel,
    onOpenChatSettings: () -> Unit,
    openImageServiceOnEntry: Boolean = false,
    onOpenLocalMcp: () -> Unit = {},
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
    val themeAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val subscriptionStatus by viewModel.subscriptionStatus.collectAsStateWithLifecycle()

    // 余额与生成页共用同一个仓库实例，不另建一份网络状态或缓存（余额规划 §11.4）。
    val balanceState by container.accountBalanceRepository.state.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val chatEnabled by container.settingsStore.chatEnabled.collectAsStateWithLifecycle()
    val chatEnterToSend by container.settingsStore.chatEnterToSend.collectAsStateWithLifecycle()
    val artistLabEnabled by container.settingsStore.artistLabEnabled.collectAsStateWithLifecycle()
    val artistPromptSplitEnabled by container.settingsStore.artistPromptSplitEnabled.collectAsStateWithLifecycle()
    val sfwMode by container.settingsStore.sfwMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var accountSwitchDialogOpen by remember { mutableStateOf(false) }
    var proxyDialogOpen by remember { mutableStateOf(false) }
    var storageDialogOpen by remember { mutableStateOf(false) }
    var updateDialogOpen by remember { mutableStateOf(false) }
    var cleanupImagesDialogOpen by remember { mutableStateOf(false) }
    var shortcutsDialogOpen by remember { mutableStateOf(false) }
    var selectedCategoryName by rememberSaveable { mutableStateOf(SettingsCategory.ACCOUNTS.name) }
    val selectedCategory = remember(selectedCategoryName) {
        SettingsCategory.entries.firstOrNull { it.name == selectedCategoryName }
            ?: SettingsCategory.ACCOUNTS
    }
    var pendingOpenImageService by rememberSaveable { mutableStateOf(openImageServiceOnEntry) }
    LaunchedEffect(openImageServiceOnEntry) {
        if (openImageServiceOnEntry) {
            selectedCategoryName = SettingsCategory.ACCOUNTS.name
            pendingOpenImageService = true
        }
    }
    LaunchedEffect(pendingOpenImageService) {
        if (pendingOpenImageService) {
            pendingOpenImageService = false
        }
    }

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

    val haptics = net.pocketnai.ui.motion.rememberPocketHaptics()
    val activeAccount = remember(connected, accountSwitchDialogOpen) {
        container.credentialStore.listAccounts().firstOrNull { it.isActive }
    }

    val proxyRow: @Composable () -> Unit = {
        SettingsRow(
            title = stringResource(R.string.proxy_section_title),
            icon = Icons.Default.Language,
            active = proxySettings.enabled,
            subtitle = proxySummaryLabel(
                enabled = proxySettings.enabled,
                mode = proxySettings.mode,
                host = proxySettings.host,
                port = proxySettings.port,
                usedBytesToday = proxyUsedBytes,
            ),
            onClick = {
                haptics.tick()
                proxyDialogOpen = true
            },
            trailing = {
                Switch(
                    checked = proxySettings.enabled,
                    onCheckedChange = {
                        haptics.tick()
                        container.proxyStore.update(proxySettings.copy(enabled = it))
                    },
                )
            },
        )
    }

    val chatEnterToSendRow: @Composable () -> Unit = {
        SettingsRow(
            title = "外接键盘 Enter 直接发送",
            icon = Icons.AutoMirrored.Filled.KeyboardReturn,
            active = chatEnterToSend,
            subtitle = if (chatEnterToSend) {
                "Enter 发送消息，Shift + Enter 换行"
            } else {
                "Ctrl + Enter 发送消息，Enter 换行"
            },
            onClick = {
                haptics.tick()
                container.settingsStore.setChatEnterToSend(!chatEnterToSend)
            },
            trailing = {
                Switch(
                    checked = chatEnterToSend,
                    onCheckedChange = {
                        haptics.tick()
                        container.settingsStore.setChatEnterToSend(it)
                    },
                )
            },
        )
    }

    val shortcutsRow: @Composable () -> Unit = {
        SettingsRow(
            title = "键鼠快捷键一览",
            icon = Icons.Default.Keyboard,
            subtitle = "画廊、生成、对话与重绘快捷操作（Ctrl + /）",
            onClick = {
                haptics.tick()
                shortcutsDialogOpen = true
            },
        )
    }

    val featureRows: @Composable () -> Unit = {
        SettingsRow(
            title = "实验性对话",
            icon = Icons.Default.ChatBubbleOutline,
            active = chatEnabled,
            subtitle = if (chatEnabled) "已在导航栏显示对话" else "开启后在导航栏显示对话",
            onClick = {
                haptics.tick()
                container.settingsStore.setChatEnabled(!chatEnabled)
            },
            trailing = {
                Switch(
                    checked = chatEnabled,
                    onCheckedChange = {
                        haptics.tick()
                        container.settingsStore.setChatEnabled(it)
                    },
                )
            },
        )
        SettingsDivider()
        SettingsRow(
            title = "连接与人格",
            icon = Icons.Default.Hub,
            subtitle = "模型服务、人格与工具",
            onClick = {
                haptics.tick()
                onOpenChatSettings()
            },
        )
        SettingsDivider()
        SettingsRow(
            title = "本机助手连接",
            icon = Icons.Default.Devices,
            subtitle = "MCP · 供 RikkaHub 等本机助手调用",
            onClick = {
                haptics.tick()
                onOpenLocalMcp()
            },
        )
        SettingsDivider()
        SettingsRow(
            title = "独立画师提示词框",
            icon = Icons.Default.Brush,
            active = artistPromptSplitEnabled,
            subtitle = "自动剥离 artist: 标签到独立框，提交时合并",
            onClick = {
                haptics.tick()
                container.settingsStore.setArtistPromptSplitEnabled(!artistPromptSplitEnabled)
            },
            trailing = {
                Switch(
                    checked = artistPromptSplitEnabled,
                    onCheckedChange = {
                        haptics.tick()
                        container.settingsStore.setArtistPromptSplitEnabled(it)
                    },
                )
            },
        )
        SettingsDivider()
        SettingsRow(
            title = "抽卡实验室（实验）",
            icon = Icons.Default.Palette,
            active = artistLabEnabled,
            subtitle = "随机画师串 · 固定种子 · 收藏与批次清理",
            onClick = {
                haptics.tick()
                container.settingsStore.setArtistLabEnabled(!artistLabEnabled)
            },
            trailing = {
                Switch(
                    checked = artistLabEnabled,
                    onCheckedChange = {
                        haptics.tick()
                        container.settingsStore.setArtistLabEnabled(it)
                    },
                )
            },
        )
    }

    val statisticsRow: @Composable () -> Unit = {
        SettingsRow(
            title = stringResource(R.string.settings_balance_ledger),
            icon = Icons.Default.QueryStats,
            subtitle = stringResource(R.string.settings_ledger_subtitle),
            onClick = {
                haptics.tick()
                onOpenStatistics()
            },
        )
    }

    val storageRow: @Composable () -> Unit = {
        SettingsRow(
            title = stringResource(R.string.settings_storage),
            icon = Icons.Default.Storage,
            subtitle = "私有历史占用 ${formatBytes(state.usedBytes)}",
            onClick = {
                haptics.tick()
                storageDialogOpen = true
            },
        )
    }

    val updateRow: @Composable () -> Unit = {
        SettingsRow(
            title = stringResource(R.string.update_section_title),
            icon = Icons.Default.SystemUpdate,
            subtitle = "${stringResource(R.string.settings_version)} " +
                "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            onClick = {
                haptics.tick()
                updateDialogOpen = true
            },
        )
    }

    val maintenanceBanner: @Composable () -> Unit = {
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 720.dp
        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    SettingsCategory.entries.forEach { category ->
                        SettingsCategoryRailItem(
                            category = category,
                            selected = category == selectedCategory,
                            onClick = {
                                haptics.tick()
                                selectedCategoryName = category.name
                            },
                        )
                    }
                }

                VerticalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )

                androidx.compose.animation.AnimatedContent(
                    targetState = selectedCategory,
                    label = "settings-category-pane",
                    transitionSpec = {
                        val forward = targetState.ordinal >= initialState.ordinal
                        val dir = if (forward) 1 else -1
                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { dir * (it / 24).coerceIn(12, 36) })
                            .togetherWith(
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(130)) +
                                    androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(160)) { -dir * (it / 28).coerceIn(10, 28) }
                            )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) { activeCategory ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 18.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 760.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = activeCategory.label,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )

                            when (activeCategory) {
                                SettingsCategory.ACCOUNTS -> {
                                    net.pocketnai.ui.openai.OpenAiImageSettingsRow(
                                        initiallyOpen = pendingOpenImageService,
                                    )
                                    SettingsSection("NovelAI 账户")
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
                                    SettingsSection("网络连接")
                                    SettingsCard {
                                        proxyRow()
                                    }
                                }

                                SettingsCategory.FEATURES -> {
                                    SettingsSection("外接键盘与鼠标")
                                    SettingsCard {
                                        chatEnterToSendRow()
                                        SettingsDivider()
                                        shortcutsRow()
                                    }
                                    SettingsSection("实验性与连接")
                                    SettingsCard {
                                        featureRows()
                                    }
                                }

                                SettingsCategory.APPEARANCE -> {
                                    ColorModeCard(
                                        mode = themeMode,
                                        onSelect = viewModel::setThemeMode,
                                        accent = themeAccent,
                                        onSelectAccent = viewModel::setThemeAccent,
                                    )
                                    SfwModeCard(sfwMode, container.settingsStore::setSfwMode)
                                }

                                SettingsCategory.DATA_ABOUT -> {
                                    SettingsCard {
                                        statisticsRow()
                                        SettingsDivider()
                                        storageRow()
                                        SettingsDivider()
                                        updateRow()
                                    }
                                }
                            }

                            maintenanceBanner()
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                )

                net.pocketnai.ui.openai.OpenAiImageSettingsRow(initiallyOpen = pendingOpenImageService)

                SettingsSection("NovelAI 账户")
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

                SettingsSection("外观与显示")
                ColorModeCard(
                    mode = themeMode,
                    onSelect = viewModel::setThemeMode,
                    accent = themeAccent,
                    onSelectAccent = viewModel::setThemeAccent,
                )
                SfwModeCard(sfwMode, container.settingsStore::setSfwMode)

                // ---- 设置项分组列表 ----
                SettingsSection("功能与数据")
                SettingsCard {
                    statisticsRow()
                    SettingsDivider()
                    proxyRow()
                    SettingsDivider()
                    featureRows()
                    SettingsDivider()
                    chatEnterToSendRow()
                    SettingsDivider()
                    shortcutsRow()
                    SettingsDivider()
                    storageRow()
                    SettingsDivider()
                    updateRow()
                }

                maintenanceBanner()
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

    if (shortcutsDialogOpen) {
        ShortcutCheatsheetDialog(
            chatEnterToSend = chatEnterToSend,
            onDismiss = { shortcutsDialogOpen = false },
        )
    }
}

@Composable
private fun SettingsCategoryRailItem(
    category: SettingsCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        modifier = Modifier
            .fillMaxWidth()
            .settingsClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = category.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    active: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    SettingsNavigationRow(title, subtitle, icon, onClick, modifier, enabled, active = active, trailing = trailing)
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
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

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
