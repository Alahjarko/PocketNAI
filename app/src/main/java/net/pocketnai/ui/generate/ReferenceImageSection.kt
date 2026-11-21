package net.pocketnai.ui.generate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import net.pocketnai.ui.motion.pressFeedback
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import net.pocketnai.ui.common.MotionIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import net.pocketnai.ui.common.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
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
        androidx.compose.animation.AnimatedVisibility(visible = source == null,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100)) +
                androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(200))) {
            ReferenceSourcePickerRow(
                enabled = connected && !state.referenceBusy,
                compact = false,
                onPickAlbum = {
                    pickImage.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                onPickHistory = { historyPickerOpen = true },
            )
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
                    val inpaintPress = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = onOpenInpaintEditor,
                        enabled = connected && !state.referenceBusy,
                        shape = RoundedCornerShape(14.dp),
                        interactionSource = inpaintPress,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (mask != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (mask != null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth().pressFeedback(inpaintPress, connected && !state.referenceBusy),
                    ) {
                        Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
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
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)) {
        AsyncImage(source, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (mask != null) AsyncImage(mask, "重绘区域", Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, colorFilter = tint)
    }
}

/**
 * 统一的「从相册选择 / 从历史选择」入口，供图生图、Precise Reference 与 Vibe Transfer 共用。
 * 空状态采用双无边框 tonal 卡片；已有参考图继续追加时切为紧凑双 tonal 药丸，避免挤占垂直空间。
 */
@Composable
internal fun ReferenceSourcePickerRow(
    enabled: Boolean,
    compact: Boolean,
    onPickAlbum: () -> Unit,
    onPickHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val albumPress = remember { MutableInteractionSource() }
    val historyPress = remember { MutableInteractionSource() }
    val contentAlpha = if (enabled) 1f else 0.45f
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (compact) {
            ReferenceSourceCompactTile(
                icon = Icons.Default.AddPhotoAlternate,
                label = stringResource(R.string.generate_reference_pick),
                enabled = enabled,
                contentAlpha = contentAlpha,
                interaction = albumPress,
                onClick = onPickAlbum,
                modifier = Modifier.weight(1f),
            )
            ReferenceSourceCompactTile(
                icon = Icons.Default.History,
                label = stringResource(R.string.generate_reference_from_history),
                enabled = enabled,
                contentAlpha = contentAlpha,
                interaction = historyPress,
                onClick = onPickHistory,
                modifier = Modifier.weight(1f),
            )
        } else {
            ReferenceSourceCardTile(
                icon = Icons.Default.AddPhotoAlternate,
                label = stringResource(R.string.generate_reference_pick),
                enabled = enabled,
                contentAlpha = contentAlpha,
                interaction = albumPress,
                onClick = onPickAlbum,
                modifier = Modifier.weight(1f),
            )
            ReferenceSourceCardTile(
                icon = Icons.Default.History,
                label = stringResource(R.string.generate_reference_from_history),
                enabled = enabled,
                contentAlpha = contentAlpha,
                interaction = historyPress,
                onClick = onPickHistory,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReferenceSourceCardTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    contentAlpha: Float,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        interactionSource = interaction,
        modifier = modifier.pressFeedback(interaction, enabled),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().alpha(contentAlpha).padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ReferenceSourceCompactTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    contentAlpha: Float,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        interactionSource = interaction,
        modifier = modifier.pressFeedback(interaction, enabled),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().alpha(contentAlpha).padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 详情页与画廊长按共用的一键「用作参考图」目标选择器（V4.5 弹出三选一，V5/OpenAI 直接跳过此弹窗）。 */
@Composable
internal fun ReferenceRoleChooserDialog(
    supportsDirector: Boolean,
    supportsVibe: Boolean,
    onSelect: (net.pocketnai.domain.model.ReferenceRole) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = { Text("用作参考图") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReferenceRoleChoiceTile(
                    title = "图生图底图",
                    subtitle = "以这张图作为起点重新绘制（不额外收费）",
                    enabled = true,
                    onClick = { onSelect(net.pocketnai.domain.model.ReferenceRole.IMG2IMG) },
                )
                ReferenceRoleChoiceTile(
                    title = "Precise Ref（角色 / 画风）",
                    subtitle = if (supportsDirector) "作为角色或画风条件参考（每张 +5 Anlas）" else "仅 V4.5 系列模型支持",
                    enabled = supportsDirector,
                    onClick = { onSelect(net.pocketnai.domain.model.ReferenceRole.DIRECTOR) },
                )
                ReferenceRoleChoiceTile(
                    title = "Vibe Transfer（风格迁移）",
                    subtitle = if (supportsVibe) "提取整体氛围与笔触风格（可与图生图并用）" else "仅 V4.5 系列模型支持",
                    enabled = supportsVibe,
                    onClick = { onSelect(net.pocketnai.domain.model.ReferenceRole.VIBE) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ReferenceRoleChoiceTile(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        interactionSource = press,
        modifier = Modifier.fillMaxWidth().pressFeedback(press, enabled),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.42f).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 从历史里选一张图作为起点。
 *
 * 这是图生图在手机端最常用的入口：拿一张自己刚生成的图继续改，
 * 比先去相册里翻半天自然得多。
 */
