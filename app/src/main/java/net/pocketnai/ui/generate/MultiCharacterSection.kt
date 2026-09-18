package net.pocketnai.ui.generate

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.model.CharacterPosition
import net.pocketnai.domain.model.CharacterPrompt

private const val MAX_CHARACTERS = 5

@Composable
fun MultiCharacterSection(
    characters: List<CharacterPrompt>,
    onAddCharacter: () -> Unit,
    onRemoveCharacter: (index: Int) -> Unit,
    onUpdateCharacter: (index: Int, character: CharacterPrompt) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(characters.isNotEmpty()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 顶层栏：未展开或无角色时为轻量条目
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = {
                    if (characters.isEmpty()) {
                        onAddCharacter()
                        expanded = true
                    } else {
                        expanded = !expanded
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                label = {
                    Text(
                        if (characters.isEmpty()) {
                            "+ 添加独立角色 (0/$MAX_CHARACTERS)"
                        } else {
                            "独立角色 (${characters.size}/$MAX_CHARACTERS)" + if (expanded) " (收起)" else " (展开)"
                        }
                    )
                },
            )

            if (characters.isNotEmpty() && expanded && characters.size < MAX_CHARACTERS) {
                TextButton(onClick = onAddCharacter) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("添加角色", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        // 展开列表
        if (expanded && characters.isNotEmpty()) {
            characters.forEachIndexed { index, character ->
                CharacterCard(
                    index = index,
                    character = character,
                    onUpdate = { onUpdateCharacter(index, it) },
                    onDelete = { onRemoveCharacter(index) },
                )
            }
        }
    }
}

@Composable
private fun CharacterCard(
    index: Int,
    character: CharacterPrompt,
    onUpdate: (CharacterPrompt) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "角色 ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "删除角色",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // 角色正向词
            OutlinedTextField(
                value = character.prompt,
                onValueChange = { onUpdate(character.copy(prompt = it)) },
                label = { Text("角色特征提示词 (例如 1girl, blue hair, witch hat)") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )

            // 角色负向词
            OutlinedTextField(
                value = character.negativePrompt,
                onValueChange = { onUpdate(character.copy(negativePrompt = it)) },
                label = { Text("角色专属排除词 (可选)") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )

            // 位置快捷选择：5 档横向位置
            val currentPos = CharacterPosition.fromCoords(character.centerX, character.centerY)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "位置布局：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CharacterPosition.entries.forEach { pos ->
                        val selected = currentPos == pos
                        FilterChip(
                            selected = selected,
                            onClick = {
                                onUpdate(character.copy(centerX = pos.x, centerY = pos.y))
                            },
                            label = { Text(pos.label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
