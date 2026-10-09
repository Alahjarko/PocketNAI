package net.pocketnai.ui.gallery

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import net.pocketnai.core.ErrorCode
import net.pocketnai.ui.motion.sharedImage
import net.pocketnai.ui.motion.sharedImageRequest
import net.pocketnai.ui.motion.LocalImageMotion
import net.pocketnai.ui.motion.ImageOriginPreview
import net.pocketnai.ui.state.GenerationPreviewStore
import java.io.File
import kotlin.math.*

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
    val requestAge = remember(slot.key) {
        (System.currentTimeMillis() - (slot.generation?.createdAt ?: System.currentTimeMillis())).coerceAtLeast(0L)
    }
    val progress = remember { Animatable(0.93f * (1f - exp(-requestAge / 12_000f))) }
    val morph = remember { Animatable(0f) }
    val imageAlpha = remember { Animatable(if (slot.reveal) 0f else 1f) }
    val indicatorAlpha = remember { Animatable(if (slot.reveal) 1f else 0f) }
    val start = remember { SystemClock.elapsedRealtime() - requestAge }
    val failed = slot.failed || decodeFailed

    LaunchedEffect(decoded, failed, slot.reveal) {
        when {
            !slot.reveal -> { imageAlpha.snapTo(1f); indicatorAlpha.snapTo(0f) }
            failed -> indicatorAlpha.snapTo(0f)
            decoded -> {
                progress.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
                morph.animateTo(1f, tween(150, easing = FastOutSlowInEasing))
                delay(70)
                imageAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
                indicatorAlpha.animateTo(0f, tween(100))
                onRevealed()
            }
            else -> while (true) {
                val frameStart = SystemClock.elapsedRealtime()
                val elapsed = (SystemClock.elapsedRealtime() - start) / 1000f
                val target = 0.93f * (1f - exp(-elapsed / 12f))
                progress.animateTo(max(progress.value, target), tween(200, easing = LinearEasing))
                // No pause between normal segments; throttle when animations are disabled.
                val minimumWait = 50L - (SystemClock.elapsedRealtime() - frameStart)
                if (minimumWait > 0) delay(minimumWait)
            }
        }
    }
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
            if (preview != null && item == null && !failed) AsyncImage(
                model = File(preview.path), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.25f },
            )
            if (item != null) AsyncImage(
                model = imageFile?.let { sharedImageRequest(it) }, contentDescription = item.title, contentScale = ContentScale.Crop,
                onSuccess = { decoded = true }, onError = { decodeFailed = true },
                modifier = Modifier.fillMaxSize().sharedImage(item.imageId).graphicsLayer { alpha = imageAlpha.value },
            )
            if (slot.reveal && !failed) {
                val ink = MaterialTheme.colorScheme.primary
                Canvas(Modifier.size(60.dp).align(Alignment.Center)
                    .graphicsLayer { alpha = indicatorAlpha.value }
                    .semantics { contentDescription = if (decoded) "图片已就绪" else "生成中" }) {
                    val stroke = 5.dp.toPx()
                    val radius = (size.minDimension - stroke) / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val blend = morph.value
                    drawCircle(ink.copy(alpha = 0.12f * (1f - blend)), radius, center, style = Stroke(stroke))
                    if (blend == 0f) {
                        drawArc(ink, -90f, max(2f, progress.value * 360f), false,
                            topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2),
                            style = Stroke(stroke, cap = StrokeCap.Round))
                    } else {
                        val path = Path()
                        val a = center + Offset(-radius * 0.58f, 0f)
                        val b = center + Offset(-radius * 0.12f, radius * 0.43f)
                        val c = center + Offset(radius * 0.64f, -radius * 0.45f)
                        for (i in 0..48) {
                            val t = i / 48f
                            val angle = (-90f + 360f * t) * PI.toFloat() / 180f
                            val circle = center + Offset(cos(angle) * radius, sin(angle) * radius)
                            val check = if (t < 0.4f) a + (b - a) * (t / 0.4f) else b + (c - b) * ((t - 0.4f) / 0.6f)
                            val point = circle + (check - circle) * blend
                            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                        }
                        drawPath(path, ink, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
                if (!decoded) Text("生成中", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
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
