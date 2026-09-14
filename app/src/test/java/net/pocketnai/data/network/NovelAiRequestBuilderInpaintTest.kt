package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
 * 请求形态已按官方网页前端 bundle 对齐（技术决策记录第十七节，2026-09-14）：
 * 1. `model` 换成专用的 `-inpainting` 模型 ID —— 此前的
 *    `doesn't support action infill` 就是因为发了常规模型 ID；
 * 2. `image` + `mask` 必须同时存在，缺一个就整组不发；
 * 3. `add_original_image` / `sm` / `sm_dyn` / `extra_noise_seed` 按官方原样照发；
 * 4. 强度等于官方默认值 1.0 时**不发**嵌套 `img2img` 对象，否则发
 *    `{strength, color_correct: true}`；
 * 5. 其它模式的请求体逐字节不变（由 T2I / Img2Img / Vibe / Director 各自的测试守住）。
 */
class NovelAiRequestBuilderInpaintTest {

    private val profile = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)
    private val v5 = ModelCatalog.profileOf(ImageModel.V5_CURATED)

    private fun base(strength: Double? = 0.7) = ReferenceImage(
        id = "base",
        role = ReferenceRole.IMG2IMG,
        ordinal = 0,
        relativePath = "references/base.png",
        width = 1216,
        height = 832,
        byteSize = 100,
        sha256 = "base",
        createdAt = 0L,
        strength = strength,
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

    // ---- 专用模型 ID（本次修正的核心） ----

    @Test
    fun `重绘请求使用专用的 inpainting 模型 ID`() {
        // 官方网页前端反解：重绘不发常规模型 ID，而是对应的 -inpainting 变体。
        // 这就是此前 "Model nai-diffusion-4-5-curated doesn't support action infill"
        // 的真正原因 —— 常规模型 ID 本就不接受这个 action。
        val json = build(listOf(base(), mask()), fullUpstream)

        assertThat(json.getValue("model").jsonPrimitive.content)
            .isEqualTo("nai-diffusion-4-5-curated-inpainting")
        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("infill")
    }

    @Test
    fun `四个模型的重绘模型 ID 映射与官方前端一致`() {
        // 映射来自官方前端的 switch（原样照搬），且每个目标 ID 都用零成本的
        // suggest-tags 端点实测存在（无效模型会 400）。
        val expected = mapOf(
            ImageModel.V4_5_CURATED to "nai-diffusion-4-5-curated-inpainting",
            ImageModel.V4_5_FULL to "nai-diffusion-4-5-full-inpainting",
            // 官方前端的原样行为：V5 Curated 没有自己的重绘模型
            // （nai-diffusion-5-curated-inpainting 实测 400），回落到 V4.5 的。
            ImageModel.V5_CURATED to "nai-diffusion-4-5-curated-inpainting",
            ImageModel.V5_FULL to "nai-diffusion-5-full-inpainting",
        )
        expected.forEach { (model, inpaintingId) ->
            assertThat(model.inpaintingApiModelId).isEqualTo(inpaintingId)
        }
    }

    @Test
    fun `非重绘模式的模型字段不受映射影响`() {
        val json = NovelAiRequestBuilder.build(
            profile = profile,
            request = GenerationRequest(params = params()),
            upstreamImages = emptyMap(),
        )

        assertThat(json.getValue("model").jsonPrimitive.content)
            .isEqualTo("nai-diffusion-4-5-curated")
    }

    // ---- 字段形态 ----

    @Test
    fun `底图与蒙版分别落在 image 与 mask`() {
        val parameters = parameters(build(listOf(base(), mask()), fullUpstream))

        assertThat(parameters.getValue("image").jsonPrimitive.content).isEqualTo("BASE64")
        assertThat(parameters.getValue("mask").jsonPrimitive.content).isEqualTo("MASK64")
    }

    @Test
    fun `官方恒发的字段原样照发`() {
        // 官方前端的 infill 请求里固定出现：add_original_image=false、
        // sm=false、sm_dyn=false、extra_noise_seed=seed-1（带底图时的官方取值）。
        val parameters = parameters(build(listOf(base(), mask()), fullUpstream))

        assertThat(parameters.getValue("add_original_image").jsonPrimitive.boolean).isFalse()
        assertThat(parameters.getValue("sm").jsonPrimitive.boolean).isFalse()
        assertThat(parameters.getValue("sm_dyn").jsonPrimitive.boolean).isFalse()
        assertThat(parameters.getValue("extra_noise_seed").jsonPrimitive.long)
            .isEqualTo(parameters.getValue("seed").jsonPrimitive.long - 1)
    }

    @Test
    fun `默认强度 1_0 时不发嵌套 img2img 对象`() {
        // 官方重绘面板的滑块初值是 1，且等于 1 时请求里没有 img2img 字段。
        val parameters = parameters(
            build(listOf(base(strength = 1.0), mask()), fullUpstream),
        )

        assertThat(parameters).doesNotContainKey("img2img")
        assertThat(parameters).doesNotContainKey("strength")
    }

    @Test
    fun `未设置 strength 时按官方默认 1_0 处理`() {
        val parameters = parameters(
            build(listOf(base(strength = null), mask()), fullUpstream),
        )

        assertThat(parameters).doesNotContainKey("img2img")
    }

    @Test
    fun `非默认强度发嵌套 img2img 且 color_correct 为 true`() {
        // 官方：inpaintImg2ImgStrength != 1 时发 img2img={strength, color_correct:true}。
        // 注意 color_correct 是 true，与图生图顶层的 false 相反，两处都是官方原样行为。
        val parameters = parameters(build(listOf(base(strength = 0.5), mask()), fullUpstream))

        assertThat(parameters).doesNotContainKey("strength")
        val nested = parameters.getValue("img2img").jsonObject
        assertThat(nested.getValue("strength").jsonPrimitive.double).isEqualTo(0.5)
        assertThat(nested.getValue("color_correct").jsonPrimitive.boolean).isTrue()
    }

    @Test
    fun `嵌套对象里不发 noise 与 extra_noise_seed`() {
        val parameters = parameters(build(listOf(base(strength = 0.5), mask()), fullUpstream))
        val nested = parameters.getValue("img2img").jsonObject

        assertThat(nested).doesNotContainKey("noise")
        assertThat(nested).doesNotContainKey("extra_noise_seed")
    }

    @Test
    fun `越界的 strength 先夹取再按官方规则取舍`() {
        // 9.0 夹取到 1.0 = 官方默认 → 嵌套对象整个不发；
        // -0.5 夹取到 0.0 → 发 {strength: 0.0, color_correct: true}。
        val high = parameters(build(listOf(base(strength = 9.0), mask()), fullUpstream))
        assertThat(high).doesNotContainKey("img2img")

        val low = parameters(build(listOf(base(strength = -0.5), mask()), fullUpstream))
        assertThat(
            low.getValue("img2img").jsonObject.getValue("strength").jsonPrimitive.double,
        ).isEqualTo(0.0)
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
        // 残缺请求按载荷退化，action 与 model 保持同一个判定：
        // 蒙版缺失由 validate 用 MissingInpaintMask 在更早的环节拦下。
        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("generate")
        assertThat(json.getValue("model").jsonPrimitive.content)
            .isEqualTo("nai-diffusion-5-full")
    }

    @Test
    fun `只有蒙版没有底图时不发这组字段`() {
        val parameters = parameters(
            build(listOf(mask()), mapOf(ReferenceRole.INPAINT_MASK to listOf("MASK64"))),
        )

        assertThat(parameters).doesNotContainKey("image")
        assertThat(parameters).doesNotContainKey("mask")
    }

    @Test
    fun `有蒙版没底图时绝不能发出 常规模型ID加infill 的自相矛盾请求`() {
        // 2026-09-14 用户实测踩中的 bug：移除底图后蒙版残留，请求带着
        // infill 却用了常规模型 ID，服务端回 "doesn't support action infill"。
        // 两道闸：validate 用 MissingInpaintBase 拦下；构造器按载荷退化为普通生成。
        val maskOnly = listOf(mask())
        val request = GenerationRequest(
            params = params(),
            mode = GenerationMode.INPAINT,
            references = maskOnly,
        )
        assertThat(request.validate(profile))
            .contains(ReferenceViolation.MissingInpaintBase)

        val json = build(
            maskOnly,
            mapOf(ReferenceRole.INPAINT_MASK to listOf("MASK64")),
        )
        assertThat(json.getValue("action").jsonPrimitive.content).isNotEqualTo("infill")
        assertThat(json.getValue("model").jsonPrimitive.content)
            .isEqualTo(profile.model.apiModelId)
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

        // 能力位已实测开放（技术决策记录第十八节），
        // 底图/蒙版/参数合法的请求不应有任何校验问题。
        assertThat(request.validate(fullProfile)).isEmpty()
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

    // ---- 模型能力位 ----

    @Test
    fun `四个模型的重绘入口都开放`() {
        // 真机实测通过（2026-09-14，技术决策记录第十八节）：
        // 专用 -inpainting 模型 ID + infill 被服务端接受，蒙版约定 PAINTED_IS_WHITE 确认。
        ImageModel.entries.forEach { model ->
            val modelProfile = ModelCatalog.profileOf(model)
            assertThat(modelProfile.supportsInpaint).isTrue()

            val request = GenerationRequest(
                params = params().copy(model = model),
                mode = GenerationMode.INPAINT,
                references = listOf(base(), mask()),
            )
            assertThat(request.validate(modelProfile))
                .doesNotContain(ReferenceViolation.ModeUnsupported(GenerationMode.INPAINT))
        }
    }

    @Test
    fun `验证通过后只需改能力位即可启用`() {
        // 请求构造本身已按官方前端对齐：把 supportsInpaint 打开后，
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
        assertThat(json.getValue("model").jsonPrimitive.content)
            .isEqualTo("nai-diffusion-5-full-inpainting")
        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("infill")
        assertThat(parameters(json)).containsKey("mask")
    }

    // ---- 不影响其它模式 ----

    @Test
    fun `纯文生图仍然使用 generate 且不携带任何重绘字段`() {
        val json = NovelAiRequestBuilder.build(
            profile = profile,
            request = GenerationRequest(params = params()),
            upstreamImages = emptyMap(),
        )
        val parameters = parameters(json)

        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("generate")
        listOf("mask", "add_original_image", "sm", "sm_dyn", "extra_noise_seed", "img2img")
            .forEach { field -> assertThat(parameters).doesNotContainKey(field) }
    }
}
