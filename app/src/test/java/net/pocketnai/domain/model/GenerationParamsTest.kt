package net.pocketnai.domain.model

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

/**
 * 生成参数的取值规则。
 *
 * 这里钉住的是"这一次生成实际用哪个 seed"：随机模式必须**每次抽一个新的**，
 * 而且抽出来的值要能被上层同时用在请求体与历史记录里。
 */
class GenerationParamsTest {

    private val profile = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)

    private fun params(seedMode: SeedMode, baseSeed: Long = 0L) =
        GenerationParams.defaultsFor(profile).copy(seedMode = seedMode, baseSeed = baseSeed)

    @Test
    fun `随机模式每次抽一个新的 seed`() {
        val random = Random(20260914)
        val first = params(SeedMode.RANDOM).withResolvedSeed(random)
        val second = params(SeedMode.RANDOM).withResolvedSeed(random)

        // 同一个 Random 连续抽两次也必须不同 —— 这正是"每张图不一样"的来源。
        assertThat(first.baseSeed).isNotEqualTo(second.baseSeed)
        assertThat(first.baseSeed).isIn(0L..GenerationParams.MAX_SEED)
        assertThat(second.baseSeed).isIn(0L..GenerationParams.MAX_SEED)
    }

    @Test
    fun `随机模式会丢掉默认的 0`() {
        // 曾经把默认值 0 当作"随机"发给服务端，结果同一提示词反复产出同一张图。
        val resolved = params(SeedMode.RANDOM, baseSeed = 0L).withResolvedSeed(Random(1))

        assertThat(resolved.baseSeed).isNotEqualTo(0L)
        assertThat(resolved.seedMode).isEqualTo(SeedMode.RANDOM)
    }

    @Test
    fun `固定模式原样返回不再抽签`() {
        val pinned = params(SeedMode.FIXED, baseSeed = 12345L)

        assertThat(pinned.withResolvedSeed(Random(7))).isEqualTo(pinned)
        assertThat(pinned.withResolvedSeed(Random(8)).baseSeed).isEqualTo(12345L)
    }

    @Test
    fun `抽出的 seed 不会越界`() {
        val random = Random(42)
        repeat(200) {
            val seed = params(SeedMode.RANDOM).withResolvedSeed(random).baseSeed
            assertThat(seed).isAtLeast(0L)
            assertThat(seed).isAtMost(GenerationParams.MAX_SEED)
        }
    }
}
