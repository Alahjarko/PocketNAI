package net.pocketnai.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pocketnai.data.repo.FavoriteImageRepository
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.domain.model.GalleryFilter
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.GallerySearch
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.GenerationSummary
import net.pocketnai.domain.model.ImageModel

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

    /** 过滤后的瀑布流内容。[GalleryItem.favorite] 已按收藏表补齐。 */
    val items: StateFlow<List<GalleryItem>> = combine(
        repository.observeGallery(),
        favorites.observeFavoriteIds(),
        _filter,
    ) { gallery, favoriteIds, filter ->
        gallery.asSequence()
            .map { item -> item.copy(favorite = item.imageId in favoriteIds) }
            .filter { GallerySearch.matches(it, filter) }
            .toList()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = emptyList(),
    )

    /** 还没有产出图片的任务，在瀑布流顶部显示为占位卡片。 */
    val generatingCards: StateFlow<List<GenerationSummary>> = repository.observeGenerations()
        .map { summaries ->
            summaries.filter { it.generation.status == GenerationStatus.GENERATING }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = emptyList(),
        )

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
    val selectedImageIds: StateFlow<Set<String>> = _selectedImageIds.asStateFlow()

    fun enterSelectionMode(initialSelectedId: String? = null) {
        _isSelectionMode.value = true
        _selectedImageIds.value = if (initialSelectedId != null) setOf(initialSelectedId) else emptySet()
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedImageIds.value = emptySet()
    }

    fun toggleSelect(imageId: String) {
        _selectedImageIds.update { current ->
            if (imageId in current) current - imageId else current + imageId
        }
    }

    fun selectAll(imageIds: Collection<String>) {
        _selectedImageIds.value = imageIds.toSet()
    }

    fun deselectAll() {
        _selectedImageIds.value = emptySet()
    }

    fun batchToggleFavorite(items: List<GalleryItem>) {
        val selectedIds = _selectedImageIds.value
        val targets = items.filter { it.imageId in selectedIds }
        if (targets.isEmpty()) return
        val allFavorited = targets.all { it.favorite }
        val targetFavoriteState = !allFavorited
        viewModelScope.launch {
            targets.forEach { item ->
                favorites.setFavorite(item.imageId, favorite = targetFavoriteState)
            }
        }
    }

    fun batchDelete(items: List<GalleryItem>) {
        val selectedIds = _selectedImageIds.value
        val targets = items.filter { it.imageId in selectedIds }
        if (targets.isEmpty()) return
        val generationIds = targets.map { it.generationId }.toSet()
        exitSelectionMode()
        viewModelScope.launch {
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

    fun onQueryChange(query: String) = _filter.update { it.copy(query = query) }

    /** [model] 为 null 表示"全部模型"。 */
    fun onModelChange(model: ImageModel?) = _filter.update { it.copy(model = model) }

    /** [mode] 为 null 表示"全部模式"。 */
    fun onModeChange(mode: GenerationMode?) = _filter.update { it.copy(mode = mode) }

    fun onFavoritesOnlyChange(enabled: Boolean) =
        _filter.update { it.copy(favoritesOnly = enabled) }

    fun clearFilter() {
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
