package net.pocketnai.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.pocketnai.domain.billing.SubscriptionOverride
import net.pocketnai.domain.model.ThemeMode

/**
 * 本地设置。
 *
 * 这里不保存任何敏感内容：Token 走 [net.pocketnai.data.security.CredentialStore]，
 * 历史与参数走 Room，本类只存行为开关。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _streamingPreviewEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_STREAMING_ENABLED, true),
    )

    /** 流式中间预览开关，设置页可直接观察。 */
    val streamingPreviewEnabled: StateFlow<Boolean> = _streamingPreviewEnabled.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.fromNameOrDefault(prefs.getString(KEY_THEME_MODE, null)),
    )

    /** 主题模式，默认跟随系统。 */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    private val _subscriptionOverride = MutableStateFlow(
        SubscriptionOverride.fromNameOrDefault(prefs.getString(KEY_SUBSCRIPTION_OVERRIDE, null)),
    )

    /**
     * 订阅等级的手动指定（默认"自动读取"）。
     *
     * 这是计费的一部分，因此**必须持久化**：用户设成 Opus 之后重启应用，
     * 生成按钮上的报价不能又变回"无订阅"的算法。
     */
    val subscriptionOverride: StateFlow<SubscriptionOverride> = _subscriptionOverride.asStateFlow()

    fun setSubscriptionOverride(override: SubscriptionOverride) {
        prefs.edit().putString(KEY_SUBSCRIPTION_OVERRIDE, override.name).apply()
        _subscriptionOverride.value = override
    }

    fun setStreamingPreviewEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STREAMING_ENABLED, enabled).apply()
        _streamingPreviewEnabled.value = enabled
        if (enabled) resetStreamingFailures()
    }

    /**
     * 记录一次流式协议失败。
     *
     * 规划书 6.3：连续失败达到阈值后，只关闭**未来任务**的流式预览，
     * 不重试刚刚失败的付费任务。返回 true 表示本次调用触发了自动关闭。
     */
    fun recordStreamingFailure(): Boolean {
        val failures = prefs.getInt(KEY_STREAMING_FAILURES, 0) + 1
        prefs.edit().putInt(KEY_STREAMING_FAILURES, failures).apply()
        if (failures >= STREAMING_FAILURE_THRESHOLD && _streamingPreviewEnabled.value) {
            setStreamingPreviewEnabled(false)
            return true
        }
        return false
    }

    fun resetStreamingFailures() {
        prefs.edit().putInt(KEY_STREAMING_FAILURES, 0).apply()
    }

    fun streamingFailures(): Int = prefs.getInt(KEY_STREAMING_FAILURES, 0)

    private companion object {
        const val PREFS_NAME = "pocketnai_settings"
        const val KEY_STREAMING_ENABLED = "streaming_preview_enabled"
        const val KEY_STREAMING_FAILURES = "streaming_consecutive_failures"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SUBSCRIPTION_OVERRIDE = "subscription_override"

        /** 连续失败阈值，需在协议探针阶段用真实数据确认（规划书 6.3）。 */
        const val STREAMING_FAILURE_THRESHOLD = 3
    }
}
