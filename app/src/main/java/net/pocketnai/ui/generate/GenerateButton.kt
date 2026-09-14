package net.pocketnai.ui.generate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pocketnai.R

/**
 * 生成按钮：圆角矩形、带阴影，**费用显示在按钮上**。
 *
 * ## 为什么放在悬浮层头部，而不是做成自由悬浮的 FAB
 *
 * 试过把按钮做成浮在画廊上的 Extended FAB，实测发现一个硬伤：
 * 悬浮层展开时会**完全覆盖**它 —— 也就是用户刚编辑完参数、正要生成的那一刻，
 * 按钮消失了。之前那版把它放在悬浮层外的底部栏，正是为了避开这个问题。
 *
 * 放在头部右侧则同时满足几件事：
 * - 头部在可滚动表单之外，所以表单怎么滚按钮都不动；
 * - 展开和收起两种状态都看得见，永远不会被自己所在的层盖住；
 * - 不再额外占用一条底部栏的高度，这是底部视觉重量最轻的方案。
 *
 * ## 费用为什么放在按钮上
 * 收费是点击这个按钮的直接后果，把预估费用放在按钮内，用户在下手前看到的就是同一处，
 * 不需要在界面里找第二遍。文案取费用模型的**收起态短文案**（免费 / 预计 17 / 费用待确认）。
 *
 * 按钮做成两行（费用小字在上、动作大字在下），比单行塞更多字更清楚，
 * 顺带把按钮撑大，头部不再显得拥挤。
 */
@Composable
fun GenerateButton(
    inFlight: Boolean,
    enabled: Boolean,
    costLabel: String,
    costDetail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { if (!inFlight && enabled) onClick() },
        enabled = enabled || inFlight,
        shape = RoundedCornerShape(16.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 3.dp,
            pressedElevation = 1.dp,
            disabledElevation = 0.dp,
        ),
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            // 费用行：小字，用半透明的内容色，与下面的动作行拉开层次。
            Text(
                text = costLabel,
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (inFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = ButtonDefaults.buttonColors().disabledContentColor,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = if (inFlight) {
                        stringResource(R.string.generate_action_busy)
                    } else {
                        stringResource(R.string.generate_action)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}
