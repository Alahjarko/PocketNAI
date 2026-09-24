package net.pocketnai.ui.generate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.repo.AccountBalanceRepository
import net.pocketnai.data.repo.AnlasLedgerRepository
import net.pocketnai.data.repo.BalanceRefreshReason
import net.pocketnai.data.repo.BalanceState
import net.pocketnai.data.repo.GenerationEvent
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.settings.GenerationDraftPreferences
import net.pocketnai.data.settings.SettingsStore
import net.pocketnai.domain.billing.AnlasCostCalculator
import net.pocketnai.domain.billing.AnlasPricingContext
import net.pocketnai.domain.billing.GenerationCostEstimate
import net.pocketnai.domain.billing.GenerationKind
import net.pocketnai.domain.billing.ObservedBalanceChange
import net.pocketnai.domain.billing.SubscriptionBalance
import net.pocketnai.domain.billing.SubscriptionStatus
import net.pocketnai.domain.billing.SubscriptionStatusResolver
import net.pocketnai.domain.image.ImageTransform
import net.pocketnai.domain.image.PixelSize
import net.pocketnai.domain.image.ResolutionPlanner
import net.pocketnai.domain.image.toPixelSize
import net.pocketnai.domain.inpaint.MaskStroke
import net.pocketnai.data.image.MaskImageProcessor
import net.pocketnai.domain.image.PreparedReference
import net.pocketnai.domain.image.ReferenceImageImporter
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.domain.metadata.ImageMetadataInspector
import net.pocketnai.domain.metadata.MetadataImportPlan
import net.pocketnai.domain.metadata.MetadataImportPlanner
import net.pocketnai.domain.metadata.MetadataImportSelection
import net.pocketnai.domain.metadata.MetadataProbeResult
import net.pocketnai.domain.metadata.NovelAiImageMetadata
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.CustomResolution
import net.pocketnai.domain.model.DirectorReferenceKind
import net.pocketnai.domain.model.GenerationDraft
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageOrientation
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ModelProfile
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.ParamViolation
import net.pocketnai.domain.model.PromptTarget
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.domain.model.ResolutionTier
import net.pocketnai.domain.model.Sampler
import net.pocketnai.domain.model.SeedMode
import net.pocketnai.domain.prompt.PromptRandomizer
import net.pocketnai.domain.prompt.TagSuggestionSource
import net.pocketnai.ui.state.GenerationDraftStore
import net.pocketnai.ui.state.GenerationPreviewStore
import java.util.UUID

/**
 * 生成页状态。
 *
 * 这里持有的是“用户正在编辑的内容”，与历史记录完全分离：
 * - 修改参数不会影响任何已保存的历史任务（规划书 3.2）；
 * - 只有点击“生成”时才把当前参数冻结成一次请求快照。
 */
@OptIn(FlowPreview::class)
class GenerateViewModel(
    private val repository: GenerationRepository,
    private val draftStore: GenerationDraftStore,
    /** 流式中间预览的落点：这里写入，画廊的占位卡片读取。 */
    private val previewStore: GenerationPreviewStore,
    private val draftPreferences: GenerationDraftPreferences,
    private val tagSuggestionSource: TagSuggestionSource,
    private val referenceImporter: ReferenceImageImporter,
    private val metadataInspector: ImageMetadataInspector,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val settingsStore: SettingsStore,
    private val costCalculator: AnlasCostCalculator = AnlasCostCalculator(),
    private val anlasLedgerRepository: AnlasLedgerRepository? = null,
    private val credentialStore: CredentialStore? = null,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    data class UiState(
        val params: GenerationParams,
        val promptTemplate: String = "",
        val negativeTemplate: String = "",
        /**
         * 提示词文本被"输入框之外的力量"整体替换过的代数。
         *
         * 输入框的文本与光标必须由输入框自己掌握：每次按键都会把文本推过来，
         * 如果反过来再按这里的文本写回输入框，就成了"写出去、再收回来"——
         * 快速输入时收回来的往往是上一拍的值。2026-09-16 实测：连续退格时
         * 输入框被整体重置（文本回滚一个字、光标跳到文末），表现为"跳行"。
         *
         * 所以输入框只在**这个代数变化**时重建（见 `GenerateSheet.rememberSyncedField`）。
         * 只有"整体替换文本"的写入者可以 +1：复用历史参数、导入元数据、收藏夹填充。
         * 打字走的 [onPromptChange] / [onNegativePromptChange] **不能**碰它 ——
         * 在那里 +1 会让输入框每次按键都重建，光标永远停在文末。
         */
        val textRevision: Int = 0,
        val inFlight: Boolean = false,
        val completedImages: Int = 0,
        /**
         * 保留完整的 [AppError]，界面才能把服务端返回的具体说明显示出来。
         * 只留一个 [ErrorCode] 的话，参数被服务端拒绝时用户只能看到笼统的“参数无效”，
         * 原始信息会埋在数据库里，排查时完全用不上。
         */
        val error: AppError? = null,
        /** 切换模型时若参数被替换，向用户说明发生了什么。 */
        val modelSwitchNotice: String? = null,
        /**
         * Image2Img 的起点图。非空即代表当前处于图生图模式。
         *
         * 模式不是独立开关，而是"挂了哪类参考图"的结果，这样不会出现
         * "选了图生图模式却没有图"这种自相矛盾的状态。
         */
        val referenceSource: ReferenceImage? = null,
        /**
         * Precise Reference 的参考图（角色 / 画风），最多 [ModelProfile.maxDirectorReferences] 张。
         *
         * 与 [referenceSource] **互斥**：图生图与 Precise Reference 用不同的 `action`，
         * 两者同时存在时请求形态没有经过验证，因此界面上一挂上其中一类就清掉另一类。
         */
        val directorReferences: List<ReferenceImage> = emptyList(),
        /**
         * Vibe Transfer 的参考图，最多 [ModelProfile.maxVibeReferences] 张。
         *
         * 与图生图 / Precise Reference **不互斥**：Vibe 是在它们之上叠加的风格条件，
         * 官方界面里也是独立面板，可以同时使用。
         */
        val vibeReferences: List<ReferenceImage> = emptyList(),
        /**
         * 局部重绘的蒙版（最多一条）。
         *
         * 蒙版与底图（[referenceSource]）配成一对；生成时它以 `parameters.mask` 提交。
         */
        val inpaintReferences: List<ReferenceImage> = emptyList(),
        /** 正在导入参考图（解码 + 落盘）。期间不该重复触发导入。 */
        val referenceBusy: Boolean = false,
        /** 参考图导入失败的原因，与生成失败分开：它不影响已经写好的提示词与参数。 */
        val referenceError: AppError? = null,
        /** 账户余额状态。只是辅助信息，永远不会阻止生成。 */
        val balanceState: BalanceState = BalanceState.Unavailable,
        /** 非空时界面弹"当前显示余额可能不足"的确认框。 */
        val pendingCostConfirmation: Boolean = false,
        /**
         * 生成结束后观察到的余额变化。
         *
         * 用词是"观察到的"而不是"本次扣费"：同一账户可能在别处同时消费、
         * 期间可能充值，服务端更新也可能有延迟。
         */
        val lastObservedChange: ObservedBalanceChange? = null,
        /** 当前可点的标签建议；补全失败或不适用时为空。 */
        val suggestions: List<String> = emptyList(),
        /**
         * [suggestions] 对应的是哪个标签片段。
         *
         * 界面拿它和当前光标所在的标签对比：不一致说明这批建议已经过期
         * （请求在途时用户又改了字），此时宁可不显示，也不能让用户点到一个
         * 与自己刚打的字不匹配的建议。
         */
        val suggestionQuery: String = "",
        /**
         * 选图时读到的 NovelAI 元数据。非空时界面弹"要不要导入这张图的参数"。
         *
         * 它只影响提示词与参数，**不会自动开始生成**：导入完成后仍要用户自己点生成，
         * 因为同 Seed 同参数很容易再产出一张几乎一样的图，而那是要花 Anlas 的。
         */
        val metadataCandidate: MetadataCandidate? = null,
        /**
         * 非空表示分辨率处于自定义模式。
         *
         * 与 `params.size/outputSize` 是"编辑器状态 vs 请求事实"的关系：
         * 前者决定输入框显示什么，后者决定发什么。只有 `applyCustomResolution` 一处写后者，
         * 因此不会出现两套尺寸互相打架。
         */
        val customResolution: CustomResolution? = null,
        /** 自定义尺寸算不出来时的原因（边长越界、面积超限），显示在输入框下方。 */
        val customResolutionError: ResolutionPlanner.Reason? = null,
    ) {
        /** 自定义模式下的规划结果；预设模式或算不出来时为 null。 */
        val resolutionPlan: ResolutionPlanner.Plan?
            get() = customResolution?.let { custom ->
                (ResolutionPlanner.plan(
                    PixelSize(custom.width, custom.height),
                    exactOutput = custom.exactOutput,
                ) as? ResolutionPlanner.Result.Success)?.plan
            }

        /** 待导入的元数据 + 当前勾选。 */
        data class MetadataCandidate(
            val metadata: NovelAiImageMetadata,
            val selection: MetadataImportSelection = MetadataImportSelection(),
        )

        val profile: ModelProfile get() = ModelCatalog.profileOf(params.model)

        val randomizerCombinations: Long get() = PromptRandomizer.estimateCombinations(promptTemplate)

        val promptLength: Int get() = promptTemplate.length

        /** 提示词软上限只提示、不阻断（规划书 3.1）。 */
        val exceedsPromptSoftLimit: Boolean get() = promptLength > profile.promptSoftLimitChars

        /** 真正会阻止提交的问题；提示词过长不在其中。 */
        val blockingViolations: List<ParamViolation>
            get() = profile.validate(params).filterNot { it is ParamViolation.PromptTooLong }

        val canGenerate: Boolean get() = !inFlight && !referenceBusy && promptTemplate.isNotBlank()

        /** 本次会用的全部参考图（图生图起点 + Precise Reference + Vibe + 重绘蒙版）。 */
        val allReferences: List<ReferenceImage>
            get() = listOfNotNull(referenceSource) + directorReferences + vibeReferences + inpaintReferences

        /** 当前模型是否支持 Vibe Transfer（目前仅 V4.5）。 */
        val supportsVibeTransfer: Boolean get() = profile.supportsVibeTransfer

        /** 当前模型是否支持局部重绘（属于 Image2Img 家族，四个模型都支持）。 */
        val supportsInpaint: Boolean get() = profile.supportsInpaint

        /**
         * 局部重绘的蒙版。它同时也是"当前是否处于重绘模式"的判据 ——
         * 没有蒙版就没有可重画的区域，模式也就不成立。
         */
        val inpaintMask: ReferenceImage?
            get() = inpaintReferences.firstOrNull { it.role == ReferenceRole.INPAINT_MASK }

        /** 重绘的底图：就是图生图那张起点图（两者共用同一个角色）。 */
        val inpaintBase: ReferenceImage? get() = referenceSource

        /** 蒙版是否已经在编辑器里画过（用于界面提示与生成按钮的可用性）。 */
        val hasInpaintMask: Boolean get() = inpaintMask != null

        /** 剩余的 Vibe 槽位。 */

        val remainingVibeSlots: Int
            get() = (profile.maxVibeReferences - vibeReferences.size).coerceAtLeast(0)

        val mode: GenerationMode
            get() = when {
                // 重绘要求底图与蒙版同时在场：只有蒙版没有底图是残缺状态
                // （旧版本曾允许移除底图后蒙版残留），绝不能按 INPAINT 提交 ——
                // 那会发出"常规模型 ID + infill"的自相矛盾请求被服务端 400。
                hasInpaintMask && inpaintBase != null -> GenerationMode.INPAINT
                directorReferences.isNotEmpty() -> GenerationMode.PRECISE_REFERENCE
                referenceSource != null -> GenerationMode.IMG2IMG
                else -> GenerationMode.TXT2IMG
            }

        /** 当前模型是否支持 Precise Reference（目前仅 V4.5；V5 不支持）。 */
        val supportsDirectorReference: Boolean get() = profile.supportsDirectorReference

        /** 还能再加几张 Precise Reference。 */
        val remainingDirectorSlots: Int
            get() = (profile.maxDirectorReferences - directorReferences.size).coerceAtLeast(0)

        /**
         * 提交时会把起点图裁切到的尺寸。
         *
         * 对用户可见很重要：图生图的输出尺寸不是他自己选的，而是按源图比例定的，
         * 界面必须说清楚"提交的是 1216×832"，否则用户会以为出图尺寸出错了。
         */
        val referenceTargetSize: ImageSizePreset? get() = referenceSource?.let { params.size }
    }

    /**
     * 启动时恢复上次的参数与提示词，让用户不必每次重开都重调一遍。
     *
     * 读取是同步的：草稿是一个很小的首选项文件，与 [net.pocketnai.data.security.CredentialStore]
     * 的做法一致；异步读会让表单先闪一下出厂默认值，反而更糟。
     */
    private val _state = MutableStateFlow(restoreDraft())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun restoreDraft(): UiState {
        val draft = draftPreferences.load() ?: GenerationDraft.defaults()
        val restoredBase = draft.references.firstOrNull { it.role == ReferenceRole.IMG2IMG }
        // 只有蒙版没有底图的草稿是残缺状态（旧版本允许移除底图后蒙版残留，
        // 直接提交会被服务端 400）。恢复时自愈：蒙版失去底图就没有意义，
        // 丢掉它，而不是把它带进一次必然失败的生成。
        val restoredMasks = if (restoredBase != null) {
            draft.references.filter { it.role == ReferenceRole.INPAINT_MASK }
        } else {
            emptyList()
        }
        return UiState(
            params = draft.params,
            promptTemplate = draft.promptTemplate,
            negativeTemplate = draft.negativeTemplate,
            referenceSource = restoredBase,
            directorReferences = draft.references.filter { it.role == ReferenceRole.DIRECTOR },
            vibeReferences = draft.references.filter { it.role == ReferenceRole.VIBE },
            inpaintReferences = restoredMasks,
            customResolution = draft.customResolution,
        )
    }

    /**
     * 界面把"光标所在的标签"推到这里。
     *
     * 单独用一个 Flow 而不是塞进 `_state`：补全要等用户停手之后才发请求，
     * 防抖是"这个输入流怎么处理"的问题，放在这里比散在界面里更容易看清。
     */
    private val suggestionInput = MutableStateFlow("")

    /**
     * 当前参数的费用预估。
     *
     * 单独一条流而不是塞进 [UiState]：它是 `params + balance + 订阅等级` 的派生结果，
     * 由计算器算出来；放回状态里就得在每个改参数的入口都记得重算一遍。
     * 计算是纯函数，参数一变就重算，不发任何网络请求。
     *
     * 订阅等级除余额外还要看设置页的手动指定（[SettingsStore.subscriptionOverride]），
     * 因此这里是三路 combine —— 用户在设置里改了等级，生成按钮上的报价要立刻跟着变。
     */
    val costEstimate: StateFlow<GenerationCostEstimate> = combine(
        _state,
        accountBalanceRepository.state,
        settingsStore.subscriptionOverride,
    ) { state, balance, override ->
        // Vibe 是否已编码要查磁盘，挪到 IO 上做；一次最多查 4 个文件。
        withContext(Dispatchers.IO) {
            costCalculator.estimate(pricingContextOf(state, balance, override))
        }
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(COST_SUBSCRIPTION_TIMEOUT_MS),
            initialValue = costCalculator.estimate(
                pricingContextOf(
                    _state.value,
                    accountBalanceRepository.state.value,
                    settingsStore.subscriptionOverride.value,
                ),
            ),
        )

    /** 有效订阅状态：服务端读数 + 设置页手动指定。 */
    fun subscriptionStatusOf(balance: BalanceState): SubscriptionStatus {
        val known = balance.knownBalance
        return SubscriptionStatusResolver.resolve(
            rawTier = known?.rawTier,
            accountType = known?.accountType,
            expiresAtEpochSeconds = known?.expiresAtEpochSeconds,
            nowEpochSeconds = clock() / 1000L,
            override = settingsStore.subscriptionOverride.value,
        )
    }

    private fun pricingContextOf(
        state: UiState,
        balance: BalanceState,
        override: net.pocketnai.domain.billing.SubscriptionOverride,
    ): AnlasPricingContext {
        val known = balance.knownBalance
        val status = SubscriptionStatusResolver.resolve(
            rawTier = known?.rawTier,
            accountType = known?.accountType,
            expiresAtEpochSeconds = known?.expiresAtEpochSeconds,
            nowEpochSeconds = clock() / 1000L,
            override = override,
        )
        val vibes = state.vibeReferences
        val uncachedVibes = vibes.count { !repository.isVibeEncoded(it, state.params.model) }
        return AnlasPricingContext(
            params = state.params,
            subscriptionTier = status.tier,
            hasSubscription = status.subscribed,
            v5UsageLimit = known?.v5UsageLimit,
            generationKind = when {
                vibes.isNotEmpty() -> GenerationKind.VIBE_TRANSFER
                else -> when (state.mode) {
                    GenerationMode.TXT2IMG -> GenerationKind.TEXT_TO_IMAGE
                    GenerationMode.IMG2IMG -> GenerationKind.IMAGE_TO_IMAGE
                    GenerationMode.PRECISE_REFERENCE -> GenerationKind.PRECISE_REFERENCE
                    GenerationMode.INPAINT -> GenerationKind.INPAINT
                    GenerationMode.UPSCALE -> GenerationKind.TEXT_TO_IMAGE
                }
            },
            hasBaseImage = state.referenceSource != null,
            // 只有 Precise Reference 的参考图按张收费；图生图的起点图不额外收费。
            referenceImageCount = if (state.mode == GenerationMode.PRECISE_REFERENCE) {
                state.directorReferences.size
            } else {
                0
            },
            vibeCount = vibes.size,
            uncachedVibeCount = uncachedVibes,
            // 官方式子把 Strength 当作基础费用的乘数；图生图/重绘用的是请求里真正发出去的那个值：
            // 图生图是顶层 strength（默认 0.7），重绘是 inpaintImg2ImgStrength（默认 1.0）。
            strengthMultiplier = if (state.referenceSource != null) {
                state.referenceSource?.strength ?: when (state.mode) {
                    GenerationMode.INPAINT -> state.profile.defaultInpaintStrength
                    else -> state.profile.defaultImg2ImgStrength
                }
            } else {
                1.0
            },
            pricingPolicyVersion = costCalculator.policyVersion,
        )
    }

    init {
        // 余额状态镜像到 UiState：界面要显示它，但它的来源是账户级的仓库。
        viewModelScope.launch {
            accountBalanceRepository.state.collect { balance ->
                _state.update { it.copy(balanceState = balance) }
            }
        }

        // 自动记住当前编辑状态。用防抖是因为拖动 Steps / Guidance 滑杆会连续产生几十次
        // 状态变化，逐次写盘既无必要也会卡顿。
        viewModelScope.launch {
            _state
                .map { current ->
                    GenerationDraft(
                        params = current.params,
                        promptTemplate = current.promptTemplate,
                        negativeTemplate = current.negativeTemplate,
                        references = current.allReferences,
                        customResolution = current.customResolution,
                    )
                }
                .distinctUntilChanged()
                .debounce(DRAFT_SAVE_DEBOUNCE_MS)
                .collect(draftPreferences::save)
        }

        viewModelScope.launch {
            draftStore.pending.collect { pending ->
                if (pending == null) return@collect
                val reused = draftStore.consume() ?: return@collect
                // 复用参数只填充编辑区，绝不自动开始生成（规划书 4.3）。
                // 参考图一并恢复：只带回 Strength 却没有图，那个参数没有任何意义。
                _state.update {
                    it.copy(
                        params = reused.params,
                        // 复用历史参数时把分辨率模式一并还原：尺寸不在预设里说明
                        // 那次用的是自定义尺寸，编辑器要回到自定义模式而不是悄悄换成预设。
                        customResolution = reused.params.let { params ->
                            val modelProfile = ModelCatalog.profileOf(params.model)
                            if (modelProfile.tierOf(params.size) == null) {
                                CustomResolution(
                                    width = params.targetSize.width,
                                    height = params.targetSize.height,
                                    exactOutput = params.needsCrop,
                                )
                            } else {
                                null
                            }
                        },
                        customResolutionError = null,
                        promptTemplate = reused.params.prompt,
                        negativeTemplate = reused.params.negativePrompt,
                        // 外部整体替换：让编辑框重建一次（见 textRevision 的说明）。
                        textRevision = it.textRevision + 1,
                        referenceSource = reused.img2imgSource,
                        directorReferences = reused.referencesOf(ReferenceRole.DIRECTOR),
                        vibeReferences = reused.referencesOf(ReferenceRole.VIBE),
                        inpaintReferences = reused.referencesOf(ReferenceRole.INPAINT_MASK),
                        error = null,
                        modelSwitchNotice = null,
                        referenceError = null,
                    )
                }
            }
        }

        // 标签补全：停手一小会儿才发请求，期间又改了字就整批作废重来。
        // 模型一并纳入键，因为建议来自服务端的模型相关标签表，换模型后不该继续显示旧建议。
        viewModelScope.launch {
            combine(
                suggestionInput,
                _state.map { it.params.model }.distinctUntilChanged(),
            ) { fragment, model -> fragment to model }
                .debounce(SUGGEST_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { (fragment, model) -> refreshSuggestions(fragment, model) }
        }
    }

    fun onPromptChange(value: String) {
        _state.update { it.copy(promptTemplate = value, params = it.params.copy(prompt = value)) }
    }

    fun onNegativePromptChange(value: String) {
        _state.update {
            it.copy(negativeTemplate = value, params = it.params.copy(negativePrompt = value))
        }
    }

    fun addCharacter() {
        _state.update { current ->
            if (current.params.characters.size >= CharacterPrompt.MAX_COUNT) return@update current
            current.copy(params = current.params.copy(characters = current.params.characters + CharacterPrompt()))
        }
    }

    fun removeCharacter(index: Int) {
        _state.update { current ->
            val list = current.params.characters.toMutableList()
            if (index in list.indices) {
                list.removeAt(index)
                current.copy(params = current.params.copy(characters = list))
            } else current
        }
    }

    fun updateCharacter(index: Int, character: CharacterPrompt) {
        _state.update { current ->
            val list = current.params.characters.toMutableList()
            if (index in list.indices) {
                list[index] = character
                current.copy(params = current.params.copy(characters = list))
            } else current
        }
    }

    /**
     * 由输入框之外的来源整体替换某一侧的提示词（目前是收藏夹填充）。
     *
     * 与打字分开：[textRevision] 是输入框"重建自己"的唯一依据，只有这种整体替换
     * 才该 +1。走 [onPromptChange] 的话输入框不会知道文本换了，界面会停在旧内容上；
     * 反过来在打字路径上 +1，输入框就会每次按键都重建、光标永远被丢到文末。
     */
    fun replacePromptText(target: PromptTarget, text: String) {
        _state.update { current ->
            val replaced = when (target) {
                PromptTarget.POSITIVE ->
                    current.copy(promptTemplate = text, params = current.params.copy(prompt = text))

                PromptTarget.NEGATIVE ->
                    current.copy(
                        negativeTemplate = text,
                        params = current.params.copy(negativePrompt = text),
                    )
            }
            replaced.copy(textRevision = current.textRevision + 1)
        }
    }

    /**
     * 切换模型。
     *
     * 规划书 8.4 的要求是“不静默替换后立即生成”，因此这里的处理是：
     * 保留仍然合法的参数，只把目标模型不支持的采样器/调度组合换回目标模型默认值，
     * 并把这次替换明确告知用户。
     */
    fun onModelSelected(model: ImageModel) {
        _state.update { current ->
            if (current.params.model == model) return@update current
            val target = ModelCatalog.profileOf(model)
            val migrated = GenerationParams.migrateTo(target, current.params)
            val notice = when {
                migrated.sampler != current.params.sampler ->
                    "已切换到 ${target.displayName}：原采样器不适用，已改用 ${target.defaultSampler.displayName}"

                migrated.noiseSchedule != current.params.noiseSchedule ->
                    "已切换到 ${target.displayName}：原 Noise Schedule 不适用，已改用 ${target.defaultNoiseSchedule.displayName}"

                migrated.size != current.params.size ->
                    "已切换到 ${target.displayName}：原尺寸不适用，已改用 ${target.defaultSize.label}"

                else -> null
            }
            current.copy(params = migrated, modelSwitchNotice = notice, error = null)
        }
    }

    /** 切换 Resolution 档位（Normal / Large），保持当前横竖方向不变。 */
    fun onResolutionTierChange(tier: ResolutionTier) {
        _state.update { current ->
            val next = current.profile.sizeInTier(tier, current.params.size)
                ?: return@update current
            // 选档位即退出自定义模式：同一时刻只能有一套尺寸是"生效的"。
            current.copy(
                params = current.params.copy(size = next, outputSize = null),
                customResolution = null,
                customResolutionError = null,
            )
        }
    }

    /** 切换横竖方方向，保持当前档位不变。 */
    fun onOrientationChange(orientation: ImageOrientation) {
        _state.update { current ->
            val tier = current.profile.tierOf(current.params.size) ?: ResolutionTier.NORMAL
            val next = current.profile.sizeFor(tier, orientation) ?: return@update current
            current.copy(
                params = current.params.copy(size = next, outputSize = null),
                customResolution = null,
                customResolutionError = null,
            )
        }
    }

    /**
     * 进入自定义分辨率模式。
     *
     * 输入框用当前生效的**最终尺寸**做初值（而不是画布尺寸）：用户刚在预设里选了
     * 1216×832，切到自定义时看到的应该是 1216×832，而不是被对齐过的别的值。
     */
    fun onCustomResolutionEnabled() {
        _state.update { current ->
            val target = current.params.targetSize
            applyCustomResolution(
                current.copy(
                    customResolution = CustomResolution(
                        width = target.width,
                        height = target.height,
                        // 已经处于裁切状态时保持"精确"勾选，避免切一下模式就丢了意图。
                        exactOutput = current.params.needsCrop,
                    ),
                    customResolutionError = null,
                ),
            )
        }
    }

    /** 退出自定义模式，回到面积最接近的预设。 */
    fun onCustomResolutionDisabled() {
        _state.update { current ->
            val profile = current.profile
            val area = current.params.targetSize.totalPixels
            val nearest = profile.sizeOptions
                .minByOrNull { kotlin.math.abs(it.size.totalPixels - area) }
                ?.size
                ?: profile.defaultSize
            current.copy(
                customResolution = null,
                customResolutionError = null,
                params = current.params.copy(size = nearest, outputSize = null),
            )
        }
    }

    fun onCustomWidthChange(width: Int) {
        _state.update { current ->
            val custom = current.customResolution ?: return@update current
            applyCustomResolution(current.copy(customResolution = custom.copy(width = width)))
        }
    }

    fun onCustomHeightChange(height: Int) {
        _state.update { current ->
            val custom = current.customResolution ?: return@update current
            applyCustomResolution(current.copy(customResolution = custom.copy(height = height)))
        }
    }

    /** 交换宽高：竖屏 ↔ 横屏。 */
    fun onCustomSwapDimensions() {
        _state.update { current ->
            val custom = current.customResolution ?: return@update current
            applyCustomResolution(
                current.copy(
                    customResolution = custom.copy(width = custom.height, height = custom.width),
                ),
            )
        }
    }

    /** 切换"精确最终尺寸"。 */
    fun onCustomExactOutputChange(exactOutput: Boolean) {
        _state.update { current ->
            val custom = current.customResolution ?: return@update current
            applyCustomResolution(
                current.copy(customResolution = custom.copy(exactOutput = exactOutput)),
            )
        }
    }

    /**
     * 把自定义目标尺寸换算成画布尺寸与最终尺寸，写进 `params`。
     *
     * **唯一的写入点**：`params.size` 永远是 64 对齐的合法画布（请求与计费都用它），
     * `params.outputSize` 只在需要裁切时才有值。算不出来时**保留上一次的合法尺寸**
     * 并把原因记下来显示 —— 输入框被清空或敲成 12 时不该发非法请求，
     * 也不该把用户正在输入的数值重置掉。
     */
    private fun applyCustomResolution(current: UiState): UiState {
        val custom = current.customResolution ?: return current
        return when (
            val result = ResolutionPlanner.plan(
                PixelSize(custom.width, custom.height),
                exactOutput = custom.exactOutput,
            )
        ) {
            is ResolutionPlanner.Result.Success -> {
                val plan = result.plan
                current.copy(
                    params = current.params.copy(
                        size = ImageSizePreset(plan.generationSize.width, plan.generationSize.height),
                        outputSize = if (plan.needsCrop) {
                            ImageSizePreset(plan.targetSize.width, plan.targetSize.height)
                        } else {
                            null
                        },
                    ),
                    customResolutionError = null,
                )
            }

            is ResolutionPlanner.Result.Failure -> current.copy(customResolutionError = result.reason)
        }
    }

    fun onSampleCountChange(count: Int) {
        _state.update { current ->
            val max = current.profile.maxSampleCount
            current.copy(params = current.params.copy(sampleCount = count.coerceIn(1, max)))
        }
    }

    fun onStepsChange(steps: Int) {
        _state.update { current ->
            current.copy(params = current.params.copy(steps = steps.coerceIn(1, 50)))
        }
    }

    fun onGuidanceChange(guidance: Double) {
        _state.update { current ->
            current.copy(
                params = current.params.copy(guidance = current.profile.guidanceRange.clamp(guidance)),
            )
        }
    }

    fun onCfgRescaleChange(value: Double) {
        _state.update { current ->
            current.copy(
                params = current.params.copy(
                    cfgRescale = current.profile.cfgRescaleRange.clamp(value),
                ),
            )
        }
    }

    /** 采样器变化时必须同时收敛 Noise Schedule，避免留下无效组合（规划书 3.1）。 */
    fun onSamplerSelected(sampler: Sampler) {
        _state.update { current ->
            val schedules = current.profile.availableSchedulesFor(sampler)
            if (schedules.isEmpty()) return@update current
            val schedule = when {
                current.params.noiseSchedule in schedules -> current.params.noiseSchedule
                else -> current.profile.defaultScheduleFor(sampler) ?: schedules.first()
            }
            current.copy(params = current.params.copy(sampler = sampler, noiseSchedule = schedule))
        }
    }

    fun onNoiseScheduleSelected(schedule: NoiseSchedule) {
        _state.update { current ->
            if (schedule !in current.profile.availableSchedulesFor(current.params.sampler)) {
                return@update current
            }
            current.copy(params = current.params.copy(noiseSchedule = schedule))
        }
    }

    fun onSeedModeChange(mode: SeedMode) {
        _state.update { it.copy(params = it.params.copy(seedMode = mode)) }
    }

    fun onSeedChange(value: String) {
        // 只接受数字，空串按 0 处理；超出 uint32 时夹取，不静默丢弃用户输入。
        val parsed = value.filter { it.isDigit() }.take(10).toLongOrNull() ?: 0L
        _state.update {
            it.copy(
                params = it.params.copy(
                    baseSeed = parsed.coerceIn(0L, GenerationParams.MAX_SEED),
                ),
            )
        }
    }

    fun onRandomizeSeed() {
        _state.update {
            it.copy(params = it.params.copy(baseSeed = kotlin.random.Random.nextLong(0L, GenerationParams.MAX_SEED + 1)))
        }
    }

    fun onQualityTagsChange(option: QualityTagsOption) {
        _state.update { it.copy(params = it.params.copy(qualityTags = option)) }
    }

    fun onUndesiredContentPresetChange(index: Int) {
        _state.update { it.copy(params = it.params.copy(undesiredContentPresetIndex = index)) }
    }

    /** 规划书 3.2：恢复模型默认值只影响当前新建任务，不动历史。 */
    fun restoreModelDefaults() {
        _state.update { current ->
            current.copy(
                params = GenerationParams.defaultsFor(current.profile).copy(
                    prompt = current.promptTemplate,
                    negativePrompt = current.negativeTemplate,
                ),
                modelSwitchNotice = null,
            )
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissModelSwitchNotice() {
        _state.update { it.copy(modelSwitchNotice = null) }
    }

    // ---- 参考图（Image2Img） ----

    /**
     * 导入一张起点图。
     *
     * 导入成功后会把 Resolution 设成与源图比例对应的官方预设 ——
     * 图生图的输出尺寸由源图决定，而不是让用户另外选一个比例再让图去迁就它。
     * 用官方预设而不是任意合法尺寸是有意的：预设是计费与观感都确定的那些尺寸
     * （Normal 档位 832×1216 / 1216×832 / 1024×1024），自己算一个尺寸等于引入一个未知量。
     */
    fun onReferencePicked(source: ReferenceSource) {
        if (_state.value.referenceBusy) return
        _state.update { it.copy(referenceBusy = true, referenceError = null) }

        viewModelScope.launch {
            // 元数据必须在**归一化之前**从原始文件读：参考图落盘时会重新编码 PNG，
            // 那时 tEXt 文本块已经没了（见 ImageMetadataInspector 的说明）。
            val probed = metadataInspector.inspect(source)

            when (val outcome = referenceImporter.import(source)) {
                is Outcome.Success -> {
                    val prepared = outcome.value
                    _state.update { current ->
                        val profile = current.profile
                        current.copy(
                            referenceBusy = false,
                            referenceSource = ReferenceImage.img2imgSource(
                                prepared = prepared,
                                strength = profile.defaultImg2ImgStrength,
                                id = idGenerator(),
                                createdAt = clock(),
                            ),
                            // 图生图与 Precise Reference 互斥：挂上起点图就清掉另一类。
                            directorReferences = emptyList(),
                            // 换底图必须作废旧蒙版：蒙版是按上一张底图的尺寸裁的，留着就会错位。
                            inpaintReferences = emptyList(),
                            // 自定义模式下尺寸由用户说了算：底图会被 Cover 到那个画布，
                            // 而不是反过来把用户的尺寸改掉。
                            params = if (current.customResolution != null) {
                                current.params
                            } else {
                                current.params.copy(
                                    size = sizeForSource(profile, prepared),
                                    outputSize = null,
                                )
                            },
                            // 只有确认是 NovelAI 图片才弹导入框：普通照片弹一个"没有元数据"是噪音。
                            metadataCandidate = (probed as? MetadataProbeResult.Found)
                                ?.let { UiState.MetadataCandidate(it.metadata) },
                        )
                    }
                }

                is Outcome.Failure -> _state.update {
                    it.copy(referenceBusy = false, referenceError = outcome.error)
                }
            }
        }
    }

    /** 用户在导入对话框里改了勾选。 */
    fun onMetadataSelectionChange(selection: MetadataImportSelection) {
        _state.update { current ->
            val candidate = current.metadataCandidate ?: return@update current
            current.copy(metadataCandidate = candidate.copy(selection = selection))
        }
    }

    /**
     * 当前勾选会导入什么、会跳过什么。
     *
     * 纯函数、按需计算（不塞进 UiState）：勾选一变界面就要立刻显示新的预览，
     * 而把它放进状态流意味着每次勾选写一遍状态、多一次重组。
     */
    fun currentMetadataPlan(): MetadataImportPlan? {
        val current = _state.value
        val candidate = current.metadataCandidate ?: return null
        return MetadataImportPlanner.plan(candidate.metadata, current.params, candidate.selection)
    }

    /** 关闭导入对话框：图片照常留作参考图，只是不导入参数。 */
    fun dismissMetadataImport() {
        _state.update { it.copy(metadataCandidate = null) }
    }

    /**
     * 按当前勾选把元数据写进编辑区。
     *
     * **一次性原子应用**：模型、尺寸、采样器之间互相约束（采样器可能不被新模型支持、
     * 尺寸必须在合法区间内），分几次 update 会出现短暂的非法中间态，
     * 草稿也会被连续写盘好几次。
     *
     * 导入后**不生成、不聚焦生成按钮**：由用户自己决定什么时候点。
     */
    fun confirmMetadataImport() {
        val current = _state.value
        val candidate = current.metadataCandidate ?: return
        val plan = MetadataImportPlanner.plan(candidate.metadata, current.params, candidate.selection)

        _state.update { state ->
            val targetModel = plan.model ?: state.params.model
            val profile = ModelCatalog.profileOf(targetModel)
            val updated = state.params.copy(
                model = targetModel,
                prompt = plan.prompt ?: state.params.prompt,
                negativePrompt = plan.negativePrompt ?: state.params.negativePrompt,
                size = plan.size ?: state.params.size,
                steps = plan.steps ?: state.params.steps,
                guidance = plan.guidance ?: state.params.guidance,
                cfgRescale = plan.cfgRescale ?: state.params.cfgRescale,
                sampler = plan.sampler ?: state.params.sampler,
                noiseSchedule = plan.noiseSchedule ?: state.params.noiseSchedule,
                qualityTags = plan.qualityTags ?: state.params.qualityTags,
                seedMode = if (plan.seed != null) SeedMode.FIXED else state.params.seedMode,
                baseSeed = plan.seed ?: state.params.baseSeed,
                // 独立角色：整组替换而不是合并 —— 元数据里的角色词与位置是一套整体，
                // 与当前草稿里的角色混在一起会得到谁也没画过的组合。
                characters = plan.characters ?: state.params.characters,
            )
            val normalized = profile.normalize(updated)
            state.copy(
                // 采样器/调度/尺寸都按新模型再过一遍，避免导入出的组合服务端不认。
                params = normalized,
                // 元数据里的尺寸不在预设里但合法时，把它当作自定义尺寸接住：
                // 那张图本来就是按这个尺寸生成的，因此不需要裁切（exactOutput = false）。
                customResolution = plan.size?.let { size ->
                    if (profile.tierOf(size) == null) {
                        CustomResolution(size.width, size.height, exactOutput = false)
                    } else {
                        null
                    }
                },
                customResolutionError = null,
                // 模板与提交值保持一致：勾了"实际提示词"时官方网页也是把展开值写进输入框
                // （模板原文不再保留），否则那个勾选项在我们这里等于没有作用 ——
                // 提交时提示词会从模板重新抽选一遍。
                promptTemplate = plan.prompt ?: state.promptTemplate,
                // 负向词同理，而且**必须写模板**：提交时 params.negativePrompt 会被
                // 模板重新解析一遍覆盖掉，只写 params 的话导入的负面词会被静默丢弃。
                negativeTemplate = plan.negativePrompt ?: state.negativeTemplate,
                // 外部整体替换：让编辑框重建一次（见 textRevision 的说明）。
                textRevision = state.textRevision + 1,
                metadataCandidate = null,
                // 导入的参数是"这张图的",与上一次的模型切换提示无关。
                modelSwitchNotice = null,
            )
        }
    }

    /**
     * 移除图生图起点图。
     *
     * 必须**连坐作废蒙版**：蒙版是"在这张底图上涂的"，底图没了，重绘模式也就没了。
     * 否则状态里只剩蒙版，模式判定仍停在 INPAINT，提交时会发出一个没有底图的
     * infill 请求被服务端拒绝 —— 2026-09-14 用户实测踩中
     * （`Model ... doesn't support action infill`，见技术决策记录第十八节）。
     */
    fun onRemoveReference() {
        _state.update {
            it.copy(
                referenceSource = null,
                inpaintReferences = emptyList(),
                referenceError = null,
            )
        }
        _inpaintStrokes.value = emptyList()
        _inpaintDilation.value = 0f
    }

    // ---- 局部重绘 ----

    /**
     * 笔画与扩张量放在 ViewModel 而不是编辑器内部：编辑器是一个独立页面，
     * 来回导航之间笔画必须留着（否则用户从编辑器返回生成页再进去，涂的东西就没了）。
     * 它们**不进草稿**：草稿只记渲染好的蒙版文件（见 [onInpaintMaskRendered]）。
     */
    private val _inpaintStrokes = MutableStateFlow<List<MaskStroke>>(emptyList())
    val inpaintStrokes: StateFlow<List<MaskStroke>> = _inpaintStrokes.asStateFlow()

    private val _inpaintDilation = MutableStateFlow(0f)
    val inpaintDilation: StateFlow<Float> = _inpaintDilation.asStateFlow()

    /**
     * 选一张图作为**局部重绘的底图**。
     *
     * 导入时就按当前输出尺寸裁切（Cover），因此编辑器显示的就是提交图，
     * 手指涂的坐标与提交的像素严格对应 —— 这是 Precise Reference 那次"忘了补齐黑边"的教训。
     */
    fun onInpaintBasePicked(source: ReferenceSource) {
        val current = _state.value
        if (current.referenceBusy) return

        _state.update { it.copy(referenceBusy = true, referenceError = null) }
        viewModelScope.launch {
            val target = ImageTransform.Cover(current.params.size.toPixelSize())
            when (val outcome = referenceImporter.import(source, target)) {
                is Outcome.Success -> {
                    val prepared = outcome.value
                    _state.update { state ->
                        state.copy(
                            referenceBusy = false,
                            // 重绘的默认强度是 1.0（蒙版内完全重画），与图生图的 0.7 不同；
                            // 这是官方重绘面板的滑块初值（技术决策记录第十七节）。
                            referenceSource = ReferenceImage.img2imgSource(
                                prepared = prepared,
                                strength = state.profile.defaultInpaintStrength,
                                id = idGenerator(),
                                createdAt = clock(),
                            ),
                            // 重绘与参考条件互斥（服务端拒绝混用）；
                            // 同时作废上一次的蒙版 —— 新底图需要重新涂。
                            directorReferences = emptyList(),
                            vibeReferences = emptyList(),
                            inpaintReferences = emptyList(),
                        )
                    }
                    _inpaintStrokes.value = emptyList()
                }

                is Outcome.Failure -> _state.update {
                    it.copy(referenceBusy = false, referenceError = outcome.error)
                }
            }
        }
    }

    /** 编辑器把渲染好的蒙版交回来。它就代表"当前处于重绘模式"。 */
    fun onInpaintMaskRendered(mask: ReferenceImage) {
        _state.update { current ->
            current.copy(
                inpaintReferences = listOf(mask),
                directorReferences = emptyList(),
                vibeReferences = emptyList(),
            )
        }
    }

    fun onInpaintMaskCleared() {
        _state.update { it.copy(inpaintReferences = emptyList()) }
        _inpaintStrokes.value = emptyList()
        _inpaintDilation.value = 0f
    }

    fun onInpaintStrokesChange(strokes: List<MaskStroke>) {
        _inpaintStrokes.value = strokes
    }

    fun onInpaintDilationChange(value: Float) {
        _inpaintDilation.value = value.coerceIn(
            MaskImageProcessor.DILATION_RANGE.start,
            MaskImageProcessor.DILATION_RANGE.endInclusive,
        )
    }

    // ---- Precise Reference ----

    /**
     * 添加一张 Precise Reference。
     *
     * 导入时**就补齐黑边**（[ImageTransform.Letterbox] 到官方要求的三种画布之一），
     * 因此缩略图看到的就是实际提交的那张图；提交时再套一次同样的 Letterbox 是恒等变换。
     */
    fun onDirectorReferencePicked(source: ReferenceSource) {
        val current = _state.value
        if (current.referenceBusy) return
        if (!current.supportsDirectorReference) {
            _state.update {
                it.copy(referenceError = AppError.of(ErrorCode.INVALID_PARAMS, detail = "当前模型不支持 Precise Reference"))
            }
            return
        }
        if (current.remainingDirectorSlots <= 0) return

        _state.update { it.copy(referenceBusy = true, referenceError = null) }
        viewModelScope.launch {
            when (
                val outcome = referenceImporter.import(
                    source = source,
                    // 导入时就按官方要求补齐黑边：缩略图与提交图因此是同一张。
                    transform = ImageTransform.DirectorCanvas,
                )
            ) {
                is Outcome.Success -> {
                    val prepared = outcome.value
                    _state.update { state ->
                        val profile = state.profile
                        val ordinal = state.directorReferences.size
                        val reference = ReferenceImage(
                            id = idGenerator(),
                            role = ReferenceRole.DIRECTOR,
                            ordinal = ordinal,
                            relativePath = prepared.relativePath,
                            width = prepared.width,
                            height = prepared.height,
                            byteSize = prepared.byteSize,
                            sha256 = prepared.sha256,
                            createdAt = clock(),
                            strength = profile.defaultDirectorStrength,
                            secondaryStrength = profile.defaultDirectorFidelity,
                            informationExtracted = profile.defaultDirectorInfoExtracted,
                            directorKind = DirectorReferenceKind.CHARACTER,
                        )
                        state.copy(
                            referenceBusy = false,
                            // 与图生图互斥。
                            referenceSource = null,
                            // 与服务端约束一致：Vibe 与 Precise Reference 不能混用；
                            // 重绘同样不能与参考条件混用。
                            vibeReferences = emptyList(),
                            inpaintReferences = emptyList(),
                            directorReferences = state.directorReferences + reference,
                        )
                    }
                }

                is Outcome.Failure -> _state.update {
                    it.copy(referenceBusy = false, referenceError = outcome.error)
                }
            }
        }
    }

    /**
     * 添加一张 Vibe 参考图。
     *
     * 导入时**不做变换**：官方是把原图交给 `encode-vibe`，编码发生在提交生成时
     * （见 `GenerationRepository.vibeBase64For`），产物会按内容寻址缓存起来。
     */
    fun onVibeReferencePicked(source: ReferenceSource) {
        val current = _state.value
        if (current.referenceBusy) return
        if (!current.supportsVibeTransfer) {
            _state.update {
                it.copy(referenceError = AppError.of(ErrorCode.INVALID_PARAMS, detail = "当前模型不支持 Vibe Transfer"))
            }
            return
        }
        if (current.remainingVibeSlots <= 0) return

        _state.update { it.copy(referenceBusy = true, referenceError = null) }
        viewModelScope.launch {
            when (val outcome = referenceImporter.import(source)) {
                is Outcome.Success -> {
                    val prepared = outcome.value
                    _state.update { state ->
                        val profile = state.profile
                        val reference = ReferenceImage(
                            id = idGenerator(),
                            role = ReferenceRole.VIBE,
                            ordinal = state.vibeReferences.size,
                            relativePath = prepared.relativePath,
                            width = prepared.width,
                            height = prepared.height,
                            byteSize = prepared.byteSize,
                            sha256 = prepared.sha256,
                            createdAt = clock(),
                            strength = VIBE_DEFAULT_STRENGTH,
                            informationExtracted = VIBE_DEFAULT_INFORMATION,
                        )
                        state.copy(
                            referenceBusy = false,
                            // 服务端明确不允许混用：`cannot mix reference and director_reference
                            // at the same time`。因此挂上 Vibe 就清掉 Precise Reference 与重绘蒙版。
                            directorReferences = emptyList(),
                            inpaintReferences = emptyList(),
                            vibeReferences = state.vibeReferences + reference,
                        )
                    }
                }

                is Outcome.Failure -> _state.update {
                    it.copy(referenceBusy = false, referenceError = outcome.error)
                }
            }
        }
    }

    fun onVibeReferenceRemoved(id: String) {
        _state.update { current ->
            current.copy(
                vibeReferences = current.vibeReferences
                    .filterNot { it.id == id }
                    // 顺序号必须连续：它直接对应请求数组的下标。
                    .mapIndexed { index, reference -> reference.copy(ordinal = index) },
            )
        }
    }

    fun onVibeStrengthChanged(id: String, value: Double) {
        updateVibeReference(id) { it.copy(strength = clampedDirector(value)) }
    }

    fun onVibeInformationExtractedChanged(id: String, value: Double) {
        updateVibeReference(id) { it.copy(informationExtracted = clampedDirector(value)) }
    }

    /**
     * 把所有 Vibe 的 Reference Strength 等比例缩放到合计 1.0。
     *
     * 官方文档的经验值：*"generally, the strengths of all your vibes should add up to 1.0 or less"*，
     * 网页端也有一个 Normalize Reference Strengths 开关做同样的事。
     * 只有一张时直接设为 1.0；合计本就不超过 1.0 时**不动**（避免用户只是想微调却被改）。
     */
    fun normalizeVibeStrengths() {
        _state.update { current ->
            val vibes = current.vibeReferences
            if (vibes.isEmpty()) return@update current
            val total = vibes.sumOf { it.strength ?: 0.0 }
            if (total <= NORMALIZE_TARGET || total <= 0.0) return@update current
            val factor = NORMALIZE_TARGET / total
            current.copy(
                vibeReferences = vibes.map { reference ->
                    val scaled = (reference.strength ?: 0.0) * factor
                    reference.copy(
                        strength = current.profile.img2imgStrengthRange.clamp(scaled),
                    )
                },
            )
        }
    }

    private fun updateVibeReference(id: String, transform: (ReferenceImage) -> ReferenceImage) {
        _state.update { current ->
            current.copy(
                vibeReferences = current.vibeReferences.map { reference ->
                    if (reference.id == id) transform(reference) else reference
                },
            )
        }
    }

    fun onDirectorReferenceRemoved(id: String) {
        _state.update { current ->
            current.copy(
                directorReferences = current.directorReferences
                    .filterNot { it.id == id }
                    // 顺序号必须连续：它直接对应请求数组的下标。
                    .mapIndexed { index, reference -> reference.copy(ordinal = index) },
            )
        }
    }

    fun onDirectorKindChanged(id: String, kind: DirectorReferenceKind) {
        updateDirectorReference(id) { it.copy(directorKind = kind) }
    }

    fun onDirectorStrengthChanged(id: String, value: Double) {
        updateDirectorReference(id) { reference ->
            reference.copy(strength = clampedDirector(value))
        }
    }

    fun onDirectorFidelityChanged(id: String, value: Double) {
        updateDirectorReference(id) { reference ->
            reference.copy(secondaryStrength = clampedDirector(value))
        }
    }

    fun onDirectorInformationExtractedChanged(id: String, value: Double) {
        updateDirectorReference(id) { reference ->
            reference.copy(informationExtracted = clampedDirector(value))
        }
    }

    private fun updateDirectorReference(id: String, transform: (ReferenceImage) -> ReferenceImage) {
        _state.update { current ->
            current.copy(
                directorReferences = current.directorReferences.map { reference ->
                    if (reference.id == id) transform(reference) else reference
                },
            )
        }
    }

    private fun clampedDirector(value: Double): Double =
        _state.value.profile.directorReferenceRange.clamp(value)

    fun onImg2imgStrengthChange(strength: Double) {
        _state.update { current ->
            val source = current.referenceSource ?: return@update current
            current.copy(
                referenceSource = source.copy(
                    strength = current.profile.img2imgStrengthRange.clamp(strength),
                ),
            )
        }
    }

    fun dismissReferenceError() {
        _state.update { it.copy(referenceError = null) }
    }

    /**
     * 源图比例 → 官方预设尺寸。
     *
     * 取 Normal 档位：它是官方默认档位，也是素材最省的档位。
     * 用户之后仍可以在界面上改成 Large 或换方向，那时 [GenerationRequest] 会带着新的尺寸，
     * 起点图在提交时按新尺寸重新裁切（见 `ReferenceImageProcessor.encodeBase64`）。
     */
    private fun sizeForSource(
        profile: ModelProfile,
        prepared: PreparedReference,
    ): ImageSizePreset {
        val orientation = ImageSizePreset(prepared.width, prepared.height).orientation
        return profile.sizeFor(ResolutionTier.NORMAL, orientation) ?: profile.defaultSize
    }

    /**
     * 光标移动后由界面调用，告诉这里当前可以补全的标签片段。
     *
     * 传空串表示没有可补全的内容（光标不在标签里、或用户正在选择一段文字），
     * 此时立刻清掉建议，不等防抖 —— 建议留在屏幕上却和输入框对不上更让人困惑。
     */
    fun onSuggestionFragmentChange(fragment: String) {
        if (fragment.isBlank()) {
            suggestionInput.value = ""
            clearSuggestions()
            return
        }
        suggestionInput.value = fragment
    }

    fun clearSuggestions() {
        if (_state.value.suggestions.isEmpty() && _state.value.suggestionQuery.isEmpty()) return
        _state.update { it.copy(suggestions = emptyList(), suggestionQuery = "") }
    }

    private suspend fun refreshSuggestions(fragment: String, model: ImageModel) {
        val query = fragment.trim()
        if (query.length < MIN_SUGGESTION_FRAGMENT_CHARS) {
            clearSuggestions()
            return
        }

        val tags = tagSuggestionSource.suggest(query, model)
            .distinct()
            .take(MAX_SUGGESTIONS)
        _state.update { it.copy(suggestions = tags, suggestionQuery = fragment) }
    }

    fun generate() {
        val snapshot = _state.value
        if (!snapshot.canGenerate) return
        if (snapshot.blockingViolations.isNotEmpty()) {
            _state.update { it.copy(error = AppError.of(ErrorCode.INVALID_PARAMS)) }
            return
        }
        // 本地预估余额不足时只弹一次非阻断确认，用户点"仍然生成"就继续。
        // 永远不因为本地算出来的数字禁用生成 —— 额度不足的权威结论是服务端 402。
        if (needsCostConfirmation(snapshot)) {
            _state.update { it.copy(pendingCostConfirmation = true) }
            return
        }
        startGeneration()
    }

    /** 用户在"当前显示余额可能不足"的确认框里选择继续。 */
    fun confirmCostAndGenerate() {
        _state.update { it.copy(pendingCostConfirmation = false) }
        startGeneration()
    }

    fun dismissCostConfirmation() {
        _state.update { it.copy(pendingCostConfirmation = false) }
    }

    /**
     * 是否需要那一次确认。
     *
     * 三个前提缺一不可：算得出具体金额、余额新鲜、预估超过余额。
     * 余额过期时不用旧数字吓唬用户（规划 §11.3）。
     */
    private fun needsCostConfirmation(snapshot: UiState): Boolean {
        val estimate = costEstimate.value
        if (estimate !is GenerationCostEstimate.EstimatedAnlas) return false
        val balance = snapshot.balanceState.knownBalance ?: return false
        if (!accountBalanceRepository.isFresh(balance)) return false
        return estimate.batchTotal > balance.totalAnlas
    }

    private fun startGeneration() {
        val snapshot = _state.value
        if (!snapshot.canGenerate) return

        // 规划书 8.3：先固定本次 Randomizer 展开结果，再作为请求快照提交。
        val resolvedPrompt = repository.resolvePromptTemplate(snapshot.promptTemplate)
        val resolvedNegative = repository.resolvePromptTemplate(snapshot.negativeTemplate)
        val frozen = snapshot.params.copy(
            prompt = resolvedPrompt,
            negativePrompt = resolvedNegative,
        )
        val request = GenerationRequest(
            params = frozen,
            mode = snapshot.mode,
            references = snapshot.allReferences,
        )

        // 生成前快照：**不等待余额请求**，只取内存里已有的值（规划 §8.1）。
        val beforeBalance = accountBalanceRepository.latestOrNull()
        val billingSession = beforeBalance?.let {
            ObservedBalanceChange.DEFAULT_MAX_PRE_SNAPSHOT_AGE_MS.let { maxAge ->
                BillingSession(
                    before = it,
                    beforeAgeMillis = clock() - it.fetchedAtMillis,
                    maxPreSnapshotAgeMillis = maxAge,
                    expectedImageCount = frozen.sampleCount,
                )
            }
        }

        _state.update {
            it.copy(
                inFlight = true,
                completedImages = 0,
                error = null,
                modelSwitchNotice = null,
                lastObservedChange = null,
            )
        }

        viewModelScope.launch {
            repository.generate(request, promptTemplate = snapshot.promptTemplate)
                .collect { event ->
                    when (event) {
                        is GenerationEvent.Started -> Unit

                        is GenerationEvent.Final -> _state.update {
                            it.copy(completedImages = it.completedImages + 1)
                        }

                        is GenerationEvent.ItemError -> _state.update {
                            it.copy(error = event.error)
                        }

                        is GenerationEvent.FatalError -> {
                            // 失败时预览文件已被仓库清掉，界面状态同步清，避免显示空白卡片。
                            previewStore.clear(event.generationId)
                            _state.update { it.copy(inFlight = false, error = event.error) }
                            when (event.error.code) {
                                // 402 之后刷新余额：那是"余额不足"最权威的信号。
                                ErrorCode.INSUFFICIENT_ANLAS -> refreshBalanceAfterGeneration(
                                    session = billingSession,
                                    completionConfirmed = false,
                                )
                                // 超时/断流时服务端可能已经计费，但这里不做断言，
                                // 只把结果标成"待确认"。
                                ErrorCode.TIMEOUT_UNCERTAIN -> refreshBalanceAfterGeneration(
                                    session = billingSession,
                                    completionConfirmed = false,
                                )

                                else -> Unit
                            }
                        }

                        is GenerationEvent.Completed -> {
                            previewStore.clear(event.generationId)
                            _state.update { it.copy(inFlight = false) }
                            refreshBalanceAfterGeneration(
                                session = billingSession,
                                completionConfirmed = event.status != GenerationStatus.FAILED,
                                generationId = event.generationId,
                                description = formatGenerationDescription(_state.value),
                            )
                        }

                        // 流式中间预览：只更新预览落点，不碰生成状态本身。
                        is GenerationEvent.Intermediate -> previewStore.update(
                            GenerationPreviewStore.Preview(
                                generationId = event.generationId,
                                path = event.previewPath,
                                progress = event.progress,
                            ),
                        )
                    }
                }
        }
    }

    private fun formatGenerationDescription(state: UiState): String {
        val model = state.params.model.displayName
        val size = "${state.params.size.width}×${state.params.size.height}"
        val mode = when {
            state.inpaintMask != null -> "局部重绘"
            state.referenceSource != null -> "图生图"
            else -> "纯文生图"
        }
        val count = if (state.params.sampleCount > 1) " (${state.params.sampleCount}张)" else ""
        return "$model $size $mode$count"
    }

    /**
     * 生成结束后强制刷新一次余额，并给出"本次观察到的余额变化"（规划 §8.3）。
     *
     * 刷新失败或缺少前快照时不给确定数字 —— 宁可说"待确认"。
     * 余额刷新无论如何都不会覆盖生成错误。
     */
    private suspend fun refreshBalanceAfterGeneration(
        session: BillingSession?,
        completionConfirmed: Boolean,
        generationId: String? = null,
        description: String? = null,
    ) {
        val outcome = accountBalanceRepository.refresh(
            reason = BalanceRefreshReason.GENERATION_COMPLETED,
            force = true,
        )
        val after = (outcome as? Outcome.Success)?.value ?: return
        val change = ObservedBalanceChange.calculate(
            before = session?.before,
            after = after,
            expectedImageCount = session?.expectedImageCount ?: 0,
            generationCompleted = completionConfirmed,
            beforeAgeMillis = session?.beforeAgeMillis,
            maxPreSnapshotAgeMillis = session?.maxPreSnapshotAgeMillis
                ?: ObservedBalanceChange.DEFAULT_MAX_PRE_SNAPSHOT_AGE_MS,
        )
        _state.update { it.copy(lastObservedChange = change) }

        if (completionConfirmed && anlasLedgerRepository != null) {
            val spent = when (change) {
                is ObservedBalanceChange.AnlasDecreased -> change.totalSpent
                is ObservedBalanceChange.V5AllowanceChanged -> change.anlasSpent
                is ObservedBalanceChange.NoVisibleChange -> 0L
                is ObservedBalanceChange.Ambiguous -> {
                    if (session?.before != null && session.before.totalAnlas >= after.totalAnlas) {
                        session.before.totalAnlas - after.totalAnlas
                    } else 0L
                }
            }
            val v5Delta = (change as? ObservedBalanceChange.V5AllowanceChanged)?.let {
                (it.beforePercent - it.afterPercent).coerceAtLeast(0)
            }
            val accountFp = credentialStore?.hint()?.fingerprint ?: "default"
            anlasLedgerRepository.record(
                accountFingerprint = accountFp,
                actionType = if (description?.contains("局部重绘") == true) "INPAINT" else "GENERATE",
                anlasSpent = spent,
                v5AllowanceDelta = v5Delta,
                balanceAfter = after.totalAnlas,
                description = description ?: "图片生成",
                generationId = generationId,
            )
        }
    }

    fun dismissObservedChange() {
        _state.update { it.copy(lastObservedChange = null) }
    }

    /**
     * 用户手动刷新余额（规划 §7.3）。
     *
     * 手动刷新无视缓存；失败只影响余额区域，不会覆盖生成错误，也不会阻止生成。
     */
    fun refreshBalance() {
        viewModelScope.launch {
            accountBalanceRepository.refresh(
                reason = BalanceRefreshReason.USER_REQUESTED,
                force = true,
            )
        }
    }

    /** 应用回到前台时的刷新：仓库内部会判断缓存是否还新鲜，不会反复打接口。 */
    fun refreshBalanceOnForeground() {
        viewModelScope.launch {
            accountBalanceRepository.refresh(reason = BalanceRefreshReason.APP_FOREGROUND)
        }
    }

    private companion object {
        /** 状态停止变化多久之后落盘。太长会在被杀进程时丢改动，太短则拖滑杆时频繁写。 */
        const val DRAFT_SAVE_DEBOUNCE_MS = 600L

        /** 停手多久之后去取标签建议。太短会在连续输入时发出一串利用率很低的请求。 */
        const val SUGGEST_DEBOUNCE_MS = 350L

        /** 少于两个字符不给建议：一个字母能匹配到的标签太多，建议没有参考价值。 */
        const val MIN_SUGGESTION_FRAGMENT_CHARS = 2

        /** 与服务端网页版一致，一次最多展示 5 条。 */
        const val MAX_SUGGESTIONS = 5

        /** 费用预估流的订阅超时，与画廊保持一致。 */
        const val COST_SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /**
         * Vibe 两个滑块的默认值。
         *
         * ⚠️ 这是**我们的选择，不是官方默认值**：官方文档只给了"所有 vibe 的强度建议合计
         * 不超过 1.0"这条经验值，网页端的具体初值无法读取（用户确认过也看不到）。
         */
        const val VIBE_DEFAULT_STRENGTH = 0.6
        const val VIBE_DEFAULT_INFORMATION = 1.0

        /** 归一化目标：官方建议的合计上限。 */
        const val NORMALIZE_TARGET = 1.0
    }

    /** 一次生成前后的余额核对上下文（规划 §8.2）。 */
    private data class BillingSession(
        val before: SubscriptionBalance,
        val beforeAgeMillis: Long,
        val maxPreSnapshotAgeMillis: Long,
        val expectedImageCount: Int,
    )
}
