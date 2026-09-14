package net.pocketnai.domain.prompt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 标签补全的文本操作（规划书 8.1）。
 *
 * 这段逻辑直接改用户已经写好的提示词，所以测的重点是**边界**：
 * 光标停在标签中间、停在逗号上、停在逗号后的空格上、以及换行分隔的情况。
 * 每一条断言的都是"用户原有的内容有没有被改坏"。
 */
class PromptTagEditingTest {

    // ---- 定位光标所在的标签 ----

    @Test
    fun `光标在末尾时取到最后一个标签`() {
        val text = "1girl, blue ey"
        val span = PromptTagEditing.tagSpanAt(text, text.length)

        assertThat(span.textIn(text)).isEqualTo("blue ey")
    }

    @Test
    fun `光标停在标签中间时整个标签都算数`() {
        // 替换必须整段换掉，否则会留下 "es" 拼成不存在的词。
        val text = "1girl, blue eyes"
        val cursor = text.indexOf("ey") + 2
        val span = PromptTagEditing.tagSpanAt(text, cursor)

        assertThat(span.textIn(text)).isEqualTo("blue eyes")
    }

    @Test
    fun `标签位于文本开头时也能取到`() {
        val span = PromptTagEditing.tagSpanAt("blue eyes, solo", 3)

        assertThat(span.textIn("blue eyes, solo")).isEqualTo("blue eyes")
    }

    @Test
    fun `逗号不算进标签`() {
        val text = "1girl, solo"
        val span = PromptTagEditing.tagSpanAt(text, text.length)

        assertThat(span.textIn(text)).isEqualTo("solo")
    }

    @Test
    fun `光标停在逗号上时取前一个标签`() {
        val text = "1girl, solo"
        val commaIndex = text.indexOf(',')
        val span = PromptTagEditing.tagSpanAt(text, commaIndex)

        assertThat(span.textIn(text)).isEqualTo("1girl")
    }

    @Test
    fun `光标停在逗号后的空格上时取到后面的标签而不是空格`() {
        // 若把空格算进标签，替换后就会变成 "1girl,blue eyes" —— 少了逗号后的空格。
        val text = "1girl, blue eyes"
        val spaceIndex = text.indexOf(',') + 1
        val span = PromptTagEditing.tagSpanAt(text, spaceIndex)

        assertThat(span.textIn(text)).isEqualTo("blue eyes")

        val replacement = PromptTagEditing.applySuggestion(text, span, "1girl")
        assertThat(replacement.text).isEqualTo("1girl, 1girl, ")
    }

    @Test
    fun `分隔符后的空位没有标签`() {
        val text = "1girl, "
        val span = PromptTagEditing.tagSpanAt(text, text.length)

        assertThat(span.textIn(text)).isEmpty()
    }

    @Test
    fun `换行也是分隔符`() {
        val text = "1girl\nblue ey"
        val span = PromptTagEditing.tagSpanAt(text, text.length)

        assertThat(span.textIn(text)).isEqualTo("blue ey")
    }

    @Test
    fun `光标越界时按边界处理`() {
        val text = "1girl, solo"
        assertThat(PromptTagEditing.tagSpanAt(text, 999).textIn(text)).isEqualTo("solo")
        assertThat(PromptTagEditing.tagSpanAt(text, -5).textIn(text)).isEqualTo("1girl")
    }

    // ---- 填入建议 ----

    @Test
    fun `标签在末尾时补一个逗号方便接着写`() {
        val text = "1girl, blue ey"
        val span = PromptTagEditing.tagSpanAt(text, text.length)

        val replacement = PromptTagEditing.applySuggestion(text, span, "blue eyes")

        assertThat(replacement.text).isEqualTo("1girl, blue eyes, ")
        assertThat(replacement.cursor).isEqualTo(replacement.text.length)
    }

    @Test
    fun `标签后面还有内容时不补逗号`() {
        // 补了会变成 "blue eyes, , solo"。
        val text = "1girl, blu, solo"
        val span = PromptTagEditing.tagSpanAt(text, text.indexOf("blu") + 3)

        val replacement = PromptTagEditing.applySuggestion(text, span, "blue eyes")

        assertThat(replacement.text).isEqualTo("1girl, blue eyes, solo")
        assertThat(replacement.cursor).isEqualTo(text.indexOf("blu") + "blue eyes".length)
    }

    @Test
    fun `若标签后面跟的是换行则不补逗号`() {
        val text = "1girl, blu\nsolo"
        val cursor = text.indexOf("blu") + 3
        val span = PromptTagEditing.tagSpanAt(text, cursor)

        val replacement = PromptTagEditing.applySuggestion(text, span, "blue eyes")

        assertThat(replacement.text).isEqualTo("1girl, blue eyes\nsolo")
    }

    @Test
    fun `末尾只剩空白时用逗号顶掉它`() {
        val text = "1girl, blue ey   "
        val cursor = text.indexOf("ey") + 2
        val span = PromptTagEditing.tagSpanAt(text, cursor)

        val replacement = PromptTagEditing.applySuggestion(text, span, "blue eyes")

        assertThat(replacement.text).isEqualTo("1girl, blue eyes, ")
    }

    @Test
    fun `整条提示词只有一个标签时也能正确填入`() {
        val span = PromptTagEditing.tagSpanAt("blue", 4)

        val replacement = PromptTagEditing.applySuggestion("blue", span, "blue eyes")

        assertThat(replacement.text).isEqualTo("blue eyes, ")
    }

    @Test
    fun `空建议不改动文本`() {
        val text = "1girl, blue ey"
        val span = PromptTagEditing.tagSpanAt(text, text.length)

        val replacement = PromptTagEditing.applySuggestion(text, span, "   ")

        assertThat(replacement.text).isEqualTo(text)
        assertThat(replacement.cursor).isEqualTo(span.end)
    }

    @Test
    fun `替换只影响当前标签`() {
        val text = "1girl, blu, solo, smile"
        val cursor = text.indexOf("blu") + 3
        val span = PromptTagEditing.tagSpanAt(text, cursor)

        val replacement = PromptTagEditing.applySuggestion(text, span, "blue eyes")

        assertThat(replacement.text).isEqualTo("1girl, blue eyes, solo, smile")
    }
}
