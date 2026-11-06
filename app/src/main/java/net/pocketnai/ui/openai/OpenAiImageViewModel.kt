package net.pocketnai.ui.openai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pocketnai.core.Outcome
import net.pocketnai.data.openai.*
import net.pocketnai.data.repo.GenerationEvent
import net.pocketnai.domain.image.ReferenceImageImporter
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.openai.*
import net.pocketnai.ui.generate.GenerateViewModel
import net.pocketnai.ui.state.GenerationDraftStore
import java.util.UUID

@OptIn(FlowPreview::class)
class OpenAiImageViewModel(
    private val store: OpenAiImageSettings,
    private val repository: OpenAiImageRepository,
    private val importer: ReferenceImageImporter,
    private val nai: GenerateViewModel,
    private val draftStore: GenerationDraftStore,
    private val awaitStartup: suspend () -> Unit = {},
) : ViewModel() {
    data class State(
        val draft: OpenAiImageDraft,
        val activeTasks: Int = 0,
        val importing: Boolean = false,
        val message: String? = null,
        val completed: Int = 0,
        val revision: Int = 0,
        val submissionRevision: Int = 0,
    ) { val busy: Boolean get() = activeTasks > 0 }
    private val _state = MutableStateFlow(State(store.loadDraft()))
    val state = _state.asStateFlow()
    init {
        viewModelScope.launch {
            state.map { it.draft }.distinctUntilChanged().debounce(400).collect(store::saveDraft)
        }
        viewModelScope.launch {
            draftStore.pending.collect { pending ->
                val params = pending?.openAi ?: return@collect
                draftStore.consume()
                store.setProvider(ImageProvider.OPEN_AI)
                restore(params, pending.references)
            }
        }
    }
    fun update(params: OpenAiImageParams) { _state.update { it.copy(draft = it.draft.copy(params = params), message = null) } }
    fun restore(params: OpenAiImageParams, references: List<ReferenceImage>) {
        _state.update { it.copy(draft = OpenAiImageDraft(params, references), revision = it.revision + 1, message = null) }
    }
    fun removeReference(id: String) { _state.update { it.copy(draft = it.draft.copy(references = it.draft.references.filterNot { ref -> ref.id == id })) } }
    fun addReference(source: ReferenceSource) = addReferences(listOf(source))
    fun addReferences(sources: List<ReferenceSource>) {
        val current = _state.value
        if (current.importing || sources.isEmpty()) return
        val remaining = 16 - current.draft.references.size
        if (remaining <= 0) { _state.update { it.copy(message = "最多添加16张参考图") }; return }
        _state.update { it.copy(importing = true, message = null) }
        viewModelScope.launch {
            try {
                for (source in sources.take(remaining)) {
                    when (val result = importer.import(source)) {
                        is Outcome.Success -> _state.update { state ->
                            val ref = ReferenceImage.img2imgSource(result.value, 1.0, UUID.randomUUID().toString(), System.currentTimeMillis())
                            state.copy(draft = state.draft.copy(references = (state.draft.references + ref).distinctBy { it.sha256 }))
                        }
                        is Outcome.Failure -> _state.update { it.copy(message = "部分参考图无法读取，请重新选择") }
                    }
                }
            } catch (_: Exception) { _state.update { it.copy(message = "参考图无法读取") } }
            finally { _state.update { it.copy(importing = false) } }
        }
    }
    fun generate() {
        val current = _state.value
        if (current.importing || store.provider.value != ImageProvider.OPEN_AI) return
        current.draft.params.validate(current.draft.references.size)?.let { error -> _state.update { it.copy(message = error) }; return }
        if (current.draft.params.isAnimated && !store.gifEnabled.value) {
            _state.update { it.copy(message = "请先在生图服务中开启 GIF 制作（实验）") }; return
        }
        if (!store.hasKey.value) { _state.update { it.copy(message = "请在设置 → 生图服务中保存 OpenAI API 密钥") }; return }
        if (!current.busy && !nai.reserveExternalGeneration()) { _state.update { it.copy(message = "另一个图片任务正在执行，请等待完成") }; return }
        val frozen = current.draft.copy(references = current.draft.references.toList())
        store.saveDraft(frozen)
        _state.update { it.copy(activeTasks = it.activeTasks + 1, submissionRevision = it.submissionRevision + 1, message = null) }
        viewModelScope.launch {
            try {
                // Navigation and Activity recreation cannot cancel or repeat a potentially billable POST.
                withContext(NonCancellable) {
                    awaitStartup()
                    repository.generate(frozen).collect { event -> when (event) {
                        is GenerationEvent.Final -> _state.update { it.copy(completed = it.completed + 1) }
                        is GenerationEvent.FatalError -> _state.update { it.copy(message = event.error.detail ?: "OpenAI 生成未完成，未重试") }
                        is GenerationEvent.ItemError -> _state.update { it.copy(message = event.error.detail) }
                        else -> Unit
                    } }
                }
            } catch (_: Exception) { _state.update { it.copy(message = "生成未完成，请核对画廊；没有自动重试") } }
            finally {
                _state.update { it.copy(activeTasks = (it.activeTasks - 1).coerceAtLeast(0)) }
                if (!_state.value.busy) nai.releaseExternalGeneration()
            }
        }
    }
}
