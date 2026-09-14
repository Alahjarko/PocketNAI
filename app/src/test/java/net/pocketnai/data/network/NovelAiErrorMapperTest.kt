package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import net.pocketnai.core.ErrorCode
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NovelAiErrorMapperTest {

    @Test
    fun `鉴权失败映射为 Token 失效`() {
        assertThat(NovelAiErrorMapper.fromHttpStatus(401, null, null).code)
            .isEqualTo(ErrorCode.TOKEN_INVALID)
        assertThat(NovelAiErrorMapper.fromHttpStatus(403, null, null).code)
            .isEqualTo(ErrorCode.TOKEN_INVALID)
    }

    @Test
    fun `402 映射为额度不足`() {
        assertThat(NovelAiErrorMapper.fromHttpStatus(402, null, null).code)
            .isEqualTo(ErrorCode.INSUFFICIENT_ANLAS)
    }

    @Test
    fun `429 映射为请求过于频繁`() {
        assertThat(NovelAiErrorMapper.fromHttpStatus(429, null, null).code)
            .isEqualTo(ErrorCode.RATE_LIMITED)
    }

    @Test
    fun `5xx 映射为服务端错误`() {
        assertThat(NovelAiErrorMapper.fromHttpStatus(500, null, null).code)
            .isEqualTo(ErrorCode.SERVER_ERROR)
        assertThat(NovelAiErrorMapper.fromHttpStatus(503, null, null).code)
            .isEqualTo(ErrorCode.SERVER_ERROR)
    }

    @Test
    fun `普通 400 映射为参数无效`() {
        assertThat(NovelAiErrorMapper.fromHttpStatus(400, "bad width", null).code)
            .isEqualTo(ErrorCode.INVALID_PARAMS)
    }

    @Test
    fun `400 中带额度提示时识别为额度不足`() {
        val body = """{"message":"Insufficient Anlas for this request"}"""
        assertThat(NovelAiErrorMapper.fromHttpStatus(400, body, null).code)
            .isEqualTo(ErrorCode.INSUFFICIENT_ANLAS)
    }

    @Test
    fun `400 中带订阅提示时识别为模型不可用`() {
        val body = """{"message":"Model not available for your subscription tier"}"""
        assertThat(NovelAiErrorMapper.fromHttpStatus(400, body, null).code)
            .isEqualTo(ErrorCode.MODEL_UNAVAILABLE)
    }

    @Test
    fun `400 要求改用 image URL 时识别为需要更新客户端`() {
        // 真实抓到的响应：api.novelai.net 对第三方 Persistent API Token 会这样拒绝。
        // 如果这里归类成“参数无效”，排查方向会被彻底带偏。
        val body = """{"statusCode":400,"message":"Please refresh NovelAI.net. If using a third-party tool, update to the image URL."}"""
        assertThat(NovelAiErrorMapper.fromHttpStatus(400, body, null).code)
            .isEqualTo(ErrorCode.CLIENT_UPDATE_REQUIRED)
    }

    @Test
    fun `超时映射为结果不确定而不是普通网络错误`() {
        // 规划书 9.2：超时后服务端可能已经接受任务，必须让用户知情而不是自动重试。
        assertThat(NovelAiErrorMapper.fromTransportError(SocketTimeoutException("timeout")).code)
            .isEqualTo(ErrorCode.TIMEOUT_UNCERTAIN)
    }

    @Test
    fun `解析失败与连接失败映射为网络不可用`() {
        assertThat(NovelAiErrorMapper.fromTransportError(UnknownHostException("no dns")).code)
            .isEqualTo(ErrorCode.NETWORK_UNAVAILABLE)
        assertThat(NovelAiErrorMapper.fromTransportError(IOException("socket closed")).code)
            .isEqualTo(ErrorCode.NETWORK_UNAVAILABLE)
    }

    @Test
    fun `correlationId 会原样带上以便用户报障`() {
        val error = NovelAiErrorMapper.fromHttpStatus(500, "boom", "req-abc-123")
        assertThat(error.correlationId).isEqualTo("req-abc-123")
        assertThat(error.detail).contains("HTTP 500")
    }

    @Test
    fun `错误明细被截断避免把整个响应塞进数据库`() {
        val huge = "x".repeat(5_000)
        val error = NovelAiErrorMapper.fromHttpStatus(400, huge, null)
        assertThat(error.detail!!.length).isLessThan(500)
    }
}
