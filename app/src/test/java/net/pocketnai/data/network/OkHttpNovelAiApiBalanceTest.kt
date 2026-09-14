package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 余额读取的协议契约（余额规划 §12.2）。
 *
 * 断言的重点是**请求形态与错误语义**：路径、方法、鉴权头，
 * 以及"余额查询超时不能被说成可能已计费"。
 */
class OkHttpNovelAiApiBalanceTest {

    private lateinit var server: MockWebServer
    private lateinit var api: NovelAiApi

    /** 虚构凭据，与任何真实账号无关。 */
    private val fakeToken = "fake-persistent-token-for-tests"

    private val validBody = """
        {"tier":3,"active":true,"trainingStepsLeft":
          {"fixedTrainingStepsLeft":10000,"purchasedTrainingSteps":2000},
         "usage":{"percent":87,"isNegative":false,"timeUntilNextPercent":3600}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = OkHttpNovelAiApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ---- 请求形态 ----

    @Test
    fun `请求路径恰为 user-subscription 且是 GET`() {
        server.enqueue(jsonResponse(200, validBody))

        runBlocking { api.fetchSubscriptionBalance(fakeToken) }

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/user/subscription")
        assertThat(request.method).isEqualTo("GET")
    }

    @Test
    fun `使用 Bearer 鉴权并接受 JSON`() {
        server.enqueue(jsonResponse(200, validBody))

        runBlocking { api.fetchSubscriptionBalance(fakeToken) }

        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer $fakeToken")
        assertThat(request.getHeader("Accept")).isEqualTo("application/json")
    }

    @Test
    fun `200 解析出余额`() {
        server.enqueue(jsonResponse(200, validBody))

        val outcome = runBlocking { api.fetchSubscriptionBalance(fakeToken) }

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat((outcome as Outcome.Success).value.totalAnlas).isEqualTo(12_000L)
    }

    // ---- 失败映射 ----

    @Test
    fun `401 映射为 Token 无效`() {
        server.enqueue(jsonResponse(401, """{"message":"Unauthorized"}"""))

        assertThat(failureCode()).isEqualTo(ErrorCode.TOKEN_INVALID)
    }

    @Test
    fun `429 映射为请求频繁`() {
        server.enqueue(jsonResponse(429, """{"message":"Too Many Requests"}"""))

        assertThat(failureCode()).isEqualTo(ErrorCode.RATE_LIMITED)
    }

    @Test
    fun `500 映射为服务端错误`() {
        server.enqueue(jsonResponse(500, """{"message":"Internal"}"""))

        assertThat(failureCode()).isEqualTo(ErrorCode.SERVER_ERROR)
    }

    @Test
    fun `其他 4xx 映射为账户数据不可用`() {
        server.enqueue(jsonResponse(400, """{"message":"Bad Request"}"""))

        assertThat(failureCode()).isEqualTo(ErrorCode.ACCOUNT_DATA_UNAVAILABLE)
    }

    @Test
    fun `超时映射为普通请求超时而不是结果不确定`() {
        // TIMEOUT_UNCERTAIN 的意思是"服务端可能已经计费"，用在只读查询上会平白吓人。
        server.enqueue(
            MockResponse()
                .setResponseCode(408)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"Request Timeout"}"""),
        )

        assertThat(failureCode()).isEqualTo(ErrorCode.REQUEST_TIMEOUT)
    }

    @Test
    fun `畸形响应映射为账户响应无效`() {
        server.enqueue(jsonResponse(200, """{"tier":3}"""))

        assertThat(failureCode()).isEqualTo(ErrorCode.ACCOUNT_RESPONSE_INVALID)
    }

    @Test
    fun `网络不可用时映射为网络错误`() {
        server.shutdown()

        assertThat(failureCode()).isEqualTo(ErrorCode.NETWORK_UNAVAILABLE)
    }

    @Test
    fun `错误明细不含响应体内容`() {
        server.enqueue(jsonResponse(500, """{"message":"upstream-trace-abcdef123456"}"""))

        val error = runBlocking { api.fetchSubscriptionBalance(fakeToken) } as Outcome.Failure

        assertThat(error.error.detail).isEqualTo("HTTP 500")
        assertThat(error.error.detail.orEmpty()).doesNotContain("upstream-trace-abcdef123456")
    }

    @Test
    fun `错误明细与诊断 id 不含凭据`() {
        server.enqueue(jsonResponse(401, """{"message":"Unauthorized"}"""))

        val error = runBlocking { api.fetchSubscriptionBalance(fakeToken) } as Outcome.Failure

        assertThat(error.error.detail.orEmpty()).doesNotContain(fakeToken)
        assertThat(error.error.correlationId.orEmpty()).doesNotContain(fakeToken)
    }

    @Test
    fun `失败时只请求一次`() {
        server.enqueue(jsonResponse(500, """{"message":"Internal"}"""))

        failureCode()

        assertThat(server.requestCount).isEqualTo(1)
    }

    private fun failureCode(): ErrorCode {
        val outcome = runBlocking { api.fetchSubscriptionBalance(fakeToken) }
        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        return (outcome as Outcome.Failure).error.code
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
