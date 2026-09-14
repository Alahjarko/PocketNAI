package net.pocketnai.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pocketnai.data.repo.PromptFavoriteRepository
import net.pocketnai.domain.model.PromptFavorite
import net.pocketnai.domain.model.PromptFavoriteKind
import net.pocketnai.domain.model.PromptTarget
import net.pocketnai.domain.prompt.PromptComposition

/**
 * 收藏提示词的状态。
 *
 * 遍历与筛选都放在 [UiState] 的派生属性里，而不是把过滤结果也存成状态 ——
 * 后者需要在每次增删改后手工同步，很容易漏掉一处导致列表不刷新。
 */
class PromptFavoritesViewModel(
    private val repository: PromptFavoriteRepository,
) : ViewModel() {

    data class UiState(
        val favorites: List<PromptFavorite> = emptyList(),
        val query: String = "",
        val kind: PromptFavoriteKind = PromptFavoriteKind.PROMPT,
        /** 提示：例如"这条已经在收藏夹里了"。 */
        val notice: String? = null,
    ) {
        val promptCount: Int get() = favorites.count { it.kind == PromptFavoriteKind.PROMPT }

        val tagCount: Int get() = favorites.count { it.kind == PromptFavoriteKind.TAG }

        /** 当前筛选下要展示的条目，按分组归拢（分组按名称排序，组内保持"最近用过优先"）。 */
        val grouped: List<Pair<String, List<PromptFavorite>>>
            get() {
                val keyword = query.trim()
                return favorites
                    .asSequence()
                    .filter { it.kind == kind }
                    .filter { favorite ->
                        keyword.isEmpty() ||
                            favorite.name.contains(keyword, ignoreCase = true) ||
                            favorite.content.contains(keyword, ignoreCase = true) ||
                            favorite.category.contains(keyword, ignoreCase = true)
                    }
                    .toList()
                    .groupBy { it.category }
                    .toList()
                    .sortedBy { (category, _) -> category }
            }

        val isEmpty: Boolean get() = grouped.isEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeFavorites().collect { favorites ->
                _state.update { it.copy(favorites = favorites) }
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    /** 切换"提示词 / 标签"时清掉搜索词，避免切过去看到空列表却不知道是被搜索过滤了。 */
    fun onKindChange(kind: PromptFavoriteKind) {
        _state.update { it.copy(kind = kind, query = "") }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * 保存一条收藏。[content] 为空或是重复条目时不写入，并给出提示。
     * 名称留空时自动从内容里取。
     */
    fun save(
        kind: PromptFavoriteKind,
        content: String,
        name: String,
        category: String,
        target: PromptTarget,
    ) {
        viewModelScope.launch {
            if (content.isBlank()) {
                _state.update { it.copy(notice = "没有可收藏的内容") }
                return@launch
            }
            val inserted = repository.add(
                kind = kind,
                content = content,
                name = name.ifBlank { PromptComposition.defaultName(content).orEmpty() },
                category = category,
                target = target,
            )
            _state.update {
                it.copy(
                    notice = if (inserted) null else "这条已经在收藏夹里了",
                    kind = kind,
                )
            }
        }
    }

    fun markUsed(favorite: PromptFavorite) {
        viewModelScope.launch { repository.markUsed(favorite.id) }
    }

    fun delete(favorite: PromptFavorite) {
        viewModelScope.launch { repository.delete(favorite.id) }
    }
}
