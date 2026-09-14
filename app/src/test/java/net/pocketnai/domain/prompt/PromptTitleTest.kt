package net.pocketnai.domain.prompt

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class PromptTitleTest {

    @Test
    fun `从提示词提取标题并压缩空白`() {
        val title = PromptTitle.titleFromPrompt("  1girl,   silver  hair ,\ncat ears ")
        assertThat(title).isEqualTo("1girl, silver hair , cat ears")
    }

    @Test
    fun `标题里去掉强调语法包裹符`() {
        val title = PromptTitle.titleFromPrompt("{{masterpiece}}, [lowres] girl")
        assertThat(title).isEqualTo("masterpiece, lowres girl")
    }

    @Test
    fun `空提示词返回 null 以便回退`() {
        assertThat(PromptTitle.titleFromPrompt("   \n\t ")).isNull()
    }

    @Test
    fun `标题超长时按上限截断`() {
        val long = "a".repeat(200)
        val title = PromptTitle.titleFromPrompt(long)
        assertThat(title).hasLength(PromptTitle.MAX_TITLE_CHARS)
    }

    @Test
    fun `无法形成标题时使用未命名加时间`() {
        val millis = timestampOf("2026-09-13 22:30:15")
        val title = PromptTitle.buildTitle("", millis)
        assertThat(title).isEqualTo("未命名生成 2026-09-13 22:30")
    }

    @Test
    fun `文件名主体替换非法字符`() {
        val stem = PromptTitle.sanitizeFileStem("a/b\\c:d*e?f\"g<h>i|j")
        assertThat(stem).isEqualTo("a-b-c-d-e-f-g-h-i-j")
    }

    @Test
    fun `中文文件名被保留`() {
        val stem = PromptTitle.sanitizeFileStem("银发少女 微笑")
        assertThat(stem).isEqualTo("银发少女-微笑")
    }

    @Test
    fun `连续分隔符合并且首尾符号去掉`() {
        val stem = PromptTitle.sanitizeFileStem("  --hello   world--  ")
        assertThat(stem).isEqualTo("hello-world")
    }

    @Test
    fun `完全无法使用的主体回退为 untitled`() {
        val stem = PromptTitle.sanitizeFileStem("///:::")
        assertThat(stem).isEqualTo("untitled")
    }

    @Test
    fun `导出文件名符合规划书示例格式`() {
        val millis = timestampOf("2026-09-13 22:30:15")
        val name = PromptTitle.exportFileName(
            title = "silver-haired-girl",
            timestampMillis = millis,
            ordinal = 1,
        )
        assertThat(name).isEqualTo("silver-haired-girl_20260913-223015_01.png")
    }

    @Test
    fun `序号补零到两位`() {
        val millis = timestampOf("2026-09-13 22:30:15")
        val name = PromptTitle.exportFileName("x", millis, ordinal = 12)
        assertThat(name).endsWith("_12.png")
    }

    private fun timestampOf(localDateTime: String): Long {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        return java.time.LocalDateTime.parse(localDateTime, formatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    @Suppress("unused")
    private fun isoOf(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toString()
}
