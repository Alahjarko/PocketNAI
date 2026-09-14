package net.pocketnai.domain.metadata

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * 往 PNG 里写 `tEXt` 文本块。
 *
 * ## 为什么需要它
 * 自定义分辨率要"生成 `1920×1088` → 裁成 `1920×1080`"，而**裁切必须解码再编码**：
 * Android 的 `Bitmap.compress(PNG)` 会把原 PNG 的 `Software` / `Source` / `Comment`
 * 全部丢掉。这样一来，用户以后再导入这张图，元数据导入就失效了 ——
 * 功能 A 被功能 B 悄悄破坏，而且没有任何报错。
 *
 * 所以裁切之后要把白名单里的元数据**原样写回**，再补一条我们自己的输出信息：
 *
 * ```text
 * NovelAI 的 Comment     仍写原始生成画布 1920×1088
 * pocketnai_output       最终 1920×1080、裁切原点 (0, 4)
 * ```
 *
 * **绝不篡改 NovelAI 的尺寸字段**：那是模型真正生成时用的画布，
 * 改掉它会让"同 Seed + 同参数复现"直接对不上。
 *
 * ## 为什么可以只写 `tEXt`
 * `tEXt` 是最基础、兼容性最好的文本块（Latin-1 关键字 + UTF-8 正文）。
 * 原图里的 `iTXt` / `zTXt` 在读取时已经被解成字符串，重写时统一用 `tEXt` 不会有信息损失；
 * 我们只关心 `NovelAI` / 参数 JSON 这类纯文本。
 */
object PngTextWriter {

    /** 单条文本的字节上限，与读取侧同源；超过就拒绝写（外部输入不能信）。 */
    const val MAX_TEXT_BYTES: Int = PngTextChunks.MAX_CHUNK_BYTES

    /** 关键字上限（PNG 规范：1–79 字节）。 */
    private const val MAX_KEYWORD_BYTES = 79

    /**
     * 在 [png] 的 `IEND` 之前插入若干 `tEXt` 块，返回新的 PNG 字节。
     *
     * 其它块（`IHDR` / `IDAT` / `pHYs` …）**逐字节原样保留**，
     * 因此对像素数据没有任何影响。找不到 `IEND` 时原样返回 ——
     * 宁可"元数据没写进去"，也不要产出一个结构损坏的 PNG。
     */
    fun withTextChunks(png: ByteArray, chunks: List<PngTextChunks.TextChunk>): ByteArray {
        if (chunks.isEmpty()) return png
        // 注意插入点是**块起点**（长度字段），不是类型字段：
        // 插错位置会把 IEND 的长度字段留在新块前面，PNG 结构立刻损坏。
        val iendOffset = findChunkOffset(png, "IEND") ?: return png

        val out = ByteArrayOutputStream(png.size + chunks.sumOf { it.keyword.length + it.text.toByteArray().size + 24 })
        out.write(png, 0, iendOffset)
        chunks.forEach { chunk ->
            if (!isEncodable(chunk)) return@forEach
            out.write(textChunk(chunk))
        }
        out.write(png, iendOffset, png.size - iendOffset)
        return out.toByteArray()
    }

    /** 读出 PNG 里的文本块，供"裁切前先把元数据拿下来"用。 */
    fun readTextChunks(png: ByteArray): List<PngTextChunks.TextChunk> =
        when (val result = PngTextChunks.read(png.inputStream())) {
            is PngTextChunks.Result.Read -> result.chunks
            else -> emptyList()
        }

    private fun isEncodable(chunk: PngTextChunks.TextChunk): Boolean {
        val keyword = chunk.keyword.toByteArray(Charsets.ISO_8859_1)
        if (keyword.isEmpty() || keyword.size > MAX_KEYWORD_BYTES) return false
        if (keyword.any { it == 0.toByte() }) return false
        return chunk.text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES
    }

    /** 组装一个 `tEXt` 块：长度 + 类型 + `keyword\0text` + CRC32。 */
    private fun textChunk(chunk: PngTextChunks.TextChunk): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(chunk.keyword.toByteArray(Charsets.ISO_8859_1))
        body.write(0)
        body.write(chunk.text.toByteArray(Charsets.UTF_8))
        val bodyBytes = body.toByteArray()

        val type = "tEXt".toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream(bodyBytes.size + 12)
        out.write(uint32(bodyBytes.size))
        out.write(type)
        out.write(bodyBytes)

        val crc = CRC32()
        crc.update(type)
        crc.update(bodyBytes)
        out.write(uint32(crc.value))
        return out.toByteArray()
    }

    /** 找到某个块的**起点**偏移（长度字段的位置），插入新块时要用它。 */
    private fun findChunkOffset(png: ByteArray, type: String): Int? {
        val target = type.toByteArray(Charsets.US_ASCII)
        var offset = SIGNATURE.size
        while (offset + 8 <= png.size) {
            val length = readUInt32(png, offset)
            if (length > png.size.toLong()) return null
            if (png.matchesAt(offset + 4, target)) return offset
            // 长度 + 类型 + 正文 + CRC
            val next = offset + 4 + 4 + length + 4
            if (next <= offset) return null
            offset = next.toInt()
        }
        return null
    }

    private fun ByteArray.matchesAt(offset: Int, target: ByteArray): Boolean {
        if (offset + target.size > size) return false
        for (i in target.indices) {
            if (this[offset + i] != target[i]) return false
        }
        return true
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) shl 24 or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun uint32(value: Long): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun uint32(value: Int): ByteArray = uint32(value.toLong() and 0xFFFFFFFFL)

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}

/**
 * PocketNAI 自己写进 PNG 的输出转换信息。
 *
 * 用一个独立关键字（而不是篡改 NovelAI 的字段）表达"这张图被本地裁过"：
 * 官网与其它工具不认识它时会忽略，而我们重新导入时能还原完整流程。
 */
object PocketNaiOutputMetadata {

    const val KEYWORD: String = "pocketnai_output"

    /** 保留的 NovelAI 元数据关键字白名单（与解析器读的一致）。 */
    val PRESERVED_KEYWORDS: Set<String> = setOf(
        "software",
        "source",
        "description",
        "comment",
        "generation_time",
        "title",
    )

    data class Entry(
        val outputWidth: Int,
        val outputHeight: Int,
        val generationWidth: Int,
        val generationHeight: Int,
        val cropX: Int,
        val cropY: Int,
    ) {
        fun encode(): String = buildString {
            append("output=").append(outputWidth).append('x').append(outputHeight)
            append(";generation=").append(generationWidth).append('x').append(generationHeight)
            append(";crop=").append(cropX).append(',').append(cropY)
        }
    }

    fun decode(text: String?): Entry? {
        if (text.isNullOrBlank()) return null
        val fields = text.split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }
            .toMap()
        val output = parseSize(fields["output"]) ?: return null
        val generation = parseSize(fields["generation"]) ?: return null
        val crop = fields["crop"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        if (crop == null || crop.size != 2) return null
        return Entry(
            outputWidth = output.first,
            outputHeight = output.second,
            generationWidth = generation.first,
            generationHeight = generation.second,
            cropX = crop[0],
            cropY = crop[1],
        )
    }

    private fun parseSize(value: String?): Pair<Int, Int>? {
        val parts = value?.split('x') ?: return null
        if (parts.size != 2) return null
        val width = parts[0].trim().toIntOrNull() ?: return null
        val height = parts[1].trim().toIntOrNull() ?: return null
        return width to height
    }

    /** 关键字归一化：`Generation_time` 与 `Generation time` 都算。 */
    fun normalizeKeyword(keyword: String): String =
        keyword.lowercase().filter { it.isLetterOrDigit() }
}
