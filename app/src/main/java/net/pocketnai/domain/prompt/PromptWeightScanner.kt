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
    /** 权重低于 1（包括负数）用绿色，高于 1 用红色；显式中性权重也标出。 */
    val direction: WeightDirection
        get() = when {
            weight < 1.0 -> WeightDirection.WEAKER
            weight > 1.0 -> WeightDirection.STRONGER
            else -> WeightDirection.NEUTRAL
        }

    /** 区间长度，界面用它跳过空片段。 */
    val length: Int get() = endExclusive - start
}

/** 权重相对 1.0 的偏向，决定底纹用哪种颜色。 */
enum class WeightDirection {
    /** 未指定数值权重或数值为 1。 */
    NEUTRAL,
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
 * - 带可选正负号的数字前缀，例如 `-1::tag`、`0.9::tag ::`。
 *
 * 本类只负责显示，不改写实际提交文本，也不自动补上闭合符号。
 *
 * :: 独立识别，不要求相邻逗号或闭合；逗号/换行可位于组内。
 * 新数值前缀开始下一段，裸 :: 关闭当前段；未闭合段延伸到文末。
 * 剩余文本按顶层逗号识别完整的 {} / [] 包裹，不在组内部切分。
 */
object PromptWeightScanner {

    /**
     * 与 1.0 的差小于这个值就当作"没加权"。
     *
     * `{[tag]}` 这类写法在浮点下会算出 0.9999999999999998 或 1.0000000000000002，
     * 直接比大小会把它误判成弱化/强化，画出一层无意义的底色。
     */
    private const val EPSILON = 1e-9

    // 数值前缀必须先于裸 :: 匹配，否则下一段的开头会被当成上一段的结尾。
    private val COLON_TOKEN = Regex(
        """(?<![0-9.+-])([+-]?[0-9]*\.?[0-9]+)\s*::|::""",
    )

    /** 按出现顺序返回所有加权片段；没有则返回空列表。 */
    fun scan(prompt: String): List<PromptWeightSpan> {
        val spans = mutableListOf<PromptWeightSpan>()
        val groupStarts = colonSpans(prompt).associateBy { it.start }
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

        var index = 0
        while (index < prompt.length) {
            val group = groupStarts[index]
            if (group != null) {
                flush(index)
                spans += group
                index = group.endExclusive
                chunkStart = index
                depth = 0
                continue
            }
            when (prompt[index]) {
                '{', '[' -> depth++
                '}', ']' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    flush(index)
                    chunkStart = index + 1
                }
            }
            index++
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
        colonSpans(trimmed).singleOrNull()
            ?.takeIf { it.start == 0 && it.endExclusive == trimmed.length }
            ?.let { return it.weight }
        return EmphasisSyntax.strengthOf(trimmed)
    }

    private fun colonSpans(prompt: String): List<PromptWeightSpan> {
        val spans = mutableListOf<PromptWeightSpan>()
        var opening: MatchResult? = null

        fun finish(bodyEnd: Int, spanEnd: Int) {
            val start = opening ?: return
            if (prompt.substring(start.range.last + 1, bodyEnd).isNotBlank()) {
                var end = spanEnd
                while (end > start.range.last + 1 && prompt[end - 1].isWhitespace()) end--
                val weight = start.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: 1.0
                spans += PromptWeightSpan(start.range.first, end, weight)
            }
        }

        for (token in COLON_TOKEN.findAll(prompt)) {
            if (token.groupValues[1].isNotEmpty()) {
                finish(token.range.first, token.range.first)
                opening = token
            } else if (opening != null) {
                finish(token.range.first, token.range.last + 1)
                opening = null
            } else {
                opening = token
            }
        }
        var end = prompt.length
        while (end > 0 && prompt[end - 1].isWhitespace()) end--
        // 开头只有权重、尚未输入正文时不产生空高亮。
        val trailing = opening
        if (trailing != null && end >= trailing.range.last + 1) finish(end, end)
        return spans
    }
}
