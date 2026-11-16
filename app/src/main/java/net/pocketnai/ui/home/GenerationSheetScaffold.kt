package net.pocketnai.ui.home

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pocketnai.ui.motion.imageMotionChrome
import net.pocketnai.ui.motion.rememberPocketHaptics
import kotlin.math.roundToInt

/**
 * 内容滚动与面板切换严格隔离：仅把手/头部接受 1:1 跟手拖动并按弹簧速度收敛，
 * 内部表单的滚动或越界阻尼绝不驱动面板位移或误收起。
 */
@Composable
internal fun GenerationSheetScaffold(
    inFlight: Boolean,
    sheetContent: @Composable (expanded: Boolean, headerDragModifier: Modifier) -> Unit,
    submissionRevision: Int = 0,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = rememberPocketHaptics()
    val peekDp = 112.dp
    val peekPx = with(density) { peekDp.toPx() }
    val thresholdPx = with(density) { 48.dp.toPx() }
    val flingVelocityPx = with(density) { 420.dp.toPx() }

    var targetExpanded by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (targetExpanded) 1f else 0f) }
    val backProgress = remember { Animatable(0f) }
    var scaffoldHeight by remember { mutableFloatStateOf(0f) }
    var sheetHeight by remember { mutableFloatStateOf(0f) }

    val sheetSpring = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
    }

    fun expandedOffset(): Float =
        if (scaffoldHeight <= 0f || sheetHeight <= 0f) 0f
        else (scaffoldHeight - sheetHeight).coerceAtLeast(0f)

    fun collapsedOffset(): Float =
        if (scaffoldHeight <= 0f) 0f
        else (scaffoldHeight - peekPx).coerceAtLeast(expandedOffset())

    // 仅当动画落定到顶部后启用内部表单滚动，避免展开途中误抢手势或重复重组整张表单。
    val contentExpanded by remember {
        derivedStateOf { targetExpanded && progress.value >= 0.99f && backProgress.value == 0f }
    }
    val handleExpandedLabel by remember {
        derivedStateOf { (progress.value * (1f - backProgress.value)) >= 0.5f }
    }

    @Composable
    fun rememberHeaderDragModifier(): Modifier {
        var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        return Modifier
            .onGloballyPositioned { coords = it }
            .pointerInput(thresholdPx, flingVelocityPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downRootY = coords?.localToRoot(down.position)?.y ?: down.position.y
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, Offset(0f, downRootY))
                    var overSlop = 0f
                    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume()
                        overSlop = over
                    } ?: return@awaitEachGesture

                    val startExpanded = progress.value >= 0.5f
                    var trackedProgress = progress.value
                    var lastRootY = coords?.localToRoot(drag.position)?.y ?: drag.position.y
                    var totalDragY = overSlop
                    velocityTracker.addPosition(drag.uptimeMillis, Offset(0f, lastRootY))
                    val initialRange = (collapsedOffset() - expandedOffset()).coerceAtLeast(1f)
                    trackedProgress = (trackedProgress - overSlop / initialRange).coerceIn(0f, 1f)
                    val initialSnap = trackedProgress
                    scope.launch { progress.snapTo(initialSnap) }

                    val completed = verticalDrag(drag.id) { change ->
                        val rootY = coords?.localToRoot(change.position)?.y ?: change.position.y
                        val deltaY = rootY - lastRootY
                        lastRootY = rootY
                        totalDragY += deltaY
                        velocityTracker.addPosition(change.uptimeMillis, Offset(0f, rootY))
                        change.consume()
                        val range = (collapsedOffset() - expandedOffset()).coerceAtLeast(1f)
                        trackedProgress = (trackedProgress - deltaY / range).coerceIn(0f, 1f)
                        val snapTarget = trackedProgress
                        scope.launch { progress.snapTo(snapTarget) }
                    }

                    if (!completed) {
                        val settle = targetExpanded
                        scope.launch { progress.animateTo(if (settle) 1f else 0f, sheetSpring) }
                    } else {
                        val vy = velocityTracker.calculateVelocity().y
                        val range = (collapsedOffset() - expandedOffset()).coerceAtLeast(1f)
                        val nextExpanded = when {
                            vy <= -flingVelocityPx -> true
                            vy >= flingVelocityPx -> false
                            !startExpanded && totalDragY <= -thresholdPx -> true
                            startExpanded && totalDragY >= thresholdPx -> false
                            else -> trackedProgress >= 0.5f
                        }
                        if (nextExpanded != startExpanded) haptics.gestureEnd()
                        targetExpanded = nextExpanded
                        val initialVelocity = (-vy / range).coerceIn(-8f, 8f)
                        scope.launch {
                            progress.animateTo(
                                targetValue = if (nextExpanded) 1f else 0f,
                                animationSpec = sheetSpring,
                                initialVelocity = initialVelocity,
                            )
                        }
                    }
                }
            }
    }

    val handleDrag = rememberHeaderDragModifier()
    val contentHeaderDrag = rememberHeaderDragModifier()

    LaunchedEffect(inFlight, submissionRevision) {
        if (inFlight) {
            targetExpanded = false
            progress.animateTo(0f, sheetSpring)
        }
    }

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { scaffoldHeight = it.height.toFloat() },
    ) {
        content(PaddingValues(bottom = peekDp))

        // 绘制期背景遮罩：随面板展开连续压暗画廊背景，零重组开销且收起时不绘制、不拦截点击。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val visual = (progress.value * (1f - backProgress.value)).coerceIn(0f, 1f)
                    val alpha = visual * 0.28f
                    if (alpha > 0.002f) {
                        drawRect(Color.Black.copy(alpha = alpha))
                    }
                },
        )

        val maxHeightModifier = if (scaffoldHeight > 0f) {
            Modifier.heightIn(max = with(density) { scaffoldHeight.toDp() })
        } else {
            Modifier
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(maxHeightModifier)
                .onSizeChanged { sheetHeight = it.height.toFloat() }
                .offset {
                    val visual = (progress.value * (1f - backProgress.value)).coerceIn(0f, 1f)
                    val collapsed = collapsedOffset()
                    val expanded = expandedOffset()
                    val y = collapsed + (expanded - collapsed) * visual
                    IntOffset(0, y.roundToInt())
                }
                .imageMotionChrome(zIndex = 2f)
                .clip(sheetShape)
                .background(surfaceColor, sheetShape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .then(handleDrag)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (handleExpandedLabel) "收起生成设置" else "展开生成设置",
                    ) {
                        val next = !targetExpanded
                        targetExpanded = next
                        haptics.tick()
                        scope.launch { progress.animateTo(if (next) 1f else 0f, sheetSpring) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp)),
                )
                Icon(
                    imageVector = Icons.Default.ExpandLess,
                    contentDescription = if (handleExpandedLabel) "收起生成设置" else "展开生成设置",
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .graphicsLayer {
                            val visual = (progress.value * (1f - backProgress.value)).coerceIn(0f, 1f)
                            rotationZ = visual * 180f
                        },
                )
            }
            Box(Modifier.fillMaxWidth()) {
                sheetContent(contentExpanded, contentHeaderDrag)
            }
        }
    }

    // Register after the gallery's handlers: the expanded foreground panel closes first.
    // Includes an opening animation, so back cannot leave halfway through expanding.
    PredictiveBackHandler(enabled = targetExpanded || progress.value > 0.01f) { events ->
        try {
            events.collect { event -> backProgress.snapTo(event.progress) }
            backProgress.animateTo(1f, tween(120))
            targetExpanded = false
            progress.snapTo(0f)
            backProgress.snapTo(0f)
        } catch (cancelled: CancellationException) {
            // A cancelled gesture preserves the expanded panel and its editor/scroll state.
            withContext(NonCancellable) { backProgress.animateTo(0f, sheetSpring) }
            throw cancelled
        }
    }
}
