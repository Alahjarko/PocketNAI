package net.pocketnai.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.domain.model.ThemeMode

@Composable
internal fun ColorModeCard(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("颜色模式", style = MaterialTheme.typography.titleMedium)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val largeText = LocalDensity.current.fontScale >= 1.3f && maxWidth < 400.dp
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (largeText) {
                        ModeChoice(ThemeMode.SYSTEM, mode, onSelect, Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ModeChoice(ThemeMode.LIGHT, mode, onSelect, Modifier.weight(1f))
                            ModeChoice(ThemeMode.DARK, mode, onSelect, Modifier.weight(1f))
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ThemeMode.entries.forEach { option ->
                            ModeChoice(option, mode, onSelect, Modifier.weight(if (option == ThemeMode.SYSTEM) 1.4f else 1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChoice(option: ThemeMode, current: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier) {
    val (label, icon) = when (option) {
        ThemeMode.SYSTEM -> R.string.theme_system to Icons.Outlined.BrightnessAuto
        ThemeMode.LIGHT -> R.string.theme_light to Icons.Outlined.LightMode
        ThemeMode.DARK -> R.string.theme_dark to Icons.Outlined.DarkMode
    }
    val selected = current == option
    val shape = RoundedCornerShape(if (selected) 24.dp else 12.dp)
    Surface(modifier.clip(shape).selectable(selected, role = Role.RadioButton, onClick = { onSelect(option) }),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = shape) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
            if (selected) Icon(icon, null, Modifier.size(18.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
}
