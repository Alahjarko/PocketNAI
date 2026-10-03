package net.pocketnai.domain.prompt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PromptWeightScannerTest {

    /** 只取区间里的原文，测试里比对比起逐个断言下标更好读。 */
    private fun String.spansOf() = PromptWeightScanner.scan(this).map {
        substring(it.start, it.endExclusive)
    }

    private fun String.weightOfFirst() = PromptWeightScanner.scan(this).first().weight

    @Test
    fun `没有加权语法时什么都不返回`() {
        assertThat("1girl, silver hair, blue eyes".spansOf()).isEmpty()
    }

    @Test
    fun `花括号包裹被识别为强化`() {
        val spans = "1girl, {silver hair}".spansOf()
        assertThat(spans).containsExactly("{silver hair}")
    }

    @Test
    fun `方括号包裹被识别为弱化`() {
        val prompt = "1girl, [simple background]"
        assertThat(prompt.spansOf()).containsExactly("[simple background]")
        assertThat(prompt.weightOfFirst()).isWithin(1e-9).of(1.0 / 1.05)
    }

    @Test
    fun `高亮范围包含括号本身`() {
        // 底纹要把整个片段圈住，只盖住裸文字会让用户看不出这是被括号包过的。
        val prompt = "a, {cat}, b"
        val span = PromptWeightScanner.scan(prompt).single()
        assertThat(prompt.substring(span.start, span.endExclusive)).isEqualTo("{cat}")
    }

    @Test
    fun `多层包裹按倍率累乘`() {
        assertThat("{{cat}}".weightOfFirst()).isWithin(1e-9).of(1.05 * 1.05)
        assertThat("[[cat]]".weightOfFirst()).isWithin(1e-9).of(1.0 / 1.05 / 1.05)
    }

    @Test
    fun `数字前缀按字面值取权重`() {
        val prompt = "1girl, 0.9::ningen mame ::, 1.3::smile::"
        val spans = PromptWeightScanner.scan(prompt)
        assertThat(spans.map { it.weight }).containsExactly(0.9, 1.3).inOrder()
        assertThat(spans.map { it.direction })
            .containsExactly(WeightDirection.WEAKER, WeightDirection.STRONGER)
            .inOrder()
    }

    @Test
    fun `数字前缀的高亮范围覆盖整个片段`() {
        val prompt = "1girl, 0.9::ningen mame ::"
        val span = PromptWeightScanner.scan(prompt).single()
        assertThat(prompt.substring(span.start, span.endExclusive)).isEqualTo("0.9::ningen mame ::")
    }

    @Test
    fun `数字后缀紧贴时也能识别`() {
        // 官方写法里冒号前常有空格，但用户手打时不一定有：两种都要认。
        assertThat("0.9::cat::".weightOfFirst()).isWithin(1e-9).of(0.9)
        assertThat("0.9 ::cat::".weightOfFirst()).isWithin(1e-9).of(0.9)
    }

    @Test
    fun `数字前缀优先于包裹语法`() {
        // 用户已经写明了数字，再叠一次包裹倍率会得到一个他没要求过的值。
        assertThat("1.2::{cat}::".weightOfFirst()).isWithin(1e-9).of(1.2)
    }

    @Test
    fun `权重为1仍标出成对语法且保持中性`() {
        assertThat("1::cat::".spansOf()).containsExactly("1::cat::")
        assertThat(PromptWeightScanner.scan("1.0::cat::").single().direction).isEqualTo(WeightDirection.NEUTRAL)
    }

    @Test
    fun `多标签数字权重不按内部逗号切断`() {
        assertThat("1.1::honkai: star rail,official art::".spansOf())
            .containsExactly("1.1::honkai: star rail,official art::")
    }

    @Test
    fun `成对权重前后不需要逗号且多组各自高亮`() {
        val text = "prefix 0.9::a,b::1.2::c,d::suffix"
        assertThat(text.spansOf()).containsExactly("0.9::a,b::", "1.2::c,d::").inOrder()
    }

    @Test
    fun `只有成对冒号也标出高亮并支持换行`() {
        assertThat("prefix::a,\nb::suffix".spansOf()).containsExactly("::a,\nb::")
    }

    @Test
    fun `数值相同但来自包裹与数字的片段都保留`() {
        val prompt = "{cat}, 1.05::dog::"
        val spans = PromptWeightScanner.scan(prompt)
        assertThat(spans).hasSize(2)
        assertThat(spans[0].weight).isWithin(1e-9).of(spans[1].weight)
    }

    @Test
    fun `被包裹短语内部的逗号不切分片段`() {
        // 拆成两半之后两半都算不出权重，高亮会整段消失。
        val prompt = "{red hair, blue eyes}"
        assertThat(prompt.spansOf()).containsExactly("{red hair, blue eyes}")
    }

    @Test
    fun `区间下标可直接用于 substring`() {
        val prompt = "1girl, {silver hair}, 0.9::blue eyes ::"
        PromptWeightScanner.scan(prompt).forEach { span ->
            assertThat(span.start).isAtLeast(0)
            assertThat(span.endExclusive).isAtMost(prompt.length)
            assertThat(span.length).isGreaterThan(0)
        }
    }

    @Test
    fun `空字符串与纯逗号不会崩溃`() {
        assertThat("".spansOf()).isEmpty()
        assertThat(",, ,".spansOf()).isEmpty()
    }

    @Test
    fun `转义与未闭合的括号按普通文字处理`() {
        // 没闭合的 `{` 不构成包裹，权重仍是 1，因此不高亮（不擅自吞掉用户输入）。
        assertThat("{cat".spansOf()).isEmpty()
        assertThat("[cat".spansOf()).isEmpty()
    }

    @Test
    fun `无意义的穿插写法得到 1 不产生高亮`() {
        assertThat("{a} and {b}".spansOf()).isEmpty()
    }

    @Test
    fun `随机选项语法不会被误判为加权`() {
        // `<a|b|c>` 是 Randomizer 的语法，与权重无关。
        assertThat("1girl, <long hair|short hair>".spansOf()).isEmpty()
    }

    @Test fun `负数权重连同负号完整高亮为绿色`() {
        val text = "-1::hat::, -.5::glasses, +1.2::smile::"
        assertThat(text.spansOf()).containsExactly("-1::hat::", "-.5::glasses,", "+1.2::smile::").inOrder()
        assertThat(PromptWeightScanner.scan(text).map { it.weight }).containsExactly(-1.0, -0.5, 1.2).inOrder()
        assertThat(PromptWeightScanner.scan(text).map { it.direction })
            .containsExactly(WeightDirection.WEAKER, WeightDirection.WEAKER, WeightDirection.STRONGER).inOrder()
    }

    @Test fun `未闭合数字权重跨逗号与换行延伸至文末`() {
        val text = "plain, 0.9::cat, dog\nbird  "
        assertThat(text.spansOf()).containsExactly("0.9::cat, dog\nbird")
        assertThat(text.weightOfFirst()).isEqualTo(0.9)
    }

    @Test fun `截图中的连续权重分别高亮`() {
        val text = "1.5::tag, tag 1.2::tag::\n1.8::tag"
        assertThat(text.spansOf()).containsExactly("1.5::tag, tag", "1.2::tag::", "1.8::tag").inOrder()
        assertThat(PromptWeightScanner.scan(text).map { it.weight }).containsExactly(1.5, 1.2, 1.8).inOrder()
    }

    @Test fun `负向未闭合组遇到正向组即结束且闭合后恢复普通文本`() {
        val text = "-2::hat, glasses 1.4::smile:: plain"
        assertThat(text.spansOf()).containsExactly("-2::hat, glasses", "1.4::smile::").inOrder()
    }

    @Test fun `同权重相邻组仍是独立区间且无逗号也能切换`() {
        val text = "1.2::cat 1.2::dog::-1::hat"
        assertThat(text.spansOf()).containsExactly("1.2::cat", "1.2::dog::", "-1::hat").inOrder()
    }

    @Test fun `未指定权重的冒号也支持未闭合正文`() {
        assertThat("::cat, dog".spansOf()).containsExactly("::cat, dog")
        assertThat(PromptWeightScanner.scan("::cat").single().direction).isEqualTo(WeightDirection.NEUTRAL)
    }

    @Test fun `正在输入的空组没有高亮实体且不会越界`() {
        listOf("::", "-1::  ", "1.2::::", "1.2:: -1::").forEach {
            assertThat(it.spansOf()).isEmpty()
        }
    }

    @Test fun `片段权重计算也接受负值和未闭合语法`() {
        assertThat(PromptWeightScanner.weightOf("-1.5::hat")).isEqualTo(-1.5)
        assertThat(PromptWeightScanner.weightOf(".5::{hat}::")).isEqualTo(0.5)
    }
}
