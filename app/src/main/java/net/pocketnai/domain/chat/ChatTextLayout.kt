package net.pocketnai.domain.chat

/** 界面投影只限制排版量；协议和持久化始终保留完整消息。 */
object ChatTextLayout {
    const val PREVIEW_CHARS = 640
    const val LIVE_CHARS = 1200
    const val PAGE_CHARS = 600

    fun isLong(text: String) = text.length > PREVIEW_CHARS || text.count { it == '\n' } > 8

    fun preview(text: String): String {
        var end = minOf(text.length, PREVIEW_CHARS)
        var lines = 0
        for (index in 0 until end) if (text[index] == '\n' && ++lines == 8) { end = index; break }
        return text.substring(0, safeEnd(text, end))
    }

    fun liveTail(text: String): String {
        var start = (text.length - LIVE_CHARS).coerceAtLeast(0)
        if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start++
        return text.substring(start)
    }

    /** 即使没有换行的极长字符串也分块，不会交给一个 Text 测量。 */
    fun pages(text: String): List<String> = buildList {
        var start = 0
        while (start < text.length) {
            val limit = minOf(start + PAGE_CHARS, text.length)
            val newline = text.lastIndexOf('\n', limit - 1)
            val end = if (limit < text.length && newline >= start + PAGE_CHARS / 2) newline + 1
                else safeEnd(text, limit)
            add(text.substring(start, end))
            start = end
        }
    }

    private fun safeEnd(text: String, end: Int): Int =
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end - 1 else end
}
