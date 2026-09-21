package net.pocketnai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.domain.proxy.ProxyMode
import net.pocketnai.domain.proxy.ProxySettings
import net.pocketnai.domain.proxy.ProxyType
import net.pocketnai.domain.proxy.PublicProxyQuota

/**
 * "网络代理"的配置内容（模式选择 + 公益/自定义配置），
 * 供设置页的代理对话框承载；行内只留开关与摘要（2026-09-21 设置页列表化）。
 *
 * 两套来源：内置公益节点（SOCKS5，社区提供、每日限额）与自定义代理（HTTP/SOCKS5）。
 * **只影响 NovelAI 的请求**：检查更新与 APK 下载固定直连（不走代理流量）。
 *
 * 凭据的处理纪律：密码输入框用 [PasswordVisualTransformation]，
 * 落盘走 [net.pocketnai.data.proxy.ProxyStore] 的 Keystore 加密 —— 界面层不做任何持久化。
 */
@Composable
fun ProxyConfigContent(
    settings: ProxySettings,
    usedBytesToday: Long,
    nodeCount: Int,
    onSettingsChange: (ProxySettings) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var notice by remember { mutableStateOf<String?>(null) }
    val pasteSuccessText = stringResource(R.string.proxy_paste_success)
    val pasteFailedText = stringResource(R.string.proxy_paste_failed)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.mode == ProxyMode.PUBLIC,
                onClick = { onSettingsChange(settings.copy(mode = ProxyMode.PUBLIC)) },
                label = { Text(stringResource(R.string.proxy_mode_public)) },
            )
            FilterChip(
                selected = settings.mode == ProxyMode.CUSTOM,
                onClick = { onSettingsChange(settings.copy(mode = ProxyMode.CUSTOM)) },
                label = { Text(stringResource(R.string.proxy_mode_custom)) },
            )
        }

        when (settings.mode) {
            ProxyMode.PUBLIC -> {
                Text(
                    text = stringResource(R.string.proxy_public_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (nodeCount > 0) {
                    Text(
                        text = stringResource(R.string.proxy_nodes_available, nodeCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val exceeded = usedBytesToday >= PublicProxyQuota.DAILY_LIMIT_BYTES
                Text(
                    text = stringResource(R.string.proxy_usage_today, formatBytes(usedBytesToday)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exceeded) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            ProxyMode.CUSTOM -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProxyType.entries.forEach { type ->
                        FilterChip(
                            selected = settings.type == type,
                            onClick = { onSettingsChange(settings.copy(type = type)) },
                            label = { Text(type.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = settings.host,
                    onValueChange = { onSettingsChange(settings.copy(host = it)) },
                    label = { Text(stringResource(R.string.proxy_host_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = if (settings.port == 0) "" else settings.port.toString(),
                    onValueChange = { value ->
                        val port = value.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0
                        onSettingsChange(settings.copy(port = port))
                    },
                    label = { Text(stringResource(R.string.proxy_port_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.username,
                    onValueChange = { onSettingsChange(settings.copy(username = it)) },
                    label = { Text(stringResource(R.string.proxy_username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.password,
                    onValueChange = { onSettingsChange(settings.copy(password = it)) },
                    label = { Text(stringResource(R.string.proxy_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        val parsed = clipboard.getText()?.text?.let(ProxySettings::parseClipboardLine)
                        if (parsed != null) {
                            // 导入只替换连接参数，保持当前的开关状态。
                            onSettingsChange(parsed.copy(enabled = settings.enabled))
                            notice = pasteSuccessText
                        } else {
                            notice = pasteFailedText
                        }
                    },
                ) {
                    Text(stringResource(R.string.proxy_paste_import))
                }
            }
        }

        notice?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
