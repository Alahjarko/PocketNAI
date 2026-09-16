package net.pocketnai.data.network

/**
 * SSE（`text/event-stream`）帧阅读器。
 *
 * 按行喂入、按帧取出：一帧以空行结束，帧内可以有多个 `data:` 行
 * （规范约定用换行拼接）与一个 `event:` 行。注释行（`:` 开头，常用作心跳）
 * 与 `id:` / `retry:` 字段一律忽略 —— 它们不影响本次流式生成的语义。
 *
 * 做成"逐行喂"而不是"整段解析"，是因为响应体是持续到达的流：
 * 中间图要尽快到达界面，不能等整个响应结束。
 */
class SseFrameReader {

    /** 一个完整帧：[event] 可能为 null（只有 data 行的服务端实现）。 */
    data class Frame(val event: String?, val data: String)

    private var event: String? = null
    private val data = StringBuilder()

    /**
     * 喂一行（不含换行符）。
     *
     * @return 读满一帧时返回它，否则返回 null。
     */
    fun feed(line: String): Frame? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        if (colon < 0) return null
        val field = line.substring(0, colon)
        val rawValue = line.substring(colon + 1)
        // 规范：冒号后允许一个空格，它不属于值。
        val value = rawValue.removePrefix(" ")

        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }

    /**
     * 流结束时调用：服务端在关闭连接前来不及补空行时，最后一帧仍然有效
     * （宽容处理，避免丢掉唯一的一张 Final 图）。
     */
    fun finish(): Frame? = flush()

    private fun flush(): Frame? {
        if (event == null && data.isEmpty()) return null
        val frame = Frame(event = event, data = data.toString())
        event = null
        data.clear()
        return frame
    }
}
