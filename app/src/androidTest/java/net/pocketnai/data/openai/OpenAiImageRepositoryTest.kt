package net.pocketnai.data.openai

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.pocketnai.core.Hashing
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.data.local.Mappers
import net.pocketnai.data.local.PocketNaiDatabase
import net.pocketnai.data.repo.GenerationEvent
import net.pocketnai.domain.model.*
import net.pocketnai.domain.openai.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OpenAiImageRepositoryTest {
    @Test fun `加密配置与编辑成图历史往返并保留部分结果`() = runBlocking<Unit> {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "openai-integration-" + UUID.randomUUID()
        val root = File(original.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(n: String, mode: Int) = original.getSharedPreferences("$name-$n", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, PocketNaiDatabase::class.java).build()
        val files = GenerationFileStore(context)
        try {
            val settings = OpenAiImageSettings(context)
            val secret = "test-only-openai-repository-key"
            settings.saveKey(secret)
            assertThat(context.getSharedPreferences("pocketnai_openai_images", 0).all.toString()).doesNotContain(secret)
            assertThat(OpenAiImageSettings(context).apiKey()).isEqualTo(secret)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val source = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
            bitmap.recycle()
            val sha = Hashing.sha256(source)
            files.writeReference(sha, source)
            val ref = ReferenceImage("reference", ReferenceRole.IMG2IMG, 0, files.referenceRelativePath(sha), 64, 64, source.size.toLong(), sha, 1)
            val params = OpenAiImageParams(prompt = "保持角色衣服，海报写\"测试\"", model = OpenAiImageParams.SUNBURST, count = 2, outputFormat = "jpeg")
            val draft = OpenAiImageDraft(params, listOf(ref))
            settings.saveDraft(draft)
            assertThat(OpenAiImageSettings(context).loadDraft()).isEqualTo(draft)
            var calls = 0
            val api = OpenAiImagesTransport { key, p, inputs ->
                calls++
                assertThat(key).isEqualTo(secret)
                assertThat(p).isEqualTo(params)
                assertThat(inputs).hasSize(1)
                assertThat(inputs.single()).isEqualTo("data:image/png;base64," + Base64.encodeToString(source, Base64.NO_WRAP))
                OpenAiImageResponse(listOf(Base64.encodeToString(bytes, Base64.NO_WRAP), "invalid"),
                    OpenAiImageSnapshot(p, OpenAiImageUsage(10, 20, 30)))
            }
            val events = OpenAiImageRepository(api, db.generationDao(), files, settings::apiKey).generate(draft).toList()
            val image = events.filterIsInstance<GenerationEvent.Final>().single().image
            assertThat(calls).isEqualTo(1)
            assertThat(events.filterIsInstance<GenerationEvent.Completed>().single().status).isEqualTo(GenerationStatus.PARTIAL)
            assertThat(files.resolve(image.privateFilePath).extension).isEqualTo("jpeg")
            assertThat(files.resolve(image.privateFilePath).readBytes()).isEqualTo(bytes)
            val row = db.generationDao().findGeneration(image.generationId)!!
            assertThat(row.modelApiId).isEqualTo(OpenAiImageParams.SUNBURST)
            assertThat(row.toString()).doesNotContain(secret)
            assertThat(Mappers.toDomain(row)!!.openAi!!.params).isEqualTo(params)
            val gallery = db.generationDao().observeGalleryImages().first().map(Mappers::toDomain).single()
            assertThat(gallery.modelApiId).isEqualTo(params.model)
            assertThat(GallerySearch.matches(gallery, GalleryFilter(modelApiId = params.model))).isTrue()
            assertThat(GallerySearch.matches(gallery, GalleryFilter(modelApiId = OpenAiImageParams.FLARE))).isFalse()
            assertThat(db.generationDao().findReferences(image.generationId)).hasSize(1)
            assertThat(events.filterIsInstance<GenerationEvent.ItemError>()).hasSize(1)
        } finally {
            db.close()
            context.getSharedPreferences("pocketnai_openai_images", 0).edit().clear().commit()
            original.deleteSharedPreferences("$name-pocketnai_openai_images")
            root.deleteRecursively()
        }
    }
}
