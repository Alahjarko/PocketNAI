package net.pocketnai.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.domain.model.PromptFavorite
import net.pocketnai.domain.model.PromptFavoriteKind
import net.pocketnai.domain.model.PromptTarget

/**
 * 保存收藏的对话框。
 *
 * [kind] 由调用方决定：提示词框里有选中文字时存成标签，没有选中就存成整条提示词。
 * 这样用户只需要面对一个"收藏"按钮，不用先想清楚要往哪个抽屉里放。
 */
@Composable
fun SaveFavoriteDialog(
    kind: PromptFavoriteKind,
    content: String,
    initialName: String,
    target: PromptTarget,
    onConfirm: (name: String, category: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var category by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (kind == PromptFavoriteKind.TAG) {
                        R.string.favorite_save_title_tag
                    } else {
                        R.string.favorite_save_title_prompt
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.favorite_save_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(stringResource(R.string.favorite_save_category)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 让人在保存前看清到底存了什么，尤其是"选中一段文字存标签"这条路径。
                Text(
                    text = stringResource(
                        if (target == PromptTarget.NEGATIVE) {
                            R.string.favorite_save_target_negative
                        } else {
                            R.string.favorite_save_target_positive
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, category) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * 收藏夹选择器：搜索 + 提示词/标签切换 + 分组列表。
 *
 * 点一行是"追加到末尾"，长按用菜单选"替换整条"或"删除" —— 追加是最常用的动作，
 * 所以给它最省事的手势；会覆盖用户已有内容的操作收进菜单里。
 */
@Composable
fun FavoritePickerDialog(
    state: PromptFavoritesViewModel.UiState,
    onQueryChange: (String) -> Unit,
    onKindChange: (PromptFavoriteKind) -> Unit,
    onAppend: (PromptFavorite) -> Unit,
    onReplace: (PromptFavorite) -> Unit,
    onDelete: (PromptFavorite) -> Unit,
    onDismiss: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.favorites_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.favorites_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.kind == PromptFavoriteKind.PROMPT,
                        onClick = { onKindChange(PromptFavoriteKind.PROMPT) },
                        label = {
                            Text(
                                stringResource(R.string.favorites_tab_prompts) +
                                    " (${state.promptCount})",
                            )
                        },
                    )
                    FilterChip(
                        selected = state.kind == PromptFavoriteKind.TAG,
                        onClick = { onKindChange(PromptFavoriteKind.TAG) },
                        label = {
                            Text(
                                stringResource(R.string.favorites_tab_tags) +
                                    " (${state.tagCount})",
                            )
                        },
                    )
                }

                state.notice?.let { notice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissNotice) {
                            Text(stringResource(R.string.action_confirm))
                        }
                    }
                }

                HorizontalDivider()

                if (state.isEmpty) {
                    Text(
                        text = stringResource(R.string.favorites_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        state.grouped.forEach { (category, favorites) ->
                            item(key = "header-$category") {
                                Text(
                                    text = category,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                )
                            }
                            items(favorites, key = { it.id }) { favorite ->
                                FavoriteRow(
                                    favorite = favorite,
                                    onAppend = { onAppend(favorite) },
                                    onReplace = { onReplace(favorite) },
                                    onDelete = { onDelete(favorite) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        },
    )
}

@Composable
private fun FavoriteRow(
    favorite: PromptFavorite,
    onAppend: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // 名称通常就是从内容派生的（整条收藏尤其如此），此时只显示一行，
            // 重复两遍同样的文字纯属噪音。
            if (favorite.content == favorite.name) {
                Text(
                    text = favorite.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = favorite.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = favorite.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        TextButton(onClick = onAppend) {
            Text(stringResource(R.string.favorites_action_append))
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favorites_action_replace)) },
                    onClick = {
                        menuExpanded = false
                        onReplace()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favorites_action_delete)) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}
