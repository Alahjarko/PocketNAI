package net.pocketnai.ui.openai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.openai.OpenAiImageEstimate
import net.pocketnai.ui.common.InfoButton
import java.util.Locale

object OpenAiCostLabels {
    fun amount(usd: Double): String = "$" + String.format(Locale.US, if (usd in 0.0..<0.01) "%.3f" else "%.2f", usd)
    fun summary(estimate: OpenAiImageEstimate): String = if (estimate.minimumUsd == estimate.maximumUsd)
        "约 ${amount(estimate.minimumUsd)} USD"
    else "约 ${amount(estimate.minimumUsd)}～${amount(estimate.maximumUsd)} USD"
}

/** Used by both the editable form and the confirmation, so they always quote the same draft. */
@Composable
internal fun OpenAiCostEstimate(estimate: OpenAiImageEstimate?, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("本次费用粗估", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(estimate?.let(OpenAiCostLabels::summary) ?: "待确定尺寸后估算",
                    style = MaterialTheme.typography.titleMedium)
                estimate?.let {
                    val note = buildList {
                        add("共 ${it.count} 张")
                        if (it.outputMinPerImage != it.outputMaxPerImage) add("自动质量：低～最高")
                        if (it.referenceCount > 0) add("参考图预留 ${OpenAiCostLabels.amount(it.referenceAllowancePerImage * it.count)}")
                    }.joinToString(" · ")
                    Text(note, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            InfoButton("按 OpenAI 官方标准美元费率估算（2026-11-05）。\n\n" +
                "图片输出按尺寸和质量计算；文本按描述长度粗估。" +
                "每张参考图暂按每张成图 $0.05 预留，属于本机预算，并非官方按张定价。多张生成按逐张计入输入费用预留。\n\n" +
                "自动质量显示低到最高档的范围；自动尺寸暂不报价。参考图、高张数和长提示词的误差可能更大。" +
                "估算不是扣费上限；兼容服务商可能使用不同价格。" +
                "完成后按接口用量折算，实际扣费以服务商账单为准。")
        }
    }
}
