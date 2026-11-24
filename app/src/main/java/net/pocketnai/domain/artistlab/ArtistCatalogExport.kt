package net.pocketnai.domain.artistlab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ArtistCatalogSelection(
    val schemaVersion: Int = 2,
    val artists: List<ArtistCatalogEntry>,
    val excluded: List<String> = emptyList(),
    val sourceName: String = "",
    val lockedArtists: List<String> = emptyList(),
    val tieredWeights: Boolean? = null,
    @kotlinx.serialization.Transient val fromJsonConfig: Boolean = false,
)

object ArtistCatalogExport {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    fun configuration(
        entries: List<ArtistCatalogEntry>,
        excluded: Set<String>,
        lockedArtists: List<String> = emptyList(),
        tieredWeights: Boolean? = null,
    ): String {
        val tags = entries.map { it.tag }.toSet()
        val validExcluded = excluded.filter { it in tags }.sorted()
        val validLocked = lockedArtists.filter { it in tags && it !in validExcluded }
            .distinct()
            .take(ArtistLabConfig.MAX_LOCKED_ARTISTS)
        return json.encodeToString(
            ArtistCatalogSelection(
                artists = entries,
                excluded = validExcluded,
                lockedArtists = validLocked,
                tieredWeights = tieredWeights,
            ),
        )
    }

    fun enabledTags(entries: List<ArtistCatalogEntry>, excluded: Set<String>): String =
        entries.filterNot { it.tag in excluded }.joinToString("\n", postfix = if (entries.any { it.tag !in excluded }) "\n" else "") { it.tag }
}
