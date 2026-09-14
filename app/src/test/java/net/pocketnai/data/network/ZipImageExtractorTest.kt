package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Hashing
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
    fun `按 entry 顺序编号并读出宽高`() {
        val zip = zipOf(
            "image_0.png" to pngHeader(width = 832, height = 1216),
            "image_1.png" to pngHeader(width = 1024, height = 1024),
        )

        val result = extract(zip)

        assertThat(result).isInstanceOf(ZipImageExtractor.Result.Success::class.java)
        val images = (result as ZipImageExtractor.Result.Success).images
        assertThat(images).hasSize(2)
        assertThat(images.map { it.ordinal }).containsExactly(1, 2).inOrder()
        assertThat(images.map { it.file.name }).containsExactly("0001.png", "0002.png").inOrder()
        assertThat(images[0].width).isEqualTo(832)
        assertThat(images[0].height).isEqualTo(1216)
        assertThat(images[1].width).isEqualTo(1024)
        assertThat(images[1].height).isEqualTo(1024)
        assertThat(images[0].byteSize).isEqualTo(24L)
    }

    @Test
    fun `sha256 与实际文件内容一致`() {
        val bytes = pngHeader(width = 64, height = 64)
        val result = extract(zipOf("a.png" to bytes)) as ZipImageExtractor.Result.Success

        assertThat(result.images.single().sha256).isEqualTo(Hashing.sha256(bytes))
    }

    @Test
    fun `不是 PNG 的 entry 被丢弃并计数`() {
        val result = extract(
            zipOf(
                "good.png" to pngHeader(512, 512),
                "bad.png" to "this is not a png".toByteArray(),
            ),
        ) as ZipImageExtractor.Result.Success

        assertThat(result.images).hasSize(1)
        assertThat(result.rejectedEntries).isEqualTo(1)
        // 被丢弃的 entry 不能留下半成品文件。
        assertThat(targetFileNames()).containsExactly("0001.png")
    }

    @Test
    fun `全部内容非法时返回 ZIP_INVALID`() {
        val result = extract(zipOf("bad.png" to "nope".toByteArray()))

        assertThat(result).isInstanceOf(ZipImageExtractor.Result.Failure::class.java)
        assertThat((result as ZipImageExtractor.Result.Failure).error.code)
            .isEqualTo(ErrorCode.ZIP_INVALID)
        assertThat(targetFileNames()).isEmpty()
    }

    @Test
    fun `拒绝路径穿越的 entry 名称`() {
        val result = extract(zipOf("../../evil.png" to pngHeader(512, 512)))

        assertThat(result).isInstanceOf(ZipImageExtractor.Result.Failure::class.java)
        assertThat(targetFileNames()).isEmpty()
    }

    @Test
    fun `拒绝绝对路径的 entry 名称`() {
        val result = extract(zipOf("/etc/evil.png" to pngHeader(512, 512)))

        assertThat(result).isInstanceOf(ZipImageExtractor.Result.Failure::class.java)
        assertThat(targetFileNames()).isEmpty()
    }

    @Test
    fun `entry 数量超过上限时失败`() {
        val strict = ZipImageExtractor(ZipImageExtractor.Limits(maxEntries = 2))
        val zip = zipOf(
            "a.png" to pngHeader(512, 512),
            "b.png" to pngHeader(512, 512),
            "c.png" to pngHeader(512, 512),
        )

        val result = strict.extract(zip.inputStream(), tempFolder.newFolder())

        assertThat(result).isInstanceOf(ZipImageExtractor.Result.Failure::class.java)
        assertThat((result as ZipImageExtractor.Result.Failure).error.code)
            .isEqualTo(ErrorCode.ZIP_INVALID)
    }

    @Test
    fun `单个 entry 超过大小上限时失败`() {
        val strict = ZipImageExtractor(ZipImageExtractor.Limits(maxEntryBytes = 16))
        val result = strict.extract(
            zipOf("a.png" to pngHeader(512, 512)).inputStream(),
            tempFolder.newFolder(),
        )

        assertThat(result).isInstanceOf(ZipImageExtractor.Result.Failure::class.java)
    }

    @Test
    fun `损坏的 ZIP 数据返回 ZIP_INVALID 而不是崩溃`() {
        val junk = ByteArray(64) { 0x41 }
        val result = extractor.extract(junk.inputStream(), tempFolder.newFolder())

        assertThat(result).isInstanceOf(ZipImageExtractor.Result.Failure::class.java)
        assertThat((result as ZipImageExtractor.Result.Failure).error.code)
            .isEqualTo(ErrorCode.ZIP_INVALID)
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
