package net.pocketnai.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.domain.model.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColorModeCard(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("颜色模式", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, option ->
                    val (label, icon) = when (option) {
                        ThemeMode.SYSTEM -> R.string.theme_system to Icons.Outlined.BrightnessAuto
                        ThemeMode.LIGHT -> R.string.theme_light to Icons.Outlined.LightMode
                        ThemeMode.DARK -> R.string.theme_dark to Icons.Outlined.DarkMode
                    }
                    SegmentedButton(
                        selected = mode == option,
                        onClick = { onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        icon = { if (mode == option) Icon(icon, null, Modifier.size(18.dp)) },
                    ) { Text(stringResource(label)) }
                }
            }
        }
    }
}
