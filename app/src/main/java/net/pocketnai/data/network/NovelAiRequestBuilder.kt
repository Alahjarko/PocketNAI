package net.pocketnai.data.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ModelProfile
import net.pocketnai.domain.model.applyQualityTags

/**
 * 构造 NovelAI `POST /ai/generate-image` 的请求体（规划书 3.3 与 6.1）。
 *
 * ## 为什么单独成类
 * 这里是纯 Kotlin（不依赖 OkHttp / Android），因此可以在 JVM 单元测试里逐字段断言请求体，
 * 尤其是 V4.5 / V5 要求的结构化 Prompt 不能出错。
 *
 * ## 结构化 Prompt 约定（规划书 3.3）
 * - `v4_prompt.caption.base_caption` = 处理后的正向提示词
 * - `v4_prompt.caption.char_captions` = 空数组
 * - `v4_negative_prompt.caption.base_caption` = 处理后的负向提示词
 * - `v4_negative_prompt.caption.char_captions` = 空数组
 * - 不启用自定义角色坐标（`use_coords = false`）
 *
 * ## 待协议探针核对（规划书 6.3）
 * 下面的字段集合是按公开 OpenAPI 与网页版行为整理的基线。首次用真实账户做本地脱敏探针时，
 * 需要逐项比对：`params_version`、`ucPreset` 的取值域、是否需要 `dynamic_thresholding`、
 * `legacy`、`controlnet_strength`、`add_original_image` 等兼容字段，以及 `v4_negative_prompt`
 * 是否需要 `legacy_uc`。修正只发生在本函数与 [ModelCatalog]，不影响其他层。
 */
object NovelAiRequestBuilder {

    const val ACTION_GENERATE: String = "generate"

    /** 构造完整请求体。会先按模型档案归一化参数，保证不会提交已知无效组合。 */
    fun build(profile: ModelProfile, params: GenerationParams): JsonObject {
        val normalized = profile.normalize(params)

        // 质量标签按官方网页版的行为追加到提示词末尾，而不是交给服务端处理。
        // 官方 UI 会明确显示 "Added to the end of the prompt: ..."，
        // 因此这里必须真的改写提交给模型的正向提示词。
        val positive = applyQualityTags(normalized.prompt, normalized.qualityTags)
        val negative = normalized.negativePrompt

        val parameters = buildJsonObject {
            put("params_version", profile.paramsVersion)

            put("width", normalized.size.width)
            put("height", normalized.size.height)

            put("scale", normalized.guidance)
            put("cfg_rescale", normalized.cfgRescale)
            put("sampler", normalized.sampler.apiValue)
            put("noise_schedule", normalized.noiseSchedule.apiValue)
            put("steps", normalized.steps)

            put("n_samples", normalized.sampleCount)
            put("seed", normalized.baseSeed)

            put("ucPreset", normalized.undesiredContentPresetIndex)
            // 质量标签已经由本应用显式写进提示词，因此关闭服务端的自动追加，
            // 否则同一段文本可能被叠加两次。
            put("qualityToggle", false)
            put("negative_prompt", negative)

            // NovelAI 的 V4.5 / V5 需要结构化 Prompt；首版不做多角色，char_captions 固定为空。
            put("v4_prompt", captionBlock(text = positive, isNegative = false))
            put("v4_negative_prompt", captionBlock(text = negative, isNegative = true))
        }

        return buildJsonObject {
            put("input", positive)
            put("model", profile.model.apiModelId)
            put("action", ACTION_GENERATE)
            put("parameters", parameters)
        }
    }

    private fun captionBlock(text: String, isNegative: Boolean): JsonObject = buildJsonObject {
        put(
            "caption",
            buildJsonObject {
                put("base_caption", text)
                put("char_captions", buildJsonArray { })
            },
        )
        if (isNegative) {
            // 负向提示词不使用角色坐标，保留 legacy_uc = false 以匹配网页版行为。
            put("legacy_uc", false)
        } else {
            put("use_coords", false)
            put("use_order", true)
        }
    }

    /** 便于测试与调试：把请求体序列化成单行 JSON。 */
    fun buildJsonString(profile: ModelProfile, params: GenerationParams): String =
        build(profile, params).toString()
}
