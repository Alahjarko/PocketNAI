package net.pocketnai.data.repo

import android.graphics.Bitmap
import android.graphics.Color
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.pocketnai.core.Outcome
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.data.local.PocketNaiDatabase
import net.pocketnai.data.network.NovelAiApi
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
    private class FakeApi(private val payloads: MutableList<JsonObject>) : NovelAiApi {
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
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.GREEN)
            val png = ByteArrayOutputStream().use { buffer ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
                buffer.toByteArray()
            }
            bitmap.recycle()
            ZipOutputStream(destinationZip.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("image_0.png"))
                zip.write(png)
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
        idGenerator: () -> String = { "gen-test" },
    ) = GenerationRepository(
        api = FakeApi(payloads),
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
    )

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
        val repo = repository(payloads, Random(1), files) { prefix }
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
        } finally { files.deleteGeneration(prefix) }
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
        val repository = repository(payloads, Random(1), GenerationFileStore(context))
        val characters = listOf(
            net.pocketnai.domain.model.CharacterPrompt(prompt = "blue hair", negativePrompt = "hat", centerX = 0.2, centerY = 0.3),
            net.pocketnai.domain.model.CharacterPrompt(prompt = "red hair", negativePrompt = "glasses", centerX = 0.8, centerY = 0.7),
        )
        val params = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED))
            .copy(prompt = "two people", characters = characters)
        val events = repository.generate(GenerationRequest(params = params), "two people").toList()
        val image = events.filterIsInstance<GenerationEvent.Final>().single().image
        assertThat(repository.loadDetail(image.id)?.generation?.params?.characters).isEqualTo(characters)
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
