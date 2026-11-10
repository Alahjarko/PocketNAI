package net.pocketnai.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import net.pocketnai.domain.model.GalleryFilter
import net.pocketnai.domain.model.GallerySearch

/** All user values are bound parameters, including literal %, _ and quotes. */
internal object GalleryQueries {
    fun build(filter: GalleryFilter, limit: Int, idsOnly: Boolean = false): SimpleSQLiteQuery {
        val fields = if (idsOnly) "i.id" else """i.id AS imageId, i.generationId, i.ordinal,
            i.relativePath AS relative_path, i.width, i.height, i.byteSize AS byte_size, i.seed,
            i.exportedUri AS exported_uri, i.createdAt AS image_created_at, g.status, g.mode,
            g.title, g.modelApiId AS model_api_id, g.prompt, g.negativePrompt,
            g.sampleCount AS sample_count, g.charactersJson"""
        val args = mutableListOf<Any>()
        val sql = StringBuilder("SELECT $fields FROM generated_images i JOIN generations g ON g.id=i.generationId WHERE g.deletedAt IS NULL")
        if (filter.favoritesOnly) sql.append(" AND EXISTS(SELECT 1 FROM favorite_images f WHERE f.imageId=i.id)")
        if (filter.modelApiIds.isNotEmpty()) {
            sql.append(" AND g.modelApiId IN (${filter.modelApiIds.joinToString { "?" }})")
            args.addAll(filter.modelApiIds.sorted())
        }
        filter.mode?.let { sql.append(" AND COALESCE(g.mode,'TXT2IMG')=?"); args.add(it.name) }
        GallerySearch.terms(filter.query).distinct().forEach {
            sql.append(" AND INSTR(COALESCE(g.searchPrompt,REPLACE(LOWER(g.prompt),'_',' ')),?)>0")
            args.add(it)
        }
        sql.append(" ORDER BY i.createdAt DESC,i.ordinal ASC,i.id ASC LIMIT ?")
        args.add(limit)
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }
}
