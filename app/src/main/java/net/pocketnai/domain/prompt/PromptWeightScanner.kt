package net.pocketnai.domain.prompt

import kotlin.math.abs

/**
 * 提示词里一段"被加了权重"的片段在原文中的位置。
 *
 * 区间是半开的 `[start, endExclusive)`，且**包含语法符号本身**
 * （`{`/`}`、`[`/`]`、`0.9::`），这样界面按它画出来的底纹能把整个片段圈住，
 * 而不是只盖住其中的裸文字。
 */
data class PromptWeightSpan(
    val start: Int,
    val endExclusive: Int,
    val weight: Double,
) {
    /** 权重低于 1 是弱化，高于 1 是强化；等于 1 的片段根本不会被扫描出来。 */
    val direction: WeightDirection
        get() = if (weight < 1.0) WeightDirection.WEAKER else WeightDirection.STRONGER

    /** 区间长度，界面用它跳过空片段。 */
    val length: Int get() = endExclusive - start
}

/** 权重相对 1.0 的偏向，决定底纹用哪种颜色。 */
enum class WeightDirection {
    /** 权重 < 1：这个标签被削弱了。 */
    WEAKER,

    /** 权重 > 1：这个标签被加强了。 */
    STRONGER,
}

/**
 * 扫描提示词里的加权片段，供编辑框画高亮底纹（技术决策记录第 22 节）。
 *
 * 认识两种写法：
 * - NovelAI 的包裹语法：每层 `{}` 乘 1.05、每层 `[]` 除以 1.05，权重计算**复用
 *   [EmphasisSyntax.strengthOf]**，不在这里另写一套 —— 否则两个入口迟早会给出不同的数；
 * - 数字前缀 `0.9::tag ::`。
 *
 * ⚠️ 数字前缀这一条是**为高亮而实现的，不代表 NovelAI 认这种写法**。
 * 现有代码注释（[EmphasisSyntax]）本来就写着"数字权重不是 NovelAI 的语法"，而这一点
 * 至今没有核对过。因此本类只做"把它画出来"这一件事：不替用户改写提示词，
 * 界面上也不提供插入这种写法的按钮。用户若要用，风险自担，详见技术决策记录 22.4。
 *
 * 只按**顶层**逗号切分片段：`{a, b}` 内部的逗号不切，避免把一个被包裹的短语拆成两半
 * （被拆开后两半都算不出权重，高亮会整段消失）。
 */
object PromptWeightScanner {

    /**
     * 与 1.0 的差小于这个值就当作"没加权"。
     *
     * `{[tag]}` 这类写法在浮点下会算出 0.9999999999999998 或 1.0000000000000002，
     * 直接比大小会把它误判成弱化/强化，画出一层无意义的底色。
     */
    private const val EPSILON = 1e-9

    /**
     * 数字前缀写法：`0.9::ningen mame ::`。
     *
     * `.*` 配 [RegexOption.DOT_MATCHES_ALL]，因为片段里可能有换行（用户没打逗号时）；
     * 贪婪匹配会一直退到最后一个 `::`，于是 `1.3::a::b::` 整体算 1.3，符合直觉。
     */
    private val NUMERIC_PREFIX = Regex(
        pattern = """^([0-9]*\.?[0-9]+)\s*::(.*)::\s*$""",
        option = RegexOption.DOT_MATCHES_ALL,
    )

    /** 按出现顺序返回所有加权片段；没有则返回空列表。 */
    fun scan(prompt: String): List<PromptWeightSpan> {
        val spans = mutableListOf<PromptWeightSpan>()
        var chunkStart = 0
        var depth = 0

        fun flush(endExclusive: Int) {
            var start = chunkStart
            var end = endExclusive
            while (start < end && prompt[start].isWhitespace()) start++
            while (end > start && prompt[end - 1].isWhitespace()) end--
            if (start >= end) return

            val weight = weightOf(prompt.substring(start, end))
            if (abs(weight - 1.0) < EPSILON) return
            spans += PromptWeightSpan(start = start, endExclusive = end, weight = weight)
        }

        for (index in prompt.indices) {
            when (prompt[index]) {
                '{', '[' -> depth++
                '}', ']' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    flush(index)
                    chunkStart = index + 1
                }
            }
        }
        flush(prompt.length)
        return spans
    }

    /**
     * 计算一个**片段**（通常是以逗号分隔的一个词条）的等效权重。
     *
     * 数字前缀优先于包裹语法：`0.9::{a}::` 取 0.9，因为用户已经写明了数字，
     * 再乘一次包裹倍率会得到一个他没有要求过的值。
     */
    fun weightOf(term: String): Double {
        val trimmed = term.trim()
        val numeric = NUMERIC_PREFIX.matchEntire(trimmed)
        if (numeric != null) {
            val value = numeric.groupValues[1].toDoubleOrNull()
            // 内容为空（`0.9::::`）时没有可高亮的实体，交回包裹语法处理。
            if (value != null && numeric.groupValues[2].isNotBlank()) return value
        }
        return EmphasisSyntax.strengthOf(trimmed)
    }
}
