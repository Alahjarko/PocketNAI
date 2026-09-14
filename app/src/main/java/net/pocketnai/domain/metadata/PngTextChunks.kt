package net.pocketnai.domain.metadata

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * PNG 文本块读取器（tEXt / iTXt / zTXt）。
 *
 * ## 为什么不能用 BitmapFactory
 * `BitmapFactory` 只负责像素，会丢掉 NovelAI 写在 PNG 文本块里的生成参数。
 * 反过来说：**任何"先解码再重新编码"的路径都会毁掉元数据**，所以这一读取必须
 * 发生在图片归一化之前，输入是用户选中的**原始文件**。
 *
 * ## 流式读取，不整张载入
 * NovelAI 的图片有 1–2 MiB，而元数据块在 **IDAT 之后**（文件尾部）。
 * 因此这里顺序读 chunk 头、跳过 IDAT 数据，只收集文本块 —— 既不把整张图读进内存，
 * 也不需要 seek（相册 URI 的 InputStream 通常不可随机访问）。
 *
 * ## 恶意文件防护
 * 图片是外部输入，所有长度字段都不能信：
 * - 单块未压缩上限 [MAX_CHUNK_BYTES]，解压后上限 [MAX_INFLATED_BYTES]（防解压炸弹）；
 * - 所有文本总量上限 [MAX_TOTAL_TEXT_BYTES]；
 * - chunk 长度超过 [MAX_CHUNK_LENGTH] 直接判定为损坏，不尝试分配；
 * - 解压用 [Inflater] 并逐段限额，绝不 `readBytes()` 一把梭。
 *
 * 解析失败**不影响把图片当参考图用** —— 调用方据此降级，而不是拒绝这张图。
 */
object PngTextChunks {

    /** 单个文本块的未压缩上限。 */
    const val MAX_CHUNK_BYTES: Int = 1 shl 20

    /** 单个 zTXt / iTXt 解压后的上限（解压炸弹防护）。 */
    const val MAX_INFLATED_BYTES: Int = 1 shl 20

    /** 所有文本块加起来的上限。 */
    const val MAX_TOTAL_TEXT_BYTES: Int = 2 shl 20

    /** 单个 chunk 的长度上限：超过它说明文件损坏或构造恶意，不分配内存。 */
    const val MAX_CHUNK_LENGTH: Long = 64L shl 20

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** 流里的一条文本块。iTXt 的语言/翻译字段我们不关心，只留关键字与正文。 */
    data class TextChunk(val keyword: String, val text: String)

    /**
     * 读取结果。
     *
     * 刻意不用 `Outcome<List<TextChunk>>`：调用方需要区分"不是 PNG"（该提示用户格式不支持）
     * 与"是 PNG 但没有文本块"（就是没有元数据）—— 这两种情况的界面文案完全不同。
     */
    sealed interface Result {
        /** 读到文本块。[chunks] 为空表示这张 PNG 没有文本块。 */
        data class Read(val chunks: List<TextChunk>) : Result

        /** 文件头不是 PNG 签名（可能是 JPEG / WebP）。 */
        data object NotPng : Result

        /** 是 PNG 但结构损坏。 */
        data object Malformed : Result

        /** 超出限额，放弃解析。 */
        data object TooLarge : Result
    }

    fun read(input: InputStream): Result = try {
        readOrThrow(input)
    } catch (e: NotPngException) {
        Result.NotPng
    } catch (e: TooLargeException) {
        Result.TooLarge
    } catch (e: IOException) {
        Result.Malformed
    } catch (e: EOFException) {
        Result.Malformed
    }

    /** 只做格式识别，不解析文本块。 */
    fun isPng(input: InputStream): Boolean = try {
        val signature = ByteArray(SIGNATURE.size)
        readFully(input, signature)
        signature.contentEquals(SIGNATURE)
    } catch (e: IOException) {
        false
    }

    // ---- 实现 ----

    private fun readOrThrow(input: InputStream): Result {
        val signature = ByteArray(SIGNATURE.size)
        readFully(input, signature)
        if (!signature.contentEquals(SIGNATURE)) throw NotPngException()

        val chunks = mutableListOf<TextChunk>()
        var totalText = 0
        val lengthBytes = ByteArray(4)

        while (true) {
            readFully(input, lengthBytes)
            val length = readUInt32(lengthBytes)
            if (length > MAX_CHUNK_LENGTH) throw TooLargeException()

            val typeBytes = ByteArray(4)
            readFully(input, typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)

            when (type) {
                "tEXt" -> {
                    readBody(input, length)?.let { body ->
                        decodeText(body)?.let { chunk ->
                            chunks += chunk
                            totalText += chunk.text.length
                        }
                    }
                }

                "zTXt" -> {
                    readBody(input, length)?.let { body ->
                        decodeCompressedText(body)?.let { chunk ->
                            chunks += chunk
                            totalText += chunk.text.length
                        }
                    }
                }

                "iTXt" -> {
                    readBody(input, length)?.let { body ->
                        decodeInternationalText(body)?.let { chunk ->
                            chunks += chunk
                            totalText += chunk.text.length
                        }
                    }
                }

                "IEND" -> return Result.Read(chunks)

                // IDAT 可能很大，逐块跳过而不是读进内存。
                else -> skipFully(input, length)
            }

            if (totalText > MAX_TOTAL_TEXT_BYTES) throw TooLargeException()

            // 每个 chunk 后面 4 字节 CRC，跳过。
            skipFully(input, 4L)
        }
    }

    /** 读取 chunk 正文；超过单块上限的正文直接跳过（不当成成功）。 */
    private fun readBody(input: InputStream, length: Long): ByteArray? {
        if (length > MAX_CHUNK_BYTES) {
            skipFully(input, length)
            return null
        }
        val body = ByteArray(length.toInt())
        readFully(input, body)
        return body
    }

    /** `tEXt`：`keyword\0text`，文本按 Latin-1 解释（PNG 规范如此，NovelAI 写入的是 UTF-8 子集）。 */
    private fun decodeText(body: ByteArray): TextChunk? {
        val separator = body.indexOf(0.toByte())
        if (separator <= 0) return null
        val keyword = String(body, 0, separator, Charsets.ISO_8859_1)
        val text = String(body, separator + 1, body.size - separator - 1, Charsets.UTF_8)
        return TextChunk(keyword, text)
    }

    /** `zTXt`：`keyword\0method(1)compressed`。 */
    private fun decodeCompressedText(body: ByteArray): TextChunk? {
        val separator = body.indexOf(0.toByte())
        if (separator <= 0 || separator + 2 > body.size) return null
        val keyword = String(body, 0, separator, Charsets.ISO_8859_1)
        val compressed = body.copyOfRange(separator + 2, body.size)
        val text = inflate(compressed) ?: return null
        return TextChunk(keyword, text)
    }

    /** `iTXt`：`keyword\0flag(1)method(1)language\0translated\0text`。 */
    private fun decodeInternationalText(body: ByteArray): TextChunk? {
        val keywordEnd = body.indexOf(0.toByte())
        if (keywordEnd <= 0 || keywordEnd + 2 >= body.size) return null
        val keyword = String(body, 0, keywordEnd, Charsets.ISO_8859_1)
        val compressed = body[keywordEnd + 1].toInt() == 1

        var cursor = keywordEnd + 3
        val languageEnd = body.indexOf(0.toByte(), cursor)
        if (languageEnd < 0) return null
        cursor = languageEnd + 1
        val translatedEnd = body.indexOf(0.toByte(), cursor)
        if (translatedEnd < 0) return null
        cursor = translatedEnd + 1
        if (cursor > body.size) return null

        val payload = body.copyOfRange(cursor, body.size)
        val text = if (compressed) inflate(payload) ?: return null else String(payload, Charsets.UTF_8)
        return TextChunk(keyword, text)
    }

    /** 限额解压。超限或格式错误返回 null —— 元数据读不出来不该让整张图不可用。 */
    private fun inflate(compressed: ByteArray): String? {
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val buffer = ByteArray(DEFAULT_INFLATE_BUFFER)
            val output = java.io.ByteArrayOutputStream()
            while (!inflater.finished()) {
                val read = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    return null
                }
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
                }
                if (output.size() + read > MAX_INFLATED_BYTES) return null
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }

    private fun readUInt32(bytes: ByteArray): Long =
        (bytes[0].toLong() and 0xFF) shl 24 or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
    }

    /** 跳过若干字节；流不支持 skip 时退化成逐块读出丢弃。 */
    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw EOFException()
                remaining -= 1
            }
        }
    }

    private fun ByteArray.indexOf(value: Byte, from: Int = 0): Int {
        for (i in from until size) {
            if (this[i] == value) return i
        }
        return -1
    }

    private const val DEFAULT_INFLATE_BUFFER = 8 * 1024

    private class NotPngException : IOException()
    private class TooLargeException : IOException()
}

/** 便利函数：把读取结果里的某个关键字取出来（关键字大小写不敏感）。 */
fun PngTextChunks.Result.value(keyword: String): String? = when (this) {
    is PngTextChunks.Result.Read -> chunks
        .firstOrNull { it.keyword.equals(keyword, ignoreCase = true) }
        ?.text

    else -> null
}
