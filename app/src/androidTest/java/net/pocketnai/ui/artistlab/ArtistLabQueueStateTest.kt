package net.pocketnai.ui.artistlab

import android.content.Context
import android.content.ContextWrapper
import android.app.ActivityManager
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import net.pocketnai.core.*
import net.pocketnai.data.artistlab.ArtistLabStore
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.data.local.PocketNaiDatabase
import net.pocketnai.data.local.ArtistLabRunEntity
import net.pocketnai.data.local.ArtistMixFavoriteEntity
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.repo.*
import net.pocketnai.data.security.*
import net.pocketnai.data.settings.*
import net.pocketnai.domain.billing.*
import net.pocketnai.domain.artistlab.*
import net.pocketnai.data.artistlab.ArtistLabForegroundService
import net.pocketnai.PocketNaiApplication
import net.pocketnai.domain.image.*
import net.pocketnai.domain.metadata.*
import net.pocketnai.domain.model.*
import net.pocketnai.domain.prompt.TagSuggestionSource
import net.pocketnai.ui.generate.GenerateViewModel
import net.pocketnai.ui.state.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 真实 ViewModel 调度 + 假 API + 内存库；不使用用户账号、草稿或历史。 */
class ArtistLabQueueStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class FakeBackground : ArtistLabBackgroundExecution {
        var fail = false
        var starts = 0
        var stops = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun start(progress: StateFlow<ArtistLabProgress>, pause: () -> Unit) {
            starts++
            if (fail) throw ArtistLabBackgroundUnavailable()
            gate?.await()
        }
        override fun stop() { stops++ }
    }
    private inner class Harness(val background: ArtistLabBackgroundExecution = FakeBackground()) {
        val name = "artist_lab_state_test_${UUID.randomUUID()}"
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(super.getCacheDir(), name).also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${this@Harness.name}_$name", mode)
        }
        val database = Room.inMemoryDatabaseBuilder(context, PocketNaiDatabase::class.java).build()
        val files = GenerationFileStore(context)
        val settings = SettingsStore(context).also { it.setArtistLabEnabled(true) }
        val states = ViewModelStore()
        val payloads = mutableListOf<JsonObject>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var holdFirst = false
        var failSecond = false
        val credentials = object : CredentialStore {
            override fun hasCredential() = true
            override fun load() = StoredCredential("fake-test-token", CredentialType.PERSISTENT_API_TOKEN)
            override fun hint() = CredentialHint(CredentialType.PERSISTENT_API_TOKEN, 15, Hashing.sha256("fake-test-token".toByteArray()).take(8))
            override fun save(credential: StoredCredential) = Unit
            override fun clear() = Unit
        }
        val api = object : NovelAiApi {
            override suspend fun fetchAccountStatus(token: String) = Outcome.Failure(AppError.of(ErrorCode.UNKNOWN))
            override suspend fun fetchSubscriptionBalance(token: String) = Outcome.Failure(AppError.of(ErrorCode.UNKNOWN))
            override suspend fun generateImage(token: String, payload: JsonObject, destinationZip: File): Outcome<Unit> {
                payloads += payload
                entered.complete(Unit)
                if (holdFirst && payloads.size == 1) release.await()
                if (failSecond && payloads.size == 2) return Outcome.Failure(AppError.of(ErrorCode.TIMEOUT_UNCERTAIN))
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                ZipOutputStream(destinationZip.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("image_0.png")); bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry()
                }; bitmap.recycle()
                return Outcome.Success(Unit)
            }
            override fun generateImageStream(token: String, payload: JsonObject) = error("不测试流式")
            override suspend fun suggestTags(token: String, model: ImageModel, prompt: String) = Outcome.Success(emptyList<String>())
            override suspend fun encodeVibe(token: String, model: ImageModel, imageBase64: String, informationExtracted: Double) = error("不得编码")
            override suspend fun upscaleImage(token: String, imageBase64: String, destinationFile: File) = error("不得超分")
        }
        val repository = GenerationRepository(api, credentials, database.generationDao(), files, liveReferencePaths = LiveReferencePathsProvider { emptySet() })
        val store = ArtistLabStore(context, database)
        lateinit var generate: GenerateViewModel
        lateinit var lab: ArtistLabViewModel
        init {
            instrumentation.runOnMainSync {
                generate = GenerateViewModel(repository, GenerationDraftStore(), GenerationPreviewStore(), GenerationDraftPreferences(context),
                    TagSuggestionSource { _, _ -> emptyList() },
                    object : ReferenceImageImporter { override suspend fun import(source: ReferenceSource, transform: ImageTransform?) = error("不得导入") },
                    object : ImageMetadataInspector { override suspend fun inspect(source: ReferenceSource) = error("不得读取图片") },
                    AccountBalanceRepository(api, credentials), settings, AnlasCostCalculator(NovelAiPaidAnlasFormula()), credentialStore = credentials)
                lab = ArtistLabViewModel(store, repository, FavoriteImageRepository(database.favoriteImageDao()), generate, settings.artistLabEnabled, background)
                states.put("generate", generate); states.put("lab", lab)
            }
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        suspend fun waitFor(check: () -> Boolean) { withTimeout(10_000) { while (!check()) delay(10) } }
        suspend fun prepare(count: Int) {
            waitFor { lab.ready.value }
            main { lab.prepare("sfw, blue sky", "lowres", "123456789", count.toString(), 3, 10, 30) }
            waitFor { lab.approval.value != null }
        }
        fun close() {
            main {
                lab.pause()
                release.complete(Unit)
                (background as? FakeBackground)?.gate?.complete(Unit)
            }
            runBlocking { withTimeout(10_000) { while (lab.busy.value) delay(10) } }
            main { background.stop() }
            main { states.clear() }
            database.close()
            // File 是独立命名的测试 cache 子目录。
            context.filesDir.deleteRecursively()
        }
    }

    @Test fun mixFavoritesLinkImagesAcrossRunsAndKeepLegacyBookmarksAfterImageCleanup() = runBlocking<Unit> {
        val h = Harness()
        val observer = launch { h.lab.favoriteMixGallery.collect() }
        try {
            h.prepare(2); h.main { h.lab.confirm() }; h.waitFor { !h.lab.busy.value }
            val run = h.store.dao.run(h.lab.selectedRun.value!!)!!
            val draws = h.store.dao.draws(run.id)
            val mix = h.store.json.decodeFromString<ArtistMix>(draws[0].mixJson)
            val legacy = mix.prompt.replace(", ::", "::")
            h.store.dao.saveMix(ArtistMixFavoriteEntity(legacy, 2))
            h.store.dao.saveMix(ArtistMixFavoriteEntity(mix.prompt, 1))
            h.store.dao.create(ArtistLabRunEntity("second-run", 3, run.configJson, "COMPLETED", ""),
                listOf(draws[1].copy(id = "second-draw", runId = "second-run", mixJson = draws[0].mixJson)))
            h.main { h.lab.select("second-run") }
            h.waitFor { h.lab.favoriteMixGallery.value.singleOrNull()?.images?.size == 2 }
            assertThat(h.lab.favoriteMixGallery.value.single().keys).containsExactly(legacy, mix.prompt)
            h.repository.cleanupLabImages(draws.mapNotNull { it.imageId })
            h.waitFor { h.lab.favoriteMixGallery.value.singleOrNull()?.images?.isEmpty() == true }
            assertThat(h.store.dao.observeMixFavorites().first()).hasSize(2)
            assertThat(h.store.dao.draws("second-run").single().imageId).isNull()
            h.main { h.lab.removeMix(h.lab.favoriteMixGallery.value.single()) }
            h.waitFor { h.lab.favoriteMixGallery.value.isEmpty() }
            assertThat(h.store.dao.observeMixFavorites().first()).isEmpty()
            assertThat(h.store.dao.run(run.id)).isNotNull()
            assertThat(h.payloads).hasSize(2)
        } finally { observer.cancelAndJoin(); h.close() }
    }

    @Test fun excludedArtistsPersistAndNewPlansOnlyUseEnabledPool() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.waitFor { h.lab.ready.value }
            val entries = h.lab.catalogEntries.value
            val kept = entries.first().tag
            h.main { entries.drop(1).forEach { h.lab.setArtistEnabled(it.tag, false) } }
            assertThat(h.lab.catalogCount.value).isEqualTo(1)
            assertThat(ArtistLabStore(h.context, h.database).excludedArtists())
                .containsExactlyElementsIn(entries.drop(1).map { it.tag })
            h.main { h.lab.prepare("sfw, blue sky", "lowres", "123", "3", 2, 10, 30) }
            h.waitFor { h.lab.message.value.contains("当前可用 1") }
            assertThat(h.lab.approval.value).isNull()
            h.main { h.lab.prepare("sfw, blue sky", "lowres", "123", "3", 1, 10, 30) }
            h.waitFor { h.lab.approval.value != null }
            h.main { h.lab.confirm() }; h.waitFor { !h.lab.busy.value }
            val before = h.store.dao.draws(h.lab.selectedRun.value!!)
            assertThat(before).hasSize(3)
            assertThat(before.all { it.mixJson.contains(kept) }).isTrue()
            h.main { h.lab.setArtistEnabled(entries[1].tag, true) }
            assertThat(h.lab.catalogCount.value).isEqualTo(2)
            assertThat(h.store.dao.draws(h.lab.selectedRun.value!!).map { it.mixJson }).isEqualTo(before.map { it.mixJson })
        } finally { h.close() }
    }

    @Test fun backgroundStartupFailureNeverSendsOrLeavesGenerationReserved() = runBlocking<Unit> {
        val background = FakeBackground().apply { fail = true }
        val h = Harness(background)
        try {
            h.prepare(3); h.main { h.lab.confirm() }
            h.waitFor { !h.lab.busy.value }
            assertThat(h.payloads).isEmpty()
            assertThat(h.lab.selectedRun.value).isNull()
            assertThat(h.generate.state.value.artistLabActive).isFalse()
            assertThat(background.starts).isEqualTo(1)
            assertThat(background.stops).isEqualTo(1)
        } finally { h.close() }
    }

    @Test fun realForegroundServiceCompletesFakeBatchWithHomeAndScreenOff() = runBlocking<Unit> {
        val host = (instrumentation.targetContext.applicationContext as PocketNaiApplication).container.artistLabBackgroundExecution
        check(host.session == null) { "不得影响已有抽卡批次" }
        val h = Harness(host)
        val automation = instrumentation.uiAutomation
        fun shell(command: String) { automation.executeShellCommand(command).close() }
        @Suppress("DEPRECATION")
        fun foregroundRunning() = instrumentation.targetContext.getSystemService(ActivityManager::class.java)
            .getRunningServices(100).any { it.service.className == ArtistLabForegroundService::class.java.name && it.foreground }
        try {
            // 测试仅临时借用 shell 的启动服务权限；发送端始终是 Harness 的假 API。
            automation.adoptShellPermissionIdentity("android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND")
            h.holdFirst = true; h.prepare(3); h.main { h.lab.confirm() }
            withTimeout(10_000) { h.entered.await() }
            assertThat(foregroundRunning()).isTrue()
            shell("input keyevent 3")
            shell("input keyevent 223")
            h.release.complete(Unit)
            h.waitFor { !h.lab.busy.value }
            assertThat(h.payloads).hasSize(3)
            assertThat(h.lab.progress.value.completed).isEqualTo(3)
            assertThat(h.store.dao.draws(h.lab.selectedRun.value!!).all { it.status == "SUCCEEDED" }).isTrue()
            h.waitFor { !foregroundRunning() }
            assertThat(host.session).isNull()
        } finally {
            shell("input keyevent 224")
            automation.dropShellPermissionIdentity()
            h.close()
        }
    }

    @Test fun uncertainSecondRequestStopsQueueAndResumeSkipsIt() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.failSecond = true; h.prepare(100)
            h.main { h.lab.confirm() }; h.waitFor { !h.lab.busy.value }
            assertThat(h.payloads).hasSize(2)
            val draws = h.store.dao.draws(h.lab.selectedRun.value!!)
            assertThat(draws[0].status).isEqualTo("SUCCEEDED")
            assertThat(draws[1].status).isEqualTo("UNCERTAIN")
            assertThat(draws.count { it.status == "PLANNED" }).isEqualTo(98)
            h.main { h.lab.prepareResume() }; h.waitFor { h.lab.approval.value != null }
            assertThat(h.lab.approval.value!!.remaining).isEqualTo(98)
            assertThat(h.payloads).hasSize(2)
        } finally { h.close() }
    }

    @Test fun changedPricePausesBeforeSendingFirstRequest() = runBlocking<Unit> {
        val h = Harness()
        try {
            h.settings.setSubscriptionOverride(SubscriptionOverride.NONE)
            h.prepare(100)
            val previous = h.lab.approval.value!!.quote
            h.main { h.lab.confirm(); h.settings.setSubscriptionOverride(SubscriptionOverride.OPUS) }
            h.waitFor { !h.lab.busy.value }
            assertThat(h.generate.quoteForArtistLab(h.generate.state.value.params.copy(sampleCount = 1))).isNotEqualTo(previous)
            assertThat(h.payloads).isEmpty()
            val id = h.lab.selectedRun.value!!
            assertThat(h.store.dao.draws(id).all { it.status == "PLANNED" }).isTrue()
            assertThat(h.store.dao.run(id)!!.status).isEqualTo("PAUSED")
            assertThat(h.generate.state.value.artistLabActive).isFalse()
        } finally { h.close() }
    }
}
