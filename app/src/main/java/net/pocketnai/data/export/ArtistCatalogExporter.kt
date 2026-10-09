package net.pocketnai.data.export

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pocketnai.domain.artistlab.ArtistCatalogEntry
import net.pocketnai.domain.artistlab.ArtistCatalogExport
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ArtistCatalogExportFormat { TEXT, JSON, ZIP }

class ArtistCatalogExporter(context: Context) {
    private val app = context.applicationContext

    suspend fun export(uri: Uri, format: ArtistCatalogExportFormat, entries: List<ArtistCatalogEntry>, excluded: Set<String>) = withContext(Dispatchers.IO) {
        val configuration = ArtistCatalogExport.configuration(entries, excluded)
        val tags = ArtistCatalogExport.enabledTags(entries, excluded)
        val output = checkNotNull(app.contentResolver.openOutputStream(uri, "wt"))
        output.use {
            when (format) {
                ArtistCatalogExportFormat.TEXT -> it.write(tags.toByteArray(Charsets.UTF_8))
                ArtistCatalogExportFormat.JSON -> it.write(configuration.toByteArray(Charsets.UTF_8))
                ArtistCatalogExportFormat.ZIP -> ZipOutputStream(it).use { zip ->
                    fun text(name: String, value: String) {
                        zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                    }
                    text("artist-catalog.json", configuration)
                    text("enabled-artists.txt", tags)
                    text("README.txt", "作品版权属于各原作者。artist-catalog.json 保留原作链接和移出名单。\n")
                    entries.flatMap { e -> e.previews }.distinctBy { preview -> preview.asset }.forEach { preview ->
                        check(preview.asset.matches(Regex("[a-f0-9]{16}-[01]\\.jpg")))
                        zip.putNextEntry(ZipEntry("previews/${preview.asset}"))
                        app.assets.open("artist-lab/previews/${preview.asset}").use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
    }
}
