package net.pocketnai.data.proxy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.pocketnai.data.security.SecretBox
import net.pocketnai.domain.proxy.ProxyMode
import net.pocketnai.domain.proxy.ProxySettings
import net.pocketnai.domain.proxy.ProxyType
import net.pocketnai.domain.proxy.PublicProxyQuota
import java.time.LocalDate

/**
 * 代理配置与"公益额度"的本机存储。
 *
 * - 配置字段存普通首选项；**密码单独用 [SecretBox]（Keystore）加密**，明文不落盘；
 * - 公益模式下的每日流量按"本机当日累计"统计（[addTraffic]），跨天自动重置 ——
 *   它是**客户端自律**（每台设备各算各的），账号级上限要在代理服务商后台设置。
 */
class ProxyStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secretBox = SecretBox()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<ProxySettings> = _settings.asStateFlow()

    private val _usedBytesToday = MutableStateFlow(readTodayUsage())
    val usedBytesToday: StateFlow<Long> = _usedBytesToday.asStateFlow()

    fun update(settings: ProxySettings) {
        val host = settings.host.trim()
        val username = settings.username.trim()
        val editor = prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_MODE, settings.mode.name)
            .putString(KEY_TYPE, settings.type.name)
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_USERNAME, username)
        if (settings.password.isNotEmpty()) {
            editor.putString(KEY_PASSWORD_ENC, secretBox.encrypt(settings.password))
        } else {
            editor.remove(KEY_PASSWORD_ENC)
        }
        editor.apply()
        _settings.value = settings.copy(host = host, username = username)
    }

    /** 今日是否已用超公益额度（网络层据此拒绝新请求）。 */
    fun quotaExceeded(): Boolean = _usedBytesToday.value >= PublicProxyQuota.DAILY_LIMIT_BYTES

    /** 记录一段代理流量（字节）。跨天时从头累计。 */
    fun addTraffic(bytes: Long) {
        if (bytes <= 0L) return
        val today = todayKey()
        val storedDay = prefs.getString(KEY_USAGE_DAY, null)
        val storedBytes = prefs.getLong(KEY_USAGE_BYTES, 0L)
        val next = (if (storedDay == today) storedBytes else 0L) + bytes
        prefs.edit().putString(KEY_USAGE_DAY, today).putLong(KEY_USAGE_BYTES, next).apply()
        _usedBytesToday.value = next
    }

    private fun readSettings(): ProxySettings {
        val mode = runCatching { ProxyMode.valueOf(prefs.getString(KEY_MODE, null).orEmpty()) }
            .getOrDefault(ProxyMode.PUBLIC)
        val type = runCatching { ProxyType.valueOf(prefs.getString(KEY_TYPE, null).orEmpty()) }
            .getOrDefault(ProxyType.SOCKS5)
        return ProxySettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            mode = mode,
            type = type,
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, 0),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD_ENC, null)?.let(secretBox::decrypt).orEmpty(),
        )
    }

    private fun readTodayUsage(): Long =
        if (prefs.getString(KEY_USAGE_DAY, null) == todayKey()) {
            prefs.getLong(KEY_USAGE_BYTES, 0L)
        } else {
            0L
        }

    private fun todayKey(): String = LocalDate.now().toString()

    private companion object {
        const val PREFS_NAME = "pocketnai_proxy"
        const val KEY_ENABLED = "enabled"
        const val KEY_MODE = "mode"
        const val KEY_TYPE = "type"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD_ENC = "password_enc"
        const val KEY_USAGE_DAY = "usage_day"
        const val KEY_USAGE_BYTES = "usage_bytes"
    }
}
