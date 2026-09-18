package net.pocketnai.data.network

import android.util.Log
import net.pocketnai.BuildConfig
import net.pocketnai.data.proxy.ProxyStore
import net.pocketnai.data.proxy.PublicProxyNodes
import net.pocketnai.domain.proxy.ProxyEndpoint
import net.pocketnai.domain.proxy.ProxyMode
import net.pocketnai.domain.proxy.ProxyQuotaExceededException
import net.pocketnai.domain.proxy.ProxyType
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketException
import java.net.URI
import kotlin.random.Random

/** 代理相关的 DEBUG 诊断日志 tag（只记结构，不记端点与凭据）。 */
private const val TAG = "PocketNai/Proxy"

/**
 * 代理的运行组件：选节点（含滚动换节点）、配额闸门、失败重试、流量统计。
 *
 * ## 滚动换节点（"3 秒不通就换"）
 * - 代理客户端把 connectTimeout 收紧到 3 秒：连代理、SOCKS 握手、到目标建连都算在内；
 * - 节点**只在连接失败时前进**（[connectFailed] 是 OkHttp 在"路由/代理连接失败"时的回调），
 *   成功的节点会被持续使用，不会每个请求乱跳；
 * - 重试交给 [ProxyFailoverInterceptor]：只在"本次尝试期间代理连接失败过"时换节点重发 ——
 *   请求一旦发出（读取阶段失败）**绝不重试**，生成请求不会重复扣费。
 */
class RotatingProxySelector(
    private val store: ProxyStore,
    private val publicNodes: PublicProxyNodes,
) : ProxySelector() {

    private var publicIndex = -1

    @Volatile
    private var lastConnectFailureAt = 0L

    /** 最近一次"连接代理失败"的时间戳（毫秒，0 = 从未）。 */
    fun lastConnectFailureAt(): Long = lastConnectFailureAt

    /** 当前会被选中的端点（SOCKS 认证要用同一个）；未启用 / 无节点时返回 null。 */
    fun currentEndpoint(): ProxyEndpoint? {
        val settings = store.settings.value
        if (!settings.active) return null
        return when (settings.mode) {
            ProxyMode.CUSTOM ->
                ProxyEndpoint(settings.host, settings.port, settings.username, settings.password)

            ProxyMode.PUBLIC -> {
                val nodes = publicNodes.all()
                if (nodes.isEmpty()) return null
                if (publicIndex < 0) publicIndex = Random.nextInt(nodes.size)
                nodes[publicIndex.mod(nodes.size)]
            }
        }
    }

    override fun select(uri: URI): List<Proxy> {
        val settings = store.settings.value
        val endpoint = currentEndpoint() ?: return NO_PROXY
        if (BuildConfig.DEBUG) {
            // 只记结构：模式与"是否走代理"，不记端点地址（AGENTS.md 日志纪律）。
            Log.d(TAG, "select mode=${settings.mode} proxied=true")
        }

        val type = if (settings.mode == ProxyMode.CUSTOM && settings.type == ProxyType.HTTP) {
            Proxy.Type.HTTP
        } else {
            // 内置公益节点实测只开 SOCKS5。
            Proxy.Type.SOCKS
        }
        return listOf(Proxy(type, InetSocketAddress.createUnresolved(endpoint.host, endpoint.port)))
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
        lastConnectFailureAt = System.currentTimeMillis()
        rotateToNext()
    }

    /** 推进到下一个公益节点（连接失败时自动调用；重试逻辑也会调用）。自定义模式无节点池，忽略。 */
    fun rotateToNext() {
        val nodes = publicNodes.all()
        if (nodes.isNotEmpty()) {
            publicIndex = (publicIndex + 1).mod(nodes.size)
        }
    }

    private companion object {
        val NO_PROXY: List<Proxy> = listOf(Proxy.NO_PROXY)
    }
}

/**
 * 额度闸门：公益模式下当日流量用尽后，直接拒绝新请求。
 *
 * 放在拦截器链最外层（先于所有网络动作），异常由 API 层映射成
 * [net.pocketnai.core.ErrorCode.PROXY_QUOTA_EXCEEDED]，界面据此提示。
 */
class ProxyQuotaInterceptor(private val store: ProxyStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val settings = store.settings.value
        if (settings.active && settings.mode == ProxyMode.PUBLIC && store.quotaExceeded()) {
            throw ProxyQuotaExceededException()
        }
        return chain.proceed(chain.request())
    }
}

/**
 * 连接/传输失败时的换节点重试。
 *
 * 安全性分两级（红线：生成请求绝不重复发送）：
 * - **代理连接失败**（[RotatingProxySelector.lastConnectFailureAt] 在本次尝试期间更新，
 *   意味着请求还没发出去）：任何方法都换节点重试；
 * - **其它 SocketException**（Connection reset、读超时等，无法确定请求是否已送达）：
 *   只有**幂等请求**（GET / HEAD —— 余额、账户、标签建议）才换节点重试；
 *   POST（生成、登录）一律原样抛出，让用户知情后手动重试。
 *
 * 换节点还顺带绕开 OkHttp 的 RouteDatabase：它会把失败过的代理路由记下来、
 * 在同一 client 上反复短路（2026-09-18 实测：一次失败后同 client 的所有请求
 * 都直接失败）。
 */
class ProxyFailoverInterceptor(
    private val selector: RotatingProxySelector,
    private val maxAttempts: Int = 3,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val idempotent = request.method == "GET" || request.method == "HEAD"
        var attempt = 0
        while (true) {
            attempt++
            val startedAt = System.currentTimeMillis()
            try {
                return chain.proceed(request)
            } catch (e: IOException) {
                val proxyConnectFailure = selector.lastConnectFailureAt() >= startedAt
                val retryable = proxyConnectFailure || (idempotent && e is SocketException)
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "attempt $attempt failed: ${e.javaClass.simpleName} " +
                            "msg=${e.message?.take(80)} " +
                            "connectFailure=$proxyConnectFailure idempotent=$idempotent",
                    )
                }
                if (!retryable || attempt >= maxAttempts) throw e
                selector.rotateToNext()
            }
        }
    }
}

/**
 * 公益/自定义代理的流量统计（只挂在代理客户端上，直连不计费）。
 *
 * 用 [EventListener] 的 body 计数而不是 contentLength：流式与未知长度都能覆盖。
 */
class ProxyTrafficListener(private val store: ProxyStore) : EventListener() {

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        store.addTraffic(byteCount)
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        store.addTraffic(byteCount)
    }
}

/**
 * SOCKS5 的用户名/密码认证。
 *
 * **与 OkHttp 的 `proxyAuthenticator` 无关**（那个处理 HTTP 代理的 407 响应）：
 * OkHttp 的 SOCKS 代理是交给 JDK 的 Socket 实现处理的，认证走**全局**
 * `java.net.Authenticator`。
 *
 * ⚠️ 两个实测结论（2026-09-18）：
 * 1. 不注册它、凭据就发不出去（表现为 `SOCKS : authentication failed`，请求快速失败、
 *    流量统计不动）；
 * 2. **Android 的 libcore 把 SOCKS 认证的 requestorType 标成 `SERVER` 而不是 JDK 的
 *    `PROXY`** —— 按类型过滤会导致凭据被吞。这里改为**按端口匹配当前代理端点**
 *    （系统未提供端口时放行），只回答"自己代理"的认证请求。
 */
class SocksProxyAuthenticator(
    private val selector: RotatingProxySelector,
) : Authenticator() {

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val endpoint = selector.currentEndpoint() ?: return null
        val portMatches = requestingPort == -1 || requestingPort == endpoint.port
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "auth callback type=$requestorType portMatch=$portMatches")
        }
        if (!portMatches) return null
        return PasswordAuthentication(endpoint.username, endpoint.password.toCharArray())
    }
}
