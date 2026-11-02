package net.pocketnai.ui.generate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.motion.ImageOriginPreview
import net.pocketnai.ui.safety.SfwAsyncImage

internal val LocalReferenceOriginOpener = staticCompositionLocalOf<((ImageOriginPreview) -> Unit)?> { null }

/** Uses the original gallery identity, never the normalized/cropped reference hash. */
@Composable
internal fun ReferenceOriginLink(reference: ReferenceImage, modifier: Modifier = Modifier) {
    val id = reference.sourceImageId ?: return
    val container = LocalAppContainer.current
    val opener = LocalReferenceOriginOpener.current
    val source = remember(container, id) {
        container.database.generationDao().observeGalleryOrigin(id).map { image ->
            true to image?.takeIf { container.fileStore.resolve(it.relativePath).isFile }
        }.flowOn(Dispatchers.IO)
    }
    val state by source.collectAsStateWithLifecycle(initialValue = false to null)
    if (!state.first) return
    val image = state.second
    ReferenceOriginContent(available = image != null, modifier = modifier, onOpen = {
        image?.let {
            opener?.invoke(ImageOriginPreview(it.id, container.fileStore.resolve(it.relativePath),
                it.width.toFloat() / it.height.coerceAtLeast(1), "参考图来源"))
        }
    }, thumbnail = {
        if (image != null) SfwAsyncImage(container.fileStore.resolve(image.relativePath), "画廊原图",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    })
}

@Composable
internal fun ReferenceOriginContent(available: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier,
    thumbnail: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().clickable(enabled = available, role = Role.Button,
            onClickLabel = "查看画廊原图", onClick = onOpen)) {
        Row(Modifier.heightIn(min = 64.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (available) Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))) { thumbnail() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (available) "画廊来源" else "来源图片已移除", style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (available) "查看原图" else "参考图仍可使用", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (available) Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
        }
    }
}
