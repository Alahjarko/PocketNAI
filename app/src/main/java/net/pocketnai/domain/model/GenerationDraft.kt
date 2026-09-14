package net.pocketnai.domain.model

/**
 * 生成页正在编辑的工作状态。
 *
 * 用户希望重新打开应用时不用再把模型、尺寸、Steps 等参数重调一遍，
 * 所以这份状态会被持久化到本地并在启动时恢复。
 *
 * 注意区分它与 [Generation]：`Generation` 是**已经提交过**的历史记录，
 * 一旦写入就不可变；[GenerationDraft] 是还没提交的草稿，每次改动都会覆盖。
 * 两者刻意不共用类型，避免"改草稿顺手改了历史"这类事故。
 */
data class GenerationDraft(
    val params: GenerationParams,
    /** 用户输入的原文（可能含 Randomizer 语法）。 */
    val promptTemplate: String,
    val negativeTemplate: String,
    /**
     * 本次编辑区里挂着的参考图（图生图起点图 / Precise Reference 参考图）。
     *
     * 只记本地文件索引，不记图片数据 —— 图片本身已经在 `files/references/` 里内容寻址存着。
     * 这样重开应用后不用重新选图，而草稿文件依然只有几百字节。
     */
    val references: List<ReferenceImage> = emptyList(),
) {
    companion object {
        fun defaults(): GenerationDraft {
            val profile = ModelCatalog.defaultProfile()
            return GenerationDraft(
                params = GenerationParams.defaultsFor(profile),
                promptTemplate = "",
                negativeTemplate = "",
            )
        }
    }
}
