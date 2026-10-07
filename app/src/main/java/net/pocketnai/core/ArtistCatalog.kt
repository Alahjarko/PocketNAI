package net.pocketnai.core

/** 词库规模由来源清单决定；仍要足够支持一次抽十位，并检查完整性。 */
object ArtistCatalog {
    fun validate(bytes: ByteArray, expectedCount: Int, expectedHash: String): List<String> {
        val tags = bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
        check(expectedCount >= 10 && tags.size == expectedCount) { "Artist catalog count mismatch" }
        check(tags.distinct().size == tags.size && tags.all {
            it.startsWith("artist: ") && it.removePrefix("artist: ").isNotBlank() &&
                "::" !in it && it.none { ch -> ch in ",{}[]" }
        }) { "Invalid artist catalog tags" }
        check(Hashing.sha256(bytes) == expectedHash) { "Artist catalog digest mismatch" }
        return tags
    }
}
