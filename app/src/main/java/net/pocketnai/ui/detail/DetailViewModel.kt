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
import net.pocketnai.data.repo.FavoriteImageRepository
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.prompt.PromptTitle
import net.pocketnai.ui.state.GenerationDraftStore

/**
 * 详情页状态：展示单张图片的完整参数，并提供收藏 / 保存 / 删除 / 复制 / 复用参数。
 */
class DetailViewModel(
    private val repository: GenerationRepository,
    private val exporter: MediaStoreExporter,
    private val draftStore: GenerationDraftStore,
    private val favorites: FavoriteImageRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val image: GeneratedImage? = null,
        val generation: Generation? = null,
        val errorCode: ErrorCode? = null,
        val savedToGallery: Boolean = false,
        val deleted: Boolean = false,
        /** 这一张是否已收藏。它来自 `favorite_images`，不是图片记录自己的字段。 */
        val favorite: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var favoriteJob: Job? = null

    fun load(imageId: String) {
        viewModelScope.launch {
            val detail = repository.loadDetail(imageId)
            // 用 copy 而不是整体赋值：收藏状态由下面那条流并行写入，整体赋值会把它冲掉。
            _state.update {
                it.copy(
                    loading = false,
                    image = detail?.image,
                    generation = detail?.generation,
                    errorCode = if (detail == null) ErrorCode.UNKNOWN else null,
                )
            }
        }

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
                    _state.value = state.copy(savedToGallery = true, errorCode = null)
                }

                is Outcome.Failure -> _state.value = state.copy(errorCode = outcome.error.code)
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
            _state.value = _state.value.copy(deleted = true)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(errorCode = null)
    }
}
