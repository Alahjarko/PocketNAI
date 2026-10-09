package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP 解包与校验。
 *
 * 测试用的“PNG”是只包含签名与 IHDR 的文件头夹具：这正好覆盖
 * [PngValidator] 实际读取的范围（规划书 6.2 要求检查签名而不是扩展名），
 * 因此这里不需要构造真正可解码的完整 PNG。
 */
class ZipImageExtractorTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val extractor = ZipImageExtractor()

    @Test
    fun `拒绝路径穿越的 entry 名称`() {
        val result = extract(zipOf("../../evil.png" to pngHeader(512, 512)))

        assertThat(result).isInstanceOf(ZipImageExtractor.Result.Failure::class.java)
        assertThat(targetFileNames()).isEmpty()
    }

    private fun extract(zipBytes: ByteArray): ZipImageExtractor.Result {
        val target = tempFolder.newFolder()
        lastTarget = target
        return extractor.extract(zipBytes.inputStream(), target)
    }

    private var lastTarget: File? = null

    private fun targetFileNames(): List<String> =
        (lastTarget ?: tempFolder.root).listFiles()?.map { it.name }.orEmpty()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    /** PNG 签名 + IHDR 块，共 24 字节，正好是 [PngValidator] 读取的量。 */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(24)
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ).copyInto(bytes, 0)
        // IHDR 数据长度固定为 13。
        bytes[11] = 13
        bytes[12] = 'I'.code.toByte()
        bytes[13] = 'H'.code.toByte()
        bytes[14] = 'D'.code.toByte()
        bytes[15] = 'R'.code.toByte()
        writeBigEndian(bytes, 16, width)
        writeBigEndian(bytes, 20, height)
        return bytes
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
