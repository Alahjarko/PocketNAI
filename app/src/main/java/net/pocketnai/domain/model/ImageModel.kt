package net.pocketnai.domain.model

/**
 * 首版支持的四个图像模型（规划书 2.1）。
 *
 * 这里只保留稳定的模型身份信息；默认参数、可用采样器和尺寸范围一律放在
 * [ModelProfile] / [ModelCatalog] 中，便于按 NovelAI 官方网页版的实际默认值校正，
 * 而不用改动模型枚举。
 */
enum class ImageModel(
    val apiModelId: String,
    /**
     * 局部重绘（`action = "infill"`）要用的**专用模型 ID**。
     *
     * 来源与验证（技术决策记录第十七、十八节，2026-09-14）：
     * 先反解官方网页前端 bundle 得到映射，再用零成本的 suggest-tags 端点
     * 确认每个 ID 存在，最后由一次真机生成确认 `infill` 被接受。
     * 此前所有 `Model ... doesn't support action infill` 都是因为发了常规模型 ID。
     *
     * 注意 [V5_CURATED] 的映射是官方前端的原样行为：服务端没有
     * `nai-diffusion-5-curated-inpainting`（实测 400），V5 Curated 的重绘
     * 回落到 V4.5 Curated 的重绘模型。
     */
    val inpaintingApiModelId: String,
    val displayName: String,
    val family: GenerationFamily,
    val tier: ModelTier,
) {
    V4_5_CURATED(
        apiModelId = "nai-diffusion-4-5-curated",
        inpaintingApiModelId = "nai-diffusion-4-5-curated-inpainting",
        displayName = "NovelAI V4.5 Curated",
        family = GenerationFamily.V4_5,
        tier = ModelTier.CURATED,
    ),
    V4_5_FULL(
        apiModelId = "nai-diffusion-4-5-full",
        inpaintingApiModelId = "nai-diffusion-4-5-full-inpainting",
        displayName = "NovelAI V4.5 Full",
        family = GenerationFamily.V4_5,
        tier = ModelTier.FULL,
    ),
    V5_CURATED(
        apiModelId = "nai-diffusion-5-curated",
        // 官方前端的原样映射：V5 Curated 没有自己的重绘模型，回落到 V4.5 的。
        inpaintingApiModelId = "nai-diffusion-4-5-curated-inpainting",
        displayName = "NovelAI V5 Curated",
        family = GenerationFamily.V5,
        tier = ModelTier.CURATED,
    ),
    V5_FULL(
        apiModelId = "nai-diffusion-5-full",
        inpaintingApiModelId = "nai-diffusion-5-full-inpainting",
        displayName = "NovelAI V5 Full",
        family = GenerationFamily.V5,
        tier = ModelTier.FULL,
    ),
    ;

    companion object {
        /** 新建任务的默认模型。 */
        val DEFAULT: ImageModel = V4_5_CURATED

        fun fromApiModelId(apiModelId: String): ImageModel? =
            entries.firstOrNull { it.apiModelId == apiModelId }
    }
}

/**
 * 模型档位。
 *
 * 历史上（2026-09-14 上午）曾用它区分"服务端接不接受 `infill`"，后来查明真正的原因是
 * 请求用了常规模型 ID 而不是 [ImageModel.inpaintingApiModelId]（技术决策记录第十七节）。
 * 档位本身目前不再承担能力判定，仅作产品信息保留。
 */
enum class ModelTier { CURATED, FULL }

/**
 * 悬浮层摘要里用的短名（去掉厂商前缀）。
 *
 * 摘要那一行还要放尺寸与质量标签，带上 `NovelAI ` 前缀会把后面的内容挤到省略号里。
 */
val ImageModel.shortDisplayName: String
    get() = displayName.removePrefix("NovelAI ")

/** 模型代际。首版只用它表达多语言提示词能力差异，不用来分裂 UI。 */
enum class GenerationFamily(val displayName: String) {
    V4_5("V4.5"),
    V5("V5"),
}
