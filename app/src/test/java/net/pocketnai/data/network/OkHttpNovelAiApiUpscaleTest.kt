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
    fun `超分请求体与官方一致：只有 image、model 与 declared_blur_sigma`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("dummy-png-or-zip"))

        val destFile = tempFolder.newFile("upscaled.bin")
        val outcome = api.upscaleImage(
            token = fakeToken,
            imageBase64 = "aGVsbG8=",
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
        assertThat(json.keys).containsExactly("image", "model", "declared_blur_sigma")
        assertThat(json["image"]?.jsonPrimitive?.content).isEqualTo("aGVsbG8=")
        // 官方前端对任何来源的图都发这个超分模型与 0 的 blur sigma；倍数固定 4 倍、不在请求里。
        assertThat(json["model"]?.jsonPrimitive?.content).isEqualTo("nai-diffusion-5-curated")
        assertThat(json["declared_blur_sigma"]?.jsonPrimitive?.content).isEqualTo("0")
    }

    @Test
    fun `超分遇到 402 时映射为 INSUFFICIENT_ANLAS`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"message":"Payment Required"}"""))

        val destFile = tempFolder.newFile("dest.bin")
        val outcome = api.upscaleImage(
            token = fakeToken,
            imageBase64 = "aGVsbG8=",
            destinationFile = destFile,
        )

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        val failure = outcome as Outcome.Failure
        assertThat(failure.error.code).isEqualTo(ErrorCode.INSUFFICIENT_ANLAS)
    }
}
