package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import org.junit.Test

/**
 * Vibe Transfer 的请求体（`reference_*_multiple` 三个数组）。
 *
 * 与 Precise Reference 一样，数组按下标一一对应，缺项不能跳过。
 * 另外这里钉住一条：**Vibe 是叠加的** —— 它与图生图同时存在时，两套字段都要发，
 * 而且 `action` 由图生图决定（Vibe 自己不换 action）。
 */
class NovelAiRequestBuilderVibeTest {

    private val v45 = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)

    private fun vibe(
        id: String,
        ordinal: Int,
        strength: Double? = null,
        information: Double? = null,
    ) = ReferenceImage(
        id = id,
        role = ReferenceRole.VIBE,
        ordinal = ordinal,
        relativePath = "references/$id.png",
        width = 1000,
        height = 1000,
        byteSize = 100,
        sha256 = id,
        createdAt = 0L,
        strength = strength,
        informationExtracted = information,
        vibeRelativePath = "vibes/$id.vibe",
    )

    private fun img2img(id: String = "base") = ReferenceImage(
        id = id,
        role = ReferenceRole.IMG2IMG,
        ordinal = 0,
        relativePath = "references/$id.png",
        width = 2560,
        height = 1440,
        byteSize = 100,
        sha256 = id,
        createdAt = 0L,
        strength = 0.7,
    )

    private fun params() = GenerationParams.defaultsFor(v45).copy(
        prompt = "1girl",
        negativePrompt = "lowres",
        qualityTags = QualityTagsOption.NONE,
    )

    private fun build(
        references: List<ReferenceImage>,
        upstream: Map<ReferenceRole, List<String>>,
        mode: GenerationMode = GenerationMode.TXT2IMG,
    ): JsonObject = NovelAiRequestBuilder.build(
        profile = v45,
        request = GenerationRequest(params = params(), mode = mode, references = references),
        upstreamImages = upstream,
    )

    private fun parameters(json: JsonObject) = json.getValue("parameters").jsonObject

    // ---- 基本形态 ----

    @Test
    fun `单张 Vibe 发三个数组`() {
        val parameters = parameters(
            build(listOf(vibe("a", 0)), mapOf(ReferenceRole.VIBE to listOf("VIBE_A"))),
        )

        assertThat(parameters.getValue("reference_image_multiple").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("VIBE_A")
        assertThat(parameters).containsKey("reference_information_extracted_multiple")
        assertThat(parameters).containsKey("reference_strength_multiple")
    }

    @Test
    fun `Vibe 不换 action`() {
        val json = build(listOf(vibe("a", 0)), mapOf(ReferenceRole.VIBE to listOf("A")))

        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("generate")
    }

    @Test
    fun `两个滑块与图片逐张对应`() {
        val parameters = parameters(
            build(
                listOf(
                    vibe("a", 0, strength = 0.3, information = 0.4),
                    vibe("b", 1, strength = 0.8, information = 0.9),
                ),
                mapOf(ReferenceRole.VIBE to listOf("A", "B")),
            ),
        )

        assertThat(
            parameters.getValue("reference_strength_multiple").jsonArray.map { it.jsonPrimitive.double },
        ).containsExactly(0.3, 0.8).inOrder()
        assertThat(
            parameters.getValue("reference_information_extracted_multiple").jsonArray
                .map { it.jsonPrimitive.double },
        ).containsExactly(0.4, 0.9).inOrder()
    }

    @Test
    fun `未设置的滑块用默认值补齐而不是跳过`() {
        // 跳过会让强度落到错误的图上，而服务端不会报错。
        val parameters = parameters(
            build(
                listOf(vibe("a", 0), vibe("b", 1, strength = 0.9)),
                mapOf(ReferenceRole.VIBE to listOf("A", "B")),
            ),
        )
        val strengths = parameters.getValue("reference_strength_multiple").jsonArray
            .map { it.jsonPrimitive.double }

        assertThat(strengths).hasSize(2)
        assertThat(strengths[1]).isEqualTo(0.9)
    }

    @Test
    fun `编码数量对不上时整组字段都不发`() {
        val parameters = parameters(
            build(listOf(vibe("a", 0), vibe("b", 1)), mapOf(ReferenceRole.VIBE to listOf("A"))),
        )

        assertThat(parameters).doesNotContainKey("reference_image_multiple")
        assertThat(parameters).doesNotContainKey("reference_strength_multiple")
    }

    // ---- 与其它模式叠加 ----

    @Test
    fun `Vibe 与图生图可以同时存在`() {
        // Vibe 是风格条件，加在图生图之上是常见用法；两套字段互不干扰。
        val json = build(
            references = listOf(img2img(), vibe("a", 0)),
            upstream = mapOf(
                ReferenceRole.IMG2IMG to listOf("BASE"),
                ReferenceRole.VIBE to listOf("VIBE_A"),
            ),
            mode = GenerationMode.IMG2IMG,
        )
        val parameters = parameters(json)

        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("img2img")
        assertThat(parameters.getValue("image").jsonPrimitive.content).isEqualTo("BASE")
        assertThat(parameters.getValue("reference_image_multiple").jsonArray.first().jsonPrimitive.content)
            .isEqualTo("VIBE_A")
    }

    @Test
    fun `纯文字加 Vibe 时不发图生图字段`() {
        val parameters = parameters(
            build(listOf(vibe("a", 0)), mapOf(ReferenceRole.VIBE to listOf("A"))),
        )

        assertThat(parameters).doesNotContainKey("image")
        assertThat(parameters).doesNotContainKey("strength")
    }

    @Test
    fun `Vibe 的三组字段与 Precise Reference 的五个字段不冲突`() {
        val director = ReferenceImage(
            id = "cr",
            role = ReferenceRole.DIRECTOR,
            ordinal = 0,
            relativePath = "references/cr.png",
            width = 1024,
            height = 1536,
            byteSize = 100,
            sha256 = "cr",
            createdAt = 0L,
        )
        val json = build(
            references = listOf(director, vibe("a", 0)),
            upstream = mapOf(
                ReferenceRole.DIRECTOR to listOf("CR"),
                ReferenceRole.VIBE to listOf("VIBE_A"),
            ),
            mode = GenerationMode.PRECISE_REFERENCE,
        )
        val parameters = parameters(json)

        assertThat(parameters).containsKey("director_reference_images")
        assertThat(parameters).containsKey("reference_image_multiple")
        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("generate")
    }

    // ---- 模型能力 ----

    @Test
    fun `V5 上 Vibe 被本地拦下`() {
        val v5 = ModelCatalog.profileOf(ImageModel.V5_CURATED)
        val request = GenerationRequest(
            params = GenerationParams.defaultsFor(v5).copy(prompt = "1girl"),
            references = listOf(vibe("a", 0)),
        )

        assertThat(request.validate(v5))
            .contains(net.pocketnai.domain.model.ReferenceViolation.FeatureUnsupported(ReferenceRole.VIBE))
    }
}
