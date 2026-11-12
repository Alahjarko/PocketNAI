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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import net.pocketnai.ui.common.MotionIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import net.pocketnai.ui.common.MotionOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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
import net.pocketnai.ui.common.messageRes
import net.pocketnai.ui.gallery.GalleryViewModel

/**
 * 图生图面板：Image2Img 的起点图。作为「参考图」卡片的一个页签，
 * 由 [ReferenceTabsSection] 承载，不再自带标题与卡片。
 *
 * ## 尺寸为什么不能由用户随便选
 * 图生图的输出尺寸由源图比例决定：选定源图后这里会把 Resolution 设成对应的官方预设，
 * 并在卡片里写明"提交尺寸"。用户之后仍可改 Resolution，那时起点图会按新尺寸重新裁切 ——
 * 裁剪发生在**提交时**，所以改尺寸永远不需要重新选图。
 */
@Composable
fun Img2ImgPanel(
    state: GenerateViewModel.UiState,
    connected: Boolean,
    viewModel: GenerateViewModel,
    /** 打开全屏蒙版编辑器。底图未就绪时按钮不可点。 */
    onOpenInpaintEditor: () -> Unit,
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

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val source = state.referenceSource
        if (source == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    enabled = connected && !state.referenceBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Text(stringResource(R.string.generate_reference_pick))
                    }
                }
                OutlinedCard(
                    onClick = { historyPickerOpen = true },
                    enabled = connected && !state.referenceBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Text(stringResource(R.string.generate_reference_from_history))
                    }
                }
            }
        }
        AnimatedReferenceList(listOfNotNull(source)) { displayedSource ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReferenceSummary(
                source = displayedSource,
                targetSizeLabel = state.referenceTargetSize?.label.orEmpty(),
                mask = state.inpaintMask,
                onRemove = viewModel::onRemoveReference,
            )
            ReferenceOriginLink(displayedSource)

            // 局部重绘入口：底图就绪才出现。
            if (!state.supportsInpaint) {
                // 不支持的模型说清楚原因，而不是只把按钮藏起来。
                // （当前四个模型都支持，这个分支留给将来可能出现的不支持模型。）
                Text(
                    text = stringResource(
                        R.string.inpaint_needs_base,
                        state.profile.displayName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                val mask = state.inpaintMask
                OutlinedButton(
                    onClick = onOpenInpaintEditor,
                    enabled = connected && !state.referenceBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (mask == null) {
                            stringResource(R.string.inpaint_open_editor)
                        } else {
                            stringResource(R.string.inpaint_masked)
                        },
                    )
                }
                if (mask != null) {
                    TextButton(onClick = viewModel::onInpaintMaskCleared) {
                        Text(stringResource(R.string.inpaint_clear))
                    }
                }
            }
            val strengthRange = state.profile.img2imgStrengthRange
            LabeledSlider(
                label = stringResource(R.string.generate_reference_strength),
                value = displayedSource.strength ?: state.profile.defaultImg2ImgStrength,
                onValueChange = viewModel::onImg2imgStrengthChange,
                valueRange = strengthRange.min..strengthRange.max,
                decimals = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }

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
    mask: ReferenceImage?,
    onRemove: () -> Unit,
) {
    val container = LocalAppContainer.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ReferenceThumbnail(container.fileStore.resolve(source.relativePath),
            mask?.let { container.fileStore.resolve(it.relativePath) }, Modifier.size(72.dp))
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

/** Stored masks are black/white in source-image coordinates; both layers share the same crop. */
@Composable
internal fun ReferenceThumbnail(source: java.io.File, mask: java.io.File?, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val tint = remember(color) { ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        0f, 0f, 0f, 0f, color.red * 255f,
        0f, 0f, 0f, 0f, color.green * 255f,
        0f, 0f, 0f, 0f, color.blue * 255f,
        0.6f, 0f, 0f, 0f, 0f,
    ))) }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
        AsyncImage(source, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (mask != null) AsyncImage(mask, "重绘区域", Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, colorFilter = tint)
    }
}

/**
 * 从历史里选一张图作为起点。
 *
 * 这是图生图在手机端最常用的入口：拿一张自己刚生成的图继续改，
 * 比先去相册里翻半天自然得多。
 */
