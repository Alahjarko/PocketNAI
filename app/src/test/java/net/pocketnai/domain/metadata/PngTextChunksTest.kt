package net.pocketnai.domain.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * PNG 文本块读取器。
 *
 * 重点不是"能读出来"，而是**读不出来的时候不炸、不乱吃内存**：
 * 图片是外部输入，长度字段全都不可信。
 */
class PngTextChunksTest {

    private fun read(bytes: ByteArray): PngTextChunks.Result =
        PngTextChunks.read(ByteArrayInputStream(bytes))

    @Test
    fun `读出 tEXt 文本块`() {
        val png = PngFixture.png(
            texts = listOf(
                "Software" to "NovelAI",
                "Source" to "NovelAI Diffusion V5 DB276663",
            ),
        )

        val result = read(png)

        assertThat(result).isInstanceOf(PngTextChunks.Result.Read::class.java)
        val chunks = (result as PngTextChunks.Result.Read).chunks
        assertThat(chunks.map { it.keyword })
            .containsExactly("Software", "Source").inOrder()
        assertThat(result.value("source")).isEqualTo("NovelAI Diffusion V5 DB276663")
        // 关键字大小写不敏感：官方两种写法（Generation_time / Generation time）都要认。
        assertThat(result.value("SOFTWARE")).isEqualTo("NovelAI")
    }

    @Test
    fun `读出 zTXt 与 iTXt`() {
        val png = PngFixture.png(
            compressedTexts = listOf("Comment" to """{"steps": 23}"""),
            internationalTexts = listOf("Description" to "1girl, 白发"),
        )

        val result = read(png)

        assertThat(result.value("Comment")).isEqualTo("""{"steps": 23}""")
        // iTXt 是 UTF-8：非 ASCII 不能读成乱码。
        assertThat(result.value("Description")).isEqualTo("1girl, 白发")
    }

    @Test
    fun `文本块在 IDAT 之后也能读到`() {
        // 真实文件就是这个形状：元数据在文件尾部，因此不能"只读前几 KB"。
        val png = PngFixture.png(
            texts = listOf("Software" to "NovelAI"),
            idatChunks = 8,
            idatSize = 64 * 1024,
        )

        assertThat(read(png).value("Software")).isEqualTo("NovelAI")
    }

    @Test
    fun `不是 PNG 时如实报告`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)

        assertThat(read(jpeg)).isEqualTo(PngTextChunks.Result.NotPng)
    }

    @Test
    fun `没有文本块的 PNG 返回空列表而不是失败`() {
        val result = read(PngFixture.png())

        assertThat((result as PngTextChunks.Result.Read).chunks).isEmpty()
        assertThat(result.value("Software")).isNull()
    }

    @Test
    fun `长度字段撒谎时判定为损坏而不是分配内存`() {
        // 签名 + 声称有 2 GiB 的 tEXt，后面什么都没有。
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            PngFixture.uint32(0x7FFFFFFFL) + "tEXt".toByteArray()

        assertThat(read(header)).isEqualTo(PngTextChunks.Result.TooLarge)
    }

    @Test
    fun `解压炸弹被限额挡住`() {
        // 声明一个 1 MiB 上限之上的压缩块：解压内容超过 MAX_CHUNK_BYTES 时正文会被跳过，
        // 因此结果是"读到了但没有这条文本块"，而不是耗尽内存。
        val bomb = "a".repeat(PngTextChunks.MAX_CHUNK_BYTES + 1)
        val png = PngFixture.png(compressedTexts = listOf("Comment" to bomb))

        val result = read(png) as PngTextChunks.Result.Read
        assertThat(result.value("Comment")).isNull()
    }

    @Test
    fun `截断的文件判定为损坏`() {
        val png = PngFixture.png(texts = listOf("Software" to "NovelAI"))

        assertThat(read(png.copyOf(png.size / 2))).isEqualTo(PngTextChunks.Result.Malformed)
    }
}
