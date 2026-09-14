package net.pocketnai.domain.prompt

import net.pocketnai.domain.model.ImageModel

/**
 * 标签补全的数据来源（规划书 8.1，属于第二层能力）。
 *
 * 实现**必须自己吞掉失败**并返回空列表：补全失败不该弹错误、更不该影响生成，
 * 用户顶多是这次没有建议可点。这条约定写在接口上，是因为它是这个功能唯一的
 * 失败策略 —— 放在实现里容易被下一个人当成漏了错误处理而"修好"。
 */
fun interface TagSuggestionSource {

    /**
     * 为正在输入的这个标签片段取回建议。
     *
     * [tagFragment] 是光标所在标签的文本（已去空白），不是整条提示词；
     * 服务端据此做前缀/相似度匹配。返回顺序即展示顺序。
     */
    suspend fun suggest(tagFragment: String, model: ImageModel): List<String>
}
