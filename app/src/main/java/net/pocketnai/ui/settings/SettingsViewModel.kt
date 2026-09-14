package net.pocketnai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pocketnai.data.repo.AccountBalanceRepository
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.data.security.SessionState
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.settings.SettingsStore
import net.pocketnai.domain.billing.SubscriptionOverride
import net.pocketnai.domain.billing.SubscriptionStatus
import net.pocketnai.domain.billing.SubscriptionStatusResolver
import net.pocketnai.domain.model.ThemeMode

class SettingsViewModel(
    private val repository: GenerationRepository,
    private val settingsStore: SettingsStore,
    private val credentialStore: CredentialStore,
    private val sessionState: SessionState,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    data class UiState(
        val usedBytes: Long = 0L,
        val maintenanceMessage: String? = null,
    )

    val streamingPreviewEnabled: StateFlow<Boolean> = settingsStore.streamingPreviewEnabled

    val themeMode: StateFlow<ThemeMode> = settingsStore.themeMode

    /** 订阅等级的手动指定（默认"自动读取"）。 */
    val subscriptionOverride: StateFlow<SubscriptionOverride> = settingsStore.subscriptionOverride

    /**
     * 有效订阅状态：服务端读数 + 手动指定。
     *
     * 余额仓库是账户级共享实例，这里只读不建第二份缓存（与 [SettingsScreen] 里余额的做法一致）。
     */
    val subscriptionStatus: StateFlow<SubscriptionStatus> = combine(
        accountBalanceRepository.state,
        settingsStore.subscriptionOverride,
    ) { balance, override ->
        val known = balance.knownBalance
        SubscriptionStatusResolver.resolve(
            rawTier = known?.rawTier,
            accountType = known?.accountType,
            expiresAtEpochSeconds = known?.expiresAtEpochSeconds,
            nowEpochSeconds = clock() / 1000L,
            override = override,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_SUBSCRIPTION_TIMEOUT_MS),
        initialValue = SubscriptionStatus.Unknown,
    )

    fun setSubscriptionOverride(override: SubscriptionOverride) {
        settingsStore.setSubscriptionOverride(override)
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsStore.setThemeMode(mode)
    }

    val connected: StateFlow<Boolean> = sessionState.connected

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshUsage()
    }

    fun setStreamingPreviewEnabled(enabled: Boolean) {
        settingsStore.setStreamingPreviewEnabled(enabled)
    }

    fun refreshUsage() {
        viewModelScope.launch {
            _state.value = _state.value.copy(usedBytes = repository.usedBytes())
        }
    }

    /**
     * 手动清理孤立临时文件，并顺带完成已标记删除任务的收尾。
     * 规划书 12 中“缓存上限与手动清理入口”尚未定稿，因此首版只提供这个显式入口。
     */
    fun runMaintenance() {
        viewModelScope.launch {
            val report = repository.cleanupOnStartup()
            repository.purgeDeleted()
            _state.value = _state.value.copy(
                usedBytes = repository.usedBytes(),
                maintenanceMessage = buildString {
                    append("已清理孤立目录 ${report.removedGenerationDirs} 个、")
                    append("临时目录 ${report.removedIncomingDirs} 个")
                    if (report.missingImageFiles > 0) {
                        append("；发现 ${report.missingImageFiles} 个记录对应的图片文件已丢失")
                    }
                },
            )
        }
    }

    fun dismissMaintenanceMessage() {
        _state.value = _state.value.copy(maintenanceMessage = null)
    }

    fun disconnect() {
        credentialStore.clear()
        sessionState.update(false)
    }

    private companion object {
        /**
         * 订阅状态的订阅超时：与生成页一致，界面不可见时停止收集，
         * 免得设置页开过一次就永远挂着一条 combine。
         */
        const val SUBSCRIPTION_SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
