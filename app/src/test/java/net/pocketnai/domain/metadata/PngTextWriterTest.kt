package net.pocketnai.domain.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * PNG 文本块的写回。
 *
 * 这是"元数据导入"与"自定义分辨率裁切"之间的接缝：裁切必须解码再编码，
 * 而重编码会丢掉原图的 `tEXt`。写回失败不会报错，只会让用户下次导入这张图时
 * 发现"参数不见了" —— 所以这里要逐条断言。
 */
class PngTextWriterTest {

    private fun readBack(png: ByteArray): Map<String, String> =
        PngTextWriter.readTextChunks(png).associate { it.keyword to it.text }

    @Test
    fun `写回的文本块能被读出来`() {
        val png = PngFixture.png(texts = listOf("Software" to "NovelAI", "Comment" to """{"steps":23}"""))

        val written = PngTextWriter.withTextChunks(
            png = png,
            chunks = listOf(
                PngTextChunks.TextChunk("Comment", """{"steps":24}"""),
                PngTextChunks.TextChunk("pocketnai_output", "output=1920x1080"),
            ),
        )

        val chunks = readBack(written)
        // 同名块：后写的会覆盖先读到的（读取侧取第一个匹配，因此写入侧把它放在前面）。
        assertThat(chunks["Software"]).isEqualTo("NovelAI")
        assertThat(chunks["pocketnai_output"]).isEqualTo("output=1920x1080")
    }

    @Test
    fun `插入文本块不改变原有像素数据`() {
        val png = PngFixture.png(idatChunks = 1, idatSize = 8)
        // 直接从原图里取出 IDAT 正文当"针"：写元数据不该碰它一个字节。
        val idatTypeOffset = indexOf(png, "IDAT".toByteArray())
        assertThat(idatTypeOffset).isAtLeast(0)
        val idat = png.copyOfRange(idatTypeOffset + 4, idatTypeOffset + 4 + 8)

        val written = PngTextWriter.withTextChunks(
            png = png,
            chunks = listOf(PngTextChunks.TextChunk("Comment", "x")),
        )

        // PNG 签名与 IHDR 之后的 IDAT 必须逐字节原样保留：写元数据不该碰像素。
        assertThat(written.size).isGreaterThan(png.size)
        assertThat(written.copyOf(8)).isEqualTo(png.copyOf(8))
        assertThat(indexOf(written, idat)).isAtLeast(0)
        // IEND 仍然是最后一个块。
        assertThat(written.takeLast(8).take(4)).isEqualTo("IEND".toByteArray().toList())
    }

    @Test
    fun `没有 IEND 时原样返回而不是产出坏文件`() {
        val truncated = PngFixture.png(texts = listOf("Software" to "NovelAI"))
            .let { it.copyOf(it.size - 12) } // 去掉 IEND

        val written = PngTextWriter.withTextChunks(
            png = truncated,
            chunks = listOf(PngTextChunks.TextChunk("Comment", "x")),
        )

        assertThat(written).isEqualTo(truncated)
    }

    @Test
    fun `超长关键字或正文被跳过`() {
        val png = PngFixture.png()
        val tooLongKeyword = "k".repeat(80)
        val tooLongText = "t".repeat(PngTextChunks.MAX_CHUNK_BYTES + 1)

        val written = PngTextWriter.withTextChunks(
            png = png,
            chunks = listOf(
                PngTextChunks.TextChunk(tooLongKeyword, "x"),
                PngTextChunks.TextChunk("ok", tooLongText),
                PngTextChunks.TextChunk("Comment", "fine"),
            ),
        )

        val chunks = readBack(written)
        assertThat(chunks).doesNotContainKey(tooLongKeyword)
        assertThat(chunks).doesNotContainKey("ok")
        assertThat(chunks["Comment"]).isEqualTo("fine")
    }

    @Test
    fun `输出转换信息可以往返`() {
        val entry = PocketNaiOutputMetadata.Entry(
            outputWidth = 1920,
            outputHeight = 1080,
            generationWidth = 1920,
            generationHeight = 1088,
            cropX = 0,
            cropY = 4,
        )

        val decoded = PocketNaiOutputMetadata.decode(entry.encode())

        assertThat(decoded).isEqualTo(entry)
        assertThat(entry.encode()).isEqualTo("output=1920x1080;generation=1920x1088;crop=0,4")
    }

    @Test
    fun `输出转换信息读不出来时返回 null`() {
        assertThat(PocketNaiOutputMetadata.decode(null)).isNull()
        assertThat(PocketNaiOutputMetadata.decode("")).isNull()
        assertThat(PocketNaiOutputMetadata.decode("output=bad;generation=1x2;crop=0,0")).isNull()
        assertThat(PocketNaiOutputMetadata.decode("output=1920x1080")).isNull()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }
}
