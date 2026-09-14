package net.pocketnai.domain.prompt

/**
 * 提示词补全所需的文本操作：定位光标所在的标签，以及把建议填入。
 *
 * 补全只改光标所在的那一个标签，其余部分原样保留。标签之间的分隔符是逗号与换行，
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
     * 只有标签位于整条提示词末尾时才补一个 ", "，方便接着输入下一个标签；
     * 标签后面本来就有分隔符时保持原样，避免出现 ", ," 这种明显是 bug 的结果。
     */
    fun applySuggestion(text: String, span: TagSpan, suggestion: String): Replacement {
        if (suggestion.isBlank()) return Replacement(text, span.end)

        val tail = text.substring(span.end)
        val atEndOfText = tail.isBlank()
        // 末尾仅剩空白时，用 ", " 顶掉它：空白本来就不参与提示词语义，
        // 留着会变成 "tag, " 后面跟一串看不见的空格。
        val separator = if (atEndOfText) PromptComposition.SEPARATOR else ""
        val suffix = if (atEndOfText) "" else tail
        val next = text.substring(0, span.start) + suggestion + separator + suffix

        return Replacement(next, span.start + suggestion.length + separator.length)
    }

    private fun isSeparator(character: Char): Boolean =
        character == COMMA || character == '\n' || character == '\r'
}
