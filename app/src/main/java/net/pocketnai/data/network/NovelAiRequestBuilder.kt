package net.pocketnai.data.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.pocketnai.domain.model.DirectorReferenceKind
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.model.ModelProfile
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
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

    /**
     * 整图图生图的 action。
     *
     * **这是真机验证出来的，不是推测。** 起初图生图沿用了 `action = "generate"`，服务端返回
     * 400：`image is not allowed for regular generations, use img2img or infill` ——
     * 也就是说 `image` 字段只在 `action` 为 `img2img` / `infill` 时才被接受。
     * 详见《参考图功能规划书》与技术决策记录。
     */
    const val ACTION_IMG2IMG: String = "img2img"

    /** 构造完整请求体。会先按模型档案归一化参数，保证不会提交已知无效组合。 */
    fun build(profile: ModelProfile, params: GenerationParams): JsonObject =
        build(profile, GenerationRequest(params = params), sourceImageBase64 = null)

    fun build(
        profile: ModelProfile,
        request: GenerationRequest,
        sourceImageBase64: String?,
    ): JsonObject = build(
        profile = profile,
        request = request,
        upstreamImages = sourceImageBase64?.let { mapOf(ReferenceRole.IMG2IMG to listOf(it)) }
            ?: emptyMap(),
    )

    /**
     * 构造完整请求体（含各类参考图）。
     *
     * [upstreamImages] 按角色给出已经编码好的 base64 列表，由调用方在提交前从本地文件生成，
     * 不进入 [GenerationRequest]，也就不会被写进数据库或长期驻留。
     *
     * ## 各类参考图落在不同字段上
     * - Image2Img：`parameters.image` + `parameters.strength`，`action` 换成 `img2img`；
     * - Precise Reference：`parameters.director_reference_*` 五个数组，与上面互不干扰。
     *
     * ## 刻意不发的字段
     * `noise`、`extra_noise_seed`、`add_original_image`、`color_correct` 一律不发：
     * 这些字段的官方默认值尚未核对，**留空让服务端用它自己的默认值**，
     * 比我们猜一个值更接近官方行为。等阶段 0 核对后再决定是否暴露给用户。
     */
    fun build(
        profile: ModelProfile,
        request: GenerationRequest,
        upstreamImages: Map<ReferenceRole, List<String>>,
    ): JsonObject {
        val params = request.params
        val normalized = profile.normalize(params)

        // 质量标签按官方网页版的行为追加到提示词末尾，而不是交给服务端处理。
        // 官方 UI 会明确显示 "Added to the end of the prompt: ..."，
        // 因此这里必须真的改写提交给模型的正向提示词。
        val positive = applyQualityTags(normalized.prompt, normalized.qualityTags)
        val negative = normalized.negativePrompt

        val img2imgSource = upstreamImages[ReferenceRole.IMG2IMG]?.firstOrNull()
            ?.takeIf { request.mode == GenerationMode.IMG2IMG && it.isNotEmpty() }
        val directorSources = upstreamImages[ReferenceRole.DIRECTOR].orEmpty()
        val directors = request.referencesOf(ReferenceRole.DIRECTOR)
        // 数量对不上说明编码环节漏了图：宁可不发这组字段，也不发一个对不齐的数组。
        val useDirector = request.mode == GenerationMode.PRECISE_REFERENCE &&
            directorSources.size == directors.size &&
            directors.isNotEmpty()

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

            // NovelAI 的 V4.5 / V5 需要结构化 Prompt；多角色坐标不在本期范围，char_captions 固定为空。
            put("v4_prompt", captionBlock(text = positive, isNegative = false))
            put("v4_negative_prompt", captionBlock(text = negative, isNegative = true))

            if (img2imgSource != null) {
                put("image", img2imgSource)
                // 越界值在这里夹取，与其它数值参数走同一条规则（ModelProfile 是唯一出口）。
                put(
                    "strength",
                    profile.img2imgStrengthRange.clamp(
                        request.img2imgSource?.strength ?: profile.defaultImg2ImgStrength,
                    ),
                )
            }

            if (useDirector) {
                appendDirectorReferences(profile, directors, directorSources)
            }
        }

        return buildJsonObject {
            put("input", positive)
            put("model", profile.model.apiModelId)
            put("action", actionFor(request.mode, img2imgSource != null))
            put("parameters", parameters)
        }
    }

    /**
     * Precise Reference 的五个数组（官方 OpenAPI 的 `director_reference_*`）。
     *
     * 数组之间**按下标一一对应**，因此任何一项缺失都不能"跳过"，
     * 否则后面对齐会整体错位。这也是为什么这里逐项都有兜底值而不是 `mapNotNull`。
     *
     * `director_reference_descriptions[].caption.base_caption` 用 `character`
     * 或 `character&style`（官方字段说明给出），前者只取角色、后者连画风一起取。
     */
    private fun JsonObjectBuilder.appendDirectorReferences(
        profile: ModelProfile,
        references: List<ReferenceImage>,
        encoded: List<String>,
    ) {
        put("director_reference_images", buildJsonArray { encoded.forEach { add(it) } })

        put(
            "director_reference_descriptions",
            buildJsonArray {
                references.forEach { reference ->
                    add(
                        buildJsonObject {
                            put(
                                "caption",
                                buildJsonObject {
                                    put(
                                        "base_caption",
                                        (reference.directorKind
                                            ?: DirectorReferenceKind.CHARACTER).apiValue,
                                    )
                                    put("char_captions", buildJsonArray { })
                                },
                            )
                            put("use_coords", false)
                            put("use_order", true)
                        },
                    )
                }
            },
        )

        val strengthRange = profile.directorReferenceRange
        put(
            "director_reference_strength_values",
            buildJsonArray {
                references.forEach { add(strengthRange.clamp(it.strength ?: profile.defaultDirectorStrength)) }
            },
        )
        put(
            "director_reference_secondary_strength_values",
            buildJsonArray {
                references.forEach {
                    add(strengthRange.clamp(it.secondaryStrength ?: profile.defaultDirectorFidelity))
                }
            },
        )
        put(
            "director_reference_information_extracted",
            buildJsonArray {
                references.forEach {
                    add(strengthRange.clamp(it.informationExtracted ?: profile.defaultDirectorInfoExtracted))
                }
            },
        )
    }

    /**
     * Precise Reference 不换 action：它是普通 `generate` 请求加上 `director_reference_*` 字段。
     * 只有 Image2Img 需要换 `img2img`（`image` 字段只在那个 action 下被接受，真机验证结论）。
     */
    private fun actionFor(mode: GenerationMode, hasImg2ImgImage: Boolean): String =
        if (hasImg2ImgImage) ACTION_IMG2IMG else ACTION_GENERATE

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
