package net.pocketnai.data.network

import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.domain.proxy.ProxyQuotaExceededException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 读取账户信息（余额）时的错误映射（规划 §6.4）。
 *
 * 单独一个映射器，**不与登录、生成共用**：三者的失败语义不同 ——
 * - 登录失败 → 引导重新输入或改用 PST；
 * - 生成失败 → 可能已经计费，措辞要谨慎；
 * - 余额读取失败 → 只是辅助信息没读到，不该让用户以为出了大事。
 *
 * 尤其注意：余额请求超时**不能**映射成 `TIMEOUT_UNCERTAIN`。
 * 那句话的意思是"服务端可能已经接受任务并计费"，用在一个只读查询上会平白吓人。
 */
object AccountReadErrorMapper {

    fun fromHttpStatus(
        statusCode: Int,
        body: String,
        correlationId: String?,
    ): AppError = when (statusCode) {
        401 -> AppError.of(ErrorCode.TOKEN_INVALID, correlationId)
        408 -> AppError.of(ErrorCode.REQUEST_TIMEOUT, correlationId, "HTTP 408")
        429 -> AppError.of(ErrorCode.RATE_LIMITED, correlationId, "HTTP 429")
        in 500..599 -> AppError.of(ErrorCode.SERVER_ERROR, correlationId, "HTTP $statusCode")
        else -> AppError.of(ErrorCode.ACCOUNT_DATA_UNAVAILABLE, correlationId, "HTTP $statusCode")
    }

    fun fromTransportError(error: IOException): AppError = when (error) {
        is ProxyQuotaExceededException -> AppError.of(ErrorCode.PROXY_QUOTA_EXCEEDED)
        is SocketTimeoutException -> AppError.of(ErrorCode.REQUEST_TIMEOUT)
        else -> AppError.of(ErrorCode.NETWORK_UNAVAILABLE)
    }
}
