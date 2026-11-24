package net.pocketnai.data.artistlab

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pocketnai.domain.artistlab.ArtistCatalogImport
import net.pocketnai.domain.artistlab.ArtistCatalogSelection
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class ArtistCatalogReader(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun read(uri: Uri): ArtistCatalogSelection = withContext(Dispatchers.IO) {
        val stream = BufferedInputStream(checkNotNull(resolver.openInputStream(uri)))
        stream.use { input ->
            input.mark(8)
            val header = ByteArray(4)
            val headerRead = input.read(header)
            input.reset()
            val isZip = headerRead >= 4 &&
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                (header[2] == 0x03.toByte() || header[2] == 0x05.toByte()) &&
                (header[3] == 0x04.toByte() || header[3] == 0x06.toByte())
            val text = if (isZip) readFromZip(input) else readBoundedUtf8(input)
            ArtistCatalogImport.parse(text)
        }
    }

    private fun readFromZip(input: InputStream): String {
        var jsonText: String? = null
        var fallbackTxt: String? = null
        var entriesVisited = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entriesVisited++
                require(entriesVisited <= 20_000) { "ZIP 压缩包内文件过多，无法解析" }
                if (!entry.isDirectory) {
                    val normalized = entry.name.replace('\\', '/').trimStart('/')
                    val ignoreMac = normalized.startsWith("__MACOSX/") || normalized.substringAfterLast('/').startsWith("._")
                    if (!ignoreMac) {
                        val fileName = normalized.substringAfterLast('/').lowercase()
                        when {
                            fileName == "artist-catalog.json" -> {
                                jsonText = readBoundedUtf8(zip)
                                break
                            }
                            fileName.endsWith(".json") && jsonText == null -> {
                                jsonText = readBoundedUtf8(zip)
                            }
                            fileName == "enabled-artists.txt" && fallbackTxt == null -> {
                                fallbackTxt = readBoundedUtf8(zip)
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return jsonText ?: fallbackTxt
            ?: throw IllegalArgumentException("ZIP 压缩包中未找到 artist-catalog.json 或画师名单文件")
    }

    private fun readBoundedUtf8(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= ArtistCatalogImport.MAX_BYTES) { "配置文件不能超过 2 MiB" }
            bytes.write(buffer, 0, count)
        }
        return try {
            bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            throw IllegalArgumentException("请将画师文件保存为 UTF-8 编码（或使用本应用导出的 JSON / ZIP）")
        }
    }
}
