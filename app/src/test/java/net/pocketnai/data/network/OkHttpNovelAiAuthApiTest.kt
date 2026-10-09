package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pocketnai.core.Outcome
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** MockWebServer 验证认证异常不泄露虚构密钥。 */
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

    @Test
    fun `错误明细不包含 Access Key`() {
        server.enqueue(jsonResponse(401, """{"message":"Unauthorized"}"""))

        val outcome = runBlocking { api.login(fakeAccessKey) } as Outcome.Failure

        assertThat(outcome.error.detail.orEmpty()).doesNotContain(fakeAccessKey)
        assertThat(outcome.error.correlationId.orEmpty()).doesNotContain(fakeAccessKey)
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
