package net.pocketnai.domain.prompt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PromptWeightScannerTest {

    /** 只取区间里的原文，测试里比对比起逐个断言下标更好读。 */
    private fun String.spansOf() = PromptWeightScanner.scan(this).map {
        substring(it.start, it.endExclusive)
    }

    @Test fun `负数权重连同负号完整高亮为绿色`() {
        val text = "-1::hat::, -.5::glasses, +1.2::smile::"
        assertThat(text.spansOf()).containsExactly("-1::hat::", "-.5::glasses,", "+1.2::smile::").inOrder()
        assertThat(PromptWeightScanner.scan(text).map { it.weight }).containsExactly(-1.0, -0.5, 1.2).inOrder()
        assertThat(PromptWeightScanner.scan(text).map { it.direction })
            .containsExactly(WeightDirection.WEAKER, WeightDirection.WEAKER, WeightDirection.STRONGER).inOrder()
    }

    @Test fun `截图中的连续权重分别高亮`() {
        val text = "1.5::tag, tag 1.2::tag::\n1.8::tag"
        assertThat(text.spansOf()).containsExactly("1.5::tag, tag", "1.2::tag::", "1.8::tag").inOrder()
        assertThat(PromptWeightScanner.scan(text).map { it.weight }).containsExactly(1.5, 1.2, 1.8).inOrder()
    }
}
