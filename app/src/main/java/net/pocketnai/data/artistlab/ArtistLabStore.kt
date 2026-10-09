package net.pocketnai.data.artistlab

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import net.pocketnai.domain.artistlab.ArtistLabForm
import net.pocketnai.domain.artistlab.ArtistCatalogEntry
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import net.pocketnai.data.local.*

class ArtistLabStore(context: Context, val database: PocketNaiDatabase) {
    private val assets = context.applicationContext.assets
    private val prefs = context.applicationContext.getSharedPreferences("pocketnai_artist_lab_draft", Context.MODE_PRIVATE)
    private val catalogPrefs = context.applicationContext.getSharedPreferences("pocketnai_artist_lab_catalog", Context.MODE_PRIVATE)
    val dao = database.artistLabDao()
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun loadForm(): ArtistLabForm? = runCatching { json.decodeFromString<ArtistLabForm>(prefs.getString("form", null) ?: return null) }
        .getOrNull()?.takeIf { it.artists in 1..10 && it.minTicks in 10..30 && it.maxTicks in it.minTicks..30 }
    fun saveForm(form: ArtistLabForm) { prefs.edit().putString("form", json.encodeToString(form)).apply() }
    fun excludedArtists(): Set<String> = catalogPrefs.getStringSet("excluded", emptySet()).orEmpty().toSet()
    fun saveExcludedArtists(tags: Set<String>) { catalogPrefs.edit().putStringSet("excluded", tags.toSet()).apply() }

    suspend fun catalogEntries(): List<ArtistCatalogEntry> = withContext(Dispatchers.IO) {
        val bytes = assets.open("artist-lab/previews.json").use { it.readBytes() }
        val source = assets.open("artist-lab/source.json").bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
        check(net.pocketnai.core.Hashing.sha256(bytes) == source.getValue("previews_sha256").jsonPrimitive.content)
        json.decodeFromString<List<ArtistCatalogEntry>>(bytes.toString(Charsets.UTF_8))
    }

    suspend fun catalog(): Pair<List<String>, String> = withContext(Dispatchers.IO) {
        val bytes = assets.open("artist-lab/artists.txt").use { it.readBytes() }
        val source = assets.open("artist-lab/source.json").bufferedReader().use { it.readText() }
        val metadata = json.parseToJsonElement(source).jsonObject
        val hash = metadata.getValue("pool_sha256").jsonPrimitive.content
        val tags = net.pocketnai.core.ArtistCatalog.validate(bytes, metadata.getValue("count").jsonPrimitive.int, hash)
        tags to hash
    }

    /** 恢复仅关联已落盘图片；没收到最终图的请求不重发。 */
    suspend fun recover() {
        database.withTransaction {
            dao.interruptedDraws().forEach { draw ->
                val image = draw.generationId?.let { database.generationDao().findImages(it).firstOrNull() }
                dao.updateDraw(draw.copy(
                    status = if (image != null) "SUCCEEDED" else "UNCERTAIN",
                    imageId = image?.id,
                    message = if (image != null) "" else "运行中断，结果待确认；请核对图库和余额。本张不会重试。",
                ))
            }
            dao.pauseInterruptedRuns()
        }
    }

    suspend fun favorite(draw: ArtistLabDrawEntity, prompt: String, favorite: Boolean) {
        database.withTransaction {
            val imageId = draw.imageId ?: return@withTransaction
            if (database.generationDao().findImage(imageId) == null) return@withTransaction
            if (favorite) {
                database.favoriteImageDao().add(FavoriteImageEntity(imageId, System.currentTimeMillis()))
                dao.saveMix(ArtistMixFavoriteEntity(prompt, System.currentTimeMillis()))
            } else database.favoriteImageDao().remove(imageId)
        }
    }
}
