package net.pocketnai.data.openai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.pocketnai.domain.openai.OpenAiImageParams
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class OpenAiImageApiTest {
    @Test fun `编辑请求保留原文且拒绝参数及服务错误均不重发或回显秘密`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = OpenAiImageApi(OkHttpClient(), server.url("/v1").toString())
            val key = "test-only-openai-secret"
            val params = OpenAiImageParams(prompt = "海报写着\"你好\", **原样保留**", model = OpenAiImageParams.SUNBURST,
                outputFormat = "webp", background = "transparent", compression = 83)
            val invalid = params.copy(size = "1025x1024")
            assertThat(runCatching { api.generate(key, invalid, emptyList()) }.exceptionOrNull()).isInstanceOf(OpenAiImageFailure::class.java)
            assertThat(server.requestCount).isEqualTo(0)
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"sample"}],"usage":{"input_tokens_details":{"text_tokens":100,"image_tokens":20},"output_tokens":200}}"""))
            val response = api.generate(key, params, listOf("data:image/png;base64,fixture"))
            val sent = server.takeRequest()
            assertThat(sent.path).isEqualTo("/v1/images/edits")
            assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer $key")
            val payload = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertThat(payload["prompt"]!!.jsonPrimitive.content).isEqualTo(params.prompt)
            assertThat(payload["output_compression"]!!.jsonPrimitive.int).isEqualTo(83)
            assertThat(payload.keys).containsExactly("model", "prompt", "n", "size", "quality", "output_format", "background", "moderation", "output_compression", "images")
            assertThat(payload["images"]!!.jsonArray.single().jsonObject["image_url"]!!.jsonPrimitive.content).isEqualTo("data:image/png;base64,fixture")
            assertThat(response.snapshot.usage!!.estimatedUsd).isWithin(0.0000001).of(0.00666)
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"sample"}],"usage":{"input_tokens":100,"output_tokens":200}}"""))
            val png = params.copy(outputFormat = "png", background = "auto")
            assertThat(api.generate(key, png, emptyList()).snapshot.usage!!.estimatedUsd).isWithin(0.0000001).of(0.0065)
            val pngRequest = server.takeRequest()
            assertThat(pngRequest.path).isEqualTo("/v1/images/generations")
            assertThat(Json.parseToJsonElement(pngRequest.body.readUtf8()).jsonObject).doesNotContainKey("output_compression")
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("echo $key ${params.prompt}"))
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"must-not-be-consumed"}]}"""))
            val error = runCatching { api.generate(key, params.copy(outputFormat = "png", background = "auto"), emptyList()) }.exceptionOrNull()
            assertThat((error as OpenAiImageFailure).uncertain).isTrue()
            assertThat(error.message).doesNotContain(key)
            assertThat(error.message).doesNotContain(params.prompt)
            assertThat(server.requestCount).isEqualTo(3)
            assertThat(server.takeRequest().path).isEqualTo("/v1/images/generations")
            assertThat(OpenAiImageApi.payload(params.copy(outputFormat = "png"))).doesNotContainKey("output_compression")
        }
    }
}
