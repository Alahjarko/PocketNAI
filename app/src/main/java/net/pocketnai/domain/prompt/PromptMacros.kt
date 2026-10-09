package net.pocketnai.domain.prompt

import net.pocketnai.domain.model.PromptFavorite

/** Local saved chunks use the same named-reference syntax as the web client. */
object PromptMacros {
    private val reference = Regex("!macro:([^!]+)!|⌜macro:([^⌟]+)⌟")
    private const val MAX_LENGTH = 128 * 1024

    fun resolve(text: String, favorites: List<PromptFavorite>): String {
        fun expand(value: String, visiting: Set<String>): String {
            require(value.length <= MAX_LENGTH) { "Prompt macro too large" }
            var length = 0
            val result = StringBuilder()
            var start = 0
            for (match in reference.findAll(value)) {
                val name = match.groups[1]?.value
                val candidates = if (name != null) favorites.filter { it.name == name }
                    else favorites.filter { it.id == match.groups[2]?.value }
                require(candidates.size == 1) { "Missing or ambiguous prompt macro" }
                val macro = candidates.single()
                require(macro.id !in visiting && visiting.size < 32) { "Circular prompt macro" }
                val replacement = expand(macro.content, visiting + macro.id)
                length += match.range.first - start + replacement.length
                require(length <= MAX_LENGTH) { "Prompt macro too large" }
                result.append(value, start, match.range.first).append(replacement)
                start = match.range.last + 1
            }
            require(length + value.length - start <= MAX_LENGTH) { "Prompt macro too large" }
            return result.append(value, start, value.length).toString()
        }
        return expand(text, emptySet())
    }

    fun reference(favorite: PromptFavorite): String = "⌜macro:${favorite.id}⌟"
}
