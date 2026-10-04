package net.pocketnai.domain.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatTextLayoutTest {
    @Test fun `超长历史与流式投影有限但全文无损`() {
        val text = "长篇聊天文本\n".repeat(10_000)
        assertThat(ChatTextLayout.isLong(text)).isTrue()
        assertThat(ChatTextLayout.preview(text).length).isAtMost(ChatTextLayout.PREVIEW_CHARS)
        assertThat(ChatTextLayout.preview(text).count { it == '\n' }).isAtMost(7)
        assertThat(ChatTextLayout.liveTail(text).length).isAtMost(ChatTextLayout.LIVE_CHARS)
        val pages = ChatTextLayout.pages(text)
        assertThat(pages.joinToString("")).isEqualTo(text)
        assertThat(pages.all { it.length <= ChatTextLayout.PAGE_CHARS }).isTrue()
    }
    @Test fun `没有段落的五万字也会分块`() {
        val text = "a".repeat(50_000)
        assertThat(ChatTextLayout.pages(text)).hasSize(84)
        assertThat(ChatTextLayout.pages(text).joinToString("")).isEqualTo(text)
    }
    @Test fun `折叠与分块不切断emoji代理对`() {
        val text = "x".repeat(639) + "🙂".repeat(10_000)
        val segments = ChatTextLayout.pages(text) + ChatTextLayout.preview(text) + ChatTextLayout.liveTail(text)
        assertThat(segments.none { it.lastOrNull()?.isHighSurrogate() == true || it.firstOrNull()?.isLowSurrogate() == true }).isTrue()
        assertThat(ChatTextLayout.pages(text).joinToString("")).isEqualTo(text)
    }
    @Test fun `短消息原样显示空文本可读`() {
        assertThat(ChatTextLayout.isLong("你好")).isFalse()
        assertThat(ChatTextLayout.preview("你好")).isEqualTo("你好")
        assertThat(ChatTextLayout.pages("")).isEmpty()
    }
}
