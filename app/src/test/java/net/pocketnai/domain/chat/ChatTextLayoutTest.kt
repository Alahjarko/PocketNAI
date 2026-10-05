package net.pocketnai.domain.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatTextLayoutTest {
    @Test fun `超长历史与流式投影有限但全文无损`() {
        val text = "长篇聊天文本\n".repeat(10_000)
        assertThat(ChatTextLayout.isLong(text)).isTrue()
        assertThat(ChatTextLayout.preview(text).length).isAtMost(ChatTextLayout.PREVIEW_CHARS)
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
    @Test fun `不足三百字的多段列表不会因换行而折叠`() {
        val text = (1..12).joinToString("\n\n") { "$it. 角色、服装、场景与光线的要点" }
        assertThat(text.length).isLessThan(300)
        assertThat(ChatTextLayout.isLong(text)).isFalse()
        assertThat(ChatTextLayout.preview(text)).isEqualTo(text)
        assertThat(ChatTextLayout.isLong("字".repeat(2000))).isFalse()
        assertThat(ChatTextLayout.isLong("字".repeat(2001))).isTrue()
    }
    @Test fun `超长正文尽量在完整段落结束处折叠`() {
        val text = "首段".repeat(650) + "\n\n" + "第二段".repeat(1000)
        assertThat(ChatTextLayout.preview(text)).isEqualTo("首段".repeat(650))
    }
}
