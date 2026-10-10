package net.pocketnai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.*
import com.mikepenz.markdown.model.State as MarkdownRenderState
import kotlinx.coroutines.delay
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import net.pocketnai.domain.chat.ChatTextLayout

private val fencePattern = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val listPattern = Regex("(?m)^\\s*(?:[-+] |\\d+[.)] )")

/** Lazy reader pages retain fenced-code context without measuring a giant code block. */
internal fun markdownReaderPages(text: String): List<String> = markdownSections(text).flatMap { section ->
    if (section.length <= 1400) listOf(section)
    else {
        val first = section.lineSequence().first()
        val fence = fencePattern.find(first)?.groupValues?.get(1)
        if (fence == null) ChatTextLayout.pages(section)
        else {
            val lines = section.lineSequence().drop(1).toList()
            val closing = lines.lastOrNull()?.trim().orEmpty()
            val closed = closing.length >= fence.length && closing.all { it == fence.first() }
            val body = (if (closed) lines.dropLast(1) else lines).joinToString("\n")
            ChatTextLayout.pages(body).map { "$first\n$it\n$fence" }
        }
    }
}

/** Small display-only cache also bridges the streamed/final message handoff. Never persisted. */
private object ParsedChatMarkdown {
    private val values = object : LinkedHashMap<String, MarkdownRenderState.Success>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MarkdownRenderState.Success>) = size > 24
    }
    @Synchronized fun get(text: String) = values[text]
    @Synchronized fun put(text: String, state: MarkdownRenderState.Success) { values[text] = state }
}

/** Stable completed paragraphs keep their parser state; only the changing tail is reparsed. */
internal fun markdownSections(text: String): List<String> = buildList {
    val buffer = StringBuilder()
    var fence: String? = null
    text.lineSequence().forEach { line ->
        val marker = fencePattern.find(line)
        if (marker != null) {
            val run = marker.groupValues[1]
            val current = fence
            if (current == null) fence = run
            else if (run.first() == current.first() && run.length >= current.length && marker.groupValues[2].isBlank()) fence = null
        }
        if (line.isBlank() && fence == null && buffer.isNotEmpty()) {
            add(buffer.toString().trimEnd()); buffer.clear()
        } else {
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(line)
        }
    }
    if (buffer.isNotBlank()) add(buffer.toString().trimEnd())
}

/** Hide an unfinished inline marker in the projection, keeping the stored/wire text untouched. */
internal fun pendingMarkdown(text: String): String {
    if (text.trimStart().startsWith("```") || text.trimStart().startsWith("~~~")) return text
    var result = text
    for (marker in listOf("**", "__", "`")) {
        val inlineCode = if (marker == "`") emptyList() else Regex("(?s)(?<!\\\\)`[^`]*`").findAll(result).map { it.range }.toList()
        var open = -1
        var offset = 0
        while (offset < result.length) {
            val next = result.indexOf(marker, offset)
            if (next < 0) break
            if ((next == 0 || result[next - 1] != '\\') && inlineCode.none { next in it }) open = if (open < 0) next else -1
            offset = next + marker.length
        }
        if (open >= 0) result = result.removeRange(open, open + marker.length)
    }
    return result
}

@Composable
private fun revealStreamingText(text: String, streaming: Boolean): String {
    var shown by remember { mutableStateOf(if (streaming) "" else text) }
    val latest by rememberUpdatedState(text)
    LaunchedEffect(streaming) {
        if (!streaming) { shown = latest; return@LaunchedEffect }
        while (true) {
            val target = latest
            if (!target.startsWith(shown)) shown = target
            else if (shown.length < target.length) {
                val step = ((target.length - shown.length + 3) / 4).coerceIn(1, 64)
                var end = (shown.length + step).coerceAtMost(target.length)
                if (end < target.length && target[end].isLowSurrogate() && target[end - 1].isHighSurrogate()) end++
                shown = target.substring(0, end)
            }
            delay(24)
            withFrameNanos { } // The reveal ticker also sleeps when this UI stops producing frames.
        }
    }
    return if (streaming) shown else text
}

@Composable
internal fun ChatMarkdownText(text: String, streaming: Boolean = false, small: Boolean = false) {
    val displayed = revealStreamingText(text, streaming)
    val sections = remember(displayed) { markdownSections(displayed) }
    val style = if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sections.forEachIndexed { index, section ->
            key(index) {
                val projected = remember(section, streaming) { if (streaming) pendingMarkdown(section) else section }
                MarkdownSection(projected, style, Modifier.testTag("chat-markdown-block:$index"))
            }
        }
    }
}

@Composable
private fun MarkdownSection(text: String, style: TextStyle, modifier: Modifier) {
    // Plain prose does not need to construct an AST at all.
    val formatted = remember(text) { text.any { it in "*`_#[]>|~" } || listPattern.containsMatchIn(text) }
    if (!formatted) { SelectionContainer { Text(text, modifier, style = style) }; return }
    val parsed by produceState<MarkdownRenderState?>(ParsedChatMarkdown.get(text), text) {
        // A cancelled parse may still be finishing on Default: never share its mutable parser.
        val flavour = GFMFlavourDescriptor()
        val ready = ParsedChatMarkdown.get(text) ?: MarkdownState(Input(text, true, flavour, MarkdownParser(flavour), ReferenceLinkHandlerImpl())).parse()
        if (ready is MarkdownRenderState.Success) ParsedChatMarkdown.put(text, ready)
        value = ready
    }
    val uriHandler = LocalUriHandler.current
    val safeLinks = remember(uriHandler) { object : androidx.compose.ui.platform.UriHandler {
        override fun openUri(uri: String) {
            if (uri.startsWith("https://", true) || uri.startsWith("http://", true)) runCatching { uriHandler.openUri(uri) }
        }
    } }
    CompositionLocalProvider(LocalUriHandler provides safeLinks) {
        SelectionContainer {
            val current = parsed
            if (current is MarkdownRenderState.Success) Markdown(
                state = current, modifier = modifier.fillMaxWidth(),
                typography = markdownTypography(text = style, paragraph = style, ordered = style, bullet = style, list = style,
                    h1 = MaterialTheme.typography.headlineSmall, h2 = MaterialTheme.typography.titleLarge,
                    h3 = MaterialTheme.typography.titleMedium, h4 = MaterialTheme.typography.titleMedium),
                padding = markdownPadding(block = 0.dp, listItemTop = 2.dp, listItemBottom = 2.dp),
                animations = markdownAnimations(animateTextSize = { this }),
            ) else Text(text.replace("**", "").replace("__", "").replace("```", "").replace("`", ""), modifier, style = style)
        }
    }
}
