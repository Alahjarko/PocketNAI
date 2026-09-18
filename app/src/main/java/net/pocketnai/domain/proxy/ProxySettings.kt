package net.pocketnai.domain.proxy

/** 代理节点来源：内置公益节点 / 用户自填。总开关是 [ProxySettings.enabled]。 */
enum class ProxyMode { PUBLIC, CUSTOM }

/** 支持的代理协议。实测用户的公益节点只开 SOCKS5（HTTP 代理协议全不通），但自定义模式两种都提供。 */
enum class ProxyType { SOCKS5, HTTP }

/** 一个代理端点（含凭据，只存在于内存与本机加密存储中，绝不落日志）。 */
data class ProxyEndpoint(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    /** 展示用标识：只含主机与端口，绝不含凭据。 */
    val label: String get() = "$host:$port"
}

/**
 * 当前生效的代理配置。
 *
 * [password] 只在本机内存里流转：读配置时由 [net.pocketnai.data.proxy.ProxyStore]
 * 解密填入，写配置时再拆出去加密 —— 明文密码不落盘、不进日志。
 */
data class ProxySettings(
    val enabled: Boolean = false,
    val mode: ProxyMode = ProxyMode.PUBLIC,
    val type: ProxyType = ProxyType.SOCKS5,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    /** 自定义模式是否填全了必填项。 */
    val customReady: Boolean get() = host.isNotBlank() && port in 1..65535

    /** 实际会不会走代理：开关打开、且所选模式可用（公益恒可用；自定义要填完整）。 */
    val active: Boolean
        get() = enabled && when (mode) {
            ProxyMode.PUBLIC -> true
            ProxyMode.CUSTOM -> customReady
        }

    companion object {
        /**
         * 解析剪贴板导入的一行：`host:port:user:pass`（代理服务商常见的分发格式）。
         * 认不出返回 null —— 不猜、不部分接受。
         */
        fun parseClipboardLine(line: String): ProxySettings? {
            val parts = line.trim().split(':')
            if (parts.size != 4) return null
            val host = parts[0].trim()
            val port = parts[1].trim().toIntOrNull() ?: return null
            val username = parts[2].trim()
            val password = parts[3].trim()
            if (host.isBlank() || port !in 1..65535) return null
            return ProxySettings(
                mode = ProxyMode.CUSTOM,
                type = ProxyType.SOCKS5,
                host = host,
                port = port,
                username = username,
                password = password,
            )
        }
    }
}

/** 公益节点的每日流量额度（按本机统计）。 */
object PublicProxyQuota {
    /** 单设备每日上限：750 MB，跨天自动重置。服务端总限额需要在代理服务商后台另行设置。 */
    const val DAILY_LIMIT_BYTES: Long = 750L * 1024 * 1024
}

/**
 * 今日公益额度已用完。
 *
 * 由网络层在发请求前抛出（只有公益模式受限）；继承 [java.io.IOException]
 * 是为了走 OkHttp 既有的失败通道，由 API 层映射成
 * [net.pocketnai.core.ErrorCode.PROXY_QUOTA_EXCEEDED]。
 */
class ProxyQuotaExceededException : java.io.IOException("public proxy daily quota exceeded")
