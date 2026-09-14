package net.pocketnai.data.image

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.domain.metadata.ImageMetadataInspector
import net.pocketnai.domain.metadata.MetadataProbeResult
import net.pocketnai.domain.metadata.NovelAiMetadataParser
import net.pocketnai.domain.metadata.PngTextChunks
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * [ImageMetadataInspector] 的 Android 实现。
 *
 * ## 只做两件事
 * 1. 把 [ReferenceSource] 变成 [InputStream]（相册 URI 走 ContentResolver，历史图走私有目录）；
 * 2. 识别文件头，PNG 交给纯 Kotlin 的 [PngTextChunks] 解析，别的格式如实报告不支持。
 *
 * 解析逻辑一行都不在这里 —— 这样"元数据读得对不对"可以用 JVM 单元测试证明，
 * 不需要模拟器（与 [MaskImageProcessor] / 图片几何的划分方式一致）。
 *
 * ## 首版只支持 PNG
 * 官方允许把生成图下载成 WebP，而相册里的照片多是 JPEG。这两类都要**明确说"不支持"**，
 * 而不是含糊地说"这张图没有元数据" —— 后者会让用户以为文件被改过。
 */
class AndroidImageMetadataInspector(
    context: Context,
    private val fileStore: GenerationFileStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ImageMetadataInspector {

    private val appContext = context.applicationContext

    override suspend fun inspect(source: ReferenceSource): MetadataProbeResult =
        withContext(Dispatchers.IO) {
            try {
                when (source) {
                    is ReferenceSource.PickedUri -> {
                        val uri = Uri.parse(source.uri)
                        appContext.contentResolver.openInputStream(uri)?.use(::probe)
                            ?: MetadataProbeResult.Unreadable
                    }

                    is ReferenceSource.LocalPath -> {
                        val file = fileStore.resolve(source.relativePath)
                        if (!file.isFile) {
                            MetadataProbeResult.Unreadable
                        } else {
                            file.inputStream().buffered().use(::probe)
                        }
                    }
                }
            } catch (e: IOException) {
                MetadataProbeResult.Unreadable
            } catch (e: SecurityException) {
                // 相册 URI 的临时授权在进程重启后可能已失效。
                MetadataProbeResult.Unreadable
            }
        }

    private fun probe(rawStream: InputStream): MetadataProbeResult {
        val stream = BufferedInputStream(rawStream, BUFFER_BYTES)
        val header = ByteArray(MAGIC_BYTES)
        val read = stream.read(header)
        if (read < MAGIC_BYTES) return MetadataProbeResult.Unsupported(null)

        if (!header.copyOf(8).contentEquals(PNG_SIGNATURE)) {
            return MetadataProbeResult.Unsupported(mimeTypeOf(header))
        }

        // 已经是流的最前面，重新包一个把刚读掉的 8 字节拼回去的流。
        val replay = java.io.SequenceInputStream(header.inputStream(), stream)
        return when (val result = PngTextChunks.read(replay)) {
            is PngTextChunks.Result.Read -> {
                val metadata = NovelAiMetadataParser.parse(result.chunks, json)
                if (metadata == null) {
                    MetadataProbeResult.NotNovelAi
                } else {
                    MetadataProbeResult.Found(metadata)
                }
            }

            PngTextChunks.Result.NotPng -> MetadataProbeResult.Unsupported(mimeTypeOf(header))
            PngTextChunks.Result.Malformed -> MetadataProbeResult.NotNovelAi
            // 文本块过大/过多：这是异常输入，当成"没有元数据"处理，但不影响当参考图用。
            PngTextChunks.Result.TooLarge -> MetadataProbeResult.NotNovelAi
        }
    }

    /** 只认出我们愿意在界面上说出口的三种格式。 */
    private fun mimeTypeOf(header: ByteArray): String? = when {
        header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() ->
            "image/jpeg"

        header.size >= 12 &&
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"

        else -> null
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        const val MAGIC_BYTES = 12
        const val BUFFER_BYTES = 16 * 1024
    }
}
