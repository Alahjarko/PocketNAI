package net.pocketnai.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.export.MediaStoreExporter
import net.pocketnai.data.repo.AccountBalanceRepository
import net.pocketnai.data.repo.AnlasLedgerRepository
import net.pocketnai.data.repo.BalanceRefreshReason
import net.pocketnai.data.repo.FavoriteImageRepository
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.domain.billing.UpscaleCost
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.prompt.PromptTitle
import net.pocketnai.ui.state.GenerationDraftStore

/**
 * 详情页状态：展示一张图片的完整参数，并提供收藏 / 保存 / 删除 / 复制 / 复用参数。
 *
 * 2026-09-18 起支持**左右滑动切换图片**：[UiState.pagerIds] 是进入详情页那一刻
 * 画廊列表的顺序快照（[net.pocketnai.ui.state.GalleryOrderSnapshot]），每页详情按 id
 * 缓存在 [UiState.details] 里 —— 滑动时读缓存、不等数据库；顶栏与所有操作按钮
 * 作用于 [UiState.currentId] 指向的那一页。
 */
class DetailViewModel(
    private val repository: GenerationRepository,
    private val exporter: MediaStoreExporter,
    private val draftStore: GenerationDraftStore,
    private val favorites: FavoriteImageRepository,
    private val accountBalanceRepository: AccountBalanceRepository? = null,
    private val credentialStore: CredentialStore? = null,
    private val anlasLedgerRepository: AnlasLedgerRepository? = null,
) : ViewModel() {

    data class UiState(
        /** 滑动顺序；只有一张时就是 `listOf(那一张)`。 */
        val pagerIds: List<String> = emptyList(),
        /** 当前页。顶栏与操作按钮都以它为准。 */
        val currentId: String? = null,
        /** 已载入的详情缓存（id → 详情）。 */
        val details: Map<String, GenerationRepository.ImageDetail> = emptyMap(),
        /** 载入过但记录不存在的页：显示错误，而不是一直转圈。 */
        val failedIds: Set<String> = emptySet(),
        val errorCode: ErrorCode? = null,
        val savedToGallery: Boolean = false,
        val deleted: Boolean = false,
        /** 这一张是否已收藏。它来自 `favorite_images`，不是图片记录自己的字段。 */
        val favorite: Boolean = false,
        /** 是否正在进行图像超分放大。 */
        val upscaling: Boolean = false,
    ) {
        val detail: GenerationRepository.ImageDetail?
            get() = currentId?.let { details[it] }

        val image: GeneratedImage? get() = detail?.image

        val generation: Generation? get() = detail?.generation

        /** 当前页还在等数据库（既没载入成功，也没失败）。 */
        val loadingCurrent: Boolean
            get() = currentId != null && currentId !in details && currentId !in failedIds
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var favoriteJob: Job? = null
    private val inFlight = mutableSetOf<String>()

    /** 进入详情页时调用一次：定下滑动顺序并载入当前页。 */
    fun bind(imageId: String, order: List<String>) {
        val pagerIds = if (imageId in order) order else listOf(imageId)
        _state.update { it.copy(pagerIds = pagerIds, currentId = imageId) }
        ensureLoaded(imageId)
        observeFavorite(imageId)
    }

    /** 滑到另一页：换当前页、重置"已保存"提示、按需载入并重新订阅收藏状态。 */
    fun selectPage(imageId: String) {
        if (_state.value.currentId == imageId) return
        _state.update { it.copy(currentId = imageId, savedToGallery = false, errorCode = null) }
        ensureLoaded(imageId)
        observeFavorite(imageId)
    }

    /** 载入某一页的详情；已有缓存、已失败或正在载入时什么都不做。 */
    fun ensureLoaded(imageId: String) {
        val state = _state.value
        if (imageId in state.details || imageId in state.failedIds || imageId in inFlight) return
        inFlight += imageId
        viewModelScope.launch {
            val detail = repository.loadDetail(imageId)
            inFlight -= imageId
            _state.update {
                if (detail == null) {
                    it.copy(failedIds = it.failedIds + imageId)
                } else {
                    it.copy(details = it.details + (imageId to detail))
                }
            }
        }
    }

    private fun observeFavorite(imageId: String) {
        favoriteJob?.cancel()
        favoriteJob = viewModelScope.launch {
            favorites.observeIsFavorite(imageId).collect { favorite ->
                _state.update { it.copy(favorite = favorite) }
            }
        }
    }

    /**
     * 切换收藏。
     *
     * 取反用的是**界面当前显示的状态**：它就是用户刚看到的那个，
     * 再查一次数据库反而可能出现"界面说没收藏、数据库说收藏了"的错位。
     */
    fun toggleFavorite() {
        val image = _state.value.image ?: return
        val next = !_state.value.favorite
        viewModelScope.launch { favorites.setFavorite(image.id, favorite = next) }
    }

    /** 保存到系统相册：复制而不是移动，私有历史保持不变（规划书 4.4）。 */
    fun saveToSystemGallery() {
        val state = _state.value
        val image = state.image ?: return
        val generation = state.generation ?: return
        viewModelScope.launch {
            val source = repository.fileOfRelativePath(image.privateFilePath)
            val displayName = PromptTitle.exportFileName(
                title = generation.title,
                timestampMillis = generation.createdAt,
                ordinal = image.ordinal,
            )
            when (val outcome = exporter.export(source, displayName)) {
                is Outcome.Success -> {
                    repository.markExported(image.id, outcome.value.toString())
                    _state.update { it.copy(savedToGallery = true, errorCode = null) }
                }

                is Outcome.Failure -> _state.update { it.copy(errorCode = outcome.error.code) }
            }
        }
    }

    /**
     * 复用参数：交给生成页，不自动开始生成（规划书 4.3）。
     *
     * 一并带上 [Generation.mode] 与参考图 —— 图生图历史如果只带回参数、不带回起点图，
     * 那些参数（Strength）就没有任何意义。
     */
    fun reuseParams() {
        val generation = _state.value.generation ?: return
        draftStore.post(
            GenerationRequest(
                params = generation.params,
                mode = generation.mode,
                references = generation.references,
            ),
        )
    }

    fun deleteGeneration() {
        val generationId = _state.value.generation?.id ?: return
        viewModelScope.launch {
            repository.deleteImmediately(generationId)
            _state.update { it.copy(deleted = true) }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorCode = null) }
    }

    /**
     * 超分产物入库后：插到当前页**后面**并跳过去。
     *
     * 不替换整个顺序 —— 用户滑回上一张时应该还是原来那批图，
     * 只是中间多了一张"放大后的"。
     */
    fun showUpscaleResult(newImageId: String) {
        _state.update { state ->
            val index = state.pagerIds.indexOf(state.currentId)
            val pagerIds = if (index < 0) {
                state.pagerIds + newImageId
            } else {
                state.pagerIds.subList(0, index + 1) +
                    newImageId +
                    state.pagerIds.subList(index + 1, state.pagerIds.size)
            }
            state.copy(
                pagerIds = pagerIds,
                currentId = newImageId,
                savedToGallery = false,
                errorCode = null,
            )
        }
        ensureLoaded(newImageId)
        observeFavorite(newImageId)
    }

    fun upscaleImage(onComplete: (newImageId: String) -> Unit) {
        val image = _state.value.image ?: return
        if (_state.value.upscaling) return
        _state.update { it.copy(upscaling = true, errorCode = null) }
        val beforeBalance = accountBalanceRepository?.latestOrNull()
        val accountFp = credentialStore?.hint()?.fingerprint ?: "default"
        viewModelScope.launch {
            when (val outcome = repository.upscaleImage(image.id)) {
                is Outcome.Success -> {
                    _state.update { it.copy(upscaling = false) }
                    val refreshOutcome = accountBalanceRepository?.refresh(
                        reason = BalanceRefreshReason.GENERATION_COMPLETED,
                        force = true,
                    )
                    val afterBalance = (refreshOutcome as? Outcome.Success)?.value
                    val spent = if (beforeBalance != null && afterBalance != null) {
                        (beforeBalance.totalAnlas - afterBalance.totalAnlas).coerceAtLeast(0L)
                    } else 0L
                    anlasLedgerRepository?.record(
                        accountFingerprint = accountFp,
                        actionType = "UPSCALE",
                        anlasSpent = spent,
                        balanceAfter = afterBalance?.totalAnlas,
                        description = "图片高清放大 (${UpscaleCost.SCALE_FACTOR}x)",
                        generationId = outcome.value,
                    )
                    onComplete(outcome.value)
                }
                is Outcome.Failure -> {
                    _state.update { it.copy(upscaling = false, errorCode = outcome.error.code) }
                }
            }
        }
    }
}
