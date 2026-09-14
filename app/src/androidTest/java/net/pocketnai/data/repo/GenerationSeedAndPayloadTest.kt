package net.pocketnai.data.repo

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
    }

    private fun repository(
        payloads: MutableList<JsonObject>,
        random: Random,
        fileStore: GenerationFileStore,
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
        idGenerator = { "gen-test" },
    )

    /** 尺寸与 seed 都在 `parameters` 里，顶层只有 input / model / action。 */
    private fun parametersOf(payload: JsonObject): JsonObject =
        payload.getValue("parameters") as JsonObject

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
    fun randomModeUsesADifferentSeedEachTime() {
        val payloads = mutableListOf<JsonObject>()
        val fileStore = GenerationFileStore(context)
        val random = Random(7)

        runGeneration(SeedMode.RANDOM, baseSeed = 0L, random = random, payloads = payloads, fileStore = fileStore)
        runGeneration(SeedMode.RANDOM, baseSeed = 0L, random = random, payloads = payloads, fileStore = fileStore)

        val seeds = payloads.map { parametersOf(it)["seed"]!!.jsonPrimitive.longOrNull }
        assertThat(seeds).hasSize(2)
        assertThat(seeds[0]).isNotEqualTo(seeds[1])
    }

    @Test
    fun fixedModeSendsThePinnedSeed() {
        val payloads = mutableListOf<JsonObject>()
        val fileStore = GenerationFileStore(context)
        runGeneration(
            SeedMode.FIXED,
            baseSeed = 12345L,
            random = Random(1),
            payloads = payloads,
            fileStore = fileStore,
        )

        assertThat(parametersOf(payloads.single())["seed"]!!.jsonPrimitive.longOrNull)
            .isEqualTo(12345L)
    }

    @Test
    fun payloadUsesTheGenerationCanvasNotTheFinalSize() {
        // 自定义分辨率的另一半保证：请求体发的是 64 对齐的画布，不是用户要的最终尺寸。
        val payloads = mutableListOf<JsonObject>()
        val fileStore = GenerationFileStore(context)
        val profile = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)
        val params = GenerationParams.defaultsFor(profile).copy(
            prompt = "1girl",
            size = net.pocketnai.domain.model.ImageSizePreset(1920, 1088),
            outputSize = net.pocketnai.domain.model.ImageSizePreset(1920, 1080),
        )

        runBlocking {
            repository(payloads, Random(1), fileStore)
                .generate(GenerationRequest(params = params), promptTemplate = "1girl")
                .toList()
        }

        val payload = payloads.single()
        val parameters = parametersOf(payload)
        assertThat(parameters["width"]!!.jsonPrimitive.intOrNull).isEqualTo(1920)
        assertThat(parameters["height"]!!.jsonPrimitive.intOrNull).isEqualTo(1088)
        // 最终尺寸只进数据库，不进请求。
        assertThat(parameters["output_width"]).isNull()
        assertThat(parameters["outputSize"]).isNull()
    }
}
