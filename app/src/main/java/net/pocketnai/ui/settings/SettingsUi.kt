package net.pocketnai.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal val LocalSettingsSheetDismiss = staticCompositionLocalOf<() -> Unit> { {} }

/** Shared by the main settings list and its connection pages; motion stays in the layer. */
@Composable
internal fun Modifier.settingsClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed && enabled) .985f else 1f,
        spring(dampingRatio = 1f, stiffness = 850f), label = "settings-press")
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = if (enabled) 1f else .6f }
        .clickable(enabled = enabled, role = Role.Button, interactionSource = source,
            indication = ripple(), onClick = onClick)
}

@Composable
internal fun SettingsIcon(icon: ImageVector, active: Boolean = false) {
    Surface(shape = CircleShape, color = if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(21.dp), tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SettingsSection(title: String, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        action()
    }
}

@Composable
internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsNavigationRow(title: String, subtitle: String, icon: ImageVector,
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    active: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isNotBlank()) ({ Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) }) else null,
        leadingContent = { SettingsIcon(icon, active) },
        trailingContent = trailing ?: { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.settingsClickable(enabled, onClick).padding(vertical = 4.dp),
    )
}

/** A single responsive editing surface. Dismiss only after the exit animation completes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsEditorSheet(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    saveLabel: String = "保存",
    saveEnabled: Boolean = true,
    onSave: (() -> Boolean)? = null,
    scrollContent: Boolean = true,
    onClosing: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val closingAction by rememberUpdatedState(onClosing)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { value -> if (value == SheetValue.Hidden) closingAction(); true })
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            try { sheet.hide() } finally { closing = false }
            if (!sheet.isVisible) onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        sheetMaxWidth = 640.dp, containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets.safeDrawing },
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.size(32.dp, 4.dp), shape = CircleShape,
                    color = MaterialTheme.colorScheme.outlineVariant) { }
            }
        }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsIcon(icon, active = true)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { close() }, enabled = !closing) { Icon(Icons.Default.Close, "关闭$title") }
            }
            val scrolling = if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier
            CompositionLocalProvider(LocalSettingsSheetDismiss provides { close() }) {
                Column(Modifier.weight(1f).fillMaxWidth().then(scrolling).padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onSave != null) {
                        OutlinedButton(onClick = { close() }, enabled = !closing) { Text("取消") }
                        Button(onClick = { if (!closing && onSave()) close() },
                            enabled = saveEnabled && !closing, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(saveLabel)
                        }
                    } else FilledTonalButton(onClick = { close() }, enabled = !closing, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
                }
            }
        }
    }
}
