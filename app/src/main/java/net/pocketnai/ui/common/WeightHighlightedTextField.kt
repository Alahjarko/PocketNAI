package net.pocketnai.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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

private class TextLayoutHolder {
    var result: TextLayoutResult? = null
}

/**
 * 带权重高亮的提示词输入框（技术决策记录第 22 节）。
 *
 * 把 [PromptWeightScanner] 认出来的加权片段垫上一层**圆角底纹**：权重 < 1 用绿色、
 * > 1 用红色，像荧光笔圈出来一样。改的是底色、不是文字颜色 —— 反色文字会让提示词
 * 本身变得难读，而提示词是用户要逐字检查的东西。
 *
 * ## 零闪烁绘制期架构（消除逐字输入/退格的底纹闪断与二次重组）
 * Compose 同一帧内固定按 **Composition → Layout → Draw** 顺序执行：
 * 原先将 `TextLayoutResult` 存为组合态 `MutableState` 并在 Composition 里比较
 * `layout.layoutInput.text.text == value.text` 来挂载 `Canvas`，会导致每次按键时
 * Composition 先看到上一帧的旧 `layout`，把 `Canvas` 卸载一帧（所有底纹瞬间闪灭），
 * 随后 `onTextLayout` 在 Layout 阶段写回状态又触发第二次重组。
 *
 * 现改为将排版结果存入非组合态 [TextLayoutHolder]（由同一帧 Layout 阶段的 `onTextLayout`
 * 同步写入，并仅推进仅在绘制期读取的计数），在外层 `Box` 的 [Modifier.drawBehind]
 * （Draw 阶段，位于文字绘制之前）直接读取：同一帧内 Layout 已更新完毕再进入 Draw，
 * 既彻底消除一帧闪断，又省去每次击键的额外重组。
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
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocusChanged(focused) }
    val spans = remember(value.text) { PromptWeightScanner.scan(value.text) }
    val currentSpans by rememberUpdatedState(spans)
    val layoutHolder = remember { TextLayoutHolder() }
    val layoutVersion = remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    val horizontalPadding = with(density) { HIGHLIGHT_HORIZONTAL_PADDING.toPx() }
    val verticalInset = with(density) { HIGHLIGHT_VERTICAL_INSET.toPx() }
    val cornerRadius = with(density) { HIGHLIGHT_CORNER_RADIUS.toPx() }
    val groupGap = with(density) { 1.dp.toPx() }
    val highlights = LocalWeightHighlightColors.current
    val neutralHighlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)

    // 文字颜色必须显式跟随主题：裸 BasicTextField 不像 Material 组件那样解析颜色，
    // textStyle 里颜色未指定时底层按**黑色**渲染 —— 浅色主题下碰巧正确，
    // 深色主题下就是"深色底上的黑字"。
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

        val targetBorderColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        }
        val borderColor by animateColorAsState(
            targetValue = targetBorderColor,
            animationSpec = tween(140),
            label = "prompt-border-color",
        )
        val containerColor by animateColorAsState(
            targetValue = when {
                !enabled -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f)
                focused -> MaterialTheme.colorScheme.surfaceContainerLowest
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
            animationSpec = tween(140),
            label = "prompt-bg-color",
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(containerColor, TextFieldShape)
                .border(
                    width = if (focused) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = TextFieldShape,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .drawBehind {
                        layoutVersion.intValue
                        val activeSpans = currentSpans
                        val currentLayout = layoutHolder.result
                        if (activeSpans.isNotEmpty() && currentLayout != null) {
                            drawWeightHighlights(
                                layout = currentLayout,
                                spans = activeSpans,
                                weakerColor = highlights.weaker,
                                strongerColor = highlights.stronger,
                                neutralColor = neutralHighlight,
                                horizontalPadding = horizontalPadding,
                                verticalInset = verticalInset,
                                cornerRadius = cornerRadius,
                                groupGap = groupGap,
                            )
                        }
                    },
            ) {
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
                    onTextLayout = { result ->
                        if (layoutHolder.result != result) {
                            layoutHolder.result = result
                            layoutVersion.intValue++
                        }
                    },
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
    neutralColor: Color,
    horizontalPadding: Float,
    verticalInset: Float,
    cornerRadius: Float,
    groupGap: Float,
) {
    val textLength = layout.layoutInput.text.length
    if (textLength <= 0 || layout.lineCount <= 0) return

    spans.forEachIndexed { spanIndex, span ->
        val safeStart = span.start.coerceIn(0, textLength)
        val safeEnd = span.endExclusive.coerceIn(0, textLength)
        if (safeStart >= safeEnd) return@forEachIndexed

        val color = when (span.direction) {
            WeightDirection.NEUTRAL -> neutralColor
            WeightDirection.WEAKER -> weakerColor
            WeightDirection.STRONGER -> strongerColor
        }

        val firstLine = layout.getLineForOffset(safeStart)
        val lastLine = layout.getLineForOffset(safeEnd - 1)

        for (line in firstLine..lastLine) {
            // visibleEnd = true：不含行尾空白与换行符，底纹才不会在折行处拖出一条空隙。
            val lineEnd = layout.getLineEnd(line, visibleEnd = true)
            val start = maxOf(safeStart, layout.getLineStart(line))
            val end = minOf(safeEnd, lineEnd)
            if (start >= end) continue

            val left = layout.getHorizontalPosition(start, usePrimaryDirection = true)
            val right = if (end == lineEnd) layout.getLineRight(line)
                else layout.getHorizontalPosition(end, usePrimaryDirection = true)
            if (right <= left) continue

            // 相邻组的外扩底纹不能相交；即使没有逗号或权重颜色相同，也留出可见分界。
            var highlightLeft = left - horizontalPadding
            var highlightRight = right + horizontalPadding
            spans.getOrNull(spanIndex - 1)?.let { prev ->
                val prevEnd = prev.endExclusive.coerceIn(0, textLength)
                if (prevEnd in 1..start && layout.getLineForOffset(prevEnd - 1) == line) {
                    val previousRight = layout.getHorizontalPosition(prevEnd, usePrimaryDirection = true)
                    highlightLeft = maxOf(highlightLeft, (previousRight + left) / 2f + groupGap / 2f)
                }
            }
            spans.getOrNull(spanIndex + 1)?.let { next ->
                val nextStart = next.start.coerceIn(0, textLength)
                if (nextStart >= end && nextStart < textLength && layout.getLineForOffset(nextStart) == line) {
                    val nextLeft = layout.getHorizontalPosition(nextStart, usePrimaryDirection = true)
                    highlightRight = minOf(highlightRight, (right + nextLeft) / 2f - groupGap / 2f)
                }
            }
            if (highlightRight <= highlightLeft) continue

            // 行的上下边界是**整个行高**（含行距），直接拿来画会让相邻两行的底纹连成一片，
            // 所以四边各内缩一点：底纹因此更像"划重点"的笔道，而不是一块整色。
            val top = layout.getLineTop(line) + verticalInset
            val bottom = layout.getLineBottom(line) - verticalInset
            if (bottom <= top) continue

            drawRoundRect(
                color = color,
                topLeft = Offset(highlightLeft, top),
                size = Size(
                    width = highlightRight - highlightLeft,
                    height = bottom - top,
                ),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            )
        }
    }
}

/** 与现代 Material 3 柔和卡片一致的大圆角。 */
private val TextFieldShape = RoundedCornerShape(16.dp)

/** 左右比文字稍微外扩一点，才像"圈住"而不是"贴着"。 */
private val HIGHLIGHT_HORIZONTAL_PADDING = 3.dp

/** 行高比字形高，上下内缩这些量才不会让相邻两行的底纹连成一片。 */
private val HIGHLIGHT_VERTICAL_INSET = 1.5.dp

/** Material 的禁用态内容透明度（`DisabledAlpha`），与 OutlinedTextField 的 disabledTextColor 一致。 */
private const val DISABLED_TEXT_ALPHA = 0.38f

private val HIGHLIGHT_CORNER_RADIUS = 4.dp
