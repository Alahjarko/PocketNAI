package net.pocketnai.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.pocketnai.ui.motion.rememberPocketHaptics

@Composable
internal fun SfwModeCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = rememberPocketHaptics()
    SettingsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .settingsClickable {
                    haptics.tick()
                    onChange(!enabled)
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsIcon(Icons.Default.VisibilityOff, active = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SFW 模式", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "模糊含敏感正向词的图片，并阻止相应的新生成。详情中点图可查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    haptics.tick()
                    onChange(it)
                },
            )
        }
    }
}
