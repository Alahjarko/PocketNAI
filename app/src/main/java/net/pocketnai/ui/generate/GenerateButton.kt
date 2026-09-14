package net.pocketnai.ui.generate

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pocketnai.R

/**
 * 生成按钮：圆角矩形、带阴影的胶囊按钮。
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
 * 圆角 + 阴影保留了“浮起来”的观感，和官方网页版生成按钮的形态也一致。
 */
@Composable
fun GenerateButton(
    inFlight: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { if (!inFlight && enabled) onClick() },
        enabled = enabled || inFlight,
        shape = RoundedCornerShape(14.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 3.dp,
            pressedElevation = 1.dp,
            disabledElevation = 0.dp,
        ),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (inFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = ButtonDefaults.buttonColors().disabledContentColor,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = if (inFlight) {
                    stringResource(R.string.generate_action_busy)
                } else {
                    stringResource(R.string.generate_action)
                },
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
