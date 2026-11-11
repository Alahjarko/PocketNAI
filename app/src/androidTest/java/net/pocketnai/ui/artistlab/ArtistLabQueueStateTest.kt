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
    private inner class Harness(val background: ArtistLabBackgroundExecution = FakeBackground(),
        val execution: net.pocketnai.domain.task.BackgroundTasks = net.pocketnai.testing.FakeBackgroundTasks()) {
        val name = "artist_lab_state_test_${UUID.randomUUID()}"
        val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(super.getCacheDir(), name).also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                preferences.add(name)
                return super.getSharedPreferences("${this@Harness.name}_$name", mode)
            }
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
                    AccountBalanceRepository(api, credentials), settings, AnlasCostCalculator(NovelAiPaidAnlasFormula()),
                    credentialStore = credentials, backgroundTasks = execution)
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
            preferences.toList().forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
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
            h.main {
                val before = h.generate.state.value
                val edit = h.generate.promptSnapshot().copy(prompt = "sfw, soft evening light")
                val revision = h.generate.applyPromptEdit(edit)!!
                assertThat(h.generate.state.value.params).isEqualTo(before.params.copy(prompt = "sfw, soft evening light"))
                assertThat(h.generate.undoPromptEdit(revision)).isTrue()
                assertThat(h.generate.state.value.params).isEqualTo(before.params)
                val next = h.generate.applyPromptEdit(h.generate.promptSnapshot().copy(prompt = "sfw, forest"))!!
                h.generate.onPromptChange("sfw, user changed")
                assertThat(h.generate.undoPromptEdit(next)).isFalse()
                assertThat(h.generate.state.value.promptTemplate).isEqualTo("sfw, user changed")
                h.generate.replacePromptText(PromptTarget.POSITIVE, before.promptTemplate)
            }
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
            h.main { h.lab.importCatalog(net.pocketnai.domain.artistlab.ArtistCatalogImport.parse("artist: imported_example")) }
            h.waitFor { !h.lab.catalogImporting.value }
            assertThat(h.lab.catalogCount.value).isEqualTo(3)
            assertThat(ArtistLabStore(h.context, h.database).catalogEntries().last().tag).isEqualTo("artist: imported example")
            assertThat(ArtistLabStore(h.context, h.database).excludedArtists()).doesNotContain(kept)
            assertThat(ArtistLabStore(h.context, h.database).excludedArtists()).contains(entries[2].tag)
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
        verifyOrdinaryAndChatBackgroundExecution()
    }

    /** Same platform lifecycle contract as the batch above, with the ordinary/OpenAI/chat owners. */
    private suspend fun verifyOrdinaryAndChatBackgroundExecution() {
        val host = (instrumentation.targetContext.applicationContext as PocketNaiApplication).container.backgroundTasks
        check(host.session == null) { "不得影响已有图片或对话任务" }
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
        }
        fun foreground() = instrumentation.targetContext.getSystemService(ActivityManager::class.java)
            .getRunningServices(100).any {
                it.service.className == net.pocketnai.data.task.GenerationForegroundService::class.java.name && it.foreground
            }
        fun awake() = shell("dumpsys power").contains("PocketNAI:Generation")
        suspend fun waitFor(stage: String, check: () -> Boolean) {
            try { withTimeout(15_000) { while (!check()) delay(30) } }
            catch (failure: TimeoutCancellationException) {
                val power = shell("dumpsys power").lineSequence().filter {
                    "PocketNAI" in it || "Wake Locks" in it || "mWakefulness=" in it
                }.joinToString("\n")
                throw AssertionError("$stage: status=${host.status.value}, foreground=${foreground()}, power=$power", failure)
            }
        }

        // An actual resumed Activity starts protection; no borrowed background-start permission.
        val ordinary = Harness(execution = host)
        val first = androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            ordinary.holdFirst = true
            ordinary.main { ordinary.generate.onPromptChange("sfw, quiet field"); ordinary.generate.generate() }
            withTimeout(10_000) { ordinary.entered.await() }
            assertThat(foreground()).isTrue()
            first.recreate()
            first.close() // A finished page must not cancel the process-owned image operation.
            shell("input keyevent 3"); shell("input keyevent 223")
            waitFor("ordinary screen-off wake lock") { awake() }
            assertThat(instrumentation.targetContext.getSystemService(android.os.PowerManager::class.java).isInteractive).isFalse()
            ordinary.release.complete(Unit)
            waitFor("ordinary completion") { !ordinary.generate.state.value.inFlight }
            assertThat(ordinary.payloads).hasSize(1)
            assertThat(ordinary.database.generationDao().allImages()).hasSize(1)
            waitFor("ordinary resource cleanup") { host.session == null && !foreground() && !awake() }
        } finally {
            ordinary.release.complete(Unit)
            waitFor("ordinary fixture cleanup") { !ordinary.generate.state.value.inFlight }
            shell("input keyevent 224"); first.close(); ordinary.close()
        }

        val h = Harness(execution = host)
        val activity = androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        val openEntered = CompletableDeferred<Unit>(); val openRelease = CompletableDeferred<Unit>()
        val llmEntered = CompletableDeferred<Unit>(); val llmRelease = CompletableDeferred<Unit>()
        var openRequests = 0
        var llmRequests = 0
        var chat: net.pocketnai.ui.chat.ChatViewModel? = null
        var open: net.pocketnai.ui.openai.OpenAiImageViewModel? = null
        try {
            val openSettings = net.pocketnai.data.openai.OpenAiImageSettings(h.context).apply {
                saveKey("offline-openai-placeholder"); setProvider(net.pocketnai.domain.openai.ImageProvider.OPEN_AI)
            }
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val openRepository = net.pocketnai.data.openai.OpenAiImageRepository(
                net.pocketnai.data.openai.OpenAiImagesTransport { _, params, _ ->
                    openRequests++; openEntered.complete(Unit); openRelease.await()
                    net.pocketnai.data.openai.OpenAiImageResponse(listOf(encoded), net.pocketnai.domain.openai.OpenAiImageSnapshot(params))
                }, h.database.generationDao(), h.files, openSettings::apiKey)
            val llm = net.pocketnai.data.chat.LlmSettingsStore(h.context).apply {
                save(net.pocketnai.domain.chat.LlmConfig(model = "offline-background", toolPermission = net.pocketnai.domain.chat.ChatToolPermission.ASK),
                    "offline-chat-placeholder")
            }
            h.main {
                open = net.pocketnai.ui.openai.OpenAiImageViewModel(openSettings, openRepository,
                    object : ReferenceImageImporter { override suspend fun import(source: ReferenceSource, transform: ImageTransform?) = error("不得导入") },
                    h.generate, GenerationDraftStore(), backgroundTasks = host).also {
                    h.states.put("openai", it); it.update(it.state.value.draft.params.copy(prompt = "sfw, blue sky"))
                }
                chat = net.pocketnai.ui.chat.ChatViewModel(llm, object : net.pocketnai.domain.chat.ChatClient {
                    override suspend fun models(config: net.pocketnai.domain.chat.LlmConfig, apiKey: String) = emptyList<String>()
                    override suspend fun complete(config: net.pocketnai.domain.chat.LlmConfig, apiKey: String,
                        messages: List<kotlinx.serialization.json.JsonObject>, tools: kotlinx.serialization.json.JsonArray,
                        onPartial: (kotlinx.serialization.json.JsonObject) -> Unit): kotlinx.serialization.json.JsonObject {
                        llmRequests++
                        if (llmRequests > 1) return net.pocketnai.domain.chat.wireMessage("assistant", "图片已保存")
                        llmEntered.complete(Unit); llmRelease.await()
                        return kotlinx.serialization.json.Json.parseToJsonElement("""{"role":"assistant","content":"","tool_calls":[{"id":"background-image","type":"function","function":{"name":"generate_image","arguments":"{\"prompt\":\"sfw, quiet forest\"}"}}]}""") as kotlinx.serialization.json.JsonObject
                    }
                }, net.pocketnai.data.chat.ChatStore(h.database.chatConversationDao()), net.pocketnai.data.chat.AgentFiles(h.context),
                    object : net.pocketnai.domain.chat.ChatImageGenerator {
                        override fun currentParams() = h.generate.state.value.params
                        override fun authorizationKey() = h.generate.artistLabAccount()
                        override suspend fun quote(params: GenerationParams) = net.pocketnai.domain.chat.ChatImageQuote("离线报价", 0)
                        override suspend fun generate(params: GenerationParams, account: String?, beforeSend: suspend () -> Unit,
                            onImage: (net.pocketnai.domain.chat.ChatImage) -> Unit) = h.generate.generateForChat(params, onImage, account, beforeSend)
                    }, MutableStateFlow(true), net.pocketnai.data.chat.ChatAttachmentStore(h.context), backgroundTasks = host)
                    .also { h.states.put("chat", it) }
            }
            val vm = chat!!; val oa = open!!
            waitFor("chat initial conversation") { vm.state.value.current != null }
            h.holdFirst = true
            h.main { vm.send("绘制一张安静的森林"); oa.generate() }
            withTimeout(10_000) { llmEntered.await(); openEntered.await() }
            assertThat(host.status.value.images).isEqualTo(1)
            assertThat(host.status.value.chats).isEqualTo(1)
            activity.recreate(); activity.close()
            shell("input keyevent 3"); shell("input keyevent 223")
            openRelease.complete(Unit)
            waitFor("OpenAI completion") { !oa.state.value.busy }
            assertThat(openRequests).isEqualTo(1)
            assertThat(h.database.generationDao().allImages()).hasSize(1)
            assertThat(foreground()).isTrue() // Completing one owner cannot stop the other.
            llmRelease.complete(Unit)
            waitFor("chat waiting for approval releases wake lock") { vm.state.value.pending != null && !host.status.value.needsCpu && !awake() }
            assertThat(h.payloads).isEmpty()
            h.main { vm.confirmImage(vm.state.value.pending!!.callId, true) }
            withTimeout(10_000) { h.entered.await() } // The image tool starts while the Activity is gone and screen is off.
            waitFor("chat image reacquires wake lock") { awake() }
            assertThat(foreground()).isTrue()
            h.release.complete(Unit)
            waitFor("chat completion") { !vm.state.value.busy }
            assertThat(h.payloads).hasSize(1)
            assertThat(llmRequests).isEqualTo(2)
            assertThat(h.database.generationDao().allImages()).hasSize(2)
            assertThat(vm.state.value.current!!.entries.sumOf { it.images.size }).isEqualTo(1)
            waitFor("shared resource cleanup") { host.session == null && !foreground() && !awake() }
        } finally {
            h.main { chat?.stop(); openRelease.complete(Unit); llmRelease.complete(Unit); h.release.complete(Unit) }
            waitFor("shared fixture cleanup") { chat?.state?.value?.busy != true && open?.state?.value?.busy != true }
            shell("input keyevent 224"); activity.close(); h.close()
        }

        val denied = Harness(execution = net.pocketnai.testing.FakeBackgroundTasks().apply { failStart = true })
        try {
            denied.main { denied.generate.onPromptChange("sfw, field"); denied.generate.generate() }
            assertThat(denied.payloads).isEmpty()
            assertThat(denied.generate.state.value.inFlight).isFalse()
            assertThat(denied.generate.state.value.error).isNotNull()
            assertThat(denied.database.generationDao().allGenerationIds()).isEmpty()
        } finally { denied.close() }
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
