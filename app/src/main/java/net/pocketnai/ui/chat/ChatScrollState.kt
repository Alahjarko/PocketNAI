package net.pocketnai.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

@Stable
internal class ChatScrollSession(val list: LazyListState, val following: MutableState<Boolean>)

/** 为最近回看的对话保留位置；流式更新和切换会话都不能擅自把用户拉回底部。 */
@Composable
internal fun rememberChatScrollSession(conversationId: String?): ChatScrollSession {
    val bookmarks = rememberSaveable { LinkedHashMap<String, IntArray>() }
    val id = conversationId ?: "loading"
    val saved = remember(id) { bookmarks[id]?.copyOf() }
    val list = key(id) { rememberLazyListState(saved?.getOrNull(0) ?: 0, saved?.getOrNull(1) ?: 0) }
    val following = rememberSaveable(id) { mutableStateOf(saved?.getOrNull(2) != 0) }
    DisposableEffect(id, list, following) {
        onDispose {
            if (conversationId != null) {
                bookmarks.remove(id)
                bookmarks[id] = intArrayOf(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, if (following.value) 1 else 0)
                while (bookmarks.size > 24) bookmarks.remove(bookmarks.keys.first())
            }
        }
    }
    return remember(list, following) { ChatScrollSession(list, following) }
}
