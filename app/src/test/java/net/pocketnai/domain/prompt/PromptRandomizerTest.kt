package net.pocketnai.domain.prompt

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class PromptRandomizerTest {

    @Test
    fun `没有选项时原样返回`() {
        val prompt = "1girl, silver hair, {masterpiece}"
        assertThat(PromptRandomizer.resolve(prompt, Random(1))).isEqualTo(prompt)
        assertThat(PromptRandomizer.hasOptions(prompt)).isFalse()
    }

    @Test
    fun `展开结果一定是候选项之一`() {
        val resolved = PromptRandomizer.resolve("1girl, <red|blue|green> hair", Random(42))
        assertThat(resolved).isAnyOf(
            "1girl, red hair",
            "1girl, blue hair",
            "1girl, green hair",
        )
    }

    @Test
    fun `相同随机种子得到相同展开结果以便复现`() {
        val prompt = "<a|b|c> and <d|e>"
        val first = PromptRandomizer.resolve(prompt, Random(7))
        val second = PromptRandomizer.resolve(prompt, Random(7))
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `组合数按各选项相乘`() {
        assertThat(PromptRandomizer.estimateCombinations("<a|b> and <c|d|e>")).isEqualTo(6)
    }

    @Test
    fun `多个选项同时出现在前后两段`() {
        val resolved = PromptRandomizer.resolve("<a|b> middle <c|d>", Random(3))
        assertThat(resolved).matches("[ab] middle [cd]")
    }

    @Test
    fun `转义的尖括号输出字面量`() {
        val resolved = PromptRandomizer.resolve("\\<a|b>", Random(1))
        assertThat(resolved).isEqualTo("<a|b>")
    }

    @Test
    fun `没有闭合的尖括号按普通字符处理`() {
        val prompt = "cat < a|b"
        assertThat(PromptRandomizer.resolve(prompt, Random(1))).isEqualTo(prompt)
    }

    @Test
    fun `只有单个候选时不视为随机选项`() {
        val prompt = "<only>"
        assertThat(PromptRandomizer.hasOptions(prompt)).isFalse()
        assertThat(PromptRandomizer.resolve(prompt, Random(1))).isEqualTo(prompt)
    }

    @Test
    fun `空候选被忽略后仍按有效候选项展开`() {
        val resolved = PromptRandomizer.resolve("<a||b>", Random(5))
        assertThat(resolved).isAnyOf("a", "b")
    }
}
