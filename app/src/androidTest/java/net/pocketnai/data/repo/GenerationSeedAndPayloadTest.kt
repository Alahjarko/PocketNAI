package net.pocketnai.data.repo

import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.ui.generate.GenerateViewModel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.pocketnai.core.Outcome
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.data.local.PocketNaiDatabase
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.network.NovelAiRequestBuilder
import net.pocketnai.data.network.ZipImageExtractor
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.security.CredentialHint
import net.pocketnai.data.security.CredentialType
import net.pocketnai.data.security.StoredCredential
import net.pocketnai.domain.image.LiveReferencePathsProvider
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.SeedMode
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * 生成链路里"发出去的东西"与"记下来的东西"必须一致（仪器化）。
 *
 * 这个测试是为一个真实 bug 写的：RANDOM 模式下 seed 只算进了落库的那一份，
 * 请求体发的是原始参数里的默认值 0 —— 同一提示词反复产出同一张图，
 * 而历史里却显示着一个"看起来随机"的 seed（那个值从没被用过）。
 *
 * 之所以放在设备上跑：`GenerationRepository` 依赖 `GenerationFileStore`（需要 Context）
 * 与 Room，JVM 单测里没有这两样，而"请求体与历史不一致"恰恰只在把它们串起来时才出现。
 * 服务端由假的 [NovelAiApi] 顶替，**不发任何真实请求**。
 */
@RunWith(AndroidJUnit4::class)
class GenerationSeedAndPayloadTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, PocketNaiDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    /** 只记录请求体，不联网；并按 ZIP 的形态产出图片，让后处理链路照常跑。 */
    private class FakeApi(private val payloads: MutableList<JsonObject>, private val onGenerate: () -> Unit = {}) : NovelAiApi {
        override suspend fun fetchAccountStatus(token: String) =
            Outcome.Failure(net.pocketnai.core.AppError.of(net.pocketnai.core.ErrorCode.UNKNOWN))

        override suspend fun fetchSubscriptionBalance(token: String) =
            Outcome.Failure(net.pocketnai.core.AppError.of(net.pocketnai.core.ErrorCode.UNKNOWN))

        override suspend fun generateImage(
            token: String,
            payload: JsonObject,
            destinationZip: File,
        ): Outcome<Unit> {
            payloads += payload
            onGenerate()
            val params = payload.getValue("parameters").jsonObject
            val inpaint = payload["action"]?.jsonPrimitive?.content == "infill"
            val bitmap = Bitmap.createBitmap(if (inpaint) params.getValue("width").jsonPrimitive.content.toInt() else 64,
                if (inpaint) params.getValue("height").jsonPrimitive.content.toInt() else 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.GREEN)
            val png = ByteArrayOutputStream().use { buffer ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
                buffer.toByteArray()
            }
            bitmap.recycle()
            ZipOutputStream(destinationZip.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("image_0.png"))
                zip.write(if (inpaint) net.pocketnai.domain.metadata.PngTextWriter.withTextChunks(png, listOf(
                    net.pocketnai.domain.metadata.PngTextChunks.TextChunk("Software", "NovelAI"),
                    net.pocketnai.domain.metadata.PngTextChunks.TextChunk("Comment", """{"prompt":"sfw, blue sky"}"""),
                )) else png)
                zip.closeEntry()
            }
            return Outcome.Success(Unit)
        }

        override suspend fun suggestTags(
            token: String,
            model: ImageModel,
            prompt: String,
        ): Outcome<List<String>> = Outcome.Success(emptyList())

        override suspend fun encodeVibe(
            token: String,
            model: ImageModel,
            imageBase64: String,
            informationExtracted: Double,
        ): Outcome<ByteArray> = Outcome.Success(ByteArray(0))

        override fun generateImageStream(
            token: String,
            payload: JsonObject,
        ): kotlinx.coroutines.flow.Flow<net.pocketnai.data.network.GenerationStreamEvent> =
            error("本测试不涉及流式生成")

        override suspend fun upscaleImage(
            token: String,
            imageBase64: String,
            destinationFile: File,
        ): Outcome<Unit> = Outcome.Success(Unit)
    }

    private fun repository(
        payloads: MutableList<JsonObject>,
        random: Random,
        fileStore: GenerationFileStore,
        macros: List<net.pocketnai.domain.model.PromptFavorite> = emptyList(),
        sfwEnabled: () -> Boolean = { false },
        monotonicNanos: () -> Long = System::nanoTime,
        onGenerate: () -> Unit = {},
        idGenerator: () -> String = { "gen-test" },
    ) = GenerationRepository(
        api = FakeApi(payloads, onGenerate),
        credentialStore = object : CredentialStore {
            override fun hasCredential(): Boolean = true

            override fun load(): StoredCredential =
                StoredCredential(token = "fake-token", type = CredentialType.PERSISTENT_API_TOKEN)

            override fun save(credential: StoredCredential) = Unit
            override fun clear() = Unit
            override fun hint(): CredentialHint? = null
        },
        dao = database.generationDao(),
        fileStore = fileStore,
        zipExtractor = ZipImageExtractor(),
        liveReferencePaths = LiveReferencePathsProvider { emptySet() },
        random = random,
        clock = { 1_000L },
        idGenerator = idGenerator,
        promptMacros = { macros },
        sfwEnabled = sfwEnabled,
        monotonicNanos = monotonicNanos,
        referenceEncoder = net.pocketnai.data.image.ReferenceImageProcessor(context, fileStore),
    )

    /** 用官网的预处理尺寸和 Fidelity 反值作为断言，不从待测实现推导期望值。 */
    @Test fun preciseReferenceMatchesWebGeometryFidelityAndHistory() = runBlocking<Unit> {
        val id = "precise-reference-test-" + java.util.UUID.randomUUID()
        val root = File(context.cacheDir, id).apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        val files = GenerationFileStore(isolated)
        val processor = net.pocketnai.data.image.ReferenceImageProcessor(isolated, files) { path -> if (path == "inputs/0.png") "source-0" else null }
        val sizes = listOf(256 to 384, 512 to 256, 450 to 500, 400 to 400)
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val strengths = listOf(0.3, 0.6, 0.9, null)
        val fidelities = listOf(1.0, 0.0, 0.65, null)
        val information = listOf(0.5, 0.8, 1.0, null)
        val kinds = listOf(net.pocketnai.domain.model.DirectorReferenceKind.CHARACTER,
            net.pocketnai.domain.model.DirectorReferenceKind.STYLE,
            net.pocketnai.domain.model.DirectorReferenceKind.CHARACTER_AND_STYLE, null)
        val payloads = mutableListOf<JsonObject>()
        try {
            val references = sizes.mapIndexed { index, (width, height) ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(colors[index]) }
                val bytes = ByteArrayOutputStream().use { buffer ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
                    buffer.toByteArray()
                }
                bitmap.recycle()
                val input = File(files.resolve("inputs").apply { mkdirs() }, "$index.png").apply { writeBytes(bytes) }
                val prepared = (processor.import(net.pocketnai.domain.image.ReferenceSource.LocalPath("inputs/$index.png"),
                    net.pocketnai.domain.image.ImageTransform.DirectorCanvas) as Outcome.Success).value
                assertThat(prepared.sourceImageId).isEqualTo(if (index == 0) "source-0" else null)
                assertThat(input.readBytes()).isEqualTo(bytes)
                net.pocketnai.domain.model.ReferenceImage("ref-$index", net.pocketnai.domain.model.ReferenceRole.DIRECTOR,
                    index, prepared.relativePath, prepared.width, prepared.height, prepared.byteSize, prepared.sha256, 0,
                    strength = strengths[index], secondaryStrength = fidelities[index],
                    informationExtracted = information[index], directorKind = kinds[index], sourceImageId = prepared.sourceImageId)
            }
            val profile = ModelCatalog.profileOf(ImageModel.V4_5_FULL)
            val params = GenerationParams.defaultsFor(profile).copy(prompt = "sfw, a traveler",
                seedMode = SeedMode.FIXED, baseSeed = 321)
            val draft = net.pocketnai.domain.model.GenerationDraft(params, params.prompt, "", references)
            val restored = net.pocketnai.data.settings.GenerationDraftCodec.decode(
                net.pocketnai.data.settings.GenerationDraftCodec.encode(draft))!!
            assertThat(restored.references.map { it.secondaryStrength }).isEqualTo(fidelities)
            val repo = repository(payloads, Random(1), files) { id }
            val request = GenerationRequest(restored.params, net.pocketnai.domain.model.GenerationMode.PRECISE_REFERENCE, restored.references)
            val events = repo.generate(request, params.prompt).toList()
            val image = events.filterIsInstance<GenerationEvent.Final>().single().image
            assertThat(payloads).hasSize(1)
            assertThat(payloads.single().getValue("action").jsonPrimitive.content).isEqualTo("generate")
            val sent = parametersOf(payloads.single())
            fun values(key: String) = sent.getValue(key).jsonArray.map { it.jsonPrimitive.double }
            assertThat(values("director_reference_strength_values")).containsExactly(0.3, 0.6, 0.9, 1.0).inOrder()
            val secondary = values("director_reference_secondary_strength_values")
            listOf(0.0, 1.0, 0.35, 0.0).forEachIndexed { index, expected ->
                assertThat(secondary[index]).isWithin(1e-12).of(expected)
            }
            assertThat(values("director_reference_information_extracted")).containsExactly(0.5, 0.8, 1.0, 1.0).inOrder()
            val descriptions = sent.getValue("director_reference_descriptions").jsonArray.map { it.jsonObject }
            assertThat(descriptions.map { it.getValue("caption").jsonObject.getValue("base_caption").jsonPrimitive.content })
                .containsExactly("character", "style", "character&style", "character&style").inOrder()
            descriptions.forEach { description ->
                assertThat(description.keys).containsExactly("caption", "legacy_uc")
                assertThat(description.getValue("legacy_uc").jsonPrimitive.content).isEqualTo("false")
            }
            val expectedSizes = listOf(1024 to 1536, 1536 to 1024, 1472 to 1472, 1472 to 1472)
            val encoded = sent.getValue("director_reference_images").jsonArray
            assertThat(encoded).hasSize(4)
            encoded.forEachIndexed { index, value ->
                val png = Base64.decode(value.jsonPrimitive.content, Base64.DEFAULT)
                val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
                val (width, height) = expectedSizes[index]
                assertThat(decoded.width to decoded.height).isEqualTo(width to height)
                assertThat(decoded.getPixel(width / 2, height / 2)).isEqualTo(colors[index])
                if (index == 0 || index == 3) assertThat(decoded.getPixel(0, 0)).isEqualTo(colors[index])
                if (index == 1) {
                    assertThat(decoded.getPixel(width / 2, 0)).isEqualTo(Color.BLACK)
                    assertThat(decoded.getPixel(0, height / 2)).isEqualTo(Color.GREEN)
                    assertThat(decoded.getPixel(width / 2, 128)).isEqualTo(Color.GREEN)
                }
                if (index == 2) {
                    assertThat(decoded.getPixel(0, height / 2)).isEqualTo(Color.BLACK)
                    assertThat(decoded.getPixel(width / 2, 0)).isEqualTo(Color.BLUE)
                    assertThat(decoded.getPixel(74, height / 2)).isEqualTo(Color.BLUE)
                }
                decoded.recycle()
            }
            assertThat(repo.loadDetail(image.id)!!.generation.references.map { it.sourceImageId }).containsExactly("source-0", null, null, null).inOrder()
            assertThat(repo.loadDetail(image.id)!!.generation.references.map { it.secondaryStrength }).isEqualTo(fidelities)
            // 保留先前的模型切换防线：素材留在草稿，V5 不提交 Precise Ref。
            val v5 = ModelCatalog.profileOf(ImageModel.V5_FULL)
            val inactive = request.copy(params = params.copy(model = v5.model)).withActiveReferences(v5)
            assertThat(inactive.references).isEmpty()
            assertThat(inactive.mode).isEqualTo(net.pocketnai.domain.model.GenerationMode.TXT2IMG)
            assertThat(inactive.validate(v5)).isEmpty()
            assertThat(request.withActiveReferences(profile).references).isEqualTo(references)
            // 同一组 Precise 条件也必须进入 img2img 请求，不能因主模式改变而丢失。
            val base = references[0].copy(id = "base", role = ReferenceRole.IMG2IMG, strength = 0.7)
            val mixed = request.copy(mode = GenerationMode.IMG2IMG, references = listOf(base) + references)
            val mixedDraft = draft.copy(references = mixed.references)
            val mixedRestored = net.pocketnai.data.settings.GenerationDraftCodec.decode(
                net.pocketnai.data.settings.GenerationDraftCodec.encode(mixedDraft))!!
            val mixedRepo = repository(payloads, Random(1), files) { "$id-mixed" }
            val mixedEvents = mixedRepo.generate(mixed.copy(references = mixedRestored.references), params.prompt).toList()
            val mixedImage = mixedEvents.filterIsInstance<GenerationEvent.Final>().single().image
            assertThat(payloads).hasSize(2)
            assertThat(payloads.last().getValue("action").jsonPrimitive.content).isEqualTo("img2img")
            val mixedSent = parametersOf(payloads.last())
            assertThat(mixedSent.getValue("image").jsonPrimitive.content).isNotEmpty()
            assertThat(mixedSent.getValue("strength").jsonPrimitive.double).isEqualTo(0.7)
            assertThat(mixedSent.getValue("director_reference_images")).isEqualTo(sent.getValue("director_reference_images"))
            assertThat(mixedSent.getValue("director_reference_secondary_strength_values"))
                .isEqualTo(sent.getValue("director_reference_secondary_strength_values"))
            val mixedHistory = mixedRepo.loadDetail(mixedImage.id)!!.generation
            assertThat(mixedHistory.mode).isEqualTo(GenerationMode.IMG2IMG)
            assertThat(mixedHistory.references.map { it.role }).containsExactly(ReferenceRole.IMG2IMG,
                ReferenceRole.DIRECTOR, ReferenceRole.DIRECTOR, ReferenceRole.DIRECTOR, ReferenceRole.DIRECTOR)
            val v5Mixed = mixed.copy(params = params.copy(model = v5.model)).withActiveReferences(v5)
            assertThat(v5Mixed.references).containsExactly(base)
            assertThat(v5Mixed.mode).isEqualTo(GenerationMode.IMG2IMG)
            val conflicting = mixed.copy(references = mixed.references + base.copy(id = "vibe", role = ReferenceRole.VIBE))
            assertThat(conflicting.validate(profile)).contains(
                net.pocketnai.domain.model.ReferenceViolation.ConflictingWithMode(GenerationMode.PRECISE_REFERENCE))
            checkReferenceSelectionAndQuote(isolated, processor, mixedRepo, params)

        } finally {
            root.deleteRecursively()
        }
    }


    /** 复现选图的两种先后顺序，使用隔离偏好与假 API，不接触用户草稿或账号。 */
    private suspend fun checkReferenceSelectionAndQuote(
        isolated: android.content.Context,
        processor: net.pocketnai.data.image.ReferenceImageProcessor,
        repo: GenerationRepository,
        params: GenerationParams,
    ) {
        checkSharedImageTaskContract()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = "reference-combo-" + java.util.UUID.randomUUID()
        val preferenceNames = mutableSetOf<String>()
        val testContext = object : android.content.ContextWrapper(isolated) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val scoped = "$prefix-$name"
                preferenceNames += scoped
                return context.getSharedPreferences(scoped, mode)
            }
        }
        val preferences = net.pocketnai.data.settings.GenerationDraftPreferences(testContext)
        preferences.save(net.pocketnai.domain.model.GenerationDraft(params, params.prompt, "", emptyList()))
        testContext.getSharedPreferences("pocketnai_settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("subscription_override", "OPUS").apply()
        val settings = net.pocketnai.data.settings.SettingsStore(testContext)
        assertThat(settings.sfwMode.value).isFalse()
        settings.setSfwMode(true)
        assertThat(net.pocketnai.data.settings.SettingsStore(testContext).sfwMode.value).isTrue()
        settings.setSfwMode(false)
        assertThat(settings.subscriptionOverride.value).isEqualTo(net.pocketnai.domain.billing.SubscriptionOverride.AUTO)
        assertThat(testContext.getSharedPreferences("pocketnai_settings", android.content.Context.MODE_PRIVATE)
            .contains("subscription_override")).isFalse()
        settings.setSubscriptionOverride(net.pocketnai.domain.billing.SubscriptionOverride.OPUS)
        val credentials = object : CredentialStore {
            override fun hasCredential() = false
            override fun load(): StoredCredential? = null
            override fun hint(): CredentialHint? = null
            override fun save(credential: StoredCredential) = error("不得保存凭据")
            override fun clear() = Unit
        }
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var vm: GenerateViewModel
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        suspend fun imported(block: () -> Unit) {
            main(block)
            withTimeout(10_000) { while (vm.state.value.referenceBusy) delay(10) }
            assertThat(vm.state.value.referenceError).isNull()
        }
        val source = net.pocketnai.domain.image.ReferenceSource.LocalPath("inputs/0.png")
        var metadataProbe: net.pocketnai.domain.metadata.MetadataProbeResult = net.pocketnai.domain.metadata.MetadataProbeResult.NotNovelAi
        try {
            main {
                vm = GenerateViewModel(repo, net.pocketnai.ui.state.GenerationDraftStore(),
                    net.pocketnai.ui.state.GenerationPreviewStore(), preferences,
                    net.pocketnai.domain.prompt.TagSuggestionSource { _, _ -> emptyList() }, processor,
                    object : net.pocketnai.domain.metadata.ImageMetadataInspector {
                        override suspend fun inspect(source: net.pocketnai.domain.image.ReferenceSource) =
                            metadataProbe
                    }, AccountBalanceRepository(FakeApi(mutableListOf()), credentials), settings)
                store.put("generate", vm)
            }
            imported { vm.onReferencePicked(source) }
            val base = vm.state.value.referenceSource
            imported { vm.onDirectorReferencePicked(source) }
            assertThat(vm.state.value.referenceSource).isEqualTo(base)
            assertThat(vm.state.value.directorReferences).hasSize(1)
            assertThat(vm.state.value.mode).isEqualTo(GenerationMode.IMG2IMG)
            val quote = withTimeout(10_000) { vm.costEstimate.first {
                it is net.pocketnai.domain.billing.GenerationCostEstimate.EstimatedAnlas && it.batchTotal == 5L
            } }
            assertThat((quote as net.pocketnai.domain.billing.GenerationCostEstimate.EstimatedAnlas).batchTotal).isEqualTo(5L)
            val director = vm.state.value.directorReferences.single()
            imported { vm.onReferencePicked(source) }
            assertThat(vm.state.value.directorReferences).containsExactly(director)
            imported { vm.onInpaintBasePicked(source) }
            assertThat(vm.state.value.directorReferences).containsExactly(director)
            val mask = director.copy(id = "mask", role = ReferenceRole.INPAINT_MASK)
            main { vm.onInpaintMaskRendered(mask) }
            imported { vm.onDirectorReferencePicked(source) }
            assertThat(vm.state.value.inpaintReferences).containsExactly(mask)
            assertThat(vm.state.value.mode).isEqualTo(GenerationMode.INPAINT)
            withTimeout(10_000) { vm.costEstimate.first {
                it is net.pocketnai.domain.billing.GenerationCostEstimate.EstimatedAnlas && it.batchTotal == 10L
            } }
            main { vm.onModelSelected(ImageModel.V5_FULL) }
            assertThat(vm.state.value.directorReferences).hasSize(2)
            assertThat(vm.state.value.activeReferences.map { it.role }).containsExactly(ReferenceRole.IMG2IMG, ReferenceRole.INPAINT_MASK)
            main { vm.onModelSelected(ImageModel.V4_5_FULL) }
            val inpaintBase = vm.state.value.referenceSource
            imported { vm.onVibeReferencePicked(source) }
            assertThat(vm.state.value.referenceSource).isEqualTo(inpaintBase)
            assertThat(vm.state.value.directorReferences).isEmpty()
            assertThat(vm.state.value.vibeReferences).hasSize(1)
            assertThat(vm.state.value.mode).isEqualTo(GenerationMode.IMG2IMG)
            imported { vm.onDirectorReferencePicked(source) }
            assertThat(vm.state.value.referenceSource).isEqualTo(inpaintBase)
            assertThat(vm.state.value.vibeReferences).isEmpty()
            main { vm.onSampleCountChange(2); vm.onStepsChange(16) }
            val beforePreset = vm.state.value
            main { vm.onOpusV5PresetEnabled(true) }
            assertThat(vm.state.value.params).isEqualTo(beforePreset.params) // V4.5 is not targeted.
            assertThat(net.pocketnai.data.settings.SettingsStore(testContext).opusV5PresetEnabled.value).isTrue()
            main { vm.onModelSelected(ImageModel.V5_FULL) }
            assertThat(vm.state.value.params.steps).isEqualTo(28)
            assertThat(vm.state.value.params.sampleCount).isEqualTo(2)
            assertThat(vm.state.value.profile.tierOf(vm.state.value.params.size)).isEqualTo(net.pocketnai.domain.model.ResolutionTier.NORMAL)
            assertThat(vm.state.value.params.size.orientation).isEqualTo(beforePreset.params.targetSize.orientation)
            assertThat(vm.state.value.params.effort).isEqualTo(net.pocketnai.domain.model.NovelAiEffort.HIGH)
            assertThat(vm.state.value.allReferences).isEqualTo(beforePreset.allReferences)
            assertThat(vm.state.value.promptTemplate).isEqualTo(beforePreset.promptTemplate)
            main {
                vm.onStepsChange(35)
            }
            assertThat(vm.state.value.params.steps).isEqualTo(35)
            main {
                vm.onResolutionTierChange(net.pocketnai.domain.model.ResolutionTier.LARGE)
                vm.onStepsChange(16)
            }
            assertThat(vm.state.value.params.steps).isEqualTo(16)
            assertThat(vm.state.value.params.sampleCount).isEqualTo(2)
            main { vm.onResolutionTierChange(net.pocketnai.domain.model.ResolutionTier.NORMAL); vm.onStepsChange(12) }
            assertThat(vm.state.value.params.steps).isEqualTo(28)
            main { vm.onEffortSelected(net.pocketnai.domain.model.NovelAiEffort.MEDIUM) }
            assertThat(vm.state.value.params.effort).isEqualTo(net.pocketnai.domain.model.NovelAiEffort.HIGH)
            main { vm.onCustomResolutionEnabled(); vm.onStepsChange(16) }
            assertThat(vm.state.value.params.steps).isEqualTo(16)
            main { vm.onEffortSelected(net.pocketnai.domain.model.NovelAiEffort.MEDIUM) }
            assertThat(vm.state.value.params.steps).isEqualTo(14)
            assertThat(vm.state.value.negativeTemplate).isEqualTo(beforePreset.negativeTemplate)
            val customBeforeImport = vm.state.value.customResolution
            val canvasBeforeImport = vm.state.value.params.size
            metadataProbe = net.pocketnai.domain.metadata.MetadataProbeResult.Found(
                net.pocketnai.domain.metadata.NovelAiMetadataParser.parse(listOf(
                    net.pocketnai.domain.metadata.PngTextChunks.TextChunk("Software", "NovelAI"),
                    net.pocketnai.domain.metadata.PngTextChunks.TextChunk("Source", "NovelAI Diffusion V5 0ADF9AB7"),
                    net.pocketnai.domain.metadata.PngTextChunks.TextChunk("Description", "sfw, imported landscape"),
                ), kotlinx.serialization.json.Json)!!)
            imported { vm.onReferencePicked(source) }
            main {
                vm.onMetadataSelectionChange(net.pocketnai.domain.metadata.MetadataImportSelection(settings = false, negativePrompt = false))
                vm.confirmMetadataImport()
            }
            assertThat(vm.state.value.promptTemplate).isEqualTo("sfw, imported landscape")
            assertThat(vm.state.value.customResolution).isEqualTo(customBeforeImport)
            assertThat(vm.state.value.params.size).isEqualTo(canvasBeforeImport)
            assertThat(vm.state.value.params.effort).isEqualTo(net.pocketnai.domain.model.NovelAiEffort.MEDIUM)
            assertThat(vm.state.value.params.steps).isEqualTo(14)
            main { vm.onResolutionTierChange(net.pocketnai.domain.model.ResolutionTier.NORMAL) }
            assertThat(vm.state.value.customResolution).isNotNull() // Normal conflicts with fixed-14 Medium.
            main {
                vm.onEffortSelected(net.pocketnai.domain.model.NovelAiEffort.HIGH)
                vm.onResolutionTierChange(net.pocketnai.domain.model.ResolutionTier.NORMAL)
            }
            assertThat(vm.state.value.params.steps).isEqualTo(28)
            settings.setSubscriptionOverride(net.pocketnai.domain.billing.SubscriptionOverride.NONE)
            main { vm.onStepsChange(12) }
            assertThat(vm.state.value.params.steps).isEqualTo(12) // Expired/missing subscription does not apply it.
            main { vm.onOpusV5PresetEnabled(false) }
        } finally {
            main { store.clear() }
            preferenceNames.forEach { context.deleteSharedPreferences(it) }
        }
    }

    /** 不启动真实主界面或读取账号；覆盖相册分享交接与打包后的组件配置。 */
    private fun checkSharedImageTaskContract() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://test.gallery/images/one")
        val incoming = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra("unrelated", "must not forward")
            flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        val forwarded = net.pocketnai.ui.sharedImageMainIntent(target, incoming)!!
        assertThat(forwarded.component).isEqualTo(ComponentName(target, net.pocketnai.ui.MainActivity::class.java))
        assertThat(net.pocketnai.ui.sharedImageUri(forwarded)).isEqualTo(uri)
        assertThat(forwarded.clipData!!.itemCount).isEqualTo(1)
        assertThat(forwarded.clipData!!.getItemAt(0).uri).isEqualTo(uri)
        assertThat(forwarded.extras!!.keySet()).containsExactly(Intent.EXTRA_STREAM)
        assertThat(forwarded.flags).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertThat(incoming.flags and Intent.FLAG_ACTIVITY_NO_HISTORY).isNotEqualTo(0)
        val clipped = Intent(Intent.ACTION_SEND).apply { type = "image/jpeg"; clipData = ClipData.newRawUri("sample", uri) }
        assertThat(net.pocketnai.ui.sharedImageUri(net.pocketnai.ui.sharedImageMainIntent(target, clipped)!!)).isEqualTo(uri)
        assertThat(net.pocketnai.ui.sharedImageMainIntent(target, Intent(incoming).setType("text/plain"))).isNull()
        assertThat(net.pocketnai.ui.sharedImageMainIntent(target, Intent(incoming)
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///private/forbidden.png")))).isNull()
        val main = target.packageManager.getActivityInfo(ComponentName(target, net.pocketnai.ui.MainActivity::class.java), 0)
        assertThat(main.launchMode).isEqualTo(ActivityInfo.LAUNCH_SINGLE_TASK)
        assertThat(main.documentLaunchMode).isEqualTo(ActivityInfo.DOCUMENT_LAUNCH_NEVER)
        assertThat(main.flags and (ActivityInfo.FLAG_NO_HISTORY or ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS)).isEqualTo(0)
        val share = target.packageManager.getActivityInfo(ComponentName(target, net.pocketnai.ui.ShareImageActivity::class.java), 0)
        assertThat(share.flags and ActivityInfo.FLAG_NO_HISTORY).isNotEqualTo(0)
        assertThat(share.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS).isNotEqualTo(0)
        assertThat(share.taskAffinity.orEmpty()).isEmpty()
    }

    /** 原请求载荷测试改成设备端完整链路，覆盖回图融合、裁切和历史一致性。 */
    @Test fun inpaintKeepsOriginalOutsideMaskAndBlendsBeforeHistoryCrop() = runBlocking<Unit> {
        val payloads = mutableListOf<JsonObject>()
        val id = "inpaint-output-test-" + java.util.UUID.randomUUID()
        val root = File(context.cacheDir, id).apply { mkdirs() }
        val isolatedContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        val files = GenerationFileStore(isolatedContext)
        val defaults = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_FULL))
        val width = defaults.size.width
        val height = defaults.size.height
        // 用户真实原图 878×1280 与 832×1216 画布不同，旧代码只缩底图、没有缩蒙版。
        val sourceWidth = 878
        val sourceHeight = 1280
        val params = defaults.copy(prompt = "sfw, blue sky", seedMode = SeedMode.FIXED, baseSeed = 1234,
            outputSize = net.pocketnai.domain.model.ImageSizePreset(width, height - 8),
            qualityTags = net.pocketnai.domain.model.QualityTagsOption.NONE)
        val base = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { buffer ->
            base.compress(Bitmap.CompressFormat.PNG, 100, buffer)
            buffer.toByteArray()
        }
        base.recycle()
        val hash = net.pocketnai.core.Hashing.sha256(bytes)
        files.writeReference(hash, bytes)
        val mask = (net.pocketnai.data.image.MaskImageProcessor(context, files).render(
            listOf(net.pocketnai.domain.inpaint.MaskStroke(isErase = false, radius = 100f, points = listOf(
                net.pocketnai.domain.inpaint.MaskPoint(sourceWidth / 2f, sourceHeight / 3f),
                net.pocketnai.domain.inpaint.MaskPoint(sourceWidth / 2f, sourceHeight * 2 / 3f),
            ))), net.pocketnai.domain.image.PixelSize(sourceWidth, sourceHeight), 0f) as Outcome.Success).value
        fun reference(role: net.pocketnai.domain.model.ReferenceRole, path: String, sha: String, size: Long) =
            net.pocketnai.domain.model.ReferenceImage(role.name, role, 0, path, sourceWidth, sourceHeight, size, sha, 0, strength = 1.0)
        val refs = listOf(reference(net.pocketnai.domain.model.ReferenceRole.IMG2IMG,
            files.referenceRelativePath(hash), hash, bytes.size.toLong()),
            reference(net.pocketnai.domain.model.ReferenceRole.INPAINT_MASK, mask.relativePath, mask.sha256, mask.byteSize),
            reference(ReferenceRole.DIRECTOR, files.referenceRelativePath(hash), hash, bytes.size.toLong()))
        try {
            val repo = repository(payloads, Random(1), files) { id }
            val events = repo.generate(GenerationRequest(params, net.pocketnai.domain.model.GenerationMode.INPAINT, refs), params.prompt).toList()
            assertThat(events.filterIsInstance<GenerationEvent.ItemError>()).isEmpty()
            val image = events.filterIsInstance<GenerationEvent.Final>().single().image
            assertThat(payloads).hasSize(1)
            val payload = payloads.single()
            assertThat(payload.getValue("action").jsonPrimitive.content).isEqualTo("infill")
            assertThat(payload.getValue("model").jsonPrimitive.content).isEqualTo(ImageModel.V4_5_FULL.inpaintingApiModelId)
            val sent = parametersOf(payload)
            assertThat(sent.getValue("director_reference_images").jsonArray).hasSize(1)
            assertThat(sent.getValue("director_reference_secondary_strength_values").jsonArray.single().jsonPrimitive.double)
                .isEqualTo(0.0)
            assertThat(repo.loadDetail(image.id)!!.generation.references.map { it.role })
                .containsExactly(ReferenceRole.IMG2IMG, ReferenceRole.INPAINT_MASK, ReferenceRole.DIRECTOR)
            assertThat(sent.getValue("seed").jsonPrimitive.longOrNull).isEqualTo(1234L)
            fun decodeField(field: String): Bitmap {
                val png = Base64.decode(sent.getValue(field).jsonPrimitive.content, Base64.DEFAULT)
                return BitmapFactory.decodeByteArray(png, 0, png.size)
            }
            val submittedMask = decodeField("mask")
            val boundaryX = width / 2 - 132
            assertThat(submittedMask.width).isEqualTo(width)
            assertThat(submittedMask.height).isEqualTo(height)
            assertThat(submittedMask.getPixel(boundaryX, height / 2)).isEqualTo(Color.BLACK)
            assertThat(submittedMask.getPixel(width / 2, height / 2)).isEqualTo(Color.WHITE)
            submittedMask.recycle()
            val submittedBase = decodeField("image")
            assertThat(submittedBase.getPixel(10, 10)).isEqualTo(Color.BLUE)
            submittedBase.recycle()
            val output = files.resolve(image.privateFilePath)
            val decoded = BitmapFactory.decodeFile(output.absolutePath)
            assertThat(decoded.width).isEqualTo(width)
            assertThat(decoded.height).isEqualTo(height - 8)
            assertThat(decoded.getPixel(10, 0)).isEqualTo(Color.BLUE)
            assertThat(decoded.getPixel(width / 2, height / 2 - 4)).isEqualTo(Color.GREEN)
            val edge = decoded.getPixel(boundaryX, height / 2 - 4)
            assertThat(Color.green(edge)).isIn(1..254)
            assertThat(Color.blue(edge)).isIn(1..254)
            assertThat(Color.alpha(edge)).isEqualTo(255)
            decoded.recycle()
            val saved = output.readBytes()
            assertThat(image.sha256).isEqualTo(net.pocketnai.core.Hashing.sha256(saved))
            assertThat(image.byteSize).isEqualTo(saved.size.toLong())
            val chunks = net.pocketnai.domain.metadata.PngTextWriter.readTextChunks(saved).associate { it.keyword to it.text }
            assertThat(chunks["Software"]).isEqualTo("NovelAI")
            assertThat(chunks["Comment"]).contains("sfw, blue sky")
            assertThat(repo.loadDetail(image.id)!!.generation.params.baseSeed).isEqualTo(1234L)
            // 仅在测试专用缓存中保存对比图，供设备截图/像素核对，不写用户历史。
            output.copyTo(File(context.cacheDir, "inpaint-regression.png"), overwrite = true)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 尺寸与 seed 都在 `parameters` 里，顶层只有 input / model / action。 */
    private fun parametersOf(payload: JsonObject): JsonObject =
        payload.getValue("parameters") as JsonObject

    @Test fun numericArtistSuffixStaysSeparatedInPayloadHistoryAndFavorite() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        val files = GenerationFileStore(context)
        val prefix = "artist-weight-test-" + java.util.UUID.randomUUID()
        val repo = repository(payloads, Random(1), files) { prefix }
        val mix = net.pocketnai.domain.artistlab.ArtistMix(listOf(
            net.pocketnai.domain.artistlab.ArtistTag("artist: salmon88", 24),
            net.pocketnai.domain.artistlab.ArtistTag("artist: mignon", 15),
        ))
        val base = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED)).copy(
            prompt = "sfw, blue sky", seedMode = SeedMode.FIXED, baseSeed = 87654321,
            qualityTags = net.pocketnai.domain.model.QualityTagsOption.NONE,
        )
        val prompt = "1.20::artist: salmon88, ::, 0.75::artist: mignon, ::, sfw, blue sky"
        try {
            val params = net.pocketnai.domain.artistlab.ArtistLabPlanner.params(base, mix)
            val events = repo.generate(GenerationRequest(params), params.prompt).toList()
            val image = events.filterIsInstance<GenerationEvent.Final>().single().image
            val payload = payloads.single()
            assertThat(payload["input"]!!.jsonPrimitive.content).isEqualTo(prompt)
            val caption = parametersOf(payload)["v4_prompt"]!!.jsonObject["caption"]!!.jsonObject
            assertThat(caption["base_caption"]!!.jsonPrimitive.content).isEqualTo(prompt)
            assertThat(repo.loadDetail(image.id)!!.generation.params.prompt).isEqualTo(prompt)
            val store = net.pocketnai.data.artistlab.ArtistLabStore(context, database)
            store.favorite(net.pocketnai.data.local.ArtistLabDrawEntity("$prefix:0", prefix, 0, "{}", "SUCCEEDED", imageId = image.id), mix.prompt, true)
            assertThat(store.dao.observeMixFavorites().first().single().prompt)
                .isEqualTo("1.20::artist: salmon88, ::, 0.75::artist: mignon, ::")
        } finally { files.deleteGeneration(prefix) }
    }

    @Test fun artistLabHundredSingleRequestsKeepSeedAndCleanupIsScoped() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        val files = GenerationFileStore(context)
        val prefix = "artist-lab-test-" + java.util.UUID.randomUUID()
        var ordinal = 0
        val repo = repository(payloads, Random(99), files) { "$prefix-${ordinal++}" }
        val base = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED)).copy(
            prompt = "sfw, blue sky", negativePrompt = "lowres", qualityTags = net.pocketnai.domain.model.QualityTagsOption.NONE,
            seedMode = SeedMode.FIXED, baseSeed = 87654321,
        )
        val config = net.pocketnai.domain.artistlab.ArtistLabConfig(
            net.pocketnai.data.settings.GenerationDraftCodec.encode(net.pocketnai.domain.model.GenerationDraft(base, base.prompt, base.negativePrompt)),
            5, 10, 30, 100, "test-pool")
        val store = net.pocketnai.data.artistlab.ArtistLabStore(context, database)
        val catalog = store.catalog()
        val catalogSource = context.assets.open("artist-lab/source.json").bufferedReader().use { it.readText() }
        assertThat(catalog.first).hasSize(Json.parseToJsonElement(catalogSource).jsonObject.getValue("count").jsonPrimitive.content.toInt())
        assertThat(catalog.first.size).isAtLeast(10)
        val plan = net.pocketnai.domain.artistlab.ArtistLabPlanner.plan(catalog.first, config, Random(42))
        val imageIds = mutableListOf<String>()
        val gallerySlots = net.pocketnai.ui.gallery.GalleryMotionSlots()
        try {
            val waits = mutableListOf<Long>()
            net.pocketnai.domain.artistlab.ArtistLabQueue(wait = { waits += it }, interval = { 1500 }).run(plan, { false }) { mix ->
                val params = net.pocketnai.domain.artistlab.ArtistLabPlanner.params(base, mix)
                val events = repo.generate(GenerationRequest(params), params.prompt).onEach { event ->
                    if (event is GenerationEvent.Started) {
                        gallerySlots.sections(emptyList(), repo.observeGenerations().first(), true)
                    }
                }.toList()
                imageIds += events.filterIsInstance<GenerationEvent.Final>().single().image.id
            }
            assertThat(payloads).hasSize(100)
            assertThat(waits.all { it in 1000..2000 }).isTrue()
            for ((index, payload) in payloads.withIndex()) {
                val params = parametersOf(payload)
                assertThat(params["seed"]!!.jsonPrimitive.longOrNull).isEqualTo(87654321)
                assertThat(params["n_samples"]!!.jsonPrimitive.intOrNull).isEqualTo(1)
                assertThat(payload["input"]!!.jsonPrimitive.contentOrNull).isEqualTo(net.pocketnai.domain.artistlab.ArtistLabPlanner.params(base, plan[index]).prompt)
            }
            val favorite = net.pocketnai.data.repo.FavoriteImageRepository(database.favoriteImageDao())
            favorite.setFavorite(imageIds[0], true)
            favorite.setFavorite(imageIds[1], true)
            // 生成一张属于其它批次的图片，清理当前批次不能波及它。
            val other = repo.generate(GenerationRequest(base), base.prompt).toList().filterIsInstance<GenerationEvent.Final>().single().image
            gallerySlots.sections(repo.observeGallery().first(), repo.observeGenerations().first(), true)
            assertThat(repo.cleanupLabImages(imageIds)).isEqualTo(98)
            assertThat(database.generationDao().findImage(imageIds[0])).isNotNull()
            assertThat(database.generationDao().findImage(imageIds[1])).isNotNull()
            assertThat(database.generationDao().findImage(other.id)).isNotNull()
            assertThat(database.generationDao().allImages()).hasSize(3)
            val afterCleanup = gallerySlots.sections(repo.observeGallery().first(), repo.observeGenerations().first(), true)
                .flatMap { it.slots }
            assertThat(afterCleanup.map { it.image?.imageId }).containsExactly(imageIds[0], imageIds[1], other.id)
            assertThat(repo.observeGenerations().first()).hasSize(101)
            // 中断请求只恢复本地结果，空结果标为待确认，不会补发。
            store.dao.create(net.pocketnai.data.local.ArtistLabRunEntity(prefix, 1, "{}", "RUNNING", ""), listOf(
                net.pocketnai.data.local.ArtistLabDrawEntity("$prefix:a", prefix, 0, "{}", "RUNNING", other.generationId),
                net.pocketnai.data.local.ArtistLabDrawEntity("$prefix:b", prefix, 1, "{}", "RUNNING", "$prefix:missing")))
            store.recover()
            val recovered = store.dao.draws(prefix)
            assertThat(recovered.map { it.status }).containsExactly("SUCCEEDED", "UNCERTAIN").inOrder()
            assertThat(store.dao.run(prefix)!!.status).isEqualTo("PAUSED")
            assertThat(payloads).hasSize(101)
        } finally {
            database.generationDao().allGenerationIds().filter { it.startsWith(prefix) }.forEach { files.deleteGeneration(it) }
        }
    }

    @Test fun preflightMustFinishBeforePostAndWrongAccountNeverSends() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        val files = GenerationFileStore(context)
        val prefix = "artist-lab-preflight-test-" + java.util.UUID.randomUUID()
        var sfw = false
        var key = prefix
        var nanos = 0L
        var cancelRequest = false
        val repo = repository(payloads, Random(1), files, sfwEnabled = { sfw }, monotonicNanos = { nanos },
            onGenerate = { nanos += 3_456_000_000L; if (cancelRequest) throw kotlinx.coroutines.CancellationException("fixture") }) { key }
        val params = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED)).copy(prompt = "sfw, blue sky")
        try {
            val denied = repo.generate(GenerationRequest(params), params.prompt, expectedAccountFingerprint = "wrong-account").toList()
            assertThat(denied.filterIsInstance<GenerationEvent.FatalError>()).hasSize(1)
            assertThat(payloads).isEmpty()
            var hookCalled = false
            try {
                repo.generate(GenerationRequest(params), params.prompt, beforeSend = {
                    hookCalled = true
                    throw net.pocketnai.domain.artistlab.ArtistLabPreflightChanged()
                }).toList()
            } catch (_: net.pocketnai.domain.artistlab.ArtistLabPreflightChanged) { }
            assertThat(hookCalled).isTrue()
            assertThat(payloads).isEmpty()
            assertThat(database.generationDao().findGeneration(prefix)!!.status).isEqualTo("FAILED")
            assertThat(database.generationDao().findGeneration(prefix)!!.generationDurationMillis).isNull()
            val marked = params.copy(prompt = "nsfw, geometry fixture")
            sfw = true; key = "$prefix-blocked"
            assertThat(repo.generate(GenerationRequest(marked), marked.prompt).toList()
                .filterIsInstance<GenerationEvent.FatalError>().single().error.detail).contains("SFW")
            assertThat(payloads).isEmpty()
            sfw = false; key = "$prefix-late"
            assertThat(repo.generate(GenerationRequest(marked), marked.prompt, beforeSend = { sfw = true }).toList()
                .filterIsInstance<GenerationEvent.FatalError>().single().error.detail).contains("SFW")
            assertThat(payloads).isEmpty()
            sfw = false; key = "$prefix-allowed"
            repo.generate(GenerationRequest(marked), marked.prompt).toList()
            assertThat(payloads).hasSize(1)
            assertThat(payloads.single()["input"]!!.jsonPrimitive.content).contains("nsfw")
            key = "$prefix-queued"
            val queued = repo.enqueue(GenerationRequest(params.copy(seedMode = SeedMode.RANDOM, model = ImageModel.V5_FULL,
                effort = net.pocketnai.domain.model.NovelAiEffort.MEDIUM, negativePrompt = "preserved draft negative")), params.prompt, 123L)
            val frozenSeed = queued.request.params.baseSeed
            assertThat(database.generationDao().findGeneration(queued.id)!!.status).isEqualTo("QUEUED")
            assertThat(payloads).hasSize(1) // Persisting an occupied slot never sends a request.
            nanos += 100_000_000_000L // queue time must not be included
            val queuedEvents = repo.generate(queued.request, queued.promptTemplate, queued = queued,
                beforeSend = { nanos += 12_000_000_000L }).toList()
            assertThat(queuedEvents.filterIsInstance<GenerationEvent.Started>().single().generationId).isEqualTo(queued.id)
            assertThat(database.generationDao().findGeneration(queued.id)!!.status).isEqualTo("SUCCEEDED")
            val completedImage = queuedEvents.filterIsInstance<GenerationEvent.Final>().single().image
            assertThat(repo.loadDetail(completedImage.id)!!.generation.generationDurationMillis).isEqualTo(3456L)
            val restoredMedium = repo.loadDetail(completedImage.id)!!.generation.params
            assertThat(restoredMedium.effort).isEqualTo(net.pocketnai.domain.model.NovelAiEffort.MEDIUM)
            assertThat(restoredMedium.steps).isEqualTo(14)
            assertThat(restoredMedium.negativePrompt).isEmpty()
            assertThat(payloads.last()["model"]!!.jsonPrimitive.content).isEqualTo("nai-diffusion-5-full-medium")
            assertThat(payloads.last()["parameters"]!!.jsonObject["seed"]!!.jsonPrimitive.longOrNull).isEqualTo(frozenSeed)
            key = "$prefix-unsent"
            val interrupted = repo.enqueue(GenerationRequest(params), params.prompt, 124L)
            repo.failQueued(interrupted.id, net.pocketnai.core.AppError.of(net.pocketnai.core.ErrorCode.INVALID_PARAMS))
            assertThat(database.generationDao().findGeneration(interrupted.id)!!.status).isEqualTo("FAILED")
            assertThat(payloads).hasSize(2)
            assertThat(database.generationDao().findGeneration(interrupted.id)!!.generationDurationMillis).isNull()
            key = "$prefix-canceled"; cancelRequest = true
            assertThat(runCatching { repo.generate(GenerationRequest(params), params.prompt).toList() }.exceptionOrNull())
                .isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
            assertThat(database.generationDao().findGeneration(key)!!.generationDurationMillis).isEqualTo(3456L)
        } finally { listOf(prefix, "$prefix-blocked", "$prefix-late", "$prefix-allowed", "$prefix-queued", "$prefix-unsent", "$prefix-canceled").forEach(files::deleteGeneration) }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun runGeneration(
        seedMode: SeedMode,
        baseSeed: Long,
        random: Random,
        payloads: MutableList<JsonObject>,
        fileStore: GenerationFileStore,
    ) {
        val profile = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)
        val params = GenerationParams.defaultsFor(profile).copy(
            seedMode = seedMode,
            baseSeed = baseSeed,
            prompt = "1girl",
        )
        val events = runBlocking {
            repository(payloads, random, fileStore)
                .generate(GenerationRequest(params = params), promptTemplate = "1girl")
                .toList()
        }
        // 生成链路里的早期失败（参数不合法、空间不足、凭据缺失）不会发请求，
        // 断言失败时把事件打出来，否则只能看到一个莫名其妙的"payloads 为空"。
        assertThat(events.joinToString { it.toString() }).isNotEmpty()
        assertThat(payloads).isNotEmpty()
    }

    @Test
    fun randomModeSendsAFreshSeedAndRecordsTheSameOne() {
        val payloads = mutableListOf<JsonObject>()
        val fileStore = GenerationFileStore(context)
        runGeneration(SeedMode.RANDOM, baseSeed = 0L, random = Random(2026), payloads, fileStore)

        val sent = parametersOf(payloads.single())["seed"]!!.jsonPrimitive.longOrNull
        // 请求体里绝不能是默认的 0：那正是"每次同一张图"的成因。
        assertThat(sent).isNotNull()
        assertThat(sent).isNotEqualTo(0L)

        // 历史记录里的 seed 必须与真正发出去的一致，否则用户按它复现不出来。
        // 注意是 `first()` 直接取流的第一帧：Room 的 Flow 永不结束，`toList()` 会一直等下去。
        val stored = runBlocking {
            database.generationDao().observeGenerations().first().single()
        }
        // 直接读数据库列：它是历史/详情页展示的那个值。
        assertThat(stored.generation.baseSeed).isEqualTo(sent)
        assertThat(stored.generation.seedMode).isEqualTo(SeedMode.RANDOM.name)
    }

    @Test
    fun charactersSurviveGenerationAndDetailReload() = runBlocking {
        val payloads = mutableListOf<JsonObject>()
        val original = "two people, a sign reads \"这是原文\", Text: 这是原文"
        val macro = net.pocketnai.domain.model.PromptFavorite("scene", net.pocketnai.domain.model.PromptFavoriteKind.TAG,
            "场景", "two people, a sign reads \"这是原文\"", "", net.pocketnai.domain.model.PromptTarget.POSITIVE, 0, 0, 0)
        val repository = repository(payloads, Random(1), GenerationFileStore(context), macros = listOf(macro))
        val characters = listOf(
            net.pocketnai.domain.model.CharacterPrompt(prompt = "blue hair", negativePrompt = "hat", centerX = 0.2, centerY = 0.3),
            net.pocketnai.domain.model.CharacterPrompt(prompt = "red hair", negativePrompt = "glasses", centerX = 0.8, centerY = 0.7),
        )
        val params = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V5_FULL))
            .copy(prompt = "!macro:场景!, Text: 这是原文", characters = characters, useCharacterCoordinates = true)
        val events = repository.generate(GenerationRequest(params = params), params.prompt).toList()
        val image = events.filterIsInstance<GenerationEvent.Final>().single().image
        val restored = repository.loadDetail(image.id)!!.generation
        assertThat(restored.params.characters).isEqualTo(characters)
        assertThat(restored.params.prompt).isEqualTo(original)
        assertThat(restored.promptTemplate).isEqualTo("!macro:场景!, Text: 这是原文")
        assertThat(restored.params.useCharacterCoordinates).isTrue()
        val expected = "two people, a sign reads \"这是原文\",, very aesthetic, masterpiece, no text Text: 这是原文"
        assertThat(payloads.single()["input"]!!.jsonPrimitive.content).isEqualTo(expected)
        assertThat(NovelAiRequestBuilder.build(ModelCatalog.profileOf(params.model), restored.params)["input"]!!.jsonPrimitive.content)
            .isEqualTo(expected)
        for (key in listOf("v4_prompt", "v4_negative_prompt")) {
            val block = parametersOf(payloads.single())[key] as JsonObject
            val captions = (block["caption"] as JsonObject)["char_captions"] as kotlinx.serialization.json.JsonArray
            val first = captions[0] as JsonObject
            val centers = first["centers"] as kotlinx.serialization.json.JsonArray
            val center = centers[0] as JsonObject
            assertThat(center["x"]!!.jsonPrimitive.contentOrNull).isEqualTo("0.2")
            assertThat(center["y"]!!.jsonPrimitive.contentOrNull).isEqualTo("0.3")
        }
    }
}
