package net.pocketnai.domain.prompt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 补全只能修改光标前片段，不能吞掉右侧用户文本。 */
class PromptTagEditingTest {

    @Test
    fun `补全只读取光标前片段并保留后面的加权组`() {
        val text = "blu1.1::honkai: star rail,official art::"
        val span = PromptTagEditing.completionSpanAt(text, 3)
        assertThat(span.textIn(text)).isEqualTo("blu")
        assertThat(PromptTagEditing.applySuggestion(text, span, "blue hair").text)
            .isEqualTo("blue hair, 1.1::honkai: star rail,official art::")
    }
}
