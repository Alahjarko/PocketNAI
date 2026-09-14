package net.pocketnai.domain.model

/**
 * 质量标签档位。
 *
 * 依据 NovelAI 官方网页版的实际行为（用户提供的界面截图，2026-09-14）：
 * 质量标签**不是**一个开关，而是把一段固定文本追加到提示词末尾，
 * 官方 UI 会明确提示 "Added to the end of the prompt"：
 *
 * - `Standard` → `very aesthetic, masterpiece, no text`
 * - `Light`    → `very aesthetic, amazing quality, no text`
 * - `None`     → 不追加任何内容
 *
 * 因此实现上必须真的改写提示词，而不是只发一个布尔字段给服务端。
 * 这一条修正了初版的理解：初版以为靠 API 的 `qualityToggle` 控制，
 * 并把 `QualityTagsRule.positiveSuffix` 留空占位。
 */
enum class QualityTagsOption(
    val displayName: String,
    /** 追加到提示词末尾的文本；[NONE] 为 null。 */
    val appendedText: String?,
) {
    STANDARD("Standard", "very aesthetic, masterpiece, no text"),
    LIGHT("Light", "very aesthetic, amazing quality, no text"),
    NONE("None", null),
    ;

    val enabled: Boolean get() = appendedText != null

    companion object {
        val DEFAULT: QualityTagsOption = STANDARD

        /** 界面下拉的展示顺序，与官方一致：Standard / Light / None。 */
        val selectable: List<QualityTagsOption> = listOf(STANDARD, LIGHT, NONE)

        fun fromNameOrNull(name: String?): QualityTagsOption? =
            entries.firstOrNull { it.name == name }

        fun fromNameOrDefault(name: String?): QualityTagsOption =
            fromNameOrNull(name) ?: DEFAULT
    }
}

/**
 * 把质量标签追加到提示词末尾。
 *
 * 拼接规则统一走 [PromptComposition.append]，因此"空提示词不产生开头逗号"这类
 * 边界情况和收藏片段的填入完全一致。
 */
fun applyQualityTags(prompt: String, option: QualityTagsOption): String {
    val suffix = option.appendedText ?: return prompt
    return net.pocketnai.domain.prompt.PromptComposition.append(prompt, suffix)
}
