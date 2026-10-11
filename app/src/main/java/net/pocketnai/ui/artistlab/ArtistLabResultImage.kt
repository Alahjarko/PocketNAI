package net.pocketnai.ui.artistlab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.ui.motion.*
import java.io.File

/** 同一 draw 的等待与成图共用尺寸、位置和动画状态；历史图片不重播完成动画。 */
@Composable
internal fun ArtistLabResultImage(
    drawId: String,
    ordinal: Int,
    status: String,
    image: GalleryItem?,
    plannedAspectRatio: Float,
    resolve: (String) -> File,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalImageMotion.current
    var watched by rememberSaveable(drawId) { mutableStateOf(status == "RUNNING" && image == null) }
    var revealed by rememberSaveable(drawId) { mutableStateOf(false) }
    val startedAt = rememberSaveable(drawId) { System.currentTimeMillis() }
    LaunchedEffect(status) { if (status == "RUNNING" && image == null) watched = true }
    val file = image?.let { resolve(it.relativePath) }
    var decoded by remember(file) { mutableStateOf(false) }
    var decodeFailed by remember(file) { mutableStateOf(false) }
    val failed = decodeFailed || (image == null && status in listOf("FAILED", "UNCERTAIN"))
    GenerationImageReveal(
        ready = decoded, failed = failed, animate = watched && !revealed,
        startedAt = startedAt, onRevealed = { revealed = true },
        modifier = modifier.fillMaxWidth().aspectRatio((image?.aspectRatio ?: plannedAspectRatio).coerceIn(0.65f, 1.5f)),
    ) { alpha ->
        if (image != null) AsyncImage(
            model = file?.let { sharedImageRequest(it) }, contentDescription = "第 ${ordinal + 1} 张",
            contentScale = ContentScale.Crop,
            onSuccess = { decoded = true }, onError = { decodeFailed = true },
            modifier = Modifier.fillMaxSize().sharedImage(image.imageId)
                .graphicsLayer { this.alpha = alpha.value }
                .clickable(enabled = decoded && (!watched || revealed)) {
                    motion?.preview = ImageOriginPreview(image.imageId, file!!, image.aspectRatio, "抽卡结果")
                    onOpen(image.imageId)
                },
        )
        if (failed) Text(
            if (decodeFailed) "图片暂时无法读取" else if (status == "FAILED") "生成失败" else "结果待确认",
            modifier = Modifier.align(Alignment.Center).padding(12.dp), style = MaterialTheme.typography.labelMedium,
        )
    }
}
