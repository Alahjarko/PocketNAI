package net.pocketnai.ui.gallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.pocketnai.ui.motion.GenerationImageReveal
import net.pocketnai.core.ErrorCode
import net.pocketnai.ui.motion.sharedImage
import net.pocketnai.ui.motion.sharedImageRequest
import net.pocketnai.ui.motion.LocalImageMotion
import net.pocketnai.ui.motion.ImageOriginPreview
import net.pocketnai.ui.state.GenerationPreviewStore
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GalleryMotionCard(
    slot: GalleryMotionSlot,
    imageFile: File?,
    preview: GenerationPreviewStore.Preview?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRevealed: () -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
    dateLabel: String? = null,
) {
    val item = slot.image
    val imageMotion = LocalImageMotion.current
    // The keyed outer card survives the transition from a task to its decoded image.
    var decoded by remember(imageFile) { mutableStateOf(false) }
    var decodeFailed by remember(imageFile) { mutableStateOf(false) }
    val failed = slot.failed || decodeFailed
    Card(
        border = if (isSelectionMode && isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.fillMaxWidth().aspectRatio(slot.aspectRatio)
            .then(if (item != null) Modifier.combinedClickable(onClick = {
                if (!isSelectionMode && imageFile != null) {
                    imageMotion?.preview = ImageOriginPreview(item.imageId, imageFile, item.aspectRatio, item.title)
                }
                onClick()
            }, onLongClick = onLongClick) else Modifier),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            GenerationImageReveal(
                ready = decoded, failed = failed, animate = slot.reveal,
                startedAt = slot.generation?.createdAt ?: slot.createdAt,
                onRevealed = onRevealed, modifier = Modifier.fillMaxSize(),
            ) { revealAlpha ->
                if (preview != null && item == null && !failed) AsyncImage(
                    model = File(preview.path), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.25f },
                )
                if (item != null) AsyncImage(
                    model = imageFile?.let { sharedImageRequest(it) }, contentDescription = item.title, contentScale = ContentScale.Crop,
                    onSuccess = { decoded = true }, onError = { decodeFailed = true },
                    modifier = Modifier.fillMaxSize().sharedImage(item.imageId).graphicsLayer { alpha = revealAlpha.value },
                )
            }
            if (failed) Column(Modifier.align(Alignment.Center).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (decodeFailed) "图片暂时无法读取" else if (slot.generation?.errorCode == ErrorCode.TIMEOUT_UNCERTAIN)
                    "结果待确认" else "未完成", style = MaterialTheme.typography.labelMedium)
                if (item == null) TextButton(onClick = onDismissFailure) { Text("收起") }
            }
            if (dateLabel != null) Text(dateLabel, color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp))
            if (item?.favorite == true) Box(Modifier.align(if (isSelectionMode) Alignment.BottomStart else Alignment.TopEnd)
                .padding(6.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(4.dp)) {
                Icon(Icons.Default.Favorite, "收藏", tint = Color.White, modifier = Modifier.size(12.dp))
            }
            if (item != null && isSelectionMode) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(2.dp)) {
                Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    null, tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
