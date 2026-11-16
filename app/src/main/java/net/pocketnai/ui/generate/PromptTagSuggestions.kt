package net.pocketnai.ui.generate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.prompt.PromptTagEditing
import net.pocketnai.ui.common.AnimatedDisclosure
import net.pocketnai.ui.motion.rememberPocketHaptics

/**
 * 全局与角色输入框共用补全；仅聚焦的输入框查询，结果必须同时匹配输入框和片段。
 *
 * 采用单行横向滑动 [LazyRow] 配合 [AnimatedDisclosure] 局部弹簧展开：
 * 避免多行 `FlowRow` 随候选词数量变化把整张表单反复上下顶跳百余像素。
 */
@Composable
internal fun PromptTagSuggestions(
    field: TextFieldValue,
    focused: Boolean,
    target: String,
    state: GenerateViewModel.UiState,
    onQuery: (String, String) -> Unit,
    onFieldChange: (TextFieldValue) -> Unit,
    macros: List<net.pocketnai.domain.model.PromptFavorite> = emptyList(),
) {
    val haptics = rememberPocketHaptics()
    DisposableEffect(target) { onDispose { onQuery("", target) } }

    val macro = if (focused && field.selection.collapsed) {
        Regex("(?<![^\\s,])@([^@,\\n]*)$").find(field.text.take(field.selection.end))
    } else null

    val macroMatches = if (macro != null) {
        LaunchedEffect(target) { onQuery("", target) }
        macros.filter { it.name.contains(macro.groupValues[1], ignoreCase = true) }.take(20)
    } else {
        emptyList()
    }

    val fragment = if (macro == null && focused && field.selection.collapsed) {
        PromptTagEditing.completionSpanAt(field.text, field.selection.end).textIn(field.text).trim()
    } else ""

    var filled by remember(target) { mutableStateOf<String?>(null) }
    var showAll by remember(fragment, target) { mutableStateOf(false) }

    if (macro == null) {
        LaunchedEffect(fragment, focused, target) {
            if (fragment.isEmpty()) filled = null
            onQuery(if (filled == fragment) "" else fragment, target)
        }
    }

    val suggestions = if (macro == null) {
        state.suggestions.takeIf {
            focused && state.suggestionTarget == target && state.suggestionQuery == fragment && filled != fragment
        }.orEmpty()
    } else {
        emptyList()
    }

    val activeMode = when {
        macroMatches.isNotEmpty() -> SuggestionStripMode.MACRO
        suggestions.isNotEmpty() -> SuggestionStripMode.TAG
        else -> SuggestionStripMode.NONE
    }

    var retainedMode by remember(target) { mutableStateOf(SuggestionStripMode.NONE) }
    var retainedMacros by remember(target) { mutableStateOf<List<net.pocketnai.domain.model.PromptFavorite>>(emptyList()) }
    var retainedTags by remember(target) { mutableStateOf<List<String>>(emptyList()) }
    if (activeMode == SuggestionStripMode.MACRO) {
        retainedMode = SuggestionStripMode.MACRO
        retainedMacros = macroMatches
    } else if (activeMode == SuggestionStripMode.TAG) {
        retainedMode = SuggestionStripMode.TAG
        retainedTags = suggestions
    }

    AnimatedDisclosure(visible = activeMode != SuggestionStripMode.NONE) {
        val displayMode = if (activeMode != SuggestionStripMode.NONE) activeMode else retainedMode
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (displayMode == SuggestionStripMode.MACRO) {
                val list = if (macroMatches.isNotEmpty()) macroMatches else retainedMacros
                Text("提示词宏", style = MaterialTheme.typography.labelSmall)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.id }) { saved ->
                        AssistChip(
                            onClick = {
                                val currentMacro = Regex("(?<![^\\s,])@([^@,\\n]*)$")
                                    .find(field.text.take(field.selection.end)) ?: return@AssistChip
                                haptics.tick()
                                val nextCharacter = field.text.getOrNull(field.selection.end)
                                val replacement = "!macro:${saved.name}!" +
                                    if (nextCharacter != null && !nextCharacter.isWhitespace() && nextCharacter != ',') ", " else ""
                                val next = field.text.replaceRange(currentMacro.range.first, field.selection.end, replacement)
                                onFieldChange(TextFieldValue(next, TextRange(currentMacro.range.first + replacement.length)))
                            },
                            label = { Text(saved.name) },
                        )
                    }
                }
            } else if (displayMode == SuggestionStripMode.TAG) {
                val list = if (suggestions.isNotEmpty()) suggestions else retainedTags
                val visibleTags = if (showAll) list else list.take(8)
                Text("标签建议", style = MaterialTheme.typography.labelSmall)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleTags, key = { it }) { suggestion ->
                        AssistChip(
                            onClick = {
                                haptics.tick()
                                val span = PromptTagEditing.completionSpanAt(field.text, field.selection.end)
                                val next = PromptTagEditing.applySuggestion(field.text, span, suggestion)
                                filled = suggestion
                                onFieldChange(TextFieldValue(next.text, TextRange(next.cursor)))
                                onQuery("", target)
                            },
                            label = { Text(suggestion) },
                        )
                    }
                    if (list.size > 8) {
                        item(key = "__more_toggle__") {
                            TextButton(onClick = { showAll = !showAll }) {
                                Text(if (showAll) "收起建议" else "更多建议（${list.size}）")
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class SuggestionStripMode {
    NONE,
    MACRO,
    TAG,
}
