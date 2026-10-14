package net.pocketnai.data.artistlab

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pocketnai.domain.artistlab.ArtistCatalogImport
import net.pocketnai.domain.artistlab.ArtistCatalogSelection
import java.io.ByteArrayOutputStream

class ArtistCatalogReader(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun read(uri: Uri): ArtistCatalogSelection = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream()
        checkNotNull(resolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= ArtistCatalogImport.MAX_BYTES) { "文件不能超过 2 MiB" }
                bytes.write(buffer, 0, count)
            }
        }
        val text = try { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) }
        catch (_: Exception) { throw IllegalArgumentException("请将画师文件保存为 UTF-8 编码") }
        ArtistCatalogImport.parse(text)
    }
}
