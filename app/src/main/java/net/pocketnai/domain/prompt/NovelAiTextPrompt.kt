package net.pocketnai.domain.prompt

import net.pocketnai.domain.model.CharacterPrompt

/** V5 web-client text preparation. Keep this out of drafts and history snapshots.
 * Protocol checked against novelai.net's public frontend on 2026-10-09.
 */
object NovelAiTextPrompt {
    private val marker = Regex(
        "(?:^|[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]|[,.:\\[\\]{}、。])text:(?!:)",
        RegexOption.IGNORE_CASE,
    )
    private val quotes = mapOf('"' to '"', '“' to '”', '「' to '」', '\'' to '\'', '‘' to '’')

    /** The case-sensitive marker and an exact quotation match prove this was web-generated. */
    fun removeAutomatic(prompt: String, characters: List<CharacterPrompt>, useCoordinates: Boolean): String =
        PromptChunks.split(prompt).joinToString("|") { base ->
            val found = Regex(marker.pattern.replace("text:", "teXt:")).find(base)
                ?: return@joinToString base
            val before = base.substring(0, found.range.first)
            val generated = appendAutomatic(before, characters, useCoordinates)
            val expected = generated.substringAfter("teXt: ", missingDelimiterValue = "")
            if (expected.isNotEmpty() && base.substring(found.range.last + 1).trim() == expected) {
                before.trimEnd { it.isWhitespace() || it == ',' }
            } else base
        }

    fun stripQuality(prompt: String, suffix: String): String? {
        val parts = PromptChunks.split(prompt).toMutableList()
        val base = parts[0]
        val found = marker.find(base)
        val before = (if (found == null) base else base.substring(0, found.range.first)).trimEnd()
        val stripped = when {
            before == suffix -> ""
            before.endsWith(", $suffix") -> before.dropLast(suffix.length + 2).trimEnd()
            else -> return null
        }
        parts[0] = stripped + if (found == null) "" else base.substring(found.range.first)
        return parts.joinToString("|")
    }

    /** Quality tags belong to the base caption, never to the literal text to be drawn. */
    fun appendQuality(prompt: String, suffix: String): String {
        val end = firstChunkEnd(prompt)
        val base = prompt.substring(0, end)
        val found = marker.find(base)
        if (found == null) return PromptComposition.append(base, suffix, preserveWhitespace = true) + prompt.substring(end)
        val before = base.substring(0, found.range.first)
        val tail = base.substring(found.range.first)
        val boundary = if (found.range.first == 0) ", " else ""
        return PromptComposition.append(before, suffix, preserveWhitespace = true) + boundary + tail + prompt.substring(end)
    }

    /** Append only once; an explicit text block in any positive caption opts out. */
    fun appendAutomatic(prompt: String, characters: List<CharacterPrompt>, useCoordinates: Boolean): String {
        val active = characters.filter { it.prompt.isNotEmpty() }
        if (marker.containsMatchIn(prompt) || active.any { marker.containsMatchIn(it.prompt) }) return prompt
        val end = firstChunkEnd(prompt)
        val base = prompt.substring(0, end)
        val ordered = if (useCoordinates) readingOrder(active) else active
        val groups = listOf(extractQuotes(base)) + ordered.map { extractQuotes(it.prompt) }
        val text = groups.flatten().joinToString("")
        if (text.isEmpty()) return prompt
        // The web client reverses each caption's quotations for predominantly CJK text.
        val cjk = text.count { it in '\u3000'..'\u303f' || it in '\u3040'..'\u30ff' ||
            it in '\uff00'..'\uff9f' || it in '\u4e00'..'\u9faf' || it in '\u3400'..'\u4dbf' }
        val pieces = (if (cjk.toDouble() / text.length > 0.3) groups.map { it.reversed() } else groups).flatten()
        val prepared = "teXt: " + pieces.joinToString("\n\n")
        return PromptComposition.append(base.trimEnd { it.isWhitespace() || it == ',' }, prepared, preserveWhitespace = true) + prompt.substring(end)
    }

    private fun extractQuotes(text: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val opening = text[index]
            val closing = quotes[opening]
            val previous = text.getOrNull(index - 1)
            if (closing == null || (opening == '\'' && previous != null &&
                    !previous.isWhitespace() && previous != ',' && previous != '.')) {
                index++
                continue
            }
            val apostrophe = closing == '\'' || closing == '’'
            var end = index + 1
            while (end < text.length && (text[end] != closing ||
                    (apostrophe && letterOrNumberAt(text, end + 1)))) end++
            if (end == text.length) { index++; continue }
            text.substring(index + 1, end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            index = end + 1
        }
        return result
    }

    private fun letterOrNumberAt(text: String, index: Int): Boolean {
        if (index >= text.length) return false
        val point = text.codePointAt(index)
        return Character.isLetterOrDigit(point) || Character.getType(point) in
            listOf(Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt())
    }

    private fun readingOrder(characters: List<CharacterPrompt>): List<CharacterPrompt> {
        fun rows(sorted: List<CharacterPrompt>): List<CharacterPrompt> {
            if (sorted.size <= 1) return sorted
            val split = (1 until sorted.size).maxBy { sorted[it].centerY - sorted[it - 1].centerY }
            val gap = sorted[split].centerY - sorted[split - 1].centerY
            return if (sorted.last().centerY - sorted.first().centerY <= 0.15 && gap <= 0.1) {
                sorted.sortedBy { it.centerX }
            } else rows(sorted.take(split)) + rows(sorted.drop(split))
        }
        return rows(characters.sortedBy { it.centerY })
    }

    /** A single | separates captions; || randomizer spans may contain literal pipes. */
    private fun firstChunkEnd(prompt: String): Int {
        var randomizer = false
        var index = 0
        while (index < prompt.length) {
            if (prompt.startsWith("||", index)) { randomizer = !randomizer; index += 2 }
            else if (prompt[index] == '|' && !randomizer) return index
            else index++
        }
        return prompt.length
    }
}
