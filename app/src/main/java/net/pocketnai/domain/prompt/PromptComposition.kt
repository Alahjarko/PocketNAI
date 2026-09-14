package net.pocketnai.domain.prompt

/**
 * 提示词文本拼接的**唯一规则**。
 *
 * 质量标签追加与收藏片段填入都走这里，避免同一件事在代码里有两种略有差异的实现：
 * 那种差异最终会表现为"有时出现两个逗号""有时开头多一个逗号"这类难查的小毛病。
 *
 * 这些都是纯函数，所以可以在 JVM 单元测试里直接断言。
 */
object PromptComposition {

    /** 相邻片段之间的分隔符，与 NovelAI 提示词的习惯写法一致。 */
    const val SEPARATOR: String = ", "

    /** 收藏名称的默认长度上限。 */
    const val MAX_NAME_CHARS: Int = 32

    /**
     * 把 [addition] 追加到 [current] 末尾。
     *
     * - [addition] 为空：原样返回 [current]，不做任何改动；
     * - [current] 为空：返回 [addition]，**不会**留下开头多余的逗号；
     * - 两者都有内容：用 [SEPARATOR] 连接，并去掉各自首尾空白。
     */
    fun append(current: String, addition: String): String {
        val base = current.trim()
        val extra = addition.trim()
        return when {
            extra.isEmpty() -> current
            base.isEmpty() -> extra
            else -> base + SEPARATOR + extra
        }
    }

    /** 取一段内容里能当名字用的最简形式；空内容返回 null 由调用方决定回退文案。 */
    fun defaultName(content: String): String? =
        content
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_NAME_CHARS)
            .trim()
            .ifEmpty { null }
}
