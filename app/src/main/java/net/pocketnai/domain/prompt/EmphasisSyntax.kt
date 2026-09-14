package net.pocketnai.domain.prompt

/**
 * NovelAI 强调语法的便捷编辑（规划书 8.2）。
 *
 * 只实现 NovelAI 明确支持的 `{}` / `[]` 嵌套语法：
 * - 每层 `{}` 把权重乘以 1.05；
 * - 每层 `[]` 把权重除以 1.05（约 0.952）。
 *
 * 刻意不做“数字权重”`(tag:1.2)` 形式，因为那不是 NovelAI 的语法；
 * 首版也不在后台重写整段提示词，所有插入都由用户点击触发、在编辑框里立即可见。
 */
object EmphasisSyntax {

    /** 单层强调的倍率。 */
    const val STEP: Double = 1.05

    private const val OPEN_CURLY = '{'
    private const val CLOSE_CURLY = '}'
    private const val OPEN_SQUARE = '['
    private const val CLOSE_SQUARE = ']'

    /** 强化一层：`tag` -> `{tag}`。 */
    fun strengthen(term: String): String {
        val body = term.trim()
        if (body.isEmpty()) return term
        return "$OPEN_CURLY$body$CLOSE_CURLY"
    }

    /** 弱化一层：`tag` -> `[tag]`。 */
    fun weaken(term: String): String {
        val body = term.trim()
        if (body.isEmpty()) return term
        return "$OPEN_SQUARE$body$CLOSE_SQUARE"
    }

    /**
     * 解析一个词条的等效权重。
     *
     * 反复剥离完整包裹的 `{}` / `[]` 层并累乘倍率；无法完整包裹时停止。
     * 例如 `{{cat}}` -> 1.1025，`[cat]` -> 0.9524，`{a} and {b}` -> 1.0。
     */
    fun strengthOf(term: String): Double {
        var current = term.trim()
        var weight = 1.0
        while (true) {
            val curly = enclosedLayers(current, OPEN_CURLY, CLOSE_CURLY)
            if (curly > 0) {
                weight *= Math.pow(STEP, curly.toDouble())
                current = stripLayers(current, curly)
                continue
            }
            val square = enclosedLayers(current, OPEN_SQUARE, CLOSE_SQUARE)
            if (square > 0) {
                weight *= Math.pow(1.0 / STEP, square.toDouble())
                current = stripLayers(current, square)
                continue
            }
            return weight
        }
    }

    /**
     * 计算 [term] 被 [open]/[close] 完整包裹的层数。
     * 只有最外层开符号与最外层闭符号真正配对、且内部不存在提前闭合时才计数。
     */
    private fun enclosedLayers(term: String, open: Char, close: Char): Int {
        var count = 0
        var current = term
        while (current.length >= 2 && current.first() == open && current.last() == close) {
            var depth = 0
            var matchedAtEnd = false
            for (index in current.indices) {
                when (current[index]) {
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) {
                            matchedAtEnd = index == current.lastIndex
                            break
                        }
                    }
                }
            }
            if (!matchedAtEnd) break
            count++
            current = current.substring(1, current.length - 1)
        }
        return count
    }

    private fun stripLayers(term: String, layers: Int): String =
        term.substring(layers, term.length - layers)
}
