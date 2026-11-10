package net.pocketnai.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.pocketnai.data.repo.FavoriteImageRepository
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.domain.model.GalleryFilter
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.GallerySearch
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.GenerationSummary

/**
 * 瀑布流画廊状态。
 *
 * 删除采用规划书 4.4 的两步语义：先标记删除（界面立刻消失、可撤销），
 * 撤销窗口结束后才真正清理文件与数据库记录。
 *
 * 筛选与收藏都在这里汇合：收藏来自另一张表（`favorite_images`），
 * 与画廊列表 `combine` 后再套一层纯函数过滤（[GallerySearch]）。
 * 三个来源任意一个变化都会重新产出列表，因此"收藏后立刻出现在'仅看收藏'里"
 * 不需要任何手工刷新。
 */
class GalleryViewModel(
    private val repository: GenerationRepository,
    private val favorites: FavoriteImageRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(GalleryFilter.None)

    /** 当前筛选条件。界面用它决定筛选控件的高亮状态与"无结果"的措辞。 */
    val filter: StateFlow<GalleryFilter> = _filter.asStateFlow()
    private val limit = MutableStateFlow(120)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val page = combine(_filter, limit) { f, n -> f to n }.flatMapLatest { (f, n) -> repository.observeGalleryWindow(f, n) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), GenerationRepository.GalleryPage(GalleryFilter.None, 120, emptyList(), false))
    val hasMore = page.map { it.hasMore }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)
    fun loadMore() {
        val p = page.value
        if (p.hasMore && p.limit == limit.value && p.filter == _filter.value) limit.value = (limit.value + 120).coerceAtMost(Int.MAX_VALUE - 1)
    }
    suspend fun fullOrder() = repository.galleryOrder(_filter.value)

    /** 过滤后的瀑布流内容。[GalleryItem.favorite] 已按收藏表补齐。 */
    val items: StateFlow<List<GalleryItem>> = combine(
        page.map { it.items },
        favorites.observeFavoriteIds(),
        _filter,
    ) { gallery, favoriteIds, filter ->
        gallery.asSequence()
            .map { item -> item.copy(favorite = item.imageId in favoriteIds) }
            .filter { GallerySearch.matches(it, filter) }
            .toList()
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = emptyList(),
    )

    internal val motionSlots = GalleryMotionSlots()

    /** Includes finished jobs so a visible placeholder survives until its image is decoded. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val generations: StateFlow<List<GenerationSummary>> = limit.flatMapLatest { repository.observeRecentGenerations(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    private val _undoGenerationIds = MutableStateFlow<Set<String>>(emptySet())

    /** 非空时界面展示“已删除，可撤销”。保持单条引用兼容。 */
    val undoGenerationId: StateFlow<String?> = _undoGenerationIds
        .map { it.firstOrNull() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = null,
        )

    val undoGenerationIds: StateFlow<Set<String>> = _undoGenerationIds.asStateFlow()

    // ---- 多选模式 ----

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedImageIds = MutableStateFlow<Set<String>>(emptySet())
    private var limitBeforeSelection = 120
    val selectedImageIds: StateFlow<Set<String>> = _selectedImageIds.asStateFlow()

    fun enterSelectionMode(initialSelectedId: String? = null) {
        limitBeforeSelection = limit.value
        limit.value = Int.MAX_VALUE
        _isSelectionMode.value = true
        _selectedImageIds.value = if (initialSelectedId != null) setOf(initialSelectedId) else emptySet()
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedImageIds.value = emptySet()
        limit.value = limitBeforeSelection
    }

    fun toggleSelect(imageId: String) {
        _selectedImageIds.update { current ->
            if (imageId in current) current - imageId else current + imageId
        }
    }

    fun selectAll(imageIds: Collection<String>) {
        _selectedImageIds.value = imageIds.toSet()
    }
    fun selectAllMatching() {
        viewModelScope.launch { _selectedImageIds.value = repository.galleryOrder(_filter.value).toSet() }
    }

    fun deselectAll() {
        _selectedImageIds.value = emptySet()
    }

    fun batchToggleFavorite(items: List<GalleryItem>) {
        val selectedIds = _selectedImageIds.value
        val filter = _filter.value
        viewModelScope.launch {
            val targets = repository.galleryOrder(filter).filter { it in selectedIds }
            val favoriteIds = favorites.observeFavoriteIds().first()
            val targetFavoriteState = !targets.all { it in favoriteIds }
            targets.forEach { id ->
                favorites.setFavorite(id, favorite = targetFavoriteState)
            }
        }
    }

    fun batchDelete(items: List<GalleryItem>) {
        val selectedIds = _selectedImageIds.value
        val filter = _filter.value
        exitSelectionMode()
        viewModelScope.launch {
            val targets = repository.observeGalleryWindow(filter, Int.MAX_VALUE).first().items.filter { it.imageId in selectedIds }
            val generationIds = targets.map { it.generationId }.toSet()
            generationIds.forEach { repository.markDeleted(it) }
            _undoGenerationIds.value = generationIds
            purgeJob?.cancel()
            purgeJob = viewModelScope.launch {
                delay(UNDO_WINDOW_MS)
                repository.purgeDeleted()
                _undoGenerationIds.value = emptySet()
            }
        }
    }

    private var purgeJob: Job? = null

    // ---- 筛选 ----

    fun onQueryChange(query: String) { limit.value = 120; _filter.update { it.copy(query = query) } }

    /** 空集合表示全部模型，集合内按 OR 匹配。 */
    fun onModelsChange(modelApiIds: Set<String>) { limit.value = 120; _filter.update { it.copy(modelApiIds = modelApiIds) } }

    /** [mode] 为 null 表示"全部模式"。 */
    fun onModeChange(mode: GenerationMode?) { limit.value = 120; _filter.update { it.copy(mode = mode) } }

    fun onFavoritesOnlyChange(enabled: Boolean) =
        _filter.update { limit.value = 120; it.copy(favoritesOnly = enabled) }

    fun clearFilter() {
        limit.value = 120
        _filter.value = GalleryFilter.None
    }

    // ---- 收藏 ----

    /**
     * 切换收藏状态。
     *
     * 由界面传入当前状态而不是在仓库里再查一次：画廊里那张卡片的状态就是用户
     * 刚看到的那个，拿它取反不会出现"界面说没收藏、数据库说收藏了"的错位。
     */
    fun toggleFavorite(item: GalleryItem) {
        viewModelScope.launch {
            favorites.setFavorite(item.imageId, favorite = !item.favorite)
        }
    }

    // ---- 删除 ----

    fun deleteGeneration(generationId: String) {
        viewModelScope.launch {
            repository.markDeleted(generationId)
            _undoGenerationIds.value = setOf(generationId)
            purgeJob?.cancel()
            purgeJob = viewModelScope.launch {
                delay(UNDO_WINDOW_MS)
                repository.purgeDeleted()
                _undoGenerationIds.value = emptySet()
            }
        }
    }

    fun undoDelete() {
        val ids = _undoGenerationIds.value
        if (ids.isEmpty()) return
        purgeJob?.cancel()
        viewModelScope.launch {
            ids.forEach { repository.undoDelete(it) }
            _undoGenerationIds.value = emptySet()
        }
    }

    /** Snackbar 消失后调用，避免撤销提示一直挂着。 */
    fun clearUndo() {
        _undoGenerationIds.value = emptySet()
    }

    private companion object {
        const val UNDO_WINDOW_MS = 5_000L
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
