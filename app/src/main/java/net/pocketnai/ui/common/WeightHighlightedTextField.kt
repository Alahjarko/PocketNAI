package net.pocketnai.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.prompt.PromptWeightScanner
import net.pocketnai.domain.prompt.PromptWeightSpan
import net.pocketnai.domain.prompt.WeightDirection
import net.pocketnai.ui.theme.LocalWeightHighlightColors

/**
 * 带权重高亮的提示词输入框（技术决策记录第 22 节）。
 *
 * 把 [PromptWeightScanner] 认出来的加权片段垫上一层**圆角底纹**：权重 < 1 用绿色、
 * > 1 用红色，像荧光笔圈出来一样。改的是底色、不是文字颜色 —— 反色文字会让提示词
 * 本身变得难读，而提示词是用户要逐字检查的东西。
 *
 * ## 为什么是自己搭，而不是 OutlinedTextField
 * 底纹要画在文字**下面**，就必须拿到文字的排版结果，而 Material 的 `OutlinedTextField`
 * 既不暴露内部的 `TextLayoutResult`，也不允许替换内部的输入框。
 *
 * 试过 `BasicTextField` + `OutlinedTextFieldDefaults.DecorationBox`（官方为此留的入口），
 * 结果是**文字被画了两遍且边框消失**：那个 `container` 参数不是"输入框的内容"，
 * 而是边框图层，`DecorationBox` 自己会放置输入框。这条路依赖 Material 的内部约定，
 * 而它的参数表在版本之间已经改过，不值得继续赌。
 *
 * 因此这里自己搭：边框与标签都在本文件里画，底纹与输入框放进**同一个 `Box`**。
 * 这样两者严格同尺寸同原点，底纹的坐标直接来自输入框自己回调的排版结果，
 * 不需要知道任何内边距常量 —— Material 改版也不会错位。
 *
 * 代价是标签做成外部标签（在框上方），而不是 Material 的浮动标签。
 * 这与本应用已有的带标签控件一致（见 `DropdownSelector`：张数 / 质量标签 / 模型
 * 都是这样），对"总是有内容的提示词框"来说也更稳定。
 *
 * 已知限制：输入框内部滚动时底纹会错位。目前两个提示词框都是"随内容长高、
 * 由外层 `verticalScroll` 滚动"，没有内部滚动，因此不构成问题。
 * 若将来给它们加上固定高度与 `maxLines`，这里必须一并处理滚动偏移。
 */
@Composable
fun WeightHighlightedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minLines: Int = 1,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val spans = remember(value.text) { PromptWeightScanner.scan(value.text) }

    // 排版结果由输入框回调过来。绝大多数提示词没有加权语法，那种情况下不保存它，
    // 后续的重组也就不会被它带着走。
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val density = LocalDensity.current
    val horizontalPadding = with(density) { HIGHLIGHT_HORIZONTAL_PADDING.toPx() }
    val verticalInset = with(density) { HIGHLIGHT_VERTICAL_INSET.toPx() }
    val cornerRadius = with(density) { HIGHLIGHT_CORNER_RADIUS.toPx() }
    val highlights = LocalWeightHighlightColors.current

    // 文字颜色必须显式跟随主题：裸 BasicTextField 不像 Material 组件那样解析颜色，
    // textStyle 里颜色未指定时底层按**黑色**渲染 —— 浅色主题下碰巧正确，
    // 深色主题下就是"深色底上的黑字"。Material 的 OutlinedTextField 内部同样是把
    // focused/unfocusedTextColor（默认 onSurface）merge 进 textStyle，这里照做。
    val textColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_TEXT_ALPHA)
    }

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }

        val borderColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            focused -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = borderColor,
                    shape = TextFieldShape,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            // 尾部按钮随内容垂直居中，与 Material 输入框的行为一致。
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                val current = layout
                if (current != null) {
                    // 放在输入框之前 = 先画，于是底纹在文字**下面**。
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawWeightHighlights(
                            layout = current,
                            spans = spans,
                            weakerColor = highlights.weaker,
                            strongerColor = highlights.stronger,
                            horizontalPadding = horizontalPadding,
                            verticalInset = verticalInset,
                            cornerRadius = cornerRadius,
                        )
                    }
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    // 填满 Box，底纹才能按同一个宽度复现换行位置。
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    textStyle = LocalTextStyle.current.copy(color = textColor),
                    singleLine = false,
                    maxLines = Int.MAX_VALUE,
                    minLines = minLines,
                    interactionSource = interactionSource,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    onTextLayout = { result -> layout = if (spans.isEmpty()) null else result },
                )
            }

            if (trailingIcon != null) {
                Box(modifier = Modifier.padding(start = 4.dp)) {
                    trailingIcon()
                }
            }
        }
    }
}

/**
 * 逐行画底纹。
 *
 * 一个片段可能跨行（长提示词里 `{...}` 被折行），所以按**行**切成小段分别画：
 * 每段一个圆角矩形，读起来就是荧光笔划过这几行。
 */
private fun DrawScope.drawWeightHighlights(
    layout: TextLayoutResult,
    spans: List<PromptWeightSpan>,
    weakerColor: Color,
    strongerColor: Color,
    horizontalPadding: Float,
    verticalInset: Float,
    cornerRadius: Float,
) {
    spans.forEach { span ->
        val color = when (span.direction) {
            WeightDirection.WEAKER -> weakerColor
            WeightDirection.STRONGER -> strongerColor
        }

        val firstLine = layout.getLineForOffset(span.start)
        val lastLine = layout.getLineForOffset(span.endExclusive - 1)

        for (line in firstLine..lastLine) {
            // visibleEnd = true：不含行尾空白与换行符，底纹才不会在折行处拖出一条空隙。
            val lineEnd = layout.getLineEnd(line, visibleEnd = true)
            val start = maxOf(span.start, layout.getLineStart(line))
            val end = minOf(span.endExclusive, lineEnd)
            if (start >= end) continue

            val left = layout.getHorizontalPosition(start, usePrimaryDirection = true)
            val right = layout.getHorizontalPosition(end, usePrimaryDirection = true)
            if (right <= left) continue

            // 行的上下边界是**整个行高**（含行距），直接拿来画会让相邻两行的底纹连成一片，
            // 所以四边各内缩一点：底纹因此更像"划重点"的笔道，而不是一块整色。
            val top = layout.getLineTop(line) + verticalInset
            val bottom = layout.getLineBottom(line) - verticalInset
            if (bottom <= top) continue

            drawRoundRect(
                color = color,
                topLeft = Offset(left - horizontalPadding, top),
                size = Size(
                    width = right - left + horizontalPadding * 2,
                    height = bottom - top,
                ),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            )
        }
    }
}

/** 与 Material 输入框一致的圆角。 */
private val TextFieldShape = RoundedCornerShape(4.dp)

/** 左右比文字稍微外扩一点，才像"圈住"而不是"贴着"。 */
private val HIGHLIGHT_HORIZONTAL_PADDING = 3.dp

/** 行高比字形高，上下内缩这些量才不会让相邻两行的底纹连成一片。 */
private val HIGHLIGHT_VERTICAL_INSET = 1.5.dp

/** Material 的禁用态内容透明度（`DisabledAlpha`），与 OutlinedTextField 的 disabledTextColor 一致。 */
private const val DISABLED_TEXT_ALPHA = 0.38f

private val HIGHLIGHT_CORNER_RADIUS = 4.dp
