package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OkHttpNovelAiApiUpscaleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: NovelAiApi
    private val fakeToken = "fake-persistent-token-for-tests"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = OkHttpNovelAiApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            clientFactory = { OkHttpClient.Builder().retryOnConnectionFailure(false).build() },
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `超分请求路径恰为 ai-upscale 且正确携带请求参数与鉴权头`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("dummy-png-or-zip"))

        val destFile = tempFolder.newFile("upscaled.bin")
        val outcome = api.upscaleImage(
            token = fakeToken,
            imageBase64 = "aGVsbG8=",
            width = 832,
            height = 1216,
            scale = 2,
            destinationFile = destFile,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(destFile.readText()).isEqualTo("dummy-png-or-zip")

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/ai/upscale")
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer $fakeToken")
        assertThat(recorded.getHeader("Content-Type")).contains("application/json")

        val json = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertThat(json["image"]?.jsonPrimitive?.content).isEqualTo("aGVsbG8=")
        assertThat(json["width"]?.jsonPrimitive?.content).isEqualTo("832")
        assertThat(json["height"]?.jsonPrimitive?.content).isEqualTo("1216")
        assertThat(json["scale"]?.jsonPrimitive?.content).isEqualTo("2")
    }

    @Test
    fun `超分遇到 402 时映射为 INSUFFICIENT_ANLAS`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"message":"Payment Required"}"""))

        val destFile = tempFolder.newFile("dest.bin")
        val outcome = api.upscaleImage(
            token = fakeToken,
            imageBase64 = "aGVsbG8=",
            width = 832,
            height = 1216,
            scale = 4,
            destinationFile = destFile,
        )

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        val failure = outcome as Outcome.Failure
        assertThat(failure.error.code).isEqualTo(ErrorCode.INSUFFICIENT_ANLAS)
    }
}
