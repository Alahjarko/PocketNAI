package net.pocketnai.ui.generate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.prompt.PromptTagEditing

/** 全局与角色输入框共用补全；仅聚焦的输入框查询，结果必须同时匹配输入框和片段。 */
@OptIn(ExperimentalLayoutApi::class)
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
    DisposableEffect(target) { onDispose { onQuery("", target) } }
    val macro = if (focused && field.selection.collapsed) {
        Regex("(?<![^\\s,])@([^@,\\n]*)$").find(field.text.take(field.selection.end))
    } else null
    if (macro != null) {
        LaunchedEffect(target) { onQuery("", target) }
        val matches = macros.filter { it.name.contains(macro.groupValues[1], ignoreCase = true) }.take(20)
        if (matches.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("提示词宏", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                matches.forEach { saved ->
                    AssistChip(onClick = {
                        val nextCharacter = field.text.getOrNull(field.selection.end)
                        val replacement = "!macro:${saved.name}!" +
                            if (nextCharacter != null && !nextCharacter.isWhitespace() && nextCharacter != ',') ", " else ""
                        val next = field.text.replaceRange(macro.range.first, field.selection.end, replacement)
                        onFieldChange(TextFieldValue(next, TextRange(macro.range.first + replacement.length)))
                    }, label = { Text(saved.name) })
                }
            }
        }
        return
    }
    val fragment = if (focused && field.selection.collapsed) {
        PromptTagEditing.completionSpanAt(field.text, field.selection.end).textIn(field.text).trim()
    } else ""
    var filled by remember(target) { mutableStateOf<String?>(null) }
    var showAll by remember(fragment, target) { mutableStateOf(false) }
    LaunchedEffect(fragment, focused, target) {
        if (fragment.isEmpty()) filled = null
        onQuery(if (filled == fragment) "" else fragment, target)
    }
    val suggestions = state.suggestions.takeIf {
        focused && state.suggestionTarget == target && state.suggestionQuery == fragment && filled != fragment
    }.orEmpty()
    if (suggestions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("标签建议", style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (showAll) suggestions else suggestions.take(8)).forEach { suggestion ->
                AssistChip(
                    onClick = {
                        val span = PromptTagEditing.completionSpanAt(field.text, field.selection.end)
                        val next = PromptTagEditing.applySuggestion(field.text, span, suggestion)
                        filled = suggestion
                        onFieldChange(TextFieldValue(next.text, TextRange(next.cursor)))
                        onQuery("", target)
                    },
                    label = { Text(suggestion) },
                )
            }
        }
        if (suggestions.size > 8) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "收起建议" else "更多建议（${suggestions.size}）")
            }
        }
    }
}
