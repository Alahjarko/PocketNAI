package net.pocketnai.domain.prompt

import kotlin.random.Random

/**
 * 本地 Prompt Randomizer（规划书 8.3）。
 *
 * 语法是 PocketNAI 自己的约定，因为 NovelAI 的提示词语法没有随机选项：
 * - `<a|b|c>`：每次生成时从候选中随机取一个；
 * - `\<`：转义，输出字面量 `<`；
 * - 不支持嵌套，`<` 与 `>` 之间不允许再出现 `<`。
 *
 * 关键语义：每次点击“生成”时**先固定本次展开结果**，再把展开后的提示词作为请求快照；
 * 历史同时保存模板原文与本次实际提示词，保证“复制提示词”和“复现生成”都不含糊。
 * 因此本类只做纯函数式的展开，随机源由调用方传入，便于测试复现。
 */
object PromptRandomizer {

    private const val OPEN = '<'
    private const val CLOSE = '>'
    private const val SEPARATOR = '|'
    private const val ESCAPE = '\\'

    /** 提示词里是否存在可展开的随机选项。 */
    fun hasOptions(prompt: String): Boolean = parse(prompt).any { it is Segment.Options }

    /**
     * 展开所有随机选项。[random] 由调用方提供，测试时可传固定种子的 [Random]。
     *
     * 这里**总是**由解析结果重建字符串，而不是“没有选项就原样返回”。
     * 因为转义符 `\<` 表示“输出字面量 `<`”，它必须在没有随机选项时也生效；
     * 而对其它内容，重建是逐字符无损的，不会改写用户输入。
     */
    fun resolve(prompt: String, random: Random): String {
        val segments = parse(prompt)
        return buildString(prompt.length) {
            for (segment in segments) {
                when (segment) {
                    is Segment.Literal -> append(segment.text)
                    is Segment.Options -> append(segment.candidates[random.nextInt(segment.candidates.size)])
                }
            }
        }
    }

    /**
     * 估算组合总数，用于在界面上提示“这个模板能出多少种不同结果”。
     * 组合数过大时返回 [Long.MAX_VALUE] 以避免溢出误导。
     */
    fun estimateCombinations(prompt: String): Long {
        var total = 1L
        for (segment in parse(prompt)) {
            if (segment !is Segment.Options) continue
            val size = segment.candidates.size.toLong()
            if (size == 0L) continue
            if (total > Long.MAX_VALUE / size) return Long.MAX_VALUE
            total *= size
        }
        return total
    }

    private sealed interface Segment {
        data class Literal(val text: String) : Segment
        data class Options(val candidates: List<String>) : Segment
    }

    private fun parse(prompt: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        val literal = StringBuilder()
        var index = 0
        while (index < prompt.length) {
            val ch = prompt[index]
            when {
                ch == ESCAPE && index + 1 < prompt.length && prompt[index + 1] == OPEN -> {
                    literal.append(OPEN)
                    index += 2
                }

                ch == OPEN -> {
                    val closeIndex = prompt.indexOf(CLOSE, startIndex = index + 1)
                    if (closeIndex < 0) {
                        // 没有闭合的 `<`：当成普通字符，不擅自吞掉用户输入。
                        literal.append(ch)
                        index++
                    } else {
                        val body = prompt.substring(index + 1, closeIndex)
                        val candidates = body.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
                        if (candidates.size < 2) {
                            // 只有一项时没有随机意义，原样保留，避免破坏用户的字面量。
                            literal.append(prompt, index, closeIndex + 1)
                        } else {
                            if (literal.isNotEmpty()) {
                                segments += Segment.Literal(literal.toString())
                                literal.clear()
                            }
                            segments += Segment.Options(candidates)
                        }
                        index = closeIndex + 1
                    }
                }

                else -> {
                    literal.append(ch)
                    index++
                }
            }
        }
        if (literal.isNotEmpty()) segments += Segment.Literal(literal.toString())
        return segments
    }
}
