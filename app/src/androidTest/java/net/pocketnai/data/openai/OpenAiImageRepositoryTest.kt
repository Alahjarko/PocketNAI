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
import kotlinx.coroutines.async
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
        val db = Room.inMemoryDatabaseBuilder(context, PocketNaiDatabase::class.java)
            .addCallback(net.pocketnai.data.local.OpenAiSpendingEntity.callback).build()
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
            val params = OpenAiImageParams(prompt = "nsfw, 保持角色衣服，海报写\"测试\"", model = OpenAiImageParams.SUNBURST_26,
                complexity = "low", count = 2, outputFormat = "jpeg")
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
            var sfw = true
            val repository = OpenAiImageRepository(api, db.generationDao(), files, settings::apiKey, sfwEnabled = { sfw })
            assertThat(repository.generate(draft).toList().filterIsInstance<GenerationEvent.FatalError>().single().error.detail).contains("SFW")
            assertThat(calls).isEqualTo(0)
            assertThat(db.generationDao().countGenerations()).isEqualTo(0)
            sfw = false
            val events = repository.generate(draft).toList()
            val image = events.filterIsInstance<GenerationEvent.Final>().single().image
            assertThat(calls).isEqualTo(1)
            assertThat(events.filterIsInstance<GenerationEvent.Completed>().single().status).isEqualTo(GenerationStatus.PARTIAL)
            assertThat(files.resolve(image.privateFilePath).extension).isEqualTo("jpeg")
            assertThat(files.resolve(image.privateFilePath).readBytes()).isEqualTo(bytes)
            val row = db.generationDao().findGeneration(image.generationId)!!
            assertThat(row.modelApiId).isEqualTo(OpenAiImageParams.SUNBURST_26)
            assertThat(row.generationDurationMillis).isNotNull()
            assertThat(row.generationDurationMillis!!).isAtLeast(0L)
            assertThat(row.toString()).doesNotContain(secret)
            assertThat(Mappers.toDomain(row)!!.openAi!!.params).isEqualTo(params)
            val storedSnapshot = Mappers.toDomain(row)!!.openAi!!
            assertThat(storedSnapshot.usage).isEqualTo(OpenAiImageUsage(10, 20, 30))
            assertThat(storedSnapshot.returnedImageCount).isEqualTo(2)
            assertThat(storedSnapshot.estimate).isEqualTo(OpenAiImagePricing.estimate(params, 1))
            assertThat(storedSnapshot.estimatedUsd!!).isWithin(0.0000001).of(0.00111)
            val gallery = db.generationDao().observeGalleryImages().first().map(Mappers::toDomain).single()
            assertThat(gallery.modelApiId).isEqualTo(params.model)
            assertThat(gallery.sensitivePrompt).isTrue()
            assertThat(GallerySearch.matches(gallery, GalleryFilter(modelApiIds = setOf(params.model)))).isTrue()
            assertThat(GallerySearch.matches(gallery, GalleryFilter(modelApiIds = setOf(OpenAiImageParams.FLARE)))).isFalse()
            assertThat(db.generationDao().findReferences(image.generationId)).hasSize(1)
            assertThat(events.filterIsInstance<GenerationEvent.ItemError>()).hasSize(1)
            // Updating usage after an image exists must preserve its image and reference child rows.
            val revised = storedSnapshot.copy(usage = OpenAiImageUsage(100, 200, 300))
            db.generationDao().updateOpenAiSnapshot(image.generationId, OpenAiImageCodec.encode(revised), 1234L)
            assertThat(db.generationDao().allImages().map { it.id }).containsExactly(image.id)
            assertThat(db.generationDao().findReferences(image.generationId)).hasSize(1)
            assertThat(Mappers.toDomain(db.generationDao().findGeneration(image.generationId)!!)!!.openAi).isEqualTo(revised)
            val billed = db.generationDao().observeOpenAiSpending().first().single()
            assertThat(billed.generationId).isEqualTo(image.generationId)
            assertThat(billed.status).isEqualTo("PARTIAL")
            assertThat(OpenAiImageCodec.decode(billed.snapshotJson)).isEqualTo(revised)
            kotlinx.coroutines.withTimeout(5_000) {
                kotlinx.coroutines.coroutineScope {
                    val entered = java.util.concurrent.atomic.AtomicInteger()
                    val both = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val concurrent = OpenAiImageRepository(OpenAiImagesTransport { _, p, _ ->
                        if (entered.incrementAndGet() == 2) both.complete(Unit)
                        both.await()
                        if (p.prompt == "failure") throw OpenAiImageFailure(false, "fake rejection")
                        OpenAiImageResponse(listOf(Base64.encodeToString(bytes, Base64.NO_WRAP)),
                            OpenAiImageSnapshot(p, OpenAiImageUsage(3, 0, 100)))
                    }, db.generationDao(), files, { secret })
                    val requests = listOf("failure", "success").map { prompt -> async {
                        concurrent.generate(OpenAiImageDraft(params.copy(prompt = prompt, count = 1))).toList()
                    } }
                    val failed = requests[0].await()
                    val succeeded = requests[1].await()
                    assertThat(entered.get()).isEqualTo(2)
                    assertThat(failed.filterIsInstance<GenerationEvent.FatalError>()).hasSize(1)
                    assertThat(succeeded.filterIsInstance<GenerationEvent.Final>()).hasSize(1)
                    assertThat(failed.first().generationId).isNotEqualTo(succeeded.first().generationId)
                    assertThat(db.generationDao().findGeneration(succeeded.first().generationId)!!.status).isEqualTo("SUCCEEDED")
                    val successful = Mappers.toDomain(db.generationDao().findGeneration(succeeded.first().generationId)!!)!!.openAi!!
                    val rejected = Mappers.toDomain(db.generationDao().findGeneration(failed.first().generationId)!!)!!.openAi!!
                    assertThat(successful.estimatedUsd!!).isWithin(0.0000001).of(0.003015)
                    assertThat(rejected.estimatedUsd).isNull()
                }
            }
            val gif = Base64.decode("R0lGODlhIAAgAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAIAAgAAAINQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJkyhTqlzJUmRAACH5BAEUAAEALAAAAAAgACAAgQAA/wAAAAAAAAAAAAg1AAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWLGDNq3Mixo8ePIEOKHEmypMmTKFOqXMlSZEAAOw==", Base64.DEFAULT)
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="32" viewBox="0 0 64 32"><rect width="64" height="32" fill="#ff0000"/><path d="M0 0L64 32" stroke="#0000ff"/></svg>""".toByteArray()
            var mediaCalls = 0
            val mediaApi = OpenAiImagesTransport { _, p, _ ->
                mediaCalls++
                OpenAiImageResponse(listOf(Base64.encodeToString(if (p.outputFormat == "gif") gif else svg, Base64.NO_WRAP)),
                    OpenAiImageSnapshot(p, OpenAiImageUsage(3, 0, 100)))
            }
            val gifParams = params.copy(prompt = "two color animation", outputFormat = "gif", count = 1, size = "auto", frameCount = 2)
            val disabled = OpenAiImageRepository(mediaApi, db.generationDao(), files, { secret })
            assertThat(disabled.generate(OpenAiImageDraft(gifParams)).toList().filterIsInstance<GenerationEvent.FatalError>()).hasSize(1)
            assertThat(mediaCalls).isEqualTo(0)
            val mediaRepo = OpenAiImageRepository(mediaApi, db.generationDao(), files, { secret }, gifEnabled = { true })
            val loader = (original.applicationContext as net.pocketnai.PocketNaiApplication).newImageLoader()
            try {
                for ((format, payload) in listOf("gif" to gif, "svg" to svg)) {
                    val p = gifParams.copy(outputFormat = format)
                    val media = mediaRepo.generate(OpenAiImageDraft(p)).toList().filterIsInstance<GenerationEvent.Final>().single().image
                    val file = files.resolve(media.privateFilePath)
                    assertThat(file.extension).isEqualTo(format)
                    assertThat(file.readBytes()).isEqualTo(payload)
                    assertThat(media.sha256).isEqualTo(Hashing.sha256(payload))
                    assertThat(media.width).isEqualTo(if (format == "gif") 32 else 64)
                    assertThat(media.height).isEqualTo(32)
                    val still = loader.execute(net.pocketnai.ui.motion.buildSharedImageRequest(original, file, false).newBuilder().size(64).build())
                    assertThat(still).isInstanceOf(coil.request.SuccessResult::class.java)
                    assertThat(still.drawable is android.graphics.drawable.Animatable).isFalse()
                    if (format == "gif") {
                        val playing = loader.execute(net.pocketnai.ui.motion.buildSharedImageRequest(original, file, false, true).newBuilder().size(64).build())
                        assertThat(playing).isInstanceOf(coil.request.SuccessResult::class.java)
                        assertThat(playing.drawable is android.graphics.drawable.Animatable).isTrue()
                        val hidden = loader.execute(net.pocketnai.ui.motion.buildSharedImageRequest(original, file, true, true))
                        assertThat(hidden).isInstanceOf(coil.request.SuccessResult::class.java)
                        assertThat(hidden.drawable is android.graphics.drawable.Animatable).isFalse()
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val exported = net.pocketnai.data.export.MediaStoreExporter(original).export(file, "pocketnai-test-$name.$format")
                        assertThat(exported).isInstanceOf(net.pocketnai.core.Outcome.Success::class.java)
                        val uri = (exported as net.pocketnai.core.Outcome.Success).value
                        try {
                            assertThat(original.contentResolver.getType(uri)).isEqualTo(net.pocketnai.data.files.MediaFormat.mimeType(file))
                            assertThat(original.contentResolver.openInputStream(uri)!!.use { it.readBytes() }).isEqualTo(payload)
                        } finally { original.contentResolver.delete(uri, null, null) }
                    }
                }
                val liveRelativePath = "generations/media-live/0001.gif"
                val liveGif = files.resolve(liveRelativePath).also { it.parentFile!!.mkdirs() }
                InstrumentationRegistry.getInstrumentation().context.assets.open("openai-blue-ball.gif").use { input ->
                    liveGif.outputStream().use { input.copyTo(it) }
                }
                // The real 512 px result includes a large C2PA extension; the tiny synthetic
                // animation cannot cover metadata-heavy decoding.
                val realRequest = net.pocketnai.ui.motion.buildSharedImageRequest(original, liveGif, false)
                    .newBuilder().size(256).build()
                val realStill = loader.execute(realRequest)
                if (realStill is coil.request.ErrorResult) throw realStill.throwable
                assertThat(realStill).isInstanceOf(coil.request.SuccessResult::class.java)
                assertThat(realStill.drawable is android.graphics.drawable.Animatable).isFalse()
                assertThat((realStill.drawable as android.graphics.drawable.BitmapDrawable).bitmap.width).isEqualTo(256)
                val realMasked = loader.execute(net.pocketnai.ui.motion.buildSharedImageRequest(original, liveGif, true))
                if (realMasked is coil.request.ErrorResult) throw realMasked.throwable
                assertThat(realMasked).isInstanceOf(coil.request.SuccessResult::class.java)
                assertThat((realMasked.drawable as android.graphics.drawable.BitmapDrawable).bitmap.width).isEqualTo(48)
                val originalHash = Hashing.sha256(liveGif.readBytes())
                val importer = net.pocketnai.data.image.ReferenceImageProcessor(context, files)
                for (referenceSource in listOf(
                    net.pocketnai.domain.image.ReferenceSource.LocalPath(liveRelativePath),
                    net.pocketnai.domain.image.ReferenceSource.PickedUri(android.net.Uri.fromFile(liveGif).toString()),
                )) {
                    val imported = importer.import(referenceSource, null)
                    assertThat(imported).isInstanceOf(net.pocketnai.core.Outcome.Success::class.java)
                    val prepared = (imported as net.pocketnai.core.Outcome.Success).value
                    assertThat(prepared.width).isEqualTo(512)
                    assertThat(prepared.height).isEqualTo(512)
                    val normalized = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(files.resolve(prepared.relativePath).path, normalized)
                    assertThat(normalized.outMimeType).isEqualTo("image/png")
                }
                assertThat(Hashing.sha256(liveGif.readBytes())).isEqualTo(originalHash)
            } finally { loader.shutdown() }
            for (unsafe in listOf(
                """<?xml-stylesheet href="https://example.com/style.css"?><svg xmlns="http://www.w3.org/2000/svg" width="64" height="32"/>""",
                """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="32"><script>alert(1)</script></svg>""",
                """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="32"><use href="https://example.org/image.svg"/></svg>""",
                """<!DOCTYPE svg [<!ENTITY ext SYSTEM "file:///etc/passwd">]><svg xmlns="http://www.w3.org/2000/svg" width="64" height="32"><text>&ext;</text></svg>""",
            )) assertThat(runCatching { net.pocketnai.data.image.GeneratedMedia.inspect(unsafe.toByteArray(), "svg") }.isFailure).isTrue()
            val ledger = db.generationDao().observeOpenAiSpending().first()
            assertThat(ledger).hasSize(5)
            db.generationDao().purgeAllGenerations()
            assertThat(db.generationDao().observeOpenAiSpending().first()).isEqualTo(ledger)
            db.generationDao().clearOpenAiSpending()
            assertThat(db.generationDao().observeOpenAiSpending().first()).isEmpty()
        } finally {
            db.close()
            context.getSharedPreferences("pocketnai_openai_images", 0).edit().clear().commit()
            original.deleteSharedPreferences("$name-pocketnai_openai_images")
            root.deleteRecursively()
        }
    }
}
