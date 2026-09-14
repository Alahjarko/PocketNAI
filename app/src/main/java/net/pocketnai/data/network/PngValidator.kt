package net.pocketnai.data.network

import java.io.File

/**
 * PNG 结构校验（规划书 6.2 第 4 步：检查文件签名，确认结果为 PNG，而不是只信扩展名）。
 */
object PngValidator {

    /** PNG 文件头：89 50 4E 47 0D 0A 1A 0A。 */
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    /** IHDR 至少需要头部 8 字节 + 长度 4 + 类型 4 + 宽 4 + 高 4 = 24 字节。 */
    private const val MIN_IHDR_BYTES = 24

    private const val IHDR_OFFSET = 12
    private const val WIDTH_OFFSET = 16
    private const val HEIGHT_OFFSET = 20

    fun hasSignature(bytes: ByteArray): Boolean {
        if (bytes.size < SIGNATURE.size) return false
        return SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }
    }

    /**
     * 从 PNG 的 IHDR 块读取宽高。数据不足或结构不对时返回 null。
     * 只读文件头 24 字节，因此对大图也很便宜。
     */
    fun readDimensions(file: File): Dimensions? {
        if (file.length() < MIN_IHDR_BYTES) return null
        val header = ByteArray(MIN_IHDR_BYTES)
        val read = file.inputStream().use { it.read(header) }
        if (read < MIN_IHDR_BYTES) return null
        if (!hasSignature(header)) return null
        if (header[IHDR_OFFSET] != 'I'.code.toByte() ||
            header[IHDR_OFFSET + 1] != 'H'.code.toByte() ||
            header[IHDR_OFFSET + 2] != 'D'.code.toByte() ||
            header[IHDR_OFFSET + 3] != 'R'.code.toByte()
        ) {
            return null
        }
        val width = readBigEndianInt(header, WIDTH_OFFSET)
        val height = readBigEndianInt(header, HEIGHT_OFFSET)
        if (width <= 0 || height <= 0) return null
        return Dimensions(width = width, height = height)
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    data class Dimensions(val width: Int, val height: Int)
}
