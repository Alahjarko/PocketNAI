package net.pocketnai.domain.prompt

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 从提示词提取用户可见标题，并生成保存到系统相册时的文件名（规划书 5.2）。
 *
 * 内部目录名不使用标题，只用 generation-id；标题只用于列表展示和导出文件名，
 * 因此这里必须做完整的不安全字符清理，避免 Prompt 里的字符影响文件系统。
 */
object PromptTitle {

    /** 列表标题的最大字符数。 */
    const val MAX_TITLE_CHARS: Int = 48

    /** 导出文件名主体的最大字符数。 */
    const val MAX_FILE_STEM_CHARS: Int = 60

    /** 无法从提示词得到标题时使用的占位词。 */
    const val UNTITLED: String = "未命名生成"

    /** Windows / Android 上都不安全或易出问题的文件名字符。 */
    private val ILLEGAL_FILE_CHARS = charArrayOf(
        '<', '>', ':', '"', '/', '\\', '|', '?', '*',
    )

    private val TITLE_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    private val FILE_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    /**
     * 从正向提示词提取标题。空白或无法形成标题时返回 null，由调用方决定回退文案。
     */
    fun titleFromPrompt(prompt: String): String? {
        val normalized = prompt
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return null
        // 去掉 NovelAI 的强调语法包裹符，避免标题里出现一堆 {} []。
        val unwrapped = normalized
            .replace(Regex("[{}\\[\\]]"), "")
            .trim()
        val source = unwrapped.ifEmpty { normalized }
        return source.take(MAX_TITLE_CHARS).trim().ifEmpty { null }
    }

    /** 回退标题：未命名生成 + 时间。 */
    fun fallbackTitle(timestampMillis: Long): String =
        "$UNTITLED ${format(timestampMillis, TITLE_TIME_FORMAT)}"

    /** 展示用标题：优先提示词，其次回退。 */
    fun buildTitle(prompt: String, timestampMillis: Long): String =
        titleFromPrompt(prompt) ?: fallbackTitle(timestampMillis)

    /**
     * 把任意标题清理成安全的文件名主体。
     *
     * 中文等非 ASCII 字符保留（规划书 5.2 允许中文标题）；
     * 非法字符、控制字符统一替换为 `-`，连续分隔符合并，首尾多余符号去掉。
     */
    fun sanitizeFileStem(raw: String): String {
        val replaced = buildString(raw.length) {
            for (ch in raw) {
                when {
                    ch in ILLEGAL_FILE_CHARS -> append('-')
                    ch.code < 0x20 || ch.code == 0x7F -> append('-')
                    ch.isWhitespace() -> append('-')
                    else -> append(ch)
                }
            }
        }
        val collapsed = replaced
            .replace(Regex("-{2,}"), "-")
            .trim('-', '.', ' ', '\t')
        val limited = collapsed.take(MAX_FILE_STEM_CHARS).trim('-', '.', ' ')
        return limited.ifEmpty { "untitled" }
    }

    /**
     * 生成保存到系统相册时的文件名，形如：
     * `silver-haired-girl_20260913-223015_01.png`
     *
     * 大小写保持用户输入原样，不强制转小写，避免改写用户的提示词。
     */
    fun exportFileName(title: String, timestampMillis: Long, ordinal: Int): String {
        val stem = sanitizeFileStem(title)
        val time = format(timestampMillis, FILE_TIME_FORMAT)
        val index = ordinal.coerceAtLeast(1).toString().padStart(2, '0')
        return "${stem}_${time}_$index.png"
    }

    private fun format(timestampMillis: Long, formatter: DateTimeFormatter): String =
        Instant.ofEpochMilli(timestampMillis)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
}
