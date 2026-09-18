package net.pocketnai.data.network

import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.domain.proxy.ProxyQuotaExceededException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * HTTP / 传输层错误到 [ErrorCode] 的映射（规划书 9.1）。
 *
 * 只依赖 JDK 的网络异常类型，不依赖 OkHttp，因此可以直接在单元测试里断言映射结果。
 * 具体的用户文案由 UI 层查 strings.xml，本类只负责分类与脱敏。
 */
object NovelAiErrorMapper {

    /** 错误体最多保留的字符数，避免把整个响应塞进数据库。 */
    private const val MAX_DETAIL_CHARS = 400

    fun fromHttpStatus(status: Int, body: String?, correlationId: String?): AppError {
        val hint = body?.lowercase().orEmpty()
        val code = when {
            status == 401 || status == 403 -> ErrorCode.TOKEN_INVALID
            status == 402 -> ErrorCode.INSUFFICIENT_ANLAS
            status == 429 -> ErrorCode.RATE_LIMITED
            status == 400 || status == 422 -> classifyBadRequest(hint)
            status in 500..599 -> ErrorCode.SERVER_ERROR
            else -> ErrorCode.UNKNOWN
        }
        return AppError(
            code = code,
            correlationId = correlationId,
            detail = "HTTP $status${body?.let { ": ${it.take(MAX_DETAIL_CHARS)}" }.orEmpty()}",
        )
    }

    /**
     * NovelAI 对“额度不足”“订阅不支持该模型”“接口已迁移”等情况也可能返回 400，
     * 因此需要在 400 的响应体里做一次关键词分类。分类失败时保守地归为参数无效。
     */
    private fun classifyBadRequest(lowercasedBody: String): ErrorCode = when {
        // NovelAI 在接口迁移时会直接这样要求客户端，例如：
        // "Please refresh NovelAI.net. If using a third-party tool, update to the image URL."
        "update to the image url" in lowercasedBody || "refresh novelai" in lowercasedBody ->
            ErrorCode.CLIENT_UPDATE_REQUIRED

        "anlas" in lowercasedBody && ("insufficient" in lowercasedBody || "not enough" in lowercasedBody) ->
            ErrorCode.INSUFFICIENT_ANLAS

        "subscription" in lowercasedBody || "tier" in lowercasedBody || "not available" in lowercasedBody ->
            ErrorCode.MODEL_UNAVAILABLE

        else -> ErrorCode.INVALID_PARAMS
    }

    /**
     * 传输层异常映射。
     *
     * 超时与连接中断统一归为 [ErrorCode.TIMEOUT_UNCERTAIN]：服务端可能已经接受并开始计费，
     * 因此规划书 9.2 要求这种情况禁止自动重试，必须先让用户知情。
     */
    fun fromTransportError(cause: Throwable): AppError {
        val code = when (cause) {
            // 公益代理额度用尽：不是网络故障，文案要指回"今日额度"。
            is ProxyQuotaExceededException -> ErrorCode.PROXY_QUOTA_EXCEEDED
            is SocketTimeoutException -> ErrorCode.TIMEOUT_UNCERTAIN
            is UnknownHostException -> ErrorCode.NETWORK_UNAVAILABLE
            is ConnectException -> ErrorCode.NETWORK_UNAVAILABLE
            is NoRouteToHostException -> ErrorCode.NETWORK_UNAVAILABLE
            is InterruptedIOException -> ErrorCode.TIMEOUT_UNCERTAIN
            is IOException -> ErrorCode.NETWORK_UNAVAILABLE
            else -> ErrorCode.UNKNOWN
        }
        return AppError(
            code = code,
            detail = "${cause::class.java.simpleName}: ${cause.message.orEmpty()}".take(MAX_DETAIL_CHARS),
        )
    }
}
