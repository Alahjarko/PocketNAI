package net.pocketnai.domain.prompt

import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.applyQualityTags
import org.junit.Test

/**
 * 提示词拼接规则的唯一实现。
 *
 * 质量标签追加上与收藏片段填入共用这里，所以这组断言同时保护两条功能路径。
 */
class PromptCompositionTest {

    @Test
    fun `两个都有内容时用逗号加空格连接`() {
        assertThat(PromptComposition.append("1girl", "silver hair"))
            .isEqualTo("1girl, silver hair")
    }

    @Test
    fun `原提示词为空时不产生开头的逗号`() {
        assertThat(PromptComposition.append("", "masterpiece")).isEqualTo("masterpiece")
        assertThat(PromptComposition.append("   ", "masterpiece")).isEqualTo("masterpiece")
    }

    @Test
    fun `追加内容为空时原样返回不做改动`() {
        // 尤其是不能把用户的首尾空白顺手 trim 掉 —— 那会让"什么都没做"的操作也有副作用。
        assertThat(PromptComposition.append("  1girl  ", "")).isEqualTo("  1girl  ")
        assertThat(PromptComposition.append("1girl", "   ")).isEqualTo("1girl")
    }

    @Test
    fun `连接前去掉各自首尾空白`() {
        assertThat(PromptComposition.append("  1girl  ", "  blue eyes "))
            .isEqualTo("1girl, blue eyes")
    }

    @Test
    fun `连续追加保持单一分隔符`() {
        var prompt = ""
        prompt = PromptComposition.append(prompt, "1girl")
        prompt = PromptComposition.append(prompt, "silver hair")
        prompt = PromptComposition.append(prompt, "blue eyes")

        assertThat(prompt).isEqualTo("1girl, silver hair, blue eyes")
    }

    @Test
    fun `默认名称压缩空白并截断`() {
        assertThat(PromptComposition.defaultName("  1girl,   silver  hair "))
            .isEqualTo("1girl, silver hair")
        assertThat(PromptComposition.defaultName("a".repeat(200)))
            .hasLength(PromptComposition.MAX_NAME_CHARS)
    }

    @Test
    fun `内容为空时默认名称为 null 交给调用方回退`() {
        assertThat(PromptComposition.defaultName("")).isNull()
        assertThat(PromptComposition.defaultName("   \n\t ")).isNull()
    }

    @Test
    fun `质量标签追加走的是同一套拼接规则`() {
        // 空提示词 + 质量标签不应出现开头的逗号，这条以前踩过。
        val suffix = QualityTagsOption.STANDARD.appendedText!!
        assertThat(applyQualityTags("", QualityTagsOption.STANDARD)).isEqualTo(suffix)
        assertThat(applyQualityTags("1girl", QualityTagsOption.STANDARD))
            .isEqualTo("1girl, $suffix")
    }
}
