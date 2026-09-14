package net.pocketnai.ui.generate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import net.pocketnai.R
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.LabeledSlider
import net.pocketnai.ui.common.SectionHeader
import net.pocketnai.ui.common.messageRes
import net.pocketnai.ui.gallery.GalleryViewModel

/**
 * 参考图卡片：Image2Img 的起点图。
 *
 * ## 为什么现在是一张卡片而不是官方那样的分页
 * 官方把 Prompt / Reference Images / Chunks 做成同一层的三个分页，是因为它有三个
 * 各自独立的参考图面板（Image2Img / Vibe Transfer / Precise Reference）。
 * 当前只做 Image2Img 一张图，做分页只会让用户多点一次才能看到唯一一个面板。
 * 等 Vibe 与 Precise Reference 落地、真的出现"多个面板"时再引入分页
 * （见《参考图功能规划书》4.1 与阶段 D/E）。
 *
 * ## 尺寸为什么不能由用户随便选
 * 图生图的输出尺寸由源图比例决定：选定源图后这里会把 Resolution 设成对应的官方预设，
 * 并在卡片里写明"提交尺寸"。用户之后仍可改 Resolution，那时起点图会按新尺寸重新裁切 ——
 * 裁剪发生在**提交时**，所以改尺寸永远不需要重新选图。
 */
@Composable
fun ReferenceImageSection(
    state: GenerateViewModel.UiState,
    connected: Boolean,
    viewModel: GenerateViewModel,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    var historyPickerOpen by remember { mutableStateOf(false) }

    // 系统照片选择器：不需要任何存储权限，也不会让应用看到整个相册。
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.onReferencePicked(ReferenceSource.PickedUri(uri.toString()))
        }
    }

    SectionHeader(stringResource(R.string.generate_reference_title), modifier = modifier)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val source = state.referenceSource
            if (source == null) {
                Text(
                    text = stringResource(R.string.generate_reference_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pickImage.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        enabled = connected && !state.referenceBusy,
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Text(
                            text = stringResource(R.string.generate_reference_pick),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = { historyPickerOpen = true },
                        enabled = connected && !state.referenceBusy,
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Text(
                            text = stringResource(R.string.generate_reference_from_history),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            } else {
                ReferenceSummary(
                    source = source,
                    targetSizeLabel = state.referenceTargetSize?.label.orEmpty(),
                    onRemove = viewModel::onRemoveReference,
                )
                val strengthRange = state.profile.img2imgStrengthRange
                LabeledSlider(
                    label = stringResource(R.string.generate_reference_strength),
                    value = source.strength ?: state.profile.defaultImg2ImgStrength,
                    onValueChange = viewModel::onImg2imgStrengthChange,
                    valueRange = strengthRange.min..strengthRange.max,
                    decimals = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.generate_reference_strength_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.referenceBusy) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.generate_reference_importing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.referenceError?.let { error ->
                Text(
                    text = stringResource(error.code.messageRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = stringResource(R.string.generate_reference_billing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (historyPickerOpen) {
        HistoryImagePickerDialog(
            onPick = { relativePath ->
                historyPickerOpen = false
                viewModel.onReferencePicked(ReferenceSource.LocalPath(relativePath))
            },
            onDismiss = { historyPickerOpen = false },
        )
    }
}

/** 已选起点图的摘要：缩略图、源图尺寸、以及提交时会被裁切到的尺寸。 */
@Composable
private fun ReferenceSummary(
    source: ReferenceImage,
    targetSizeLabel: String,
    onRemove: () -> Unit,
) {
    val container = LocalAppContainer.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        AsyncImage(
            model = container.fileStore.resolve(source.relativePath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 72.dp, height = 72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.generate_reference_source_size,
                    source.width,
                    source.height,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            // 这句话是必需的：图生图的出图尺寸不是用户选的，而是按源图比例定的。
            if (targetSizeLabel.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.generate_reference_target_size, targetSizeLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.generate_reference_remove),
            )
        }
    }
}

/**
 * 从历史里选一张图作为起点。
 *
 * 这是图生图在手机端最常用的入口：拿一张自己刚生成的图继续改，
 * 比先去相册里翻半天自然得多。
 */
@Composable
internal fun HistoryImagePickerDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val container = LocalAppContainer.current
    val galleryViewModel: GalleryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { GalleryViewModel(container.generationRepository) }
        },
    )
    val items by galleryViewModel.items.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.generate_reference_history_title)) },
        text = {
            if (items.isEmpty()) {
                Text(stringResource(R.string.generate_reference_history_empty))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(360.dp),
                ) {
                    items(items, key = { it.imageId }) { item ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(item.width.toFloat() / item.height)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onPick(item.relativePath) },
                        ) {
                            AsyncImage(
                                model = container.fileStore.resolve(item.relativePath),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (item.prompt.isNotBlank()) {
                                Text(
                                    text = item.prompt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                                        .padding(horizontal = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
