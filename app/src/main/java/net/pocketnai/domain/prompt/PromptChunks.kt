package net.pocketnai.domain.prompt

/** Single pipes separate captions; pipes inside an official randomizer remain literal. */
object PromptChunks {
    fun split(text: String): List<String> {
        val parts = mutableListOf<String>()
        var randomizer = false
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text.startsWith("||", index)) { randomizer = !randomizer; index += 2 }
            else if (text[index] == '|' && !randomizer) {
                parts += text.substring(start, index)
                start = ++index
            } else index++
        }
        parts += text.substring(start)
        return parts
    }

    fun mapBase(text: String, transform: (String) -> String): String {
        val parts = split(text).toMutableList()
        parts[0] = transform(parts[0])
        return parts.joinToString("|")
    }
}
