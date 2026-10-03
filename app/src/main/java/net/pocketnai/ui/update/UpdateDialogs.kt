package net.pocketnai.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.core.ErrorCode
import net.pocketnai.domain.update.UpdateRelease
import net.pocketnai.ui.common.messageRes

/**
 * "发现新版本"对话框。
 *
 * 三种形态共用这一个框：待下载 / 下载中 / 出错（可重试）。
 * 下载中禁用重复下载与关闭操作；仍可打开 GitHub 页面查看发布说明。
 */
@Composable
fun UpdateAvailableDialog(
    release: UpdateRelease,
    currentVersionName: String,
    download: UpdateViewModel.DownloadProgress?,
    errorCode: ErrorCode?,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onDismissError: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = { if (download == null) onLater() },
        title = { Text(stringResource(R.string.update_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.update_version_line,
                        release.versionName,
                        currentVersionName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                // 说明只取第一行：Release notes 可能很长，弹窗不是读它的地方。
                release.notes?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { firstLine ->
                    Text(
                        text = firstLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (errorCode != null) {
                    Text(
                        text = stringResource(errorCode.messageRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (download != null) {
                    val percent = download.percent
                    if (percent != null) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.update_downloading, percent),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.update_downloading_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LatestReleaseButton(uriHandler)
            }
        },
        confirmButton = {
            if (errorCode != null) {
                TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.update_retry))
                }
            } else {
                TextButton(onClick = onDownload, enabled = download == null) {
                    Text(stringResource(R.string.update_download_install))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { if (errorCode != null) onDismissError() else onLater() },
                enabled = download == null,
            ) {
                Text(
                    if (errorCode != null) {
                        stringResource(R.string.action_cancel)
                    } else {
                        stringResource(R.string.update_later)
                    },
                )
            }
        },
    )
}

/** 设置页手动检查入口，成功、失败或检查中都保留浏览器更新途径。 */
@Composable
fun UpdateCheckDialog(
    currentVersionName: String,
    currentVersionCode: Int,
    state: UpdateViewModel.UiState,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_section_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.update_current_version, currentVersionName) + " (build $currentVersionCode)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.settings_update_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCheck, enabled = !state.checking) {
                    Text(stringResource(if (state.checking) R.string.update_checking else R.string.update_check_action))
                }
                when {
                    state.upToDateNotice -> Text(
                        stringResource(R.string.update_up_to_date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    state.error != null -> Text(
                        stringResource(state.error.messageRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LatestReleaseButton(uriHandler)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}
