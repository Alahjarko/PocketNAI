package net.pocketnai.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import net.pocketnai.core.Hashing
import net.pocketnai.data.network.ZipImageExtractor
import net.pocketnai.domain.image.PixelRegion
import net.pocketnai.domain.image.PixelSize
import net.pocketnai.domain.metadata.PngTextChunks
import net.pocketnai.domain.metadata.PngTextWriter
import net.pocketnai.domain.metadata.PocketNaiOutputMetadata
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 生成结果的裁切与元数据保全（仪器化测试）。
 *
 * 这一段必须用 Android 的位图 API，因此只能在设备上验证 —— 但它恰恰是最不能猜的部分：
 * 裁错位置、裁完丢了元数据，都不会有任何报错，只会让用户拿到一张尺寸不对的图，
 * 以及让元数据导入功能在"裁过一次"之后静默失效。
 */
@RunWith(AndroidJUnit4::class)
class OutputImageProcessorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 造一张 1920×1088 的 PNG：三行标记色 + NovelAI 风格的文本块，用于验证裁切位置。 */
    private fun buildCanvas(width: Int = 1920, height: Int = 1088): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // 逐行填色：y=0 红、y=4..height-5 绿、最后一行蓝。
        // 裁掉上下各 4 px 之后，第一行应该正好是绿色 —— 这就是"裁对了位置"的判据。
        val pixels = IntArray(width)
        for (y in 0 until height) {
            val color = when {
                y < 4 -> Color.RED
                y >= height - 4 -> Color.BLUE
                else -> Color.GREEN
            }
            java.util.Arrays.fill(pixels, color)
            bitmap.setPixels(pixels, 0, width, 0, y, width, 1)
        }
        val png = ByteArrayOutputStream().use { buffer ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
            buffer.toByteArray()
        }
        bitmap.recycle()
        // 加上真实文件里那几块元数据（内容为构造值）。
        return PngTextWriter.withTextChunks(
            png = png,
            chunks = listOf(
                PngTextChunks.TextChunk("Comment", """{"prompt":"1girl","width":1920,"height":1088}"""),
                PngTextChunks.TextChunk("Software", "NovelAI"),
                PngTextChunks.TextChunk("Source", "NovelAI Diffusion V5 DB276663"),
                PngTextChunks.TextChunk("Generation_time", "1.5"),
                // 不在白名单里的块不应该被带走。
                PngTextChunks.TextChunk("UnknownTool", "should-not-survive"),
            ),
        )
    }

    private fun extractedFrom(file: File, bytes: ByteArray, width: Int, height: Int) =
        ZipImageExtractor.ExtractedImage(
            ordinal = 1,
            file = file,
            byteSize = bytes.size.toLong(),
            sha256 = Hashing.sha256(bytes),
            width = width,
            height = height,
        )

    @Test
    fun cropsToTargetSizeAndKeepsNovelAiMetadata() {
        val dir = File(context.cacheDir, "output-crop-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, "0001.png")
        val original = buildCanvas()
        file.writeBytes(original)

        val result = OutputImageProcessor().apply(
            images = listOf(extractedFrom(file, original, 1920, 1088)),
            crop = PixelRegion(x = 0, y = 4, width = 1920, height = 1080),
            canvas = PixelSize(1920, 1088),
        )

        val cropped = result.single()
        assertThat(cropped.width).isEqualTo(1920)
        assertThat(cropped.height).isEqualTo(1080)

        // 文件、体积、哈希三者必须自洽：数据库里记的就是这三个值。
        val bytes = file.readBytes()
        assertThat(cropped.byteSize).isEqualTo(bytes.size.toLong())
        assertThat(cropped.sha256).isEqualTo(Hashing.sha256(bytes))
        assertThat(cropped.sha256).isNotEqualTo(Hashing.sha256(original))

        // 裁切位置：原来 y=4 的那一行（绿色）应该变成新图的第一行。
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        assertThat(decoded.width).isEqualTo(1920)
        assertThat(decoded.height).isEqualTo(1080)
        assertThat(decoded.getPixel(10, 0)).isEqualTo(Color.GREEN)
        assertThat(decoded.getPixel(10, 1079)).isEqualTo(Color.GREEN)
        decoded.recycle()

        // 元数据：白名单里的原样保留，未知块丢掉，再补一条我们自己的输出信息。
        val chunks = PngTextWriter.readTextChunks(bytes).associate { it.keyword to it.text }
        assertThat(chunks["Software"]).isEqualTo("NovelAI")
        assertThat(chunks["Source"]).isEqualTo("NovelAI Diffusion V5 DB276663")
        assertThat(chunks["Comment"]).contains(""""width":1920,"height":1088""")
        assertThat(chunks).doesNotContainKey("UnknownTool")

        val output = PocketNaiOutputMetadata.decode(chunks[PocketNaiOutputMetadata.KEYWORD])
        assertThat(output).isNotNull()
        assertThat(output!!.outputWidth).isEqualTo(1920)
        assertThat(output.outputHeight).isEqualTo(1080)
        assertThat(output.generationWidth).isEqualTo(1920)
        assertThat(output.generationHeight).isEqualTo(1088)
        assertThat(output.cropX).isEqualTo(0)
        assertThat(output.cropY).isEqualTo(4)
    }

    @Test
    fun leavesBytesUntouchedWhenNoCropRequested() {
        val dir = File(context.cacheDir, "output-crop-noop").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, "0001.png")
        val original = buildCanvas(width = 832, height = 1216)
        file.writeBytes(original)
        val extracted = extractedFrom(file, original, 832, 1216)

        val result = OutputImageProcessor().apply(
            images = listOf(extracted),
            crop = null,
            canvas = null,
        )

        // 预设尺寸的历史记录完全不受新功能影响：不重新编码，哈希与体积保持原样。
        assertThat(result).isEqualTo(listOf(extracted))
        assertThat(file.readBytes()).isEqualTo(original)
    }

    @Test
    fun keepsOriginalWhenCropRectExceedsImage() {
        val dir = File(context.cacheDir, "output-crop-invalid").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, "0001.png")
        val original = buildCanvas(width = 832, height = 1216)
        file.writeBytes(original)
        val extracted = extractedFrom(file, original, 832, 1216)

        val result = OutputImageProcessor().apply(
            images = listOf(extracted),
            // 故意给一个越界的范围：宁可原样保留，也不能写出一个尺寸对不上的文件。
            crop = PixelRegion(x = 0, y = 1000, width = 832, height = 1216),
            canvas = PixelSize(832, 1216),
        )

        assertThat(result.single()).isEqualTo(extracted)
        assertThat(file.readBytes()).isEqualTo(original)
    }
}
