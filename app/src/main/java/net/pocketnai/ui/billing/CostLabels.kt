package net.pocketnai.ui.billing

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.pocketnai.R
import net.pocketnai.data.repo.BalanceState
import net.pocketnai.domain.billing.GenerationCostEstimate
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

    is GenerationCostEstimate.EstimatedAnlas -> when {
        imageCount > 1 && freeImageCount > 0 -> stringResource(
            R.string.cost_estimated_detail_batch_with_free,
            batchTotal,
            freeImageCount,
            imageCount,
        )

        imageCount > 1 -> stringResource(
            R.string.cost_estimated_detail_batch,
            batchTotal,
            formatDecimal(averagePerImage),
        )

        // 单张、但被参考图附加费顶出一个非零总额：说清楚"免费的是生成，收的是附加费"，
        // 否则"免费"和"5 Anlas"同时出现会让人以为是 bug（实测 407 → 402 就是这种情况）。
        freeImageCount > 0 && batchTotal > 0 -> stringResource(
            R.string.cost_estimated_detail_with_free,
            batchTotal,
        )

        else -> stringResource(R.string.cost_estimated_detail, batchTotal)
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
