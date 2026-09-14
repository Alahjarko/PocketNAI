package net.pocketnai.domain.metadata

import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.Sampler

/**
 * 从图片里读出来的 NovelAI 生成信息（纯数据，不含任何 Android 类型）。
 *
 * ## 字段来源（2026-09-14 用本机真实生成图核对过）
 * PNG 文本块（顺序不固定，关键字大小写敏感但我们按忽略大小写匹配）：
 *
 * | 关键字 | 内容 |
 * |---|---|
 * | `Software` | 固定 `NovelAI`，是"这是不是 NovelAI 图片"的唯一依据 |
 * | `Source` | `NovelAI Diffusion V5 0ADF9AB7` 之类：模型名 + 模型哈希 |
 * | `Description` | 提交时的提示词（含 Randomizer 原文） |
 * | `Comment` | **完整参数 JSON**（下面 [settings] / [prompt] 等大多来自它） |
 * | `Generation_time` | 生成耗时（秒）。注意是下划线，官方 WebP 里是空格 |
 * | `Title` | 固定 `AI generated image`，没有信息量 |
 *
 * ## 这里不做什么
 * - 不保留 `Comment` 原文：它可能包含用户没打算展示的内容，也没有二次解析价值；
 * - 不做任何"猜测式"映射：认不出的模型/采样器/尺寸一律记成"不支持"由上层展示，
 *   绝不悄悄换成"最接近的"。
 */
data class NovelAiImageMetadata(
    val software: String?,
    val source: String?,
    val description: String?,
    val generationTimeSeconds: Double?,
    /** 基础正向提示词。优先 `v4_prompt.caption.base_caption`，回退 `Description` / `Comment.prompt`。 */
    val prompt: String?,
    /** 基础反向提示词：`v4_negative_prompt.caption.base_caption` 或 `Comment.uc`。 */
    val negativePrompt: String?,
    /** Randomizer 展开后的实际提示词（`actual_prompts.prompt.base_caption`）；没有 Randomizer 时为 null。 */
    val actualPrompt: String?,
    val actualNegativePrompt: String?,
    /**
     * 角色提示词。
     *
     * ⚠️ 目前**只用于"检测到但暂不支持导入"的提示**：PocketNAI 还没有多角色提示词功能，
     * 把角色词拼进基础提示词会丢掉角色独立反向词、位置坐标与数组顺序。
     */
    val characters: List<ImportedCharacter>,
    val settings: ImportedSettings,
    /**
     * 是否用过 Vibe Transfer / Precise Reference。
     *
     * 只表示"用过"：原始 Vibe 编码与参考图**不在**图片里，无法复原，
     * 界面必须如实说"检测到但无法恢复"，而不是建一条空引用。
     */
    val usedVibeReferences: Boolean,
    val usedDirectorReferences: Boolean,
    val usedBaseImage: Boolean,
) {
    /** 有提示词或有参数，才算"值得导入"。 */
    val hasImportableContent: Boolean
        get() = !prompt.isNullOrBlank() ||
            !negativePrompt.isNullOrBlank() ||
            settings != ImportedSettings.EMPTY

    companion object {
        /** 官方前端判定"是不是 NovelAI 图片"用的标记（只认 `Software` 字段）。 */
        const val SOFTWARE_MARKER: String = "NovelAI"
    }
}

/**
 * 角色提示词（V4/V5 的 `v4_prompt.caption.char_captions`）。
 *
 * 解析出来是为了**如实告诉用户有几条**，而不是为了导入 ——
 * 详见 [NovelAiImageMetadata.characters] 的说明。
 */
data class ImportedCharacter(
    val prompt: String,
    val negativePrompt: String?,
    val centerX: Double?,
    val centerY: Double?,
)

/**
 * 从 `Comment` JSON 里取出、且 PocketNAI 认识的那部分参数。
 *
 * 字段全部可空：**认不出来的一律留空**，由导入规划器逐项降级，
 * 而不是塞一个默认值假装读到了。
 */
data class ImportedSettings(
    val model: ImageModel?,
    /** `Source` 里读到的原文，模型认不出来时保留，供界面说明"这是哪个模型"。 */
    val unsupportedModelSource: String?,
    val width: Int?,
    val height: Int?,
    val steps: Int?,
    val guidance: Double?,
    val cfgRescale: Double?,
    val sampler: Sampler?,
    /** 采样器名字认不出来时的原文。 */
    val unsupportedSampler: String?,
    val noiseSchedule: NoiseSchedule?,
    val unsupportedNoiseSchedule: String?,
    val seed: Long?,
    val sampleCount: Int?,
) {
    companion object {
        val EMPTY: ImportedSettings = ImportedSettings(
            model = null,
            unsupportedModelSource = null,
            width = null,
            height = null,
            steps = null,
            guidance = null,
            cfgRescale = null,
            sampler = null,
            unsupportedSampler = null,
            noiseSchedule = null,
            unsupportedNoiseSchedule = null,
            seed = null,
            sampleCount = null,
        )
    }
}
