package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.pocketnai.domain.model.DirectorReferenceKind
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
 * Precise Reference 的请求体（官方 `director_reference_*` 五个数组）。
 *
 * 重点：数组之间**按下标一一对应**，任何一处错位都会让"这个角色的强度"落到"那个角色"上，
 * 而服务端不会报错 —— 结果只是"参数好像没生效"。
 */
class NovelAiRequestBuilderDirectorTest {

    private val v45 = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)
    private val v5 = ModelCatalog.profileOf(ImageModel.V5_CURATED)

    private fun director(
        id: String,
        ordinal: Int,
        kind: DirectorReferenceKind? = DirectorReferenceKind.CHARACTER,
        strength: Double? = null,
        fidelity: Double? = null,
        information: Double? = null,
        width: Int = 1024,
        height: Int = 1536,
    ) = ReferenceImage(
        id = id,
        role = ReferenceRole.DIRECTOR,
        ordinal = ordinal,
        relativePath = "references/$id.png",
        width = width,
        height = height,
        byteSize = 1000,
        sha256 = id,
        createdAt = 0L,
        strength = strength,
        secondaryStrength = fidelity,
        informationExtracted = information,
        directorKind = kind,
    )

    private fun params() = GenerationParams.defaultsFor(v45).copy(
        prompt = "1girl",
        negativePrompt = "lowres",
        qualityTags = QualityTagsOption.NONE,
    )

    private fun build(
        references: List<ReferenceImage>,
        encoded: List<String>,
        mode: GenerationMode = GenerationMode.PRECISE_REFERENCE,
    ): JsonObject = NovelAiRequestBuilder.build(
        profile = v45,
        request = GenerationRequest(params = params(), mode = mode, references = references),
        upstreamImages = mapOf(ReferenceRole.DIRECTOR to encoded),
    )

    private fun parameters(json: JsonObject) = json.getValue("parameters").jsonObject

    // ---- 基本形态 ----

    @Test
    fun `单张参考图发五个数组`() {
        val json = build(listOf(director("a", 0)), listOf("BASE64_A"))
        val parameters = parameters(json)

        assertThat(parameters.getValue("director_reference_images").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("BASE64_A")
        assertThat(parameters).containsKey("director_reference_descriptions")
        assertThat(parameters).containsKey("director_reference_strength_values")
        assertThat(parameters).containsKey("director_reference_secondary_strength_values")
        assertThat(parameters).containsKey("director_reference_information_extracted")
    }

    @Test
    fun `取用方式写进 caption 的 base_caption`() {
        val json = build(
            listOf(
                director("a", 0, kind = DirectorReferenceKind.CHARACTER),
                director("b", 1, kind = DirectorReferenceKind.CHARACTER_AND_STYLE),
            ),
            listOf("A", "B"),
        )

        val captions = parameters(json)
            .getValue("director_reference_descriptions")
            .jsonArray
            .map { it.jsonObject.getValue("caption").jsonObject.getValue("base_caption").jsonPrimitive.content }

        assertThat(captions).containsExactly("character", "character&style").inOrder()
    }

    @Test
    fun `char_captions 保持为空且不启用坐标`() {
        // 多角色坐标不在本期范围；发了坐标反而会改变模型行为。
        val description = parameters(build(listOf(director("a", 0)), listOf("A")))
            .getValue("director_reference_descriptions")
            .jsonArray
            .first()
            .jsonObject

        assertThat(description.getValue("caption").jsonObject.getValue("char_captions").jsonArray).isEmpty()
        assertThat(description.getValue("use_coords").jsonPrimitive.content).isEqualTo("false")
    }

    // ---- 下标对齐 ----

    @Test
    fun `三个数值数组与图片逐张对应`() {
        val json = build(
            listOf(
                director("a", 0, strength = 0.3, fidelity = 0.4, information = 0.5),
                director("b", 1, strength = 0.6, fidelity = 0.7, information = 0.8),
            ),
            listOf("A", "B"),
        )
        val parameters = parameters(json)

        fun values(key: String) = parameters.getValue(key).jsonArray.map { it.jsonPrimitive.double }

        assertThat(values("director_reference_strength_values")).containsExactly(0.3, 0.6).inOrder()
        assertThat(values("director_reference_secondary_strength_values")).containsExactly(0.4, 0.7).inOrder()
        assertThat(values("director_reference_information_extracted")).containsExactly(0.5, 0.8).inOrder()
    }

    @Test
    fun `未设置的滑块用模型默认值补齐而不是跳过`() {
        // 跳过会让后面的下标整体错位 —— 那是最难查的一类问题。
        val json = build(
            listOf(director("a", 0), director("b", 1, strength = 0.9)),
            listOf("A", "B"),
        )
        val strengths = parameters(json)
            .getValue("director_reference_strength_values")
            .jsonArray
            .map { it.jsonPrimitive.double }

        assertThat(strengths).hasSize(2)
        assertThat(strengths[0]).isEqualTo(v45.defaultDirectorStrength)
        assertThat(strengths[1]).isEqualTo(0.9)
    }

    @Test
    fun `越界的滑块值被夹取`() {
        val json = build(
            listOf(director("a", 0, strength = 5.0, fidelity = -1.0, information = 99.0)),
            listOf("A"),
        )
        val parameters = parameters(json)

        assertThat(parameters.getValue("director_reference_strength_values").jsonArray.first().jsonPrimitive.double)
            .isEqualTo(1.0)
        assertThat(
            parameters.getValue("director_reference_secondary_strength_values").jsonArray.first()
                .jsonPrimitive.double,
        ).isEqualTo(0.0)
        assertThat(
            parameters.getValue("director_reference_information_extracted").jsonArray.first()
                .jsonPrimitive.double,
        ).isEqualTo(1.0)
    }

    @Test
    fun `编码数量与参考图数量不一致时整组字段都不发`() {
        // 发一个对不齐的数组比不发更糟：服务端不会报错，只是行为诡异。
        val json = build(listOf(director("a", 0), director("b", 1)), listOf("A"))
        val parameters = parameters(json)

        listOf(
            "director_reference_images",
            "director_reference_descriptions",
            "director_reference_strength_values",
        ).forEach { assertThat(parameters).doesNotContainKey(it) }
    }

    // ---- action 与形状 ----

    @Test
    fun `Precise Reference 不换 action`() {
        // 它是普通 generate 加上 director 字段；换成 img2img 会让服务端按另一种语义处理。
        val json = build(listOf(director("a", 0)), listOf("A"))

        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("generate")
    }

    @Test
    fun `Precise Reference 不发 image 字段`() {
        val parameters = parameters(build(listOf(director("a", 0)), listOf("A")))

        assertThat(parameters).doesNotContainKey("image")
        assertThat(parameters).doesNotContainKey("strength")
    }

    @Test
    fun `没有参考图时不发任何 director 字段`() {
        val json = NovelAiRequestBuilder.build(
            profile = v45,
            request = GenerationRequest(params = params()),
            upstreamImages = emptyMap(),
        )

        assertThat(parameters(json).keys.filter { it.startsWith("director_reference") }).isEmpty()
    }

    // ---- 能力位 ----

    @Test
    fun `V5 不支持 Precise Reference`() {
        // 账号所有者确认：参考条件类功能目前只有 V4.5 能用。
        assertThat(v45.supportsDirectorReference).isTrue()
        assertThat(v5.supportsDirectorReference).isFalse()
    }

    @Test
    fun `V5 不支持 Vibe Transfer 而 V4_5 支持`() {
        assertThat(v45.supportsVibeTransfer).isTrue()
        assertThat(v5.supportsVibeTransfer).isFalse()
    }

    @Test
    fun `图生图四个模型都支持`() {
        ImageModel.entries.forEach { model ->
            assertThat(ModelCatalog.profileOf(model).supportsImg2Img).isTrue()
        }
    }
}
