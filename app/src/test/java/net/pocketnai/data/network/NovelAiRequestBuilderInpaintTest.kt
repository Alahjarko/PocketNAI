package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
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
}
