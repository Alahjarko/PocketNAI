package net.pocketnai.domain.artistlab

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.SeedMode
import net.pocketnai.domain.prompt.PromptComposition
import kotlin.random.Random

/** 权重以 1/20 为单位存储，避免浮点累加偏离 0.05 网格。 */
@Serializable
data class ArtistTag(val tag: String, val ticks: Int) {
    init { require(ticks in 10..30); require(tag.startsWith("artist: ")) }
    // 名字可能以数字结尾；逗号与空格隔开裸 ::，避免 salmon88:: 被解析成 88:: 权重。
    val weighted: String get() = "${ticks / 20}.${((ticks % 20) * 5).toString().padStart(2, '0')}::$tag, ::"
}

@Serializable
data class ArtistMix(val artists: List<ArtistTag>) {
    val prompt: String get() = artists.joinToString(", ") { it.weighted }

    companion object {
        // 旧收藏只存完整文本。复用时修正旧版自动生成的画师段，不改数据库主键或历史快照。
        private val legacyWeightedArtist = Regex("(\\d+\\.\\d{2}::artist: [^,:]+)::")

        fun promptForReuse(prompt: String): String = legacyWeightedArtist.replace(prompt) {
            "${it.groupValues[1]}, ::"
        }
    }
}

class ArtistLabRequestFailure(val uncertain: Boolean, val userMessage: String) : RuntimeException(userMessage)
class ArtistLabPreflightChanged : RuntimeException("发送前账号或费用改变")

/** 实验表单草稿与批次参数快照分开，允许保留用户尚未填完的数字。 */
@Serializable
data class ArtistLabForm(val prompt: String, val negative: String, val seed: String, val count: String,
                         val artists: Int, val minTicks: Int, val maxTicks: Int)

@Serializable
data class ArtistLabConfig(
    val paramsJson: String,
    val artistCount: Int,
    val minTicks: Int,
    val maxTicks: Int,
    val drawCount: Int,
    val catalogHash: String,
    val templatesResolved: Boolean = false,
) {
    init {
        require(artistCount in 1..10)
        require(minTicks in 10..30 && maxTicks in minTicks..30)
        require(drawCount in 1..10_000)
    }
}

object ArtistLabPlanner {
    fun plan(pool: List<String>, config: ArtistLabConfig, random: Random): List<ArtistMix> {
        require(pool.distinct().size == pool.size && pool.size >= config.artistCount)
        return List(config.drawCount) {
            // 只抽前 k 项的 Fisher–Yates：每张 O(k)，不为一万次抽卡重复洗牌整个词库。
            val swaps = mutableMapOf<Int, Int>()
            val artists = (0 until config.artistCount).map { index ->
                val pick = random.nextInt(index, pool.size)
                val selected = swaps[pick] ?: pick
                swaps[pick] = swaps[index] ?: index
                ArtistTag(pool[selected], random.nextInt(config.minTicks, config.maxTicks + 1))
            }
            ArtistMix(artists)
        }
    }

    fun params(base: GenerationParams, mix: ArtistMix): GenerationParams {
        require(base.seedMode == SeedMode.FIXED && base.baseSeed in 0..GenerationParams.MAX_SEED)
        require(!Regex("artist\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(base.prompt)) {
            "基础提示词中请移除 artist: 标签，让每张只改变抽取的画师串"
        }
        return base.copy(prompt = PromptComposition.append(mix.prompt, base.prompt), sampleCount = 1, characters = emptyList())
    }
}

/** 暂停只阻止下一张；异常直接向上抛出，没有重试、并发或补发。 */
class ArtistLabQueue(
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val interval: () -> Long = { Random.nextLong(1000, 2001) },
) {
    suspend fun <T> run(items: List<T>, shouldPause: () -> Boolean, execute: suspend (T) -> Unit) {
        for ((index, item) in items.withIndex()) {
            if (shouldPause()) return
            // 重新进入队列也先等一秒，避免暂停/恢复绕过服务端请求间隔。
            wait(if (index == 0) 1000 else interval().coerceIn(1000, 2000))
            if (shouldPause()) return
            execute(item)
        }
    }
}
