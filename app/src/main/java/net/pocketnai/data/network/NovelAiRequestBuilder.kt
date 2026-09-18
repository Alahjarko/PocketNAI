package net.pocketnai.data.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.DirectorReferenceKind
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.model.ModelTier
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

    /**
     * 局部重绘的 action。
     *
     * 来源：服务端自己的报错 —— `image is not allowed for regular generations, use img2img or infill`
     * （做图生图时实测到的，见技术决策记录第十二节）。
     */
    const val ACTION_INFILL: String = "infill"

    /**
     * Vibe 两个滑块的默认值（2026-09-18 已核对为官方值，技术决策记录 §30.7）。
     *
     * 官方前端按模型给默认值：`strength` 恒为 **0.6**；`information_extracted`
     * **V4.5 Full 是 0.7，其余模型（含 V4.5 Curated 与 V5）是 1.0**。
     * 我们对所有模型统一用 0.6 / 1.0 —— 与官方只差 V4.5 Full 的那 0.3，
     * 目前没有做按模型分叉（要动的话记得它同时是 encode-vibe 的缓存键之一）。
     *
     * 这两个值必须由客户端发送（数组要与图片一一对应），没有留空的余地。
     */
    private const val VIBE_DEFAULT_STRENGTH = 0.6
    private const val VIBE_DEFAULT_INFORMATION = 1.0

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
     * `noise`、`extra_noise_seed`、`add_original_image`、`color_correct` 对文生图/图生图
     * 一律不发：这些字段的官方默认值尚未核对，**留空让服务端用它自己的默认值**，
     * 比我们猜一个值更接近官方行为。等阶段 0 核对后再决定是否暴露给用户。
     *
     * 唯一的例外是局部重绘：官方前端的 infill 请求形态已经反解确认
     * （见 [appendInpaint]），那几个字段在重绘里按官方原样照发。
     */
    fun build(
        profile: ModelProfile,
        request: GenerationRequest,
        upstreamImages: Map<ReferenceRole, List<String>>,
        /**
         * 流式请求：在 `parameters` 里显式声明 `stream = "sse"`（OpenAPI 的
         * `image.StreamingType` 枚举是 msgpack / sse）。官方网页用的是 msgpack，
         * 我们走有文档的 SSE 端点，因此不依赖服务端的默认值。
         */
        streaming: Boolean = false,
    ): JsonObject {
        val params = request.params
        val normalized = profile.normalize(params)

        // 质量标签按官方网页版的行为追加到提示词末尾，而不是交给服务端处理。
        // 官方 UI 会明确显示 "Added to the end of the prompt: ..."，
        // 因此这里必须真的改写提交给模型的正向提示词。
        val positive = applyQualityTags(normalized.prompt, normalized.qualityTags)
        val negative = normalized.negativePrompt

        val baseImage = upstreamImages[ReferenceRole.IMG2IMG]?.firstOrNull()?.takeIf { it.isNotEmpty() }
        val maskImage = upstreamImages[ReferenceRole.INPAINT_MASK]?.firstOrNull()?.takeIf { it.isNotEmpty() }
        val img2imgSource = baseImage?.takeIf { request.mode == GenerationMode.IMG2IMG }
        // 局部重绘要求底图与蒙版同时具备：缺一个都不发，
        // 否则服务端只会回一句笼统的参数错误，指不到真正的原因。
        val useInpaint = request.mode == GenerationMode.INPAINT &&
            baseImage != null &&
            maskImage != null
        val directorSources = upstreamImages[ReferenceRole.DIRECTOR].orEmpty()
        val directors = request.referencesOf(ReferenceRole.DIRECTOR)
        // 数量对不上说明编码环节漏了图：宁可不发这组字段，也不发一个对不齐的数组。
        val useDirector = request.mode == GenerationMode.PRECISE_REFERENCE &&
            directorSources.size == directors.size &&
            directors.isNotEmpty()

        val vibeSources = upstreamImages[ReferenceRole.VIBE].orEmpty()
        val vibes = request.referencesOf(ReferenceRole.VIBE)
        val useVibe = vibeSources.size == vibes.size && vibes.isNotEmpty()

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

            // NovelAI 的 V4.5 / V5 结构化 Prompt（含多角色支持）。
            put("v4_prompt", captionBlock(text = positive, characters = normalized.characters, isNegative = false))
            put("v4_negative_prompt", captionBlock(text = negative, characters = normalized.characters, isNegative = true))

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

            if (useInpaint) {
                appendInpaint(profile, baseImage, maskImage, request, normalized.baseSeed)
            }

            if (useDirector) {
                appendDirectorReferences(profile, directors, directorSources)
            }

            if (useVibe) {
                appendVibeReferences(profile, vibes, vibeSources)
            }

            if (streaming) {
                put("stream", "sse")
            }
        }

        return buildJsonObject {
            put("input", positive)
            // 局部重绘要走专用的 `-inpainting` 模型 ID（官方前端反解结论，
            // 见 ImageModel.inpaintingApiModelId）；常规模型 ID 不接受 infill。
            put(
                "model",
                if (useInpaint) profile.model.inpaintingApiModelId else profile.model.apiModelId,
            )
            put("action", actionFor(img2imgSource != null, useInpaint))
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
     * Vibe Transfer 的三个数组。
     *
     * ## 为什么用 `_multiple` 那一组
     * OpenAPI 同时提供了单数（`reference_image` / `reference_strength` /
     * `reference_information_extracted`）与复数（`..._multiple`）两套字段。
     * 官方网页最多支持 4 张，因此它用的是复数那一套；我们也统一走复数，
     * 免得出现"一张用单数、两张用复数"这种两套代码路径。
     *
     * ## 数组按下标一一对应
     * 与 Precise Reference 一样，任何一项缺失都不能跳过，否则强度会落到错误的图上。
     *
     * ## 传的是编码后的 `.vibe`
     * 调用方给的是 `encode-vibe` 的产物 base64（见 `GenerationRepository.vibeBase64For`）。
     * OpenAPI 没有说明这个数组收原始图片还是编码产物，这一层按官方行为实现且**可摘除**。
     */
    private fun JsonObjectBuilder.appendVibeReferences(
        profile: ModelProfile,
        references: List<ReferenceImage>,
        encoded: List<String>,
    ) {
        val range = profile.img2imgStrengthRange
        put("reference_image_multiple", buildJsonArray { encoded.forEach { add(it) } })
        put(
            "reference_information_extracted_multiple",
            buildJsonArray {
                references.forEach { add(range.clamp(it.informationExtracted ?: VIBE_DEFAULT_INFORMATION)) }
            },
        )
        put(
            "reference_strength_multiple",
            buildJsonArray {
                references.forEach { add(range.clamp(it.strength ?: VIBE_DEFAULT_STRENGTH)) }
            },
        )
    }

    /**
     * 局部重绘的字段（`action = "infill"`，模型换成 [ImageModel.inpaintingApiModelId]）。
     *
     * ## 字段集合已按官方前端 bundle 对齐（技术决策记录第十七节，2026-09-14）
     * 官方网页的 infill 请求里固定出现、而我们此前按"默认值未知就不发"省掉的字段，
     * 现在有了确切依据，对重绘**一律照发**：
     * - `add_original_image = false`（官方 infill 恒发）；
     * - `sm = false`、`sm_dyn = false`（官方对**一切带底图的请求**恒发）；
     * - `extra_noise_seed = seed - 1`（同上，未显式设置时的官方取值）。
     *
     * ## 强度：默认 1.0 时整个嵌套对象都不发
     * 官方重绘的生效强度是 `inpaintImg2ImgStrength`（计价函数也用它）：
     * 等于 1（官方默认）时请求里**没有** `img2img` 字段；不等于 1 时发
     * `img2img = {strength, color_correct: true}` —— 注意 `color_correct` 是 **true**，
     * 与图生图顶层的 `color_correct: false` 相反，两处都是官方前端的原样行为。
     * `noise` 依然不发（官方重绘请求里没有它）。
     */
    private fun JsonObjectBuilder.appendInpaint(
        profile: ModelProfile,
        baseImageBase64: String,
        maskBase64: String,
        request: GenerationRequest,
        normalizedSeed: Long,
    ) {
        put("image", baseImageBase64)
        put("mask", maskBase64)
        put("add_original_image", false)
        put("sm", false)
        put("sm_dyn", false)
        put("extra_noise_seed", normalizedSeed - 1)

        val strength = profile.img2imgStrengthRange.clamp(
            request.img2imgSource?.strength ?: profile.defaultInpaintStrength,
        )
        if (strength != profile.defaultInpaintStrength) {
            put(
                "img2img",
                buildJsonObject {
                    put("strength", strength)
                    put("color_correct", true)
                },
            )
        }
    }

    /**
     * action 取值。
     *
     * - 局部重绘：`infill`。**只有这个 action 会真正读取蒙版** ——
     *   实测把蒙版挂到 `img2img` 上时服务端完全忽略它（涂了 3.55% 的区域，
     *   出图却有 52.6% 的像素变了、包围盒是整张图，即做了一次普通图生图）。
     *   此前 `infill` 被服务端拒绝（`Model ... doesn't support action infill`）
     *   的真正原因是**用错了模型 ID**：重绘必须换用 `ImageModel.inpaintingApiModelId`
     *   （官方前端反解 + 真机验证结论，技术决策记录第十七、十八节），
     *   见本类 `build` 里的 `model` 字段。
     * - 图生图：`img2img`（`image` 字段只在那个 action 下被接受，真机验证结论）；
     * - 其余（含 Precise Reference / Vibe）：`generate`。
     *
     * ⚠️ **action 与 model 必须用同一个判定**（都只看载荷是否齐备的 [useInpaint]）。
     * 曾经 action 看模式、model 看载荷，两者在"有蒙版没底图"的残缺状态下脱钩，
     * 发出"常规模型 ID + infill"的组合，被服务端 400（2026-09-14 用户实测）。
     * 残缺请求由 `GenerationRequest.validate` 在更早的环节拦下（MissingInpaintBase /
     * MissingInpaintMask），这里的严格判定是兜底：宁可退化成一次普通生成，
     * 也不能发出自相矛盾的请求。
     */
    private fun actionFor(
        hasImg2ImgImage: Boolean,
        useInpaint: Boolean,
    ): String = when {
        useInpaint -> ACTION_INFILL
        hasImg2ImgImage -> ACTION_IMG2IMG
        else -> ACTION_GENERATE
    }

    private fun captionBlock(
        text: String,
        characters: List<CharacterPrompt> = emptyList(),
        isNegative: Boolean,
    ): JsonObject = buildJsonObject {
        val activeCharacters = characters.filter { !it.isBlank }
        val hasCharacters = activeCharacters.isNotEmpty()

        put(
            "caption",
            buildJsonObject {
                put("base_caption", text)
                put(
                    "char_captions",
                    buildJsonArray {
                        activeCharacters.forEach { character ->
                            add(
                                buildJsonObject {
                                    put(
                                        "char_caption",
                                        if (isNegative) character.negativePrompt else character.prompt,
                                    )
                                    put(
                                        "centers",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("x", character.centerX)
                                                    put("y", character.centerY)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            },
        )
        if (isNegative) {
            // 负向提示词不使用角色坐标，保留 legacy_uc = false 以匹配网页版行为。
            put("legacy_uc", false)
        } else {
            put("use_coords", hasCharacters)
            put("use_order", true)
        }
    }

    /** 便于测试与调试：把请求体序列化成单行 JSON。 */
    fun buildJsonString(profile: ModelProfile, params: GenerationParams): String =
        build(profile, params).toString()
}
