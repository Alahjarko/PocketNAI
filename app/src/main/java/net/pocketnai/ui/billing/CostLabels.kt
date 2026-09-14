package net.pocketnai.ui.billing

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.pocketnai.R
import net.pocketnai.data.repo.BalanceState
import net.pocketnai.domain.billing.GenerationCostEstimate
import net.pocketnai.domain.billing.ObservedBalanceChange
import net.pocketnai.domain.billing.SubscriptionBalance
import java.text.NumberFormat

/**
 * 费用与余额的文案映射（余额规划 §3.1 与 §11）。
 *
 * 集中在一处的原因和 `ErrorMessages` 一样：四态费用各有"收起态短文案"与"展开态说明"两套说法，
 * 散在界面里迟早会出现两处措辞不一致。
 *
 * 措辞纪律（规划 §3.2）：
 * - 生成前一律叫"预计费用"；
 * - 生成后一律叫"本次观察到的余额变化"；
 * - 不出现"精确价格""正式账单""一定扣除"。
 */
@Composable
fun GenerationCostEstimate.shortLabel(): String = when (this) {
    is GenerationCostEstimate.Free -> stringResource(R.string.cost_free)
    is GenerationCostEstimate.UsesV5Allowance -> stringResource(R.string.cost_v5_allowance_short)
    is GenerationCostEstimate.EstimatedAnlas -> stringResource(R.string.cost_estimated_short, batchTotal)
    is GenerationCostEstimate.Unknown -> stringResource(R.string.cost_unknown_short)
}

@Composable
fun GenerationCostEstimate.detailLabel(): String = when (this) {
    is GenerationCostEstimate.Free -> stringResource(R.string.cost_free_detail)

    is GenerationCostEstimate.UsesV5Allowance -> stringResource(R.string.cost_v5_allowance_detail)

    is GenerationCostEstimate.EstimatedAnlas -> if (imageCount > 1) {
        stringResource(
            R.string.cost_estimated_detail_batch,
            batchTotal,
            formatDecimal(averagePerImage),
        )
    } else {
        stringResource(R.string.cost_estimated_detail, batchTotal)
    }

    is GenerationCostEstimate.Unknown -> stringResource(R.string.cost_unknown_detail)
}

/**
 * 余额的紧凑表示：`余额 12,000 Anlas`。
 *
 * 只放余额，**不放费用**：费用已经在生成按钮上，这里再写一遍既重复又会被截断。
 * V5 额度只在真的有额度（>0）时才带上 —— 无订阅账户的 `percent = 0` 写进来是噪音，
 * 会被误读成"额度用尽了"。
 */
@Composable
fun BalanceState.compactLabel(): String? {
    val balance = knownBalance ?: return null
    val anlas = stringResource(R.string.balance_anlas_short, formatAnlas(balance.totalAnlas))
    val v5 = balance.v5UsageLimit
    return if (v5 != null && v5.rawPercent > 0) {
        val v5Label = if (v5.isNegative) {
            stringResource(R.string.balance_v5_unavailable)
        } else {
            "${stringResource(R.string.balance_v5_allowance)} ${v5.rawPercent}%"
        }
        "$anlas · $v5Label"
    } else {
        anlas
    }
}

@Composable
fun ObservedBalanceChange.summaryLabel(): String = when (this) {
    is ObservedBalanceChange.AnlasDecreased -> if (requestedImageCount > 1) {
        stringResource(
            R.string.observed_change_anlas_batch,
            totalSpent,
            requestedImageCount,
        )
    } else {
        stringResource(
            R.string.observed_change_anlas,
            totalSpent,
            subscriptionSpent,
            purchasedSpent,
        )
    }

    is ObservedBalanceChange.V5AllowanceChanged ->
        stringResource(R.string.observed_change_v5, beforePercent, afterPercent)

    // 整数百分比前后相同不代表没有消耗，只能说"暂未观察到"。
    is ObservedBalanceChange.NoVisibleChange -> stringResource(R.string.observed_change_none)

    is ObservedBalanceChange.Ambiguous -> stringResource(R.string.observed_change_ambiguous)
}

/** Anlas 用千位分隔符显示，数字很长时更容易读。 */
fun formatAnlas(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private fun formatDecimal(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}
