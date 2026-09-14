package net.pocketnai.ui.generate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.repo.GenerationEvent
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.data.settings.GenerationDraftPreferences
import net.pocketnai.domain.image.PreparedReference
import net.pocketnai.domain.image.ReferenceImageImporter
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.domain.model.GenerationDraft
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageOrientation
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ModelProfile
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.ParamViolation
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ResolutionTier
import net.pocketnai.domain.model.Sampler
import net.pocketnai.domain.model.SeedMode
import net.pocketnai.domain.prompt.PromptRandomizer
import net.pocketnai.domain.prompt.TagSuggestionSource
import net.pocketnai.ui.state.GenerationDraftStore
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
    private val draftPreferences: GenerationDraftPreferences,
    private val tagSuggestionSource: TagSuggestionSource,
    private val referenceImporter: ReferenceImageImporter,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    data class UiState(
        val params: GenerationParams,
        val promptTemplate: String = "",
        val negativeTemplate: String = "",
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
         * Image2Img 的起点图。非空即代表当前处于图生图模式 ——
         * 模式不是一个独立的开关，而是"有没有起点图"的结果，
         * 这样就不可能出现"选了图生图模式却没有图"这种自相矛盾的状态。
         */
        val referenceSource: ReferenceImage? = null,
        /** 正在导入参考图（解码 + 落盘）。期间不该重复触发导入。 */
        val referenceBusy: Boolean = false,
        /** 参考图导入失败的原因，与生成失败分开：它不影响已经写好的提示词与参数。 */
        val referenceError: AppError? = null,
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
    ) {
        val profile: ModelProfile get() = ModelCatalog.profileOf(params.model)

        val randomizerCombinations: Long get() = PromptRandomizer.estimateCombinations(promptTemplate)

        val promptLength: Int get() = promptTemplate.length

        /** 提示词软上限只提示、不阻断（规划书 3.1）。 */
        val exceedsPromptSoftLimit: Boolean get() = promptLength > profile.promptSoftLimitChars

        /** 真正会阻止提交的问题；提示词过长不在其中。 */
        val blockingViolations: List<ParamViolation>
            get() = profile.validate(params).filterNot { it is ParamViolation.PromptTooLong }

        val canGenerate: Boolean get() = !inFlight && !referenceBusy && promptTemplate.isNotBlank()

        val mode: GenerationMode
            get() = if (referenceSource != null) GenerationMode.IMG2IMG else GenerationMode.TXT2IMG

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
        return UiState(
            params = draft.params,
            promptTemplate = draft.promptTemplate,
            negativeTemplate = draft.negativeTemplate,
            referenceSource = draft.referenceSource,
        )
    }

    /**
     * 界面把"光标所在的标签"推到这里。
     *
     * 单独用一个 Flow 而不是塞进 `_state`：补全要等用户停手之后才发请求，
     * 防抖是"这个输入流怎么处理"的问题，放在这里比散在界面里更容易看清。
     */
    private val suggestionInput = MutableStateFlow("")

    init {
        // 自动记住当前编辑状态。用防抖是因为拖动 Steps / Guidance 滑杆会连续产生几十次
        // 状态变化，逐次写盘既无必要也会卡顿。
        viewModelScope.launch {
            _state
                .map { current ->
                    GenerationDraft(
                        params = current.params,
                        promptTemplate = current.promptTemplate,
                        negativeTemplate = current.negativeTemplate,
                        referenceSource = current.referenceSource,
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
                        promptTemplate = reused.params.prompt,
                        negativeTemplate = reused.params.negativePrompt,
                        referenceSource = reused.img2imgSource,
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
            current.copy(params = current.params.copy(size = next))
        }
    }

    /** 切换横竖方方向，保持当前档位不变。 */
    fun onOrientationChange(orientation: ImageOrientation) {
        _state.update { current ->
            val tier = current.profile.tierOf(current.params.size) ?: ResolutionTier.NORMAL
            val next = current.profile.sizeFor(tier, orientation) ?: return@update current
            current.copy(params = current.params.copy(size = next))
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
                            params = current.params.copy(size = sizeForSource(profile, prepared)),
                        )
                    }
                }

                is Outcome.Failure -> _state.update {
                    it.copy(referenceBusy = false, referenceError = outcome.error)
                }
            }
        }
    }

    fun onRemoveReference() {
        _state.update { it.copy(referenceSource = null, referenceError = null) }
    }

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
            references = listOfNotNull(snapshot.referenceSource),
        )

        _state.update {
            it.copy(inFlight = true, completedImages = 0, error = null, modelSwitchNotice = null)
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

                        is GenerationEvent.FatalError -> _state.update {
                            it.copy(inFlight = false, error = event.error)
                        }

                        is GenerationEvent.Completed -> _state.update {
                            it.copy(inFlight = false)
                        }

                        is GenerationEvent.Intermediate -> Unit
                    }
                }
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
    }
}
