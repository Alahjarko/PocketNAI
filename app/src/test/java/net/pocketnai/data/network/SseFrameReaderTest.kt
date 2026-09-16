package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SseFrameReaderTest {

    private fun readAll(vararg lines: String): List<SseFrameReader.Frame> {
        val reader = SseFrameReader()
        val frames = mutableListOf<SseFrameReader.Frame>()
        lines.forEach { line -> reader.feed(line)?.let { frames += it } }
        reader.finish()?.let { frames += it }
        return frames
    }

    @Test
    fun `事件名与数据组成一帧`() {
        val frames = readAll("event: intermediate", "data: {\"a\":1}", "")
        assertThat(frames).hasSize(1)
        assertThat(frames[0].event).isEqualTo("intermediate")
        assertThat(frames[0].data).isEqualTo("{\"a\":1}")
    }

    @Test
    fun `没有 event 字段的帧也能读出数据`() {
        val frames = readAll("data: hello", "")
        assertThat(frames).hasSize(1)
        assertThat(frames[0].event).isNull()
        assertThat(frames[0].data).isEqualTo("hello")
    }

    @Test
    fun `多行 data 用换行拼接`() {
        val frames = readAll("data: line1", "data: line2", "")
        assertThat(frames[0].data).isEqualTo("line1\nline2")
    }

    @Test
    fun `注释行与 id retry 字段被忽略`() {
        val frames = readAll(": keep-alive", "id: 7", "retry: 1000", "data: x", "")
        assertThat(frames).hasSize(1)
        assertThat(frames[0].data).isEqualTo("x")
    }

    @Test
    fun `冒号后没有空格的值原样保留`() {
        val frames = readAll("data:{\"a\":1}", "")
        assertThat(frames[0].data).isEqualTo("{\"a\":1}")
    }

    @Test
    fun `空行才结束一帧，连续空行不产生空帧`() {
        val frames = readAll("data: a", "", "", "data: b", "")
        assertThat(frames.map { it.data }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `流结束时未闭合的帧仍然有效`() {
        // 服务端关闭连接前没来得及补空行时，不能丢掉最后一帧（可能正是 Final 图）。
        val frames = readAll("event: final", "data: last")
        assertThat(frames).hasSize(1)
        assertThat(frames[0].data).isEqualTo("last")
    }

    @Test
    fun `空输入不产生帧`() {
        val reader = SseFrameReader()
        assertThat(reader.finish()).isNull()
    }
}
