package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import net.pocketnai.core.ErrorCode
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 登录错误映射。
 *
 * 重点是它与生成错误映射**必须是两套**：同一件事（403）在两个接口上含义不同，
 * 混用会让用户看到完全误导的提示。最后两个用例专门把这个分离钉住。
 */
class NovelAiAuthErrorMapperTest {

    @Test
    fun `401 映射为凭据不正确`() {
        val error = NovelAiAuthErrorMapper.fromHttpStatus(401, correlationId = "req-1")
        assertThat(error.code).isEqualTo(ErrorCode.LOGIN_CREDENTIALS_INVALID)
        assertThat(error.correlationId).isEqualTo("req-1")
    }

    @Test
    fun `403 映射为需要额外验证`() {
        assertThat(NovelAiAuthErrorMapper.fromHttpStatus(403, null).code)
            .isEqualTo(ErrorCode.LOGIN_VERIFICATION_REQUIRED)
    }

    @Test
    fun `429 映射为限流`() {
        assertThat(NovelAiAuthErrorMapper.fromHttpStatus(429, null).code)
            .isEqualTo(ErrorCode.RATE_LIMITED)
    }

    @Test
    fun `5xx 映射为服务端错误`() {
        assertThat(NovelAiAuthErrorMapper.fromHttpStatus(500, null).code)
            .isEqualTo(ErrorCode.SERVER_ERROR)
        assertThat(NovelAiAuthErrorMapper.fromHttpStatus(503, null).code)
            .isEqualTo(ErrorCode.SERVER_ERROR)
    }

    @Test
    fun `未预期的状态码映射为无法识别的登录结果`() {
        // 400 不能被当成"密码错了"：否则用户会反复重试一个根本不是凭据问题的请求。
        assertThat(NovelAiAuthErrorMapper.fromHttpStatus(400, null).code)
            .isEqualTo(ErrorCode.LOGIN_RESPONSE_INVALID)
        assertThat(NovelAiAuthErrorMapper.fromHttpStatus(418, null).code)
            .isEqualTo(ErrorCode.LOGIN_RESPONSE_INVALID)
    }

    @Test
    fun `响应内容不合法一律映射为协议错误`() {
        assertThat(NovelAiAuthErrorMapper.forInvalidResponse("缺少 accessToken").code)
            .isEqualTo(ErrorCode.LOGIN_RESPONSE_INVALID)
    }

    @Test
    fun `登录超时使用普通超时码而不是结果不确定`() {
        // TIMEOUT_UNCERTAIN 带着"服务端可能已经接受并计费"的含义，只适用于图片生成。
        // 登录不消耗 Anlas，用它是纯粹的误导。
        val error = NovelAiAuthErrorMapper.fromTransportError(SocketTimeoutException("timeout"))
        assertThat(error.code).isEqualTo(ErrorCode.REQUEST_TIMEOUT)
        assertThat(error.code).isNotEqualTo(ErrorCode.TIMEOUT_UNCERTAIN)
    }

    @Test
    fun `解析失败与连接失败映射为网络不可用`() {
        assertThat(NovelAiAuthErrorMapper.fromTransportError(UnknownHostException("no dns")).code)
            .isEqualTo(ErrorCode.NETWORK_UNAVAILABLE)
        assertThat(NovelAiAuthErrorMapper.fromTransportError(IOException("closed")).code)
            .isEqualTo(ErrorCode.NETWORK_UNAVAILABLE)
    }

    @Test
    fun `错误明细只保留状态码不保留响应体`() {
        val error = NovelAiAuthErrorMapper.fromHttpStatus(500, correlationId = null)
        assertThat(error.detail).isEqualTo("HTTP 500")
    }

    @Test
    fun `传输层错误明细不含异常消息`() {
        // 网络异常消息里偶尔会带上 URL 或请求片段，只留异常类型更安全。
        val error = NovelAiAuthErrorMapper.fromTransportError(
            IOException("failed to connect to https://image.novelai.net/user/login?key=SHOULD-NOT-APPEAR"),
        )
        assertThat(error.detail).isEqualTo("IOException")
        assertThat(error.detail.orEmpty()).doesNotContain("key")
    }

    @Test
    fun `403 在登录与生成两套映射下的含义不同`() {
        // 生成接口：403 属于凭据失效；登录接口：403 属于风控/需要额外验证。
        // 如果哪天有人把两套合并成一个，这个用例会失败。
        assertThat(NovelAiErrorMapper.fromHttpStatus(403, null, null).code)
            .isEqualTo(ErrorCode.TOKEN_INVALID)
        assertThat(NovelAiAuthErrorMapper.fromHttpStatus(403, null).code)
            .isEqualTo(ErrorCode.LOGIN_VERIFICATION_REQUIRED)
    }

    @Test
    fun `超时在两套映射下的含义也不同`() {
        val timeout = SocketTimeoutException("timeout")
        assertThat(NovelAiErrorMapper.fromTransportError(timeout).code)
            .isEqualTo(ErrorCode.TIMEOUT_UNCERTAIN)
        assertThat(NovelAiAuthErrorMapper.fromTransportError(timeout).code)
            .isEqualTo(ErrorCode.REQUEST_TIMEOUT)
    }
}
