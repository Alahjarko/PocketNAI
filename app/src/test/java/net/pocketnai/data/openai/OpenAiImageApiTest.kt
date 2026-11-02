package net.pocketnai.data.openai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.pocketnai.domain.openai.*
import net.pocketnai.data.local.GalleryImageRow
import net.pocketnai.data.local.Mappers
import net.pocketnai.domain.model.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class OpenAiImageApiTest {
    @Test fun `编辑请求保留原文且拒绝参数及服务错误均不重发或回显秘密`() = runBlocking {
        verifyPricingAndUsage()
        // Cover the read-model handoff too: dropping an unrecognised NAI enum used to erase OpenAI identity.
        val items = (GalleryModels.apiIds + "future-image-model").map { apiId ->
            Mappers.toDomain(GalleryImageRow(apiId, apiId, 1, "generations/sample.png", 832, 1216, 1L, null, null,
                1L, "SUCCEEDED", "IMG2IMG", "sample", apiId, "Silver_hair, blue eyes", "", 1)).copy(favorite = true)
        }
        assertThat(GalleryModels.apiIds).containsAtLeast(OpenAiImageParams.FLARE, OpenAiImageParams.SUNBURST)
        assertThat(GalleryModels.labelOf(OpenAiImageParams.FLARE)).contains("Flare")
        assertThat(GalleryModels.labelOf(OpenAiImageParams.SUNBURST)).contains("Sunburst")
        items.forEach { item ->
            val filter = GalleryFilter(modelApiIds = setOf(item.modelApiId))
            assertThat(items.filter { GallerySearch.matches(it, filter) }.map { it.modelApiId }).containsExactly(item.modelApiId)
            assertThat(filter.isActive).isTrue()
        }
        val combined = GalleryFilter(query = "SILVER blue", modelApiIds = setOf(OpenAiImageParams.FLARE),
            mode = GenerationMode.IMG2IMG, favoritesOnly = true)
        val flare = items.single { it.modelApiId == OpenAiImageParams.FLARE }
        assertThat(GallerySearch.matches(flare, combined)).isTrue()
        assertThat(GallerySearch.matches(flare.copy(favorite = false), combined)).isFalse()
        assertThat(GallerySearch.matches(flare.copy(mode = GenerationMode.TXT2IMG), combined)).isFalse()
        assertThat(GallerySearch.matches(flare, combined.copy(query = "red hair"))).isFalse()
        assertThat(items.filter { GallerySearch.matches(it, combined.copy(modelApiIds = emptySet())) }).hasSize(items.size)
        assertThat(GalleryFilter.None.isActive).isFalse()
        assertThat(items.filter { GallerySearch.matches(it, GalleryFilter.None) }).hasSize(items.size)
        val models = setOf(OpenAiImageParams.FLARE, OpenAiImageParams.SUNBURST)
        val selected = combined.copy(modelApiIds = models, favoritesOnly = false)
        assertThat(items.filter { GallerySearch.matches(it, selected) }.map { it.modelApiId }).containsExactlyElementsIn(models)
        val legacy = GenerationParams.defaultsFor(ModelCatalog.defaultProfile()).copy(prompt = "Silver hair, blue eyes", sampleCount = 2)
        val jobs = models.map { model -> Generation(model, 1L, 1L, GenerationStatus.GENERATING, "sample", "", legacy, 1,
            mode = GenerationMode.IMG2IMG, openAi = net.pocketnai.domain.openai.OpenAiImageSnapshot(OpenAiImageParams(model = model, prompt = legacy.prompt, count = 2))) }
        val slots = net.pocketnai.ui.gallery.GalleryMotionSlots()
        val queueSlots = slots.sections(emptyList(), listOf(GenerationSummary(
            jobs.first().copy(id = "queued", status = GenerationStatus.QUEUED), 0)), true).flatMap { it.slots }
        val activeSlots = slots.sections(emptyList(), listOf(GenerationSummary(
            jobs.first().copy(id = "queued", status = GenerationStatus.GENERATING), 0)), true).flatMap { it.slots }
        assertThat(queueSlots.map { it.key }).isEqualTo(activeSlots.map { it.key })
        assertThat(queueSlots).hasSize(2)
        fun pending(filter: GalleryFilter) = slots.sections(emptyList(), jobs.map { GenerationSummary(it, 0) }, true,
            filter = filter).flatMap { it.slots }
        assertThat(pending(selected)).hasSize(4)
        assertThat(pending(selected.copy(modelApiIds = setOf(OpenAiImageParams.SUNBURST)))).hasSize(2)
        assertThat(pending(selected.copy(query = "not present"))).isEmpty()
        assertThat(pending(selected.copy(favoritesOnly = true))).isEmpty()
        assertThat(pending(GalleryFilter.None)).hasSize(4)
        MockWebServer().use { server ->
            server.start()
            val api = OpenAiImageApi(OkHttpClient(), server.url("/v1").toString())
            val key = "test-only-openai-secret"
            val params = OpenAiImageParams(prompt = "海报写着\"你好\", **原样保留**", model = OpenAiImageParams.SUNBURST,
                size = "3840x2160", outputFormat = "webp", background = "transparent", compression = 83)
            listOf("1025x1024", "3840x3840", "3856x2160", "3840x480", "480x480", "x2160").forEach { size ->
                assertThat(runCatching { api.generate(key, params.copy(size = size), emptyList()) }.exceptionOrNull())
                    .isInstanceOf(OpenAiImageFailure::class.java)
            }
            assertThat(server.requestCount).isEqualTo(0)
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"sample"}],"usage":{"input_tokens_details":{"text_tokens":100,"image_tokens":20},"output_tokens":200}}"""))
            val response = api.generate(key, params, listOf("data:image/png;base64,fixture"))
            val sent = server.takeRequest()
            assertThat(sent.path).isEqualTo("/v1/images/edits")
            assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer $key")
            val payload = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertThat(payload["prompt"]!!.jsonPrimitive.content).isEqualTo(params.prompt)
            assertThat(payload["size"]!!.jsonPrimitive.content).isEqualTo("3840x2160")
            val snapshot = net.pocketnai.domain.openai.OpenAiImageSnapshot(params)
            assertThat(net.pocketnai.domain.openai.OpenAiImageCodec.decode(net.pocketnai.domain.openai.OpenAiImageCodec.encode(snapshot)))
                .isEqualTo(snapshot)
            val draft = net.pocketnai.domain.openai.OpenAiImageDraft(params)
            val codec = net.pocketnai.domain.openai.OpenAiImageCodec.json
            assertThat(codec.decodeFromString<net.pocketnai.domain.openai.OpenAiImageDraft>(
                codec.encodeToString(net.pocketnai.domain.openai.OpenAiImageDraft.serializer(), draft))).isEqualTo(draft)
            assertThat(payload["output_compression"]!!.jsonPrimitive.int).isEqualTo(83)
            assertThat(payload.keys).containsExactly("model", "prompt", "n", "size", "quality", "output_format", "background", "moderation", "output_compression", "images")
            assertThat(payload["images"]!!.jsonArray.single().jsonObject["image_url"]!!.jsonPrimitive.content).isEqualTo("data:image/png;base64,fixture")
            assertThat(response.snapshot.estimatedUsd!!).isWithin(0.0000001).of(0.00666)
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"sample"}],"usage":{"input_tokens":100,"output_tokens":200}}"""))
            val png = params.copy(size = "2160x3840", outputFormat = "png", background = "auto")
            assertThat(api.generate(key, png, emptyList()).snapshot.estimatedUsd!!).isWithin(0.0000001).of(0.0065)
            val pngRequest = server.takeRequest()
            assertThat(pngRequest.path).isEqualTo("/v1/images/generations")
            val pngPayload = Json.parseToJsonElement(pngRequest.body.readUtf8()).jsonObject
            assertThat(pngPayload).doesNotContainKey("output_compression")
            assertThat(pngPayload["size"]!!.jsonPrimitive.content).isEqualTo("2160x3840")
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"sample"}],"usage":[]} """))
            val unknownUsage = api.generate(key, png, emptyList())
            assertThat(unknownUsage.data).containsExactly("sample")
            assertThat(unknownUsage.snapshot.estimatedUsd).isNull()
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("echo $key ${params.prompt}"))
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"must-not-be-consumed"}]}"""))
            val error = runCatching { api.generate(key, params.copy(outputFormat = "png", background = "auto"), emptyList()) }.exceptionOrNull()
            assertThat((error as OpenAiImageFailure).uncertain).isTrue()
            assertThat(error.message).doesNotContain(key)
            assertThat(error.message).doesNotContain(params.prompt)
            assertThat(server.requestCount).isEqualTo(4)
            assertThat(server.takeRequest().path).isEqualTo("/v1/images/generations")
            assertThat(OpenAiImageApi.payload(params.copy(outputFormat = "png"))).doesNotContainKey("output_compression")
        }
    }

    private fun verifyPricingAndUsage() {
        // Independent results evaluated from the official calculator JS, not this implementation.
        val fixtures = mapOf(
            "1024x1024" to listOf(196L, 439L, 1756L, 3122L, 7024L),
            "1536x1024" to listOf(158L, 343L, 1372L, 2459L, 5488L),
            "3840x2160" to listOf(371L, 865L, 3336L, 5930L, 13342L),
            "1024x800" to listOf(136L, 322L, 1286L, 2256L, 5075L),
            "2880x2880" to listOf(659L, 1483L, 5930L, 10542L, 23719L),
        )
        val qualities = listOf("low", "medium", "high", "xhigh", "max")
        for (model in OpenAiImageParams.MODELS) for ((size, expected) in fixtures) {
            val params = OpenAiImageParams(model = model, size = size)
            assertThat(qualities.map { OpenAiImagePricing.outputTokens(params.copy(quality = it)) }).isEqualTo(expected)
            assertThat(qualities.map { OpenAiImagePricing.outputTokens(params.copy(
                quality = it, size = size.split('x').reversed().joinToString("x"))) }).isEqualTo(expected)
        }
        // Official floating point arithmetic places these just below / above a half-grid boundary.
        assertThat(OpenAiImagePricing.outputTokens(OpenAiImageParams(size = "944x1536", quality = "high"))).isEqualTo(1201L)
        assertThat(OpenAiImagePricing.outputTokens(OpenAiImageParams(size = "784x2048", quality = "xhigh"))).isEqualTo(1443L)
        val params = OpenAiImageParams(prompt = "海报 poster", quality = "high")
        val one = OpenAiImagePricing.estimate(params, 2)!!
        assertThat(one.outputMinPerImage).isWithin(0.0000001).of(0.05268)
        assertThat(one.referenceAllowancePerImage).isWithin(0.0000001).of(0.10)
        assertThat(one.textInputPerImage).isGreaterThan(0.0)
        val many = OpenAiImagePricing.estimate(params.copy(count = 10), 2)!!
        assertThat(many.minimumUsd).isWithin(0.0000001).of(one.minimumUsd * 10)
        val auto = OpenAiImagePricing.estimate(params.copy(quality = "auto"), 0)!!
        assertThat(auto.outputMinPerImage).isWithin(0.0000001).of(0.00588)
        assertThat(auto.outputMaxPerImage).isWithin(0.0000001).of(0.21072)
        listOf(params.copy(size = "auto"), params.copy(size = "3840x3840"), params.copy(model = "unknown"),
            params.copy(quality = "unknown"), params.copy(count = 0)).forEach {
            assertThat(OpenAiImagePricing.estimate(it, 0)).isNull()
        }
        assertThat(OpenAiImagePricing.estimate(params, 17)).isNull()

        fun usage(json: String, edits: Boolean = false) = OpenAiImageApi.parseUsage(Json.parseToJsonElement(json), edits)
        val complete = usage("""{"input_tokens":120,"input_tokens_details":{"text_tokens":100,"image_tokens":20},"output_tokens":200,"output_tokens_details":{"image_tokens":200,"text_tokens":0}}""", true)!!
        val snapshot = OpenAiImageSnapshot(params.copy(count = 10), complete,
            pricing = OpenAiImagePricing.officialRates, estimate = many, returnedImageCount = 8)
        val roundTrip = OpenAiImageCodec.decode(OpenAiImageCodec.encode(snapshot))!!
        assertThat(roundTrip).isEqualTo(snapshot)
        // The response is request-wide: don't multiply by 10 requested or 8 returned images.
        assertThat(roundTrip.estimatedUsd!!).isWithin(0.0000001).of(0.00666)
        assertThat(OpenAiImageCodec.decode("""{"params":{"prompt":"legacy"}}""")!!.estimate).isNull()
        assertThat(usage("""{"input_tokens":100,"output_tokens":200}""", true)).isNull()
        listOf("null", "{}", "[]", """{"input_tokens":-1,"output_tokens":200}""",
            """{"input_tokens":100,"output_tokens":200,"output_tokens_details":{"image_tokens":190,"text_tokens":10}}""",
            """{"input_tokens":100,"input_tokens_details":{"text_tokens":90,"image_tokens":20},"output_tokens":200}""")
            .forEach { assertThat(usage(it)).isNull() }
        assertThat(OpenAiImageRates().cost(OpenAiImageUsage(-1, 0, 0))).isNull()
        assertThat(OpenAiImageRates(imageOutputPerMillion = Double.NaN).cost(complete)).isNull()
        assertThat(snapshot.copy(pricing = OpenAiImageRates(imageOutputPerMillion = 60.0)).estimatedUsd!!)
            .isWithin(0.0000001).of(0.01266)
    }
}
