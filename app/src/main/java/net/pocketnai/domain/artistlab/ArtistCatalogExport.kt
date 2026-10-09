package net.pocketnai.domain.artistlab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ArtistCatalogSelection(val schemaVersion: Int = 1, val artists: List<ArtistCatalogEntry>, val excluded: List<String>)

object ArtistCatalogExport {
    fun configuration(entries: List<ArtistCatalogEntry>, excluded: Set<String>): String =
        Json { prettyPrint = true; encodeDefaults = true }.encodeToString(ArtistCatalogSelection(artists = entries, excluded = excluded.sorted()))

    fun enabledTags(entries: List<ArtistCatalogEntry>, excluded: Set<String>): String =
        entries.filterNot { it.tag in excluded }.joinToString("\n", postfix = if (entries.any { it.tag !in excluded }) "\n" else "") { it.tag }
}
