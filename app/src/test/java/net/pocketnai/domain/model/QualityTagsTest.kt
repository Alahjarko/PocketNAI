package net.pocketnai.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 质量标签的追加行为。
 *
 * 依据是官方网页版的实际行为：质量标签不是开关，而是把固定文本追加到提示词末尾，
 * 官方 UI 会显示 "Added to the end of the prompt"。
 * 这套断言把三个档位对应的具体文本钉死，避免以后手改字符串时悄悄偏离官方。
 */
class QualityTagsTest {

    @Test
    fun `Standard 追加官方的标准质量标签`() {
        assertThat(QualityTagsOption.STANDARD.appendedText)
            .isEqualTo("very aesthetic, masterpiece, no text")
    }

    @Test
    fun `Light 追加官方的高质量标签`() {
        assertThat(QualityTagsOption.LIGHT.appendedText)
            .isEqualTo("very aesthetic, amazing quality, no text")
    }

    @Test
    fun `None 不追加任何内容`() {
        assertThat(QualityTagsOption.NONE.appendedText).isNull()
        assertThat(QualityTagsOption.NONE.enabled).isFalse()
    }

    @Test
    fun `追加使用逗号加空格连接`() {
        val result = applyQualityTags("1girl, silver hair", QualityTagsOption.STANDARD)
        assertThat(result)
            .isEqualTo("1girl, silver hair, very aesthetic, masterpiece, no text")
    }

    @Test
    fun `None 档位原样返回提示词`() {
        val prompt = "1girl, silver hair"
        assertThat(applyQualityTags(prompt, QualityTagsOption.NONE)).isEqualTo(prompt)
    }

    @Test
    fun `提示词为空时不会产生开头的逗号`() {
        assertThat(applyQualityTags("", QualityTagsOption.STANDARD))
            .isEqualTo("very aesthetic, masterpiece, no text")
        assertThat(applyQualityTags("   ", QualityTagsOption.LIGHT))
            .isEqualTo("very aesthetic, amazing quality, no text")
    }

    @Test
    fun `追加前会去掉提示词首尾空白`() {
        assertThat(applyQualityTags("  cat  ", QualityTagsOption.LIGHT))
            .isEqualTo("cat, very aesthetic, amazing quality, no text")
    }

    @Test
    fun `数据库里读不到档位时回退到默认值而不是崩溃`() {
        assertThat(QualityTagsOption.fromNameOrDefault(null)).isEqualTo(QualityTagsOption.DEFAULT)
        assertThat(QualityTagsOption.fromNameOrDefault("NOT_A_REAL_OPTION"))
            .isEqualTo(QualityTagsOption.DEFAULT)
        assertThat(QualityTagsOption.fromNameOrDefault("LIGHT")).isEqualTo(QualityTagsOption.LIGHT)
    }

    @Test
    fun `界面下拉顺序与官方一致`() {
        assertThat(QualityTagsOption.selectable).containsExactly(
            QualityTagsOption.STANDARD,
            QualityTagsOption.LIGHT,
            QualityTagsOption.NONE,
        ).inOrder()
    }
}
