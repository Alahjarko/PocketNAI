package net.pocketnai.ui.artistlab

import net.pocketnai.data.local.ArtistMixFavoriteEntity
import net.pocketnai.domain.artistlab.ArtistMix
import net.pocketnai.domain.model.GalleryItem

/** 保留原始收藏主键；旧权重文本与修复后的同一串共用图片，不修改历史。 */
data class FavoriteArtistMix(
    val keys: List<String>,
    val prompt: String,
    val artists: List<String>,
    val images: List<GalleryItem>,
)

object ArtistMixFavoriteGallery {
    private val artistName = Regex("artist:\\s*([^,:]+)")

    fun matches(mix: FavoriteArtistMix, query: String): Boolean {
        val names = mix.artists.joinToString(" ")
        return query.trim().replace('_', ' ').split(Regex("\\s+")).filter { it.isNotBlank() }
            .all { names.contains(it, ignoreCase = true) }
    }

    fun build(
        saved: List<ArtistMixFavoriteEntity>,
        imageLinks: Map<String, List<String>>,
        gallery: List<GalleryItem>,
        favoriteIds: Set<String>,
    ): List<FavoriteArtistMix> {
        val images = gallery.associateBy { it.imageId }
        return saved.groupBy { ArtistMix.promptForReuse(it.prompt) }.map { (prompt, entries) ->
            val linked = imageLinks[prompt].orEmpty().distinct().mapNotNull { images[it] }
                .sortedWith(compareByDescending<GalleryItem> { it.imageId in favoriteIds }
                    .thenByDescending { it.createdAt }.thenBy { it.imageId })
            FavoriteArtistMix(entries.map { it.prompt }, prompt,
                artistName.findAll(prompt).map { it.groupValues[1].trim().replace('_', ' ') }.toList(), linked)
        }
    }
}
