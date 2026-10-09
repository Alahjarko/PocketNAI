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
    fun `解压炸弹被限额挡住`() {
        // 声明一个 1 MiB 上限之上的压缩块：解压内容超过 MAX_CHUNK_BYTES 时正文会被跳过，
        // 因此结果是"读到了但没有这条文本块"，而不是耗尽内存。
        val bomb = "a".repeat(PngTextChunks.MAX_CHUNK_BYTES + 1)
        val png = PngFixture.png(compressedTexts = listOf("Comment" to bomb))

        val result = read(png) as PngTextChunks.Result.Read
        assertThat(result.value("Comment")).isNull()
    }

    @Test fun `压缩Latin1提示词读取后传入元数据解析仍保留空格`() {
        val prompt = "girl,\u00a0\u00a0café"
        val png = PngFixture.png(
            texts = listOf("Software" to "NovelAI"),
            compressedTexts = listOf("Comment" to PngFixture.commentJson(prompt = prompt)),
            textCharset = Charsets.ISO_8859_1,
        )
        val result = read(png) as PngTextChunks.Result.Read
        val parsed = NovelAiMetadataParser.parse(result.chunks, kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        assertThat(parsed?.prompt).isEqualTo(prompt)
    }
}
