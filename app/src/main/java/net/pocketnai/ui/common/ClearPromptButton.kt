package net.pocketnai.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import net.pocketnai.ui.common.MotionIconButton as IconButton
import androidx.compose.runtime.*

/** Undo belongs to this field and revision, and expires once editing or replacement resumes. */
@Composable
fun ClearPromptButton(
    text: String,
    label: String,
    revision: Int = 0,
    onReplace: (String) -> Int,
) {
    var cleared by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val undo = cleared?.takeIf { text.isEmpty() && it.second == revision }
    LaunchedEffect(text, revision) {
        if (text.isNotEmpty() || cleared?.second != revision) cleared = null
    }
    IconButton(enabled = text.isNotEmpty() || undo != null, onClick = {
        if (undo != null) {
            cleared = null
            onReplace(undo.first)
        } else {
            val original = text
            val resultingRevision = onReplace("")
            cleared = original to resultingRevision
        }
    }) {
        FeedbackIcon(if (undo == null) Icons.Default.Close else Icons.AutoMirrored.Filled.Undo,
            description = if (undo == null) "清空$label" else "撤销清空$label",
            tint = androidx.compose.material3.LocalContentColor.current)
    }
}
