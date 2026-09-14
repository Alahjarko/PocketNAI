package net.pocketnai.data.network

import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 登录错误的映射。
 *
 * ## 为什么不复用 [NovelAiErrorMapper]
 *
 * 生成接口的映射器把 `401 || 403` 都当作"凭据失效"。登录接口不一样：
 * - `401` 是"邮箱或密码不对"；
 * - `403` 更可能是风控或需要人机验证，而不是凭据本身有问题。
 *
 * 混在一起会让用户看到"Token 无效"这种完全误导的提示，所以登录有自己的一份。
 *
 * ## 为什么不把响应体放进 detail
 *
 * 登录请求与响应的任何内容都不该被存下来：请求体里有 Access Key，
 * 响应体里有 Access Token。错误明细会落进数据库并显示在界面上，
 * 所以这里只保留状态码与脱敏 correlation id。
 */
object NovelAiAuthErrorMapper {

    fun fromHttpStatus(status: Int, correlationId: String?): AppError {
        val code = when {
            status == 401 -> ErrorCode.LOGIN_CREDENTIALS_INVALID
            status == 403 -> ErrorCode.LOGIN_VERIFICATION_REQUIRED
            status == 429 -> ErrorCode.RATE_LIMITED
            status in 500..599 -> ErrorCode.SERVER_ERROR
            // 其余状态码（含 400）都属于"看不懂的登录结果"，而不是凭据错误 ——
            // 不能让用户以为自己密码打错了而反复重试。
            else -> ErrorCode.LOGIN_RESPONSE_INVALID
        }
        return AppError(code = code, correlationId = correlationId, detail = "HTTP $status")
    }

    /** 2xx 但响应内容不符合协议（缺 accessToken、畸形 JSON、超过大小上限）。 */
    fun forInvalidResponse(detail: String, correlationId: String? = null): AppError =
        AppError(
            code = ErrorCode.LOGIN_RESPONSE_INVALID,
            correlationId = correlationId,
            detail = detail,
        )

    /**
     * 登录的传输层错误。
     *
     * 登录超时用 [ErrorCode.REQUEST_TIMEOUT]，**不用** `TIMEOUT_UNCERTAIN`：
     * 后者带着"服务端可能已经计费"的含义，只适用于图片生成。
     */
    fun fromTransportError(cause: Throwable): AppError {
        val code = when (cause) {
            is SocketTimeoutException -> ErrorCode.REQUEST_TIMEOUT
            is UnknownHostException -> ErrorCode.NETWORK_UNAVAILABLE
            is ConnectException -> ErrorCode.NETWORK_UNAVAILABLE
            is NoRouteToHostException -> ErrorCode.NETWORK_UNAVAILABLE
            is InterruptedIOException -> ErrorCode.REQUEST_TIMEOUT
            is IOException -> ErrorCode.NETWORK_UNAVAILABLE
            else -> ErrorCode.UNKNOWN
        }
        // 只记异常类型，不记 message —— 网络异常信息里偶尔会带上 URL 或请求片段。
        return AppError(
            code = code,
            detail = cause::class.java.simpleName,
        )
    }
}
