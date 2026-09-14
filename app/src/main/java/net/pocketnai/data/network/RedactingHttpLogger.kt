package net.pocketnai.data.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import net.pocketnai.core.LogRedaction

/**
 * 网络日志拦截器（规划书第 10 节）。
 *
 * 有意做到“能力上的不可能”而不是“靠自觉”：
 * - 只记录方法、路径、状态码和耗时；
 * - 从不读取 `Authorization`；[LogRedaction.sanitizeHeader] 仅用于万一需要输出头的场合；
 * - 从不读取请求体或响应体，因此 Prompt 原文不可能进入日志；
 * - Debug 与 Release 走同一段代码，没有“Debug 打印明文”的分支。
 *
 * 项目**不引入** OkHttp 的 HttpLoggingInterceptor，避免有人顺手打开 BODY 级别。
 */
class RedactingHttpLogger(private val enabled: Boolean) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!enabled) return chain.proceed(request)

        val startedAt = System.nanoTime()
        val response = chain.proceed(request)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        Log.d(
            TAG,
            "http ${request.method} ${request.url.encodedPath} -> ${response.code} (${elapsedMs}ms)",
        )
        return response
    }

    private companion object {
        const val TAG = "PocketNAI/Http"
    }
}

/**
 * 供调试期排查“某个头是否被带上”使用。永远不要把返回值写到日志时忘了它已经脱敏。
 */
internal fun sanitizedHeaderForDebug(name: String, value: String): String =
    LogRedaction.sanitizeHeader(name, value)
