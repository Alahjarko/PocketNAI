package net.pocketnai.domain.prompt

/**
 * 规范 `artist:` 画师标签的识别、剥离与合并工具。
 *
 * 支持 NovelAI V4 / V4.5 / V5 常见写法：
 * - 普通标签：`artist:ciloranko`、`artist: ask (askzy)`
 * - 括号权重：`{artist:foo}`、`{{artist:foo}}`、`[artist:bar]`
 * - 数值权重：`1.2::artist:foo::`、`0.8::artist:foo, artist:bar ::`
 *
 * 提交时通过 [combine] 将画师提示词单独成行并附带逗号与正向提示词合并。
 */
object ArtistPromptSplitter {

    data class SplitResult(
        val artistPrompt: String,
        val mainPrompt: String,
        val extractedAny: Boolean,
    )

    private val artistPrefixRegex = Regex("(?i)^artist\\s*:.*")
    private val numericWeightBlockRegex = Regex("""^([-+]?\d+(?:\.\d+)?::)(.+?)(::)$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * 判断单个规范标签片段是否为画师标签：剥离外层 `{}` / `[]` 与 `num::...::` 后，
     * 必须以 `artist:` 开头且后面有非空内容（避免把半截 `artist:` 当成完整标签）。
     */
    fun isArtistTag(rawToken: String): Boolean {
        val trimmed = rawToken.trim()
        if (trimmed.isEmpty()) return false
        val unwrapped = unwrapWeight(trimmed)
        if (!artistPrefixRegex.matches(unwrapped)) return false
        val colonIndex = unwrapped.indexOf(':')
        return colonIndex >= 0 && unwrapped.substring(colonIndex + 1).trim().isNotEmpty()
    }

    /**
     * 从整段提示词中剥离画师标签。
     *
     * @param extractOnlyCompleted 为 true 时（用户输入中），仅剥离后面已出现逗号或换行的已完成标签，
     * 避免用户刚输入到 `artist:ci` 时文字被突然抽走。
     */
    fun split(prompt: String, extractOnlyCompleted: Boolean = false): SplitResult {
        if (prompt.isBlank()) return SplitResult("", prompt, false)
        val tokens = tokenizeTopLevel(prompt)
        if (tokens.isEmpty()) return SplitResult("", prompt, false)

        val artistTokens = mutableListOf<String>()
        val mainTokens = mutableListOf<String>()
        var extracted = false

        tokens.forEachIndexed { index, piece ->
            val isTrailingUnfinished = extractOnlyCompleted && index == tokens.lastIndex && !piece.hasTrailingSeparator
            if (isTrailingUnfinished) {
                mainTokens += piece.raw.trim()
                return@forEachIndexed
            }
            val token = piece.raw.trim()
            if (token.isEmpty()) return@forEachIndexed

            val weightMatch = numericWeightBlockRegex.matchEntire(token)
            if (weightMatch != null) {
                val prefix = weightMatch.groupValues[1]
                val inner = weightMatch.groupValues[2]
                val suffix = weightMatch.groupValues[3]
                val innerParts = inner.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                if (innerParts.isNotEmpty() && innerParts.all { isArtistTag(it) }) {
                    artistTokens += token
                    extracted = true
                } else if (innerParts.any { isArtistTag(it) }) {
                    val innerArtists = innerParts.filter { isArtistTag(it) }
                    val innerMain = innerParts.filterNot { isArtistTag(it) }
                    if (innerArtists.isNotEmpty()) {
                        artistTokens += "$prefix${innerArtists.joinToString(", ")}$suffix"
                        extracted = true
                    }
                    if (innerMain.isNotEmpty()) {
                        mainTokens += "$prefix${innerMain.joinToString(", ")}$suffix"
                    }
                } else {
                    mainTokens += token
                }
            } else if (isArtistTag(token)) {
                artistTokens += token
                extracted = true
            } else {
                mainTokens += token
            }
        }

        if (!extracted) return SplitResult("", prompt, false)
        val trailingComma = if (extractOnlyCompleted && tokens.lastOrNull()?.hasTrailingSeparator == true && mainTokens.isNotEmpty()) ", " else ""
        return SplitResult(
            artistPrompt = artistTokens.joinToString(", "),
            mainPrompt = mainTokens.joinToString(", ") + trailingComma,
            extractedAny = true,
        )
    }

    /** 将独立画师提示词与正向提示词合并为最终提交字符串：画师串单独成行置于前方。 */
    fun combine(artistPrompt: String, mainPrompt: String): String {
        val artist = artistPrompt.trim()
        val main = mainPrompt.trim()
        if (artist.isEmpty()) return mainPrompt
        if (main.isEmpty()) return artist
        val artistLine = if (artist.endsWith(",") || artist.endsWith("，")) artist else "$artist,"
        return "$artistLine\n$main"
    }

    /** 将新剥离出的画师串追加到已有画师框文本中，避免重复逗号。 */
    fun appendArtist(existing: String, added: String): String {
        val left = existing.trim().trimEnd(',', '，').trim()
        val right = added.trim().trimStart(',', '，').trim()
        return when {
            left.isEmpty() -> right
            right.isEmpty() -> left
            else -> "$left, $right"
        }
    }

    private fun unwrapWeight(token: String): String {
        var s = token.trim()
        while (s.length >= 2 && ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]")))) {
            s = s.substring(1, s.length - 1).trim()
        }
        val m = numericWeightBlockRegex.matchEntire(s)
        if (m != null) {
            s = m.groupValues[2].trim()
            while (s.length >= 2 && ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]")))) {
                s = s.substring(1, s.length - 1).trim()
            }
        }
        return s
    }

    private data class Piece(val raw: String, val hasTrailingSeparator: Boolean)

    /** 按顶层逗号与换行分词，保持 `num:: ... ::` 权重块与 `{...}` / `[...]` 成对完整。 */
    private fun tokenizeTopLevel(input: String): List<Piece> {
        val result = mutableListOf<Piece>()
        val buf = StringBuilder()
        var braceDepth = 0
        var bracketDepth = 0
        var inNumericWeight = false
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            if (c == ':' && i + 1 < n && input[i + 1] == ':') {
                inNumericWeight = !inNumericWeight
                buf.append("::")
                i += 2
                continue
            }
            when (c) {
                '{' -> { braceDepth++; buf.append(c) }
                '}' -> { if (braceDepth > 0) braceDepth--; buf.append(c) }
                '[' -> { bracketDepth++; buf.append(c) }
                ']' -> { if (bracketDepth > 0) bracketDepth--; buf.append(c) }
                ',', '，', '\n' -> {
                    if (braceDepth == 0 && bracketDepth == 0 && !inNumericWeight) {
                        result += Piece(buf.toString(), hasTrailingSeparator = true)
                        buf.clear()
                    } else {
                        buf.append(c)
                    }
                }
                else -> buf.append(c)
            }
            i++
        }
        if (buf.isNotEmpty()) {
            result += Piece(buf.toString(), hasTrailingSeparator = false)
        }
        return result
    }
}
