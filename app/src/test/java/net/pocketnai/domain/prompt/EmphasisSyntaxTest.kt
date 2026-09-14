package net.pocketnai.domain.prompt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmphasisSyntaxTest {

    @Test
    fun `强化一层包裹花括号`() {
        assertThat(EmphasisSyntax.strengthen("cat")).isEqualTo("{cat}")
    }

    @Test
    fun `弱化一层包裹方括号`() {
        assertThat(EmphasisSyntax.weaken("cat")).isEqualTo("[cat]")
    }

    @Test
    fun `未包裹的词条权重为 1`() {
        assertThat(EmphasisSyntax.strengthOf("cat")).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `一层强化为 1_05`() {
        assertThat(EmphasisSyntax.strengthOf("{cat}")).isWithin(1e-9).of(1.05)
    }

    @Test
    fun `两层强化按倍率累乘`() {
        assertThat(EmphasisSyntax.strengthOf("{{cat}}")).isWithin(1e-9).of(1.05 * 1.05)
    }

    @Test
    fun `一层弱化为 1 除以 1_05`() {
        assertThat(EmphasisSyntax.strengthOf("[cat]")).isWithin(1e-9).of(1.0 / 1.05)
    }

    @Test
    fun `只有真正包裹整段的括号才计入权重`() {
        // 这是最容易写错的一种情况：多个独立词条不应被当成一个整体。
        assertThat(EmphasisSyntax.strengthOf("{a} and {b}")).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `花括号与方括号可以相互抵消`() {
        assertThat(EmphasisSyntax.strengthOf("{[cat]}")).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `空词条原样返回不产生悬空括号`() {
        assertThat(EmphasisSyntax.strengthen("   ")).isEqualTo("   ")
    }
}
