package net.pocketnai.domain.artistlab

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Locale

/** 文件只声明标签和来源，不把自报的活跃度或人气当作审核结果。 */
object ArtistCatalogImport {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ARTISTS = 5000
    private val json = Json { ignoreUnknownKeys = true }
    private val namePattern = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} .()'\\-]{0,99}")
    val template: String get() = ArtistCatalogExport.configuration(listOf(ArtistCatalogEntry(
        tag = "artist: mignon", name = "Mignon", sourceName = "我的画师来源",
        notes = "这是格式示例，请换成新找到的画师。请在这里附近期作品日期与链接、人气证据、Danbooru 标签链接及画风说明；不因 NSFW 创作经历排除作者。",
    )), emptySet())

    fun tag(raw: String): String {
        val text = raw.trim().replaceFirst(Regex("^artist\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ').lowercase(Locale.ROOT).replace(Regex(" +"), " ")
        require(namePattern.matches(text)) { "画师标签格式不正确；请每行只填写一个画师，不包含权重或其他提示词" }
        return "artist: $text"
    }

    fun parse(text: String): ArtistCatalogSelection {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "文件不能超过 2 MiB" }
        val content = text.removePrefix("\uFEFF").trim()
        require(content.isNotEmpty()) { "文件中没有画师" }
        val selection = if (content.startsWith("{")) {
            try { json.decodeFromString<ArtistCatalogSelection>(content) }
            catch (_: Exception) { throw IllegalArgumentException("JSON 格式不正确，请使用画师来源模板或导出的配置文件") }
        } else {
            require(!content.startsWith("[")) { "JSON 顶层应包含 artists 字段，请使用画师来源模板" }
            val lines = content.lineSequence().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.toList()
            require(lines.size <= MAX_ARTISTS) { "每个文件最多包含 5000 位画师" }
            ArtistCatalogSelection(artists = lines.mapIndexed { index, line ->
                val canonical = try { tag(line) } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("第 ${index + 1} 个画师标签格式不正确，请去掉权重和其他提示词")
                }
                ArtistCatalogEntry(canonical, canonical.removePrefix("artist: "))
            })
        }
        require(selection.schemaVersion in 1..2) { "暂不支持此画师文件版本" }
        require(selection.artists.size in 1..MAX_ARTISTS && selection.excluded.size <= MAX_ARTISTS) { "文件应包含 1～5000 位画师" }
        require(selection.sourceName.length <= 120 && selection.sourceName.none(Char::isISOControl)) { "来源名称过长或包含控制字符" }
        val entries = selection.artists.map { entry ->
            val canonical = tag(entry.tag)
            require(entry.name.length <= 120 && entry.name.none(Char::isISOControl)) { "画师显示名过长或包含控制字符" }
            require(entry.sourceName.length <= 120 && entry.sourceName.none(Char::isISOControl)) { "来源名称过长或包含控制字符" }
            require(entry.notes.length <= 2000) { "每位画师的说明不能超过 2000 字" }
            require(entry.profileUrl.length <= 2048 && validProfile(entry.profileUrl)) { "主页链接必须是 Pixiv 或 X 的 HTTPS 链接" }
            // JSON 不含图片；不信任外部文件声明的 assets 路径，也不自动下载图片。
            entry.copy(tag = canonical, name = entry.name.ifBlank { canonical.removePrefix("artist: ") },
                sourceName = entry.sourceName.ifBlank { selection.sourceName }, previews = emptyList())
        }.distinctBy { it.tag }
        val excluded = selection.excluded.map(::tag).distinct()
        val tags = entries.map { it.tag }.toSet()
        require(excluded.all { it in tags }) { "移出名单中包含文件没有列出的画师" }
        return selection.copy(artists = entries, excluded = excluded)
    }

    private fun validProfile(url: String): Boolean = url.isBlank() || runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host?.lowercase(Locale.ROOT) in setOf("pixiv.net", "www.pixiv.net", "x.com", "www.x.com", "twitter.com", "www.twitter.com")
    }.getOrDefault(false)

    /** 已有标签与用户的移出状态优先，新条目才采用文件中的启用状态。 */
    fun merge(current: List<ArtistCatalogEntry>, excluded: Set<String>, incoming: ArtistCatalogSelection): ArtistCatalogSelection {
        val existing = current.map { it.tag }.toSet()
        val added = incoming.artists.filterNot { it.tag in existing }
        require(current.size + added.size <= MAX_ARTISTS) { "画师库最多保存 5000 位画师" }
        return ArtistCatalogSelection(artists = current + added,
            excluded = (excluded + incoming.excluded.filter { it !in existing }).sorted())
    }
}
