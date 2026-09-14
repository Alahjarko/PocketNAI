package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
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
import net.pocketnai.domain.model.ReferenceViolation
import org.junit.Test

/**
 * 局部重绘的请求体（`action = "infill"`）。
 *
 * 三条纪律：
 * 1. `image` + `mask` 必须同时存在，缺一个就整组不发；
 * 2. 强度发在**嵌套的** `parameters.img2img` 里（OpenAPI 的 `used by inpaint`），不发顶层；
 * 3. 其它模式的请求体逐字节不变。
 */
class NovelAiRequestBuilderInpaintTest {

    private val profile = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)
    private val v5 = ModelCatalog.profileOf(ImageModel.V5_CURATED)

    private fun base() = ReferenceImage(
        id = "base",
        role = ReferenceRole.IMG2IMG,
        ordinal = 0,
        relativePath = "references/base.png",
        width = 1216,
        height = 832,
        byteSize = 100,
        sha256 = "base",
        createdAt = 0L,
        strength = 0.7,
    )

    private fun mask() = ReferenceImage(
        id = "mask",
        role = ReferenceRole.INPAINT_MASK,
        ordinal = 0,
        relativePath = "references/mask.png",
        width = 1216,
        height = 832,
        byteSize = 100,
        sha256 = "mask",
        createdAt = 0L,
    )

    private fun params() = GenerationParams.defaultsFor(profile).copy(
        prompt = "1girl",
        negativePrompt = "lowres",
        qualityTags = QualityTagsOption.NONE,
    )

    private fun build(
        references: List<ReferenceImage>,
        upstream: Map<ReferenceRole, List<String>>,
        mode: GenerationMode = GenerationMode.INPAINT,
        onProfile: net.pocketnai.domain.model.ModelProfile = profile,
    ): JsonObject = NovelAiRequestBuilder.build(
        profile = onProfile,
        request = GenerationRequest(
            params = params().copy(model = onProfile.model),
            mode = mode,
            references = references,
        ),
        upstreamImages = upstream,
    )

    private fun parameters(json: JsonObject) = json.getValue("parameters").jsonObject

    private val fullUpstream = mapOf(
        ReferenceRole.IMG2IMG to listOf("BASE64"),
        ReferenceRole.INPAINT_MASK to listOf("MASK64"),
    )

    // ---- 字段形态 ----

    @Test
    fun `局部重绘使用 infill 这个 action`() {
        // 只有 infill 会真正读取蒙版：实测把蒙版挂到 img2img 上时被完全忽略
        // （涂了 3.55% 的区域，出图却有 52.6% 的像素变了）。
        val json = build(
            references = listOf(base(), mask()),
            upstream = fullUpstream,
            onProfile = ModelCatalog.profileOf(ImageModel.V4_5_FULL),
        )

        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("infill")
        assertThat(json.getValue("action").jsonPrimitive.content)
            .isEqualTo(NovelAiRequestBuilder.ACTION_INFILL)
    }

    @Test
    fun `底图与蒙版分别落在 image 与 mask`() {
        val parameters = parameters(build(listOf(base(), mask()), fullUpstream))

        assertThat(parameters.getValue("image").jsonPrimitive.content).isEqualTo("BASE64")
        assertThat(parameters.getValue("mask").jsonPrimitive.content).isEqualTo("MASK64")
    }

    @Test
    fun `强度发在嵌套的 img2img 对象里而不是顶层`() {
        // OpenAPI 里那个嵌套对象的字段说明是 "used by inpaint"，因此重绘走它。
        val parameters = parameters(build(listOf(base(), mask()), fullUpstream))

        assertThat(parameters).doesNotContainKey("strength")
        val nested = parameters.getValue("img2img").jsonObject
        assertThat(nested.getValue("strength").jsonPrimitive.double).isEqualTo(0.7)
    }

    @Test
    fun `嵌套对象里不擅自发默认值未知的字段`() {
        val nested = parameters(build(listOf(base(), mask()), fullUpstream))
            .getValue("img2img").jsonObject

        listOf("noise", "extra_noise_seed", "color_correct").forEach { field ->
            assertThat(nested).doesNotContainKey(field)
        }
    }

    @Test
    fun `未设置 strength 时用模型默认值`() {
        val parameters = parameters(
            build(listOf(base().copy(strength = null), mask()), fullUpstream),
        )

        assertThat(parameters.getValue("img2img").jsonObject.getValue("strength").jsonPrimitive.double)
            .isEqualTo(profile.defaultImg2ImgStrength)
    }

    @Test
    fun `越界的 strength 被夹取`() {
        val parameters = parameters(
            build(listOf(base().copy(strength = 9.0), mask()), fullUpstream),
        )

        assertThat(parameters.getValue("img2img").jsonObject.getValue("strength").jsonPrimitive.double)
            .isEqualTo(1.0)
    }

    // ---- 缺一不可 ----

    @Test
    fun `只有底图没有蒙版时不发这组字段`() {
        val json = build(
            listOf(base()),
            mapOf(ReferenceRole.IMG2IMG to listOf("BASE64")),
            onProfile = ModelCatalog.profileOf(ImageModel.V5_FULL),
        )
        val parameters = parameters(json)

        assertThat(parameters).doesNotContainKey("mask")
        assertThat(parameters).doesNotContainKey("image")
        // action 仍按模式标明意图（校验会在更早的环节拦下并给出明确原因）。
        assertThat(json.getValue("action").jsonPrimitive.content)
            .isEqualTo(NovelAiRequestBuilder.ACTION_INFILL)
    }

    @Test
    fun `只有蒙版没有底图时不发这组字段`() {
        val parameters = parameters(
            build(listOf(mask()), mapOf(ReferenceRole.INPAINT_MASK to listOf("MASK64"))),
        )

        assertThat(parameters).doesNotContainKey("image")
        assertThat(parameters).doesNotContainKey("mask")
    }

    // ---- 校验 ----

    @Test
    fun `重绘缺少蒙版时不允许提交`() {
        val request = GenerationRequest(
            params = params(),
            mode = GenerationMode.INPAINT,
            references = listOf(base()),
        )

        assertThat(request.validate(profile)).contains(ReferenceViolation.MissingInpaintMask)
    }

    @Test
    fun `底图与蒙版齐全时请求字段完整`() {
        val fullProfile = ModelCatalog.profileOf(ImageModel.V4_5_FULL)
        val request = GenerationRequest(
            params = params().copy(model = ImageModel.V4_5_FULL),
            mode = GenerationMode.INPAINT,
            references = listOf(base(), mask()),
        )

        // 唯一的问题是能力位（接口未开放）；底图/蒙版/参数本身都合法。
        assertThat(request.validate(fullProfile))
            .containsExactly(ReferenceViolation.ModeUnsupported(GenerationMode.INPAINT))
    }

    @Test
    fun `重绘不允许混用参考条件`() {
        // 服务端对互斥关系有硬约束，本地先拦一次。
        val vibe = base().copy(id = "v", role = ReferenceRole.VIBE, ordinal = 0)
        val request = GenerationRequest(
            params = params(),
            mode = GenerationMode.INPAINT,
            references = listOf(base(), mask(), vibe),
        )

        assertThat(request.validate(profile))
            .contains(ReferenceViolation.ConflictingWithMode(GenerationMode.INPAINT))
    }

    // ---- 模型能力：重绘属于 Image2Img 家族 ----

    @Test
    fun `公开 API 目前一律不支持局部重绘`() {
        // 服务端对 V4.5 的 Curated 与 Full 都回 "doesn't support action infill"，
        // 因此在接口开放之前，四个模型的入口都是关闭的 —— 免得给用户一个
        // 必然失败（在未核实的模型上还可能真扣费）的按钮。
        ImageModel.entries.forEach { model ->
            val modelProfile = ModelCatalog.profileOf(model)
            assertThat(modelProfile.supportsInpaint).isFalse()

            val request = GenerationRequest(
                params = params().copy(model = model),
                mode = GenerationMode.INPAINT,
                references = listOf(base(), mask()),
            )
            assertThat(request.validate(modelProfile))
                .contains(ReferenceViolation.ModeUnsupported(GenerationMode.INPAINT))
        }
    }

    @Test
    fun `接口开放后只需改能力位即可启用`() {
        // 请求构造本身是按 OpenAPI 写好的：把 supportsInpaint 打开后，
        // 校验通过、字段齐全 —— 这条断言锁住"代码已就绪"这件事。
        val fullProfile = ModelCatalog.profileOf(ImageModel.V5_FULL)
        val request = GenerationRequest(
            params = params().copy(model = ImageModel.V5_FULL),
            mode = GenerationMode.INPAINT,
            references = listOf(base(), mask()),
        )
        val violations = request.validate(fullProfile)
            .filterNot { it is ReferenceViolation.ModeUnsupported }

        assertThat(violations).isEmpty()

        val json = build(
            references = listOf(base(), mask()),
            upstream = fullUpstream,
            onProfile = fullProfile,
        )
        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("infill")
        assertThat(parameters(json)).containsKey("mask")
    }

    // ---- 不影响其它模式 ----

    @Test
    fun `纯文生图仍然使用 generate`() {
        val json = NovelAiRequestBuilder.build(
            profile = profile,
            request = GenerationRequest(params = params()),
            upstreamImages = emptyMap(),
        )

        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("generate")
        assertThat(parameters(json)).doesNotContainKey("mask")
    }
}
