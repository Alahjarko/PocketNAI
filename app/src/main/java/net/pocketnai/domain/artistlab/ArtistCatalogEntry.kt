package net.pocketnai.domain.artistlab

import kotlinx.serialization.Serializable

@Serializable
data class ArtistArtworkPreview(val asset: String, val sourceUrl: String)

@Serializable
data class ArtistCatalogEntry(val tag: String, val name: String, val previews: List<ArtistArtworkPreview>)
