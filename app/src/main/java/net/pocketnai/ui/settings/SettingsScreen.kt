package net.pocketnai.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.pocketnai.BuildConfig
import net.pocketnai.R
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.LocalAppContainer

@Composable
fun SettingsScreen(onRequestConnect: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    repository = container.generationRepository,
                    settingsStore = container.settingsStore,
                    credentialStore = container.credentialStore,
                    sessionState = container.sessionState,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val streamingEnabled by viewModel.streamingPreviewEnabled.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        // ---- 连接状态 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (connected) {
                        stringResource(R.string.connect_connected)
                    } else {
                        stringResource(R.string.generate_no_token)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "PocketNAI 只用你自己的 Persistent API Token 直连 NovelAI，" +
                        "不经过任何第三方服务器，也不保存你的邮箱或密码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRequestConnect) {
                        Text(stringResource(R.string.connect_title))
                    }
                    if (connected) {
                        OutlinedButton(onClick = viewModel::disconnect) {
                            Text(stringResource(R.string.connect_delete))
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // ---- 外观 ----
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.settings_appearance),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.settings_theme_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(stringResource(mode.labelRes())) },
                    )
                }
            }
        }

        HorizontalDivider()

        // ---- 流式预览 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_streaming_preview),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_streaming_preview_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = streamingEnabled,
                onCheckedChange = viewModel::setStreamingPreviewEnabled,
            )
        }

        HorizontalDivider()

        // ---- 存储 ----
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.settings_storage),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "私有历史占用 ${formatBytes(state.usedBytes)}。" +
                    "这些图片保存在应用私有目录，删除应用数据会一并清除；" +
                    "已经保存到系统相册的副本不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::runMaintenance) {
                    Text(stringResource(R.string.settings_clear_orphans))
                }
                OutlinedButton(onClick = viewModel::refreshUsage) {
                    Text("刷新占用")
                }
            }
        }

        state.maintenanceMessage?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = message, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::dismissMaintenanceMessage) {
                        Text(stringResource(R.string.action_confirm))
                    }
                }
            }
        }

        HorizontalDivider()

        Text(
            text = "${stringResource(R.string.settings_version)} ${BuildConfig.VERSION_NAME} " +
                "(build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "首次发布只支持文生图（T2I）与四个 V4.5 / V5 模型。" +
                "Img2Img、Inpaint、Vibe Transfer 等能力不在首版范围内。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
