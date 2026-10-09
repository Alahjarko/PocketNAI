package net.pocketnai.ui.generate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.ui.LocalAppContainer

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InactiveReferences(references: List<ReferenceImage>, onRemove: (String) -> Unit) {
    Text(if (references.isEmpty()) "当前模型不使用此类参考图。"
        else "已保留 ${references.size} 张参考图，当前模型不使用。切回 V4.5 后恢复。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val container = LocalAppContainer.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        references.forEach { reference ->
            Column {
                AsyncImage(model = container.fileStore.resolve(reference.relativePath),
                    contentDescription = "已保留的参考图", contentScale = ContentScale.Fit, modifier = Modifier.size(80.dp))
                TextButton(onClick = { onRemove(reference.id) }) { Text("移除") }
            }
        }
    }
}
