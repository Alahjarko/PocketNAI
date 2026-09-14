package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pocketnai.data.security.CredentialHint
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.security.CredentialType
import net.pocketnai.data.security.StoredCredential
import net.pocketnai.domain.model.ImageModel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 标签补全的来源（规划书 8.1）。
 *
 * 重点不是"能取到标签"，而是**这一个功能的失败边界**：
 * 它必须在任何异常情况下都安静地返回空列表 —— 补全不能弹错误、不能影响生成。
 * 用的凭据是虚构值，断言里也不会出现真实 Token。
 */
class NovelAiTagSuggestionSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var api: NovelAiApi

    /** 虚构凭据；与任何真实账号无关。 */
    private val fakeToken = "fake-persistent-token-for-tests"

    private val model = ImageModel.V4_5_CURATED

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
        server.shutdown()
    }

    // ---- 成功路径 ----

    @Test
    fun `返回服务端给出的标签且保持顺序`() {
        server.enqueue(
            jsonResponse(200, """{"tags":[{"tag":"blue eyes","count":10,"confidence":0.9},{"tag":"blue hair","count":8,"confidence":0.7}]}"""),
        )

        val tags = runBlocking { source().suggest("blue", model) }

        assertThat(tags).containsExactly("blue eyes", "blue hair").inOrder()
    }

    @Test
    fun `标签是纯字符串数组时同样能解析`() {
        server.enqueue(jsonResponse(200, """{"tags":["1girl","solo"]}"""))

        assertThat(runBlocking { source().suggest("1g", model) }).containsExactly("1girl", "solo")
    }

    @Test
    fun `请求带上鉴权头与标签片段`() {
        server.enqueue(jsonResponse(200, """{"tags":[]}"""))

        runBlocking { source().suggest("blue ey", model) }

        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer $fakeToken")
        // 按解析后的参数断言，不比对编码形式（空格是 %20 还是 + 属于序列化细节）。
        val url = request.requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/ai/generate-image/suggest-tags")
        assertThat(url.queryParameter("prompt")).isEqualTo("blue ey")
        assertThat(url.queryParameter("model")).isEqualTo(model.apiModelId)
    }

    // ---- 失败一律静默 ----

    @Test
    fun `服务端错误返回空列表而不是抛异常`() {
        server.enqueue(jsonResponse(500, """{"message":"Internal"}"""))

        assertThat(runBlocking { source().suggest("blue", model) }).isEmpty()
    }

    @Test
    fun `凭据失效返回空列表`() {
        server.enqueue(jsonResponse(401, """{"message":"Unauthorized"}"""))

        assertThat(runBlocking { source().suggest("blue", model) }).isEmpty()
    }

    @Test
    fun `限流返回空列表`() {
        server.enqueue(jsonResponse(429, """{"message":"Too Many Requests"}"""))

        assertThat(runBlocking { source().suggest("blue", model) }).isEmpty()
    }

    @Test
    fun `缺少 tags 字段返回空列表`() {
        server.enqueue(jsonResponse(200, """{"somethingElse":true}"""))

        assertThat(runBlocking { source().suggest("blue", model) }).isEmpty()
    }

    @Test
    fun `畸形 JSON 返回空列表`() {
        server.enqueue(jsonResponse(200, """{"tags":[{"tag":""""))

        assertThat(runBlocking { source().suggest("blue", model) }).isEmpty()
    }

    @Test
    fun `网络不可用返回空列表`() {
        server.shutdown()

        assertThat(runBlocking { source().suggest("blue", model) }).isEmpty()
    }

    @Test
    fun `没有凭据时不发请求`() {
        val source = NovelAiTagSuggestionSource(api = api, credentialStore = FakeCredentialStore(null))

        assertThat(runBlocking { source.suggest("blue", model) }).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    // ---- 工具 ----

    private fun source(): NovelAiTagSuggestionSource = NovelAiTagSuggestionSource(
        api = api,
        credentialStore = FakeCredentialStore(
            StoredCredential(token = fakeToken, type = CredentialType.PERSISTENT_API_TOKEN),
        ),
    )

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private class FakeCredentialStore(private var credential: StoredCredential?) : CredentialStore {

        override fun hasCredential(): Boolean = credential != null

        override fun load(): StoredCredential? = credential

        override fun save(credential: StoredCredential) {
            this.credential = credential
        }

        override fun clear() {
            credential = null
        }

        override fun hint(): CredentialHint? = null
    }
}
