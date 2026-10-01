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
) {
    DisposableEffect(target) { onDispose { onQuery("", target) } }
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
