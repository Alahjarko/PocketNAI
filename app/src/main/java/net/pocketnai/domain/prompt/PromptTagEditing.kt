package net.pocketnai.domain.prompt

/**
 * 提示词补全所需的文本操作：定位光标所在的标签，以及把建议填入。
 *
 * 补全使用 [completionSpanAt]，只改光标前的片段，右侧内容原样保留。标签之间的分隔符是逗号与换行，
 * 与用户在提示词里的习惯写法一致（[PromptComposition.SEPARATOR] 负责拼接，这里负责切分）。
 *
 * 全是纯函数，因此可以在 JVM 单元测试里逐条断言边界情况 —— 这段逻辑直接决定
 * 用户已经写好的提示词会不会被改坏，值得单独测。
 */
object PromptTagEditing {

    private const val COMMA = ','

    /** 标签在文本中的范围；两端的空白不计入范围内。 */
    data class TagSpan(val start: Int, val end: Int) {

        fun textIn(source: String): String = source.substring(start, end)
    }

    /** 一次替换的结果：新文本，以及替换后光标应处的位置。 */
    data class Replacement(val text: String, val cursor: Int)

    /** 补全只读取和替换光标左侧的片段，右侧字符一个也不删除。权重边界也分隔片段。 */
    fun completionSpanAt(text: String, cursor: Int): TagSpan {
        val end = cursor.coerceIn(0, text.length)
        var start = end
        while (start > 0) {
            val previous = text[start - 1]
            if (isSeparator(previous) || previous in "{}[]") break
            if (start >= 2 && text.substring(start - 2, start) == "::") break
            start--
        }
        while (start < end && text[start].isWhitespace()) start++
        return TagSpan(start, end)
    }

    /**
     * 找出光标所在标签的范围。
     *
     * 光标停在一个标签中间时（例如 `blue ey|es`），整个 `blue eyes` 都算作这个标签 ——
     * 替换时应当整段换掉，否则会与用户原有的后半截拼成一个不存在的词。
     */
    fun tagSpanAt(text: String, cursor: Int): TagSpan {
        val safeCursor = cursor.coerceIn(0, text.length)

        // 先把整个"标签槽位"框出来：从上一个分隔符之后，到下一个分隔符之前。
        // 光标落在标签中间时这个槽位仍然覆盖整个标签 —— 替换必须整段换掉，
        // 否则会把用户原有的后半截拼成一个不存在的词。
        var start = safeCursor
        while (start > 0 && !isSeparator(text[start - 1])) start--

        var end = safeCursor
        while (end < text.length && !isSeparator(text[end])) end++

        // 再裁掉槽位两端的空白。裁完 start 可能落在光标右侧（光标停在逗号后的
        // 空格上时），这是有意的：那段空白本就属于分隔符，不该被算进标签里。
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--

        return TagSpan(start, end)
    }

    /**
     * 用 [suggestion] 替换 [span]。
     *
     * 末尾或未分隔的右侧内容前补 ", "；已有逗号/换行或闭合权重符号时不补。
     * 不删空白，不消费光标之后的普通文本或权重组。
     */
    fun applySuggestion(text: String, span: TagSpan, suggestion: String): Replacement {
        if (suggestion.isBlank()) return Replacement(text, span.end)

        val tail = text.substring(span.end)
        val atEndOfText = tail.isEmpty()
        // 只插入必要的分隔符，保留原来的右侧内容。
        val closesWeight = tail.startsWith("::") || tail.firstOrNull() in listOf('}', ']')
        val separator = if (atEndOfText || (!closesWeight && tail.isNotEmpty() && !isSeparator(tail[0]))) {
            PromptComposition.SEPARATOR
        } else ""
        val suffix = tail
        val next = text.substring(0, span.start) + suggestion + separator + suffix

        return Replacement(next, span.start + suggestion.length + separator.length)
    }

    private fun isSeparator(character: Char): Boolean =
        character == COMMA || character == '\n' || character == '\r'
}
