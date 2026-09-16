package net.pocketnai.di

import android.app.Application
import kotlinx.serialization.json.Json
import net.pocketnai.BuildConfig
import net.pocketnai.data.export.MediaStoreExporter
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.data.image.AndroidImageMetadataInspector
import net.pocketnai.data.image.ReferenceImageProcessor
import net.pocketnai.data.local.PocketNaiDatabase
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.network.NovelAiAuthApi
import net.pocketnai.data.network.NovelAiTagSuggestionSource
import net.pocketnai.data.network.OkHttpNovelAiAuthApi
import net.pocketnai.data.network.OkHttpNovelAiApi
import net.pocketnai.data.network.RedactingHttpLogger
import net.pocketnai.data.repo.AccountBalanceRepository
import net.pocketnai.data.repo.FavoriteImageRepository
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.data.repo.PromptFavoriteRepository
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.security.KeystoreCredentialStore
import net.pocketnai.data.security.SessionState
import net.pocketnai.data.settings.DraftReferencePathsProvider
import net.pocketnai.data.settings.GenerationDraftPreferences
import net.pocketnai.domain.auth.AccessKeyDeriver
import net.pocketnai.domain.auth.NovelAiAccessKeyDeriver
import net.pocketnai.domain.billing.AnlasCostCalculator
import net.pocketnai.domain.billing.NovelAiPaidAnlasFormula
import net.pocketnai.domain.prompt.TagSuggestionSource
import net.pocketnai.data.settings.SettingsStore
import net.pocketnai.ui.state.GenerationDraftStore
import net.pocketnai.ui.state.GenerationPreviewStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 手写的依赖装配。
 *
 * 首版刻意不引入 Hilt / Koin：应用只有一条依赖链、一个数据库和一个网络客户端，
 * 手工装配更容易在阅读时一眼看清全貌，也少一层注解处理器。
 * 依赖数量增长到需要作用域管理时再考虑替换。
 *
 * 所有成员都是懒加载：应用启动只初始化 [credentialStore] 与 [sessionState]，
 * 数据库和网络客户端要到真正使用时才创建。
 */
class AppContainer(application: Application) {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * 超时配置按生成任务的特点设置：
     * 单次高清生成可能耗时较久，因此读超时给到 5 分钟；
     * 但不设置整体 callTimeout，避免把“服务端仍在排队”误判为失败。
     */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor(RedactingHttpLogger(enabled = BuildConfig.DEBUG))
        .build()

    /**
     * 登录专用客户端：从基客户端派生，但把整体超时收紧到 60 秒。
     *
     * 图片生成需要 5 分钟的读取等待（服务端排队 + 出图），而登录只在一次
     * 快速请求里完成。让登录继承 5 分钟会让"网络不通"表现为长时间无响应。
     */
    private val authHttpClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    val credentialStore: CredentialStore = KeystoreCredentialStore(application)

    val sessionState: SessionState = SessionState(
        connected = credentialStore.hasCredential(),
        type = credentialStore.hint()?.type,
    )

    val api: NovelAiApi = OkHttpNovelAiApi(
        baseUrl = BuildConfig.NOVELAI_API_BASE_URL,
        client = httpClient,
        json = json,
    )

    val database: PocketNaiDatabase by lazy { PocketNaiDatabase.build(application) }

    val fileStore: GenerationFileStore by lazy { GenerationFileStore(application) }

    val settingsStore: SettingsStore by lazy { SettingsStore(application) }

    val generationDraftPreferences: GenerationDraftPreferences by lazy {
        GenerationDraftPreferences(application)
    }

    val mediaStoreExporter: MediaStoreExporter by lazy { MediaStoreExporter(application) }

    /** 参考图处理管线（解码 / 变换 / 编码 / 落盘）。全部几何计算在 domain 层，见 ImageGeometry。 */
    val referenceImageProcessor: ReferenceImageProcessor by lazy {
        ReferenceImageProcessor(application, fileStore)
    }

    /**
     * 图片元数据探针。
     *
     * 与参考图处理器共用 [fileStore]，但**必须在它之前调用**：参考图落盘时会重新编码 PNG，
     * 那一步会把 NovelAI 写在文本块里的参数全部丢掉。
     */
    val imageMetadataInspector: AndroidImageMetadataInspector by lazy {
        AndroidImageMetadataInspector(application, fileStore, json)
    }

    val draftStore: GenerationDraftStore = GenerationDraftStore()

    /**
     * 流式中间预览的状态出口：生成页写入、画廊占位卡读取。
     *
     * 与 [draftStore] 同层 —— 都是"跨界面共享的一小块内存状态"，
     * 不需要落盘（预览图本身在 cache 目录，由文件层管）。
     */
    val generationPreviewStore: GenerationPreviewStore = GenerationPreviewStore()

    val generationRepository: GenerationRepository by lazy {
        GenerationRepository(
            api = api,
            credentialStore = credentialStore,
            dao = database.generationDao(),
            fileStore = fileStore,
            referenceEncoder = referenceImageProcessor,
            liveReferencePaths = DraftReferencePathsProvider(generationDraftPreferences),
            // 流式预览的开关与失败计数都归设置层：连续失败 3 次会自动关闭，
            // 那时这里读到的就是 false，生成回到已验证的 ZIP 链路。
            streamingEnabled = { settingsStore.streamingPreviewEnabled.value },
            onStreamingFailure = { settingsStore.recordStreamingFailure() },
        )
    }

    /**
     * Access Key 派生器。默认在 Dispatchers.Default 上执行 ——
     * Argon2 的 CPU 与内存开销不能落在主线程上。
     */
    val accessKeyDeriver: AccessKeyDeriver = NovelAiAccessKeyDeriver()

    val authApi: NovelAiAuthApi = OkHttpNovelAiAuthApi(
        baseUrl = BuildConfig.NOVELAI_API_BASE_URL,
        client = authHttpClient,
        json = json,
    )

    val promptFavoriteRepository: PromptFavoriteRepository by lazy {
        PromptFavoriteRepository(dao = database.promptFavoriteDao())
    }

    /**
     * 图片收藏。
     *
     * 与 [generationRepository] 分开：它只管 `favorite_images` 这一张关联表，
     * 不参与生成链路，两边没有必要绑在一起（画廊与详情页各自消费）。
     */
    val favoriteImageRepository: FavoriteImageRepository by lazy {
        FavoriteImageRepository(dao = database.favoriteImageDao())
    }

    /**
     * 账户余额。只在内存中缓存，不落库（规划书 §7.4）——
     * 余额属于"当前会话的账户事实"，跨会话持久化会把上一个账号的余额显示给下一个账号。
     */
    val accountBalanceRepository: AccountBalanceRepository = AccountBalanceRepository(
        api = api,
        credentialStore = credentialStore,
    )

    /**
     * 费用预估。用 [NovelAiPaidAnlasFormula]：计价式子与免费规则都是 2026-09-14
     * 从官方网页前端 bundle 反解出来的（见技术决策记录），不再是"未校准"状态。
     * 超出官方报价范围的参数仍然返回"费用待确认"，不给猜测数字（余额规划 §10）。
     */
    val anlasCostCalculator: AnlasCostCalculator = AnlasCostCalculator(NovelAiPaidAnlasFormula())

    /**
     * 标签补全的来源。与生成共用同一个 [api]，但补全失败会静默成"没有建议"，
     * 不会碰生成链路上的任何状态。
     */
    val tagSuggestionSource: TagSuggestionSource by lazy {
        NovelAiTagSuggestionSource(api = api, credentialStore = credentialStore)
    }
}
