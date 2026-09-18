package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Hashing
import net.pocketnai.core.Outcome
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 登录协议层的契约测试（《双认证模式实施计划》阶段 B 完成标准）。
 *
 * 断言的重点不是"能登录"，而是**协议边界有没有守住**：
 * 路径、方法、请求体字段、有没有多带敏感信息、失败会不会自动重发。
 *
 * ## 关于敏感值
 * 测试里用的全是虚构值。对 Access Key 不做全值断言，只比较长度与 SHA-256 ——
 * 这样即使断言失败，输出里也不会出现完整凭据；真实凭据也绝不允许写进测试。
 */
class OkHttpNovelAiAuthApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: NovelAiAuthApi

    /** 虚构的 64 字符 Access Key（与真实账号无关）。 */
    private val fakeAccessKey = "TESTKEY_" + "a".repeat(56)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .build()
        api = OkHttpNovelAiAuthApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            clientFactory = { client },
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- 请求形态 ----

    @Test
    fun `请求路径恰为 user-login`() {
        server.enqueue(jsonResponse(201, """{"accessToken":"fake-token"}"""))

        runBlocking { api.login(fakeAccessKey) }

        assertThat(server.takeRequest().path).isEqualTo("/user/login")
    }

    @Test
    fun `使用 POST 且不携带 Authorization 头`() {
        server.enqueue(jsonResponse(201, """{"accessToken":"fake-token"}"""))

        runBlocking { api.login(fakeAccessKey) }

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        // 登录的目的就是拿到 Token，带上 Authorization 没有意义。
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    fun `请求体只包含 key 字段`() {
        server.enqueue(jsonResponse(201, """{"accessToken":"fake-token"}"""))

        runBlocking { api.login(fakeAccessKey) }

        val body = server.takeRequest().body.readUtf8()
        val json = Json.parseToJsonElement(body).jsonObject

        assertThat(json.keys).containsExactly("key")
        val sent = json.getValue("key").jsonPrimitive.content
        assertThat(sent).hasLength(64)
        // 用哈希比较而不是全值比较：断言失败时不会打印出完整凭据。
        assertThat(Hashing.sha256(sent.toByteArray()))
            .isEqualTo(Hashing.sha256(fakeAccessKey.toByteArray()))
    }

    @Test
    fun `请求体不带邮箱也不带密码特征`() {
        server.enqueue(jsonResponse(201, """{"accessToken":"fake-token"}"""))

        runBlocking { api.login(fakeAccessKey) }

        val body = server.takeRequest().body.readUtf8()
        // 基础 Access Key 登录不需要邮箱；一份都不多带。
        assertThat(body).doesNotContain("@")
        assertThat(body).doesNotContain("email")
        assertThat(body).doesNotContain("password")
    }

    // ---- 成功路径 ----

    @Test
    fun `201 返回可用的会话`() {
        server.enqueue(jsonResponse(201, """{"accessToken":"fake-access-token"}"""))

        val outcome = runBlocking { api.login(fakeAccessKey) }

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat((outcome as Outcome.Success).value.accessToken).isEqualTo("fake-access-token")
    }

    // ---- 失败映射 ----

    @Test
    fun `401 映射为凭据无效`() {
        server.enqueue(jsonResponse(401, """{"message":"Unauthorized"}"""))
        assertThat(failureCode()).isEqualTo(ErrorCode.LOGIN_CREDENTIALS_INVALID)
    }

    @Test
    fun `403 映射为需要额外验证而不是凭据失效`() {
        // 登录的 403 更可能是风控或需要人机验证；按"凭据失效"提示会把用户带偏。
        server.enqueue(jsonResponse(403, """{"message":"Forbidden"}"""))
        assertThat(failureCode()).isEqualTo(ErrorCode.LOGIN_VERIFICATION_REQUIRED)
    }

    @Test
    fun `429 映射为限流`() {
        server.enqueue(jsonResponse(429, """{"message":"Too Many Requests"}"""))
        assertThat(failureCode()).isEqualTo(ErrorCode.RATE_LIMITED)
    }

    @Test
    fun `500 映射为服务端错误`() {
        server.enqueue(jsonResponse(500, """{"message":"Internal"}"""))
        assertThat(failureCode()).isEqualTo(ErrorCode.SERVER_ERROR)
    }

    @Test
    fun `其他状态码映射为无法识别的登录结果`() {
        server.enqueue(jsonResponse(400, """{"message":"Bad Request"}"""))
        assertThat(failureCode()).isEqualTo(ErrorCode.LOGIN_RESPONSE_INVALID)
    }

    // ---- 响应解析 ----

    @Test
    fun `201 但缺少 accessToken 视为协议错误`() {
        server.enqueue(jsonResponse(201, """{"somethingElse":true}"""))
        assertThat(failureCode()).isEqualTo(ErrorCode.LOGIN_RESPONSE_INVALID)
    }

    @Test
    fun `accessToken 类型错误视为协议错误`() {
        server.enqueue(jsonResponse(201, """{"accessToken":12345}"""))
        assertThat(failureCode()).isEqualTo(ErrorCode.LOGIN_RESPONSE_INVALID)
    }

    @Test
    fun `accessToken 为空字符串视为协议错误`() {
        server.enqueue(jsonResponse(201, """{"accessToken":"   "}"""))
        assertThat(failureCode()).isEqualTo(ErrorCode.LOGIN_RESPONSE_INVALID)
    }

    @Test
    fun `畸形 JSON 视为协议错误`() {
        server.enqueue(jsonResponse(201, """{"accessToken":"""))
        assertThat(failureCode()).isEqualTo(ErrorCode.LOGIN_RESPONSE_INVALID)
    }

    @Test
    fun `空响应体视为协议错误`() {
        server.enqueue(jsonResponse(201, ""))
        assertThat(failureCode()).isEqualTo(ErrorCode.LOGIN_RESPONSE_INVALID)
    }

    @Test
    fun `响应体超过大小上限视为协议错误`() {
        // 64 KiB 上限；超出时不应该继续读进内存。
        server.enqueue(jsonResponse(201, "x".repeat(64 * 1024 + 1)))
        assertThat(failureCode()).isEqualTo(ErrorCode.LOGIN_RESPONSE_INVALID)
    }

    @Test
    fun `分块传输的超大响应同样被拒绝`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setChunkedBody("y".repeat(64 * 1024 + 1), 4096),
        )
        assertThat(failureCode()).isEqualTo(ErrorCode.LOGIN_RESPONSE_INVALID)
    }

    @Test
    fun `网络不可用时映射为网络错误`() {
        server.shutdown()

        val outcome = runBlocking { api.login(fakeAccessKey) }

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.NETWORK_UNAVAILABLE)
    }

    // ---- 不重发 ----

    @Test
    fun `凭据错误时请求次数恰为一次`() {
        server.enqueue(jsonResponse(401, """{"message":"Unauthorized"}"""))

        runBlocking { api.login(fakeAccessKey) }

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `服务端错误时请求次数恰为一次`() {
        server.enqueue(jsonResponse(500, """{"message":"Internal"}"""))

        runBlocking { api.login(fakeAccessKey) }

        assertThat(server.requestCount).isEqualTo(1)
    }

    // ---- 错误明细 ----

    @Test
    fun `错误明细不包含响应体内容`() {
        // 响应体可能带有会话相关信息，不应该被存进数据库或显示在界面上。
        val sensitiveLooking = "upstream-trace-abcdef123456"
        server.enqueue(jsonResponse(500, """{"message":"$sensitiveLooking"}"""))

        val outcome = runBlocking { api.login(fakeAccessKey) } as Outcome.Failure

        assertThat(outcome.error.detail).isEqualTo("HTTP 500")
        assertThat(outcome.error.detail).doesNotContain(sensitiveLooking)
    }

    @Test
    fun `错误明细不包含 Access Key`() {
        server.enqueue(jsonResponse(401, """{"message":"Unauthorized"}"""))

        val outcome = runBlocking { api.login(fakeAccessKey) } as Outcome.Failure

        assertThat(outcome.error.detail.orEmpty()).doesNotContain(fakeAccessKey)
        assertThat(outcome.error.correlationId.orEmpty()).doesNotContain(fakeAccessKey)
    }

    // ---- 工具 ----

    private fun failureCode(): ErrorCode {
        val outcome = runBlocking { api.login(fakeAccessKey) }
        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        return (outcome as Outcome.Failure).error.code
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
