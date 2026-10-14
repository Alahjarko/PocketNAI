package net.pocketnai.domain.artistlab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ArtistCatalogSelection(val schemaVersion: Int = 2, val artists: List<ArtistCatalogEntry>,
                                  val excluded: List<String> = emptyList(), val sourceName: String = "")

object ArtistCatalogExport {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    fun configuration(entries: List<ArtistCatalogEntry>, excluded: Set<String>): String {
        val tags = entries.map { it.tag }.toSet()
        return json.encodeToString(ArtistCatalogSelection(artists = entries, excluded = excluded.filter { it in tags }.sorted()))
    }

    fun enabledTags(entries: List<ArtistCatalogEntry>, excluded: Set<String>): String =
        entries.filterNot { it.tag in excluded }.joinToString("\n", postfix = if (entries.any { it.tag !in excluded }) "\n" else "") { it.tag }
}
