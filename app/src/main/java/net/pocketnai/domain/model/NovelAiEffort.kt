package net.pocketnai.domain.model

/** Medium uses the V5 Full distilled model; this is not an arbitrary API effort field. */
enum class NovelAiEffort(val label: String) {
    HIGH("High"), MEDIUM("Medium");
    companion object {
        fun fromName(value: String?): NovelAiEffort = entries.firstOrNull { it.name == value } ?: HIGH
    }
}
