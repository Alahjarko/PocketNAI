package net.pocketnai.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.export.MediaStoreExporter
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.prompt.PromptTitle
import net.pocketnai.ui.state.GenerationDraftStore

/**
 * 详情页状态：展示单张图片的完整参数，并提供保存 / 删除 / 复制 / 复用参数。
 */
class DetailViewModel(
    private val repository: GenerationRepository,
    private val exporter: MediaStoreExporter,
    private val draftStore: GenerationDraftStore,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val image: GeneratedImage? = null,
        val generation: Generation? = null,
        val errorCode: ErrorCode? = null,
        val savedToGallery: Boolean = false,
        val deleted: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(imageId: String) {
        viewModelScope.launch {
            val detail = repository.loadDetail(imageId)
            _state.value = UiState(
                loading = false,
                image = detail?.image,
                generation = detail?.generation,
                errorCode = if (detail == null) ErrorCode.UNKNOWN else null,
            )
        }
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

    /** 复用参数：交给生成页，不自动开始生成（规划书 4.3）。 */
    fun reuseParams() {
        val params = _state.value.generation?.params ?: return
        draftStore.post(params)
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
