package net.pocketnai.domain.artistlab

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.pocketnai.data.settings.GenerationDraftCodec
import net.pocketnai.domain.model.*
import net.pocketnai.domain.prompt.PromptWeightScanner
import org.junit.Test
import kotlin.random.Random

class ArtistLabTest {
    private val base = GenerationParams.defaultsFor(ModelCatalog.defaultProfile()).copy(
        prompt = "1girl, blue sky", negativePrompt = "lowres", seedMode = SeedMode.FIXED, baseSeed = 123456789,
    )
    private fun config(count: Int = 100, artists: Int = 10) = ArtistLabConfig(
        GenerationDraftCodec.encode(GenerationDraft(base, base.prompt, base.negativePrompt)), artists, 10, 30, count, "hash",
    )
    @Test fun `一百张各有十位不同画师和固定种子其它参数不变`() {
        val pool = (1..1000).map { "artist: artist $it" }
        val plan = ArtistLabPlanner.plan(pool, config(), Random(42))
        assertThat(plan).hasSize(100)
        plan.forEach { mix ->
            assertThat(mix.artists.map { it.tag }.distinct()).hasSize(10)
            assertThat(mix.artists.all { it.ticks in 10..30 }).isTrue()
            val params = ArtistLabPlanner.params(base, mix)
            assertThat(params.baseSeed).isEqualTo(base.baseSeed)
            assertThat(params.sampleCount).isEqualTo(1)
            assertThat(params.copy(prompt = base.prompt, sampleCount = base.sampleCount)).isEqualTo(base)
        }
        assertThat(plan.distinct()).hasSize(100)
        assertThat(ArtistLabPlanner.plan(pool, config(), Random(42))).isEqualTo(plan)
    }
    @Test fun `二十一档权重严格按零点零五并闭合`() {
        assertThat((10..30).map { ArtistTag("artist: sample", it).weighted }).containsExactlyElementsIn(
            listOf("0.50", "0.55", "0.60", "0.65", "0.70", "0.75", "0.80", "0.85", "0.90", "0.95", "1.00", "1.05", "1.10", "1.15", "1.20", "1.25", "1.30", "1.35", "1.40", "1.45", "1.50").map { "$it::artist: sample, ::" },
        ).inOrder()
        val locked = config().copy(minTicks = 20, maxTicks = 20)
        assertThat(ArtistLabPlanner.plan((1..1000).map { "artist: $it" }, locked, Random(1)).flatMap { it.artists }.all { it.ticks == 20 }).isTrue()
    }
    @Test fun `数字结尾画师不会开启新权重且基础提示词不受画师权重影响`() {
        val mix = ArtistMix(listOf(ArtistTag("artist: salmon88", 24), ArtistTag("artist: mignon", 15)))
        val prompt = ArtistLabPlanner.params(base, mix).prompt
        assertThat(prompt).isEqualTo("1.20::artist: salmon88, ::, 0.75::artist: mignon, ::, 1girl, blue sky")
        val spans = PromptWeightScanner.scan(prompt)
        assertThat(spans.map { it.weight }).containsExactly(1.2, 0.75).inOrder()
        assertThat(spans.map { prompt.substring(it.start, it.endExclusive) })
            .containsExactly("1.20::artist: salmon88, ::", "0.75::artist: mignon, ::").inOrder()
    }
    @Test fun `旧批次还原保留画师与权重并使用安全闭合格式`() {
        val mix = Json.decodeFromString<ArtistMix>("""{"artists":[{"tag":"artist: salmon88","ticks":24},{"tag":"artist: 123","ticks":10}]}""")
        assertThat(mix.artists.map { it.tag }).containsExactly("artist: salmon88", "artist: 123").inOrder()
        assertThat(mix.prompt).isEqualTo("1.20::artist: salmon88, ::, 0.50::artist: 123, ::")
        assertThat(PromptWeightScanner.scan(ArtistLabPlanner.params(base, mix).prompt).map { it.weight })
            .containsExactly(1.2, 0.5).inOrder()
    }
    @Test fun `旧收藏复制修正画师段且重复处理不变其它权重保持原样`() {
        val legacy = "1.20::artist: salmon88::, 0.75::artist: mignon::, 0.50::artist: 123::"
        val safe = "1.20::artist: salmon88, ::, 0.75::artist: mignon, ::, 0.50::artist: 123, ::"
        assertThat(ArtistMix.promptForReuse(legacy)).isEqualTo(safe)
        assertThat(ArtistMix.promptForReuse(safe)).isEqualTo(safe)
        assertThat(ArtistMix.promptForReuse("1.20::year 2024::, -1::hat ::")).isEqualTo("1.20::year 2024::, -1::hat ::")
    }
    @Test fun `一万张计划与参数快照往返不失真`() {
        assertThat(ArtistLabPlanner.plan((1..1000).map { "artist: $it" }, config(10_000, 1), Random(4))).hasSize(10_000)
        val raw = config().paramsJson
        assertThat(GenerationDraftCodec.encode(GenerationDraftCodec.decode(raw)!!)).isEqualTo(raw)
    }
    @Test fun `一百次独立串行调用严格间隔一至两秒`() = runTest {
        var active = 0
        var maxActive = 0
        val waits = mutableListOf<Long>()
        val calls = mutableListOf<Int>()
        ArtistLabQueue(wait = { waits += it }, interval = { if (calls.size % 2 == 0) 1000 else 2000 })
            .run((1..100).toList(), { false }) { active++; maxActive = maxOf(maxActive, active); calls += it; active-- }
        assertThat(calls).containsExactlyElementsIn(1..100).inOrder()
        assertThat(waits).hasSize(100)
        assertThat(waits.all { it in 1000..2000 }).isTrue()
        assertThat(maxActive).isEqualTo(1)
    }
    @Test fun `暂停与失败不重试当前请求也不启动下一张`() = runTest {
        val calls = mutableListOf<Int>()
        var pause = false
        val queue = ArtistLabQueue(wait = {})
        queue.run((1..100).toList(), { pause }) { calls += it; pause = true }
        assertThat(calls).containsExactly(1)
        calls.clear()
        try { queue.run((1..100).toList(), { false }) { calls += it; error("uncertain") } } catch (_: IllegalStateException) { }
        assertThat(calls).containsExactly(1)
        calls.clear(); pause = false
        ArtistLabQueue(wait = { pause = true }).run((1..100).toList(), { pause }) { calls += it }
        assertThat(calls).isEmpty()
    }
}
