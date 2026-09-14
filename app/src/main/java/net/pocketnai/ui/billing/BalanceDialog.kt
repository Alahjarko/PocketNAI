package net.pocketnai.ui.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.data.repo.BalanceState
import net.pocketnai.ui.common.messageRes
import java.text.DateFormat
import java.util.Date

/**
 * 余额详情弹层（余额规划 §11.2）。
 *
 * 三个刻意的处理：
 * - **加载中与失败时都保留上一次的值**，并说明"这是什么时候的数据"；
 * - `usage` 缺失时**整块隐藏 V5 区域**，而不是显示 0% —— 那会让账户看起来额度用尽；
 * - 余额请求失败只说"余额暂不可用"，不弹打断式错误：它是辅助信息。
 */
@Composable
fun BalanceDetailDialog(
    state: BalanceState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val balance = state.knownBalance

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.balance_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusLine(state = state, balanceTime = balance?.fetchedAtMillis)

                if (balance != null) {
                    BalanceRow(
                        label = stringResource(R.string.balance_subscription),
                        value = formatAnlas(balance.subscriptionAnlas),
                    )
                    BalanceRow(
                        label = stringResource(R.string.balance_purchased),
                        value = formatAnlas(balance.purchasedAnlas),
                    )
                    BalanceRow(
                        label = stringResource(R.string.balance_total),
                        value = formatAnlas(balance.totalAnlas),
                        emphasize = true,
                    )

                    // 没有 usage 就不显示这一块：0% 会被读成"额度用尽了"。
                    balance.v5UsageLimit?.let { usage ->
                        Text(
                            text = stringResource(R.string.balance_v5_allowance),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (usage.isNegative) {
                            Text(
                                text = stringResource(R.string.balance_v5_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { usage.progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "${usage.rawPercent}%",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            usage.timeUntilNextPercentSeconds
                                ?.takeIf { it > 0 }
                                ?.let { seconds ->
                                    Text(
                                        text = stringResource(
                                            R.string.balance_v5_next_percent,
                                            formatDuration(seconds),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.balance_refresh))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        },
    )
}

@Composable
private fun StatusLine(state: BalanceState, balanceTime: Long?) {
    val text = when (state) {
        is BalanceState.Loading -> if (balanceTime == null) {
            stringResource(R.string.balance_refreshing)
        } else {
            stringResource(R.string.balance_refreshing_with_cache)
        }

        is BalanceState.RefreshFailed -> if (balanceTime == null) {
            stringResource(state.error.code.messageRes())
        } else {
            stringResource(R.string.balance_failed_with_cache, formatTime(balanceTime))
        }

        is BalanceState.Available -> stringResource(
            R.string.balance_updated_at,
            formatTime(state.balance.fetchedAtMillis),
        )

        BalanceState.Unavailable -> stringResource(R.string.balance_never_loaded)
    }

    val isError = state is BalanceState.RefreshFailed
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun BalanceRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = if (emphasize) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
        Text(
            text = value,
            style = if (emphasize) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
    }
}

private fun formatTime(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    return when {
        minutes >= 60 -> "${minutes / 60} 小时"
        minutes >= 1 -> "$minutes 分钟"
        else -> "$seconds 秒"
    }
}
