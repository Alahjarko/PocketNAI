package net.pocketnai.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Native editor retains IME composition, attachments and the app's execution rules. */
@Composable
internal fun ChatComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    assistantName: String,
    busy: Boolean,
    generating: Boolean,
    importing: Boolean,
    hasAttachments: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    attachments: @Composable () -> Unit = {},
    leadingActions: @Composable RowScope.() -> Unit = {},
) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("chat-composer"),
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
        Column {
            attachments()
            BasicTextField(value, onValueChange,
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp).testTag("chat-input"),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!busy) onSend() }),
                decorationBox = { inner ->
                    Box(Modifier.heightIn(min = 26.dp)) {
                        if (value.text.isEmpty()) Text("给 ${assistantName.ifBlank { "绘伴" }} 发消息",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                leadingActions()
                Spacer(Modifier.weight(1f))
                FilledIconButton(onClick = if (busy) onStop else onSend,
                    enabled = if (busy) !generating else !importing && (value.text.isNotBlank() || hasAttachments),
                    shape = CircleShape, modifier = Modifier.size(40.dp).testTag(if (busy) "chat-stop" else "chat-send")) {
                    Crossfade(busy, animationSpec = tween(120), label = "chat-send-stop") { stopping ->
                        Icon(if (stopping) Icons.Default.Stop else Icons.Default.ArrowUpward,
                            if (stopping) "停止回复" else "发送", modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}
