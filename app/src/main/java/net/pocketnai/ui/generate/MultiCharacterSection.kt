package net.pocketnai.ui.generate

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.model.CharacterPosition
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.ui.common.WeightHighlightedTextField

@Composable
fun MultiCharacterSection(
    characters: List<CharacterPrompt>,
    onAddCharacter: () -> Unit,
    onRemoveCharacter: (index: Int) -> Unit,
    onUpdateCharacter: (index: Int, character: CharacterPrompt) -> Unit,
    modifier: Modifier = Modifier,
    textRevision: Int = 0,
    suggestionState: GenerateViewModel.UiState? = null,
    onSuggestionQuery: (String, String) -> Unit = { _, _ -> },
    canvasSize: ImageSizePreset = ImageSizePreset(1024, 1024),
    maxCharacters: Int = CharacterPrompt.MAX_COUNT,
    useCoordinates: Boolean = false,
    onCoordinateModeChange: (Boolean) -> Unit = {},
    macros: List<net.pocketnai.domain.model.PromptFavorite> = emptyList(),
) {
    var expanded by remember { mutableStateOf(characters.isNotEmpty()) }
    var positionsOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = {
                    if (characters.isEmpty()) { onAddCharacter(); expanded = true }
                    else expanded = !expanded
                },
                leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(FilterChipDefaults.IconSize)) },
                label = {
                    Text(if (characters.isEmpty()) "+ 添加独立角色 (0/$maxCharacters)"
                        else "独立角色 (${characters.size}/$maxCharacters)" + if (expanded) " (收起)" else " (展开)")
                },
            )
            if (expanded && characters.isNotEmpty() && characters.size < maxCharacters) {
                TextButton(onClick = onAddCharacter) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                    Text("添加", Modifier.padding(start = 4.dp))
                }
            }
        }
        if (expanded && characters.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !useCoordinates, onClick = { onCoordinateModeChange(false) }, label = { Text("AI 自动安排") })
                FilterChip(selected = useCoordinates, onClick = { onCoordinateModeChange(true) }, label = { Text("手动位置") })
            }
            if (useCoordinates) TextButton(onClick = { positionsOpen = true }) { Text("在画布上设置角色位置") }
            characters.forEachIndexed { index, character ->
                key(character.id) {
                    CharacterCard(
                        index, character, textRevision, suggestionState, onSuggestionQuery,
                        onUpdate = { onUpdateCharacter(index, it) },
                        onDelete = { onRemoveCharacter(index) },
                        showPosition = useCoordinates,
                        macros = macros,
                    )
                }
            }
        }
    }
    if (positionsOpen && characters.isNotEmpty()) {
        CharacterPositionDialog(
            characters = characters,
            canvasSize = canvasSize,
            onUpdate = onUpdateCharacter,
            onDismiss = { positionsOpen = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterCard(
    index: Int,
    character: CharacterPrompt,
    textRevision: Int,
    suggestionState: GenerateViewModel.UiState?,
    onSuggestionQuery: (String, String) -> Unit,
    onUpdate: (CharacterPrompt) -> Unit,
    onDelete: () -> Unit,
    showPosition: Boolean,
    macros: List<net.pocketnai.domain.model.PromptFavorite>,
) {
    var positive by remember(character.id, textRevision) {
        mutableStateOf(TextFieldValue(character.prompt, TextRange(character.prompt.length)))
    }
    var negative by remember(character.id, textRevision) {
        mutableStateOf(TextFieldValue(character.negativePrompt, TextRange(character.negativePrompt.length)))
    }
    var focused by remember { mutableStateOf(false) }
    var negativeFocused by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("角色 ${index + 1}", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, "删除角色", tint = MaterialTheme.colorScheme.error)
                }
            }
            WeightHighlightedTextField(
                value = positive,
                onValueChange = { positive = it; onUpdate(character.copy(prompt = it.text)) },
                onFocusChanged = { focused = it },
                label = "角色特征提示词",
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            suggestionState?.let { state ->
                PromptTagSuggestions(
                    positive, focused, "character:${character.id}", state, onSuggestionQuery,
                    onFieldChange = { positive = it; onUpdate(character.copy(prompt = it.text)) },
                    macros = macros,
                )
            }
            CollapsedNegativePrompt("角色专属排除词", negative.text.isNotBlank()) {
                WeightHighlightedTextField(
                    value = negative,
                    onValueChange = { negative = it; onUpdate(character.copy(negativePrompt = it.text)) },
                    label = null,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    onFocusChanged = { negativeFocused = it },
                )
                suggestionState?.let { state ->
                    PromptTagSuggestions(
                        negative, negativeFocused, "character-negative:${character.id}", state, onSuggestionQuery,
                        onFieldChange = { negative = it; onUpdate(character.copy(negativePrompt = it.text)) },
                        macros = macros,
                    )
                }
            }
            if (showPosition) {
                val selected = CharacterPosition.entries.firstOrNull {
                    it.x == character.centerX && it.y == character.centerY
                }
                Text("位置：" + (selected?.label ?: "x=${"%.2f".format(java.util.Locale.ROOT, character.centerX)} / y=${"%.2f".format(java.util.Locale.ROOT, character.centerY)}"),
                    style = MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CharacterPosition.entries.forEach { pos ->
                        FilterChip(selected = selected == pos,
                            onClick = { onUpdate(character.copy(centerX = pos.x, centerY = pos.y)) },
                            label = { Text(pos.label) })
                    }
                }
            }
        }
    }
}
