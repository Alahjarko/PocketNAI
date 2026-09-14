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
    val displayName: String,
    val family: GenerationFamily,
    val tier: ModelTier,
) {
    V4_5_CURATED(
        apiModelId = "nai-diffusion-4-5-curated",
        displayName = "NovelAI V4.5 Curated",
        family = GenerationFamily.V4_5,
        tier = ModelTier.CURATED,
    ),
    V4_5_FULL(
        apiModelId = "nai-diffusion-4-5-full",
        displayName = "NovelAI V4.5 Full",
        family = GenerationFamily.V4_5,
        tier = ModelTier.FULL,
    ),
    V5_CURATED(
        apiModelId = "nai-diffusion-5-curated",
        displayName = "NovelAI V5 Curated",
        family = GenerationFamily.V5,
        tier = ModelTier.CURATED,
    ),
    V5_FULL(
        apiModelId = "nai-diffusion-5-full",
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
 * 目前唯一的用途是区分**服务端对 action 的支持范围**：实机验证发现
 * `nai-diffusion-4-5-curated` 不支持 `action: "infill"`（局部重绘），
 * 服务端原话是 `Model nai-diffusion-4-5-curated doesn't support action infill`。
 * 这与 NovelAI 一贯的"简版模型不开放编辑类操作、完整版才开放"一致。
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
