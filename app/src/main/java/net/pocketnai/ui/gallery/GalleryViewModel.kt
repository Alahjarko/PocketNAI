package net.pocketnai.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.GenerationSummary

/**
 * 瀑布流画廊状态。
 *
 * 删除采用规划书 4.4 的两步语义：先标记删除（界面立刻消失、可撤销），
 * 撤销窗口结束后才真正清理文件与数据库记录。
 */
class GalleryViewModel(
    private val repository: GenerationRepository,
) : ViewModel() {

    val items: StateFlow<List<GalleryItem>> = repository.observeGallery()
        .stateIn(
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

    private val _undoGenerationId = MutableStateFlow<String?>(null)

    /** 非空时界面展示“已删除，可撤销”。 */
    val undoGenerationId: StateFlow<String?> = _undoGenerationId.asStateFlow()

    private var purgeJob: Job? = null

    fun deleteGeneration(generationId: String) {
        viewModelScope.launch {
            repository.markDeleted(generationId)
            _undoGenerationId.value = generationId
            purgeJob?.cancel()
            purgeJob = viewModelScope.launch {
                delay(UNDO_WINDOW_MS)
                repository.purgeDeleted()
                _undoGenerationId.value = null
            }
        }
    }

    fun undoDelete() {
        val generationId = _undoGenerationId.value ?: return
        purgeJob?.cancel()
        viewModelScope.launch {
            repository.undoDelete(generationId)
            _undoGenerationId.value = null
        }
    }

    /** Snackbar 消失后调用，避免撤销提示一直挂着。 */
    fun clearUndo() {
        _undoGenerationId.value = null
    }

    private companion object {
        const val UNDO_WINDOW_MS = 5_000L
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
