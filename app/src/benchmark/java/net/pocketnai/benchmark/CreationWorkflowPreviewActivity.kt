package net.pocketnai.benchmark

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.pocketnai.domain.chat.ChatEntry
import net.pocketnai.domain.chat.PromptEdit
import net.pocketnai.data.local.*
import net.pocketnai.di.AppContainer
import net.pocketnai.domain.model.*
import net.pocketnai.domain.openai.*
import net.pocketnai.ui.*
import net.pocketnai.ui.theme.PocketNaiTheme
import org.json.JSONObject
import java.io.File

/** Explicit benchmark build only: separate app ID, no INTERNET permission, no user data. */
class CreationWorkflowPreviewActivity : ComponentActivity() {
    private companion object {
        // Match PocketNaiApplication's process-scoped dependencies across font/rotation changes.
        var sharedContainer: AppContainer? = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = sharedContainer ?: AppContainer(application).also { sharedContainer = it }
        lifecycleScope.launch {
            container.awaitStartup()
            withContext(Dispatchers.IO) {
                val db = container.database
                if (db.generationDao().allGenerationIds().isEmpty()) {
                    val now = System.currentTimeMillis()
                    val images = (0..3).map { n -> File(filesDir, "generations/demo-0/$n.png").also { file ->
                        file.parentFile!!.mkdirs()
                        Bitmap.createBitmap(160, 224, Bitmap.Config.ARGB_8888).let { bitmap ->
                            bitmap.eraseColor(listOf(0xff97b5cf.toInt(), 0xffb6c9ad.toInt(), 0xffd5b8a1.toInt(), 0xffbcb0d4.toInt())[n])
                            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                        }
                    } }
                    val base = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED))
                    db.withTransaction {
                        repeat(2000) { n ->
                            val prompt = if (n % 2 == 0) "sfw, silver_hair, studio, soft light" else "sfw, blue_sky, landscape, mountain"
                            val params = base.copy(prompt = prompt, negativePrompt = "lowres")
                            val openAi = if (n % 4 >= 2) OpenAiImageSnapshot(OpenAiImageParams(prompt = prompt, model = OpenAiImageParams.MODELS[n % 2])) else null
                            val generation = Generation("demo-$n", now - n * 1000L, now, GenerationStatus.SUCCEEDED,
                                "离线图片 $n", prompt, params, 1, openAi = openAi)
                            db.generationDao().upsertGeneration(Mappers.toEntity(generation))
                            val file = images[n % 4]
                            db.generationDao().upsertImages(listOf(GeneratedImageEntity("demo-image-$n", generation.id, 1, 42,
                                file.relativeTo(filesDir).path, 160, 224, file.length(), "demo", null, generation.createdAt, null)))
                            if (n % 7 == 0) db.favoriteImageDao().add(FavoriteImageEntity("demo-image-$n", now))
                        }
                        val body = "[{\"id\":\"old\",\"wire\":{\"role\":\"assistant\",\"content\":\"${"offline creative notes ".repeat(700)}\"}}]"
                        repeat(2000) { n -> db.chatConversationDao().save(ChatConversationEntity("history-$n", "创作记录 $n", now - n, body)) }
                        db.chatConversationDao().save(ChatConversationEntity("current-demo", "当前创作", now + 1000, "[]"))
                        db.chatConversationDao().saveDraft(ChatDraftEntity("current-demo", "保持构图，只调整光线", 4, 4, "[]"))
                    }
                }
                // Startup cleanup correctly closes abandoned jobs. Refresh only this isolated
                // fixture's two fake jobs so each preview launch can exercise the task sheet.
                val now = System.currentTimeMillis()
                val base = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED))
                repeat(2) { n ->
                    val status = if (n == 0) GenerationStatus.QUEUED else GenerationStatus.GENERATING
                    db.generationDao().upsertGeneration(Mappers.toEntity(Generation("active-$n", now + 1000 + n,
                        now, status, "离线任务 $n", "sfw, silver hair", base.copy(prompt = "sfw, silver hair"), 1)))
                    db.generationDao().updateStatus("active-$n", status.name, now, null, null, null)
                }
                db.chatConversationDao().find("current-demo")?.takeIf { it.entriesJson == "[]" }?.let { row ->
                    val entry = ChatEntry("offline-edit", buildJsonObject { put("role", "assistant"); put("content", "保持构图，仅调整光线。") },
                        promptEdit = PromptEdit(title = "柔化光线", prompt = "soft evening light"))
                    db.chatConversationDao().save(row.copy(entriesJson = Json.encodeToString(listOf(entry))))
                }
                val start = System.nanoTime()
                val page = container.generationRepository.observeGalleryWindow(GalleryFilter.None, 120).first()
                val firstPageMs = (System.nanoTime() - start) / 1_000_000.0
                val order = container.generationRepository.galleryOrder(GalleryFilter.None)
                val silver = container.generationRepository.galleryOrder(GalleryFilter(query = "SILVER hair"))
                val summaries = container.chatStore.observeSummaries().first()
                check(page.items.size == 120 && page.hasMore && order.size == 2000 && silver.size == 1000 && summaries.size == 2001)
                File(filesDir, "creation-fixture-result.json").writeText(JSONObject(mapOf("gallery" to order.size, "silver_matches" to silver.size,
                    "first_page" to page.items.size, "has_more" to page.hasMore, "summaries" to summaries.size, "first_query_ms" to firstPageMs)).toString())
            }
            container.settingsStore.setChatEnabled(true)
            // Preview navigation without saving credentials or contacting a provider.
            container.sessionState.update(true)
            setContent { PocketNaiTheme { CompositionLocalProvider(LocalAppContainer provides container) { PocketNaiApp(checkUpdatesOnLaunch = false) } } }
        }
    }
}
