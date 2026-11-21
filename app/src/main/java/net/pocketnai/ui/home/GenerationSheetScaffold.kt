package net.pocketnai.ui.home

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pocketnai.ui.motion.imageMotionChrome
import net.pocketnai.ui.motion.rememberPocketHaptics
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class SheetBodyCollapseController(
    val isReadyAtTop: () -> Boolean,
    val currentRange: () -> Float,
    val snapProgress: (Float) -> Unit,
    val settleFromBodyPull: (pulledSheetPx: Float, rootVelocityY: Float, alreadyTicked: Boolean) -> Unit,
    val onThresholdTick: () -> Unit,
    val collapseDistanceForRange: (Float) -> Float,
    val cushionPx: Float,
)

internal val LocalSheetBodyCollapse = compositionLocalOf<SheetBodyCollapseController?> { null }

/**
 * 当创作抽屉已完全展开且内部表单原本就停在最顶部（按下瞬间 `scrollState.value == 0`）时，
 * 允许继续大幅下拉直接收起抽屉：
 * - 从表单中下部滚回顶部（按下时 `scrollState.value > 0`）或长输入框内部滚动绝不触发抽屉下拉；
 * - 顶部附带 `16dp` 微阻尼缓冲，且必须拉过大幅阈值（`max(148dp, 24% 行程)`，或快速大挥动 `>=108dp`）才收起，
 *   短距离下滑一律按弹簧回弹，彻底杜绝阅读与点按误触。
 */
internal fun Modifier.collapseSheetOnTopPullDown(scrollState: ScrollState): Modifier = composed {
    val controller = LocalSheetBodyCollapse.current ?: return@composed this
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val state = remember(controller, scrollState) {
        object {
            var isPointerDown = false
            var eligible = false
            var pulling = false
            var hadBodyPull = false
            var lastRootY = 0f
            var pullOriginRootY = 0f
            var rawPullPx = 0f
            var pulledSheetPx = 0f
            var thresholdArmed = false
            val velocityTracker = VelocityTracker()

            fun rawToSheetPx(raw: Float, range: Float): Float {
                if (raw <= 0f) return 0f
                val c = controller.cushionPx
                val mapped = if (raw <= c) {
                    raw * 0.4f
                } else {
                    c * 0.4f + (raw - c) * 0.95f
                }
                return mapped.coerceIn(0f, range)
            }

            fun updatePull(newRaw: Float) {
                val range = controller.currentRange()
                rawPullPx = newRaw.coerceAtLeast(0f)
                pulledSheetPx = rawToSheetPx(rawPullPx, range)
                val nextProgress = (1f - pulledSheetPx / range).coerceIn(0f, 1f)
                controller.snapProgress(nextProgress)

                val threshold = controller.collapseDistanceForRange(range)
                if (!thresholdArmed && pulledSheetPx >= threshold) {
                    thresholdArmed = true
                    controller.onThresholdTick()
                } else if (thresholdArmed && pulledSheetPx < threshold * 0.85f) {
                    thresholdArmed = false
                }
            }

            fun startPull(initialAvailableY: Float) {
                pulling = true
                hadBodyPull = true
                pullOriginRootY = lastRootY - initialAvailableY
                updatePull(initialAvailableY)
            }

            fun finishIfPulling() {
                if (!pulling && rawPullPx <= 0f && pulledSheetPx <= 0f) {
                    eligible = false
                    return
                }
                val vy = velocityTracker.calculateVelocity().y
                val finalPulled = pulledSheetPx
                val ticked = thresholdArmed
                eligible = false
                pulling = false
                rawPullPx = 0f
                pulledSheetPx = 0f
                thresholdArmed = false
                controller.settleFromBodyPull(finalPulled, vy, ticked)
            }
        }
    }

    val nestedConnection = remember(state) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // 若子组件（如多行提示词输入框或表单自身）已发生内容滚动，本轮手势锁定不拉抽屉。
                if ((consumed.y > 0.5f || consumed.y < -0.5f) && !state.pulling) {
                    state.eligible = false
                    return Offset.Zero
                }
                if (!state.eligible || source != NestedScrollSource.UserInput) {
                    return Offset.Zero
                }
                if (!state.pulling && available.y > 0f && scrollState.value == 0) {
                    state.startPull(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.isPointerDown) {
                    return if (state.pulling || state.hadBodyPull) available else Velocity.Zero
                }
                if (state.pulling || state.rawPullPx > 0f || state.pulledSheetPx > 0f) {
                    state.finishIfPulling()
                    state.hadBodyPull = false
                    return available
                }
                if (state.hadBodyPull) {
                    state.hadBodyPull = false
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    this
        .onGloballyPositioned { coords = it }
        .pointerInput(state, scrollState) {
            awaitPointerEventScope {
                var wasPressed = false
                while (true) {
                    val initialEvent = awaitPointerEvent(PointerEventPass.Initial)
                    val pressedChanges = initialEvent.changes.filter { it.pressed }
                    val isPressed = pressedChanges.isNotEmpty()
                    val primary = pressedChanges.firstOrNull() ?: initialEvent.changes.firstOrNull()
                    val rootY = primary?.let { coords?.localToRoot(it.position)?.y ?: it.position.y }

                    if (!wasPressed && isPressed) {
                        state.isPointerDown = true
                        state.velocityTracker.resetTracking()
                        state.pulling = false
                        state.rawPullPx = 0f
                        state.pulledSheetPx = 0f
                        state.hadBodyPull = false
                        state.thresholdArmed = false
                        if (rootY != null) {
                            state.lastRootY = rootY
                            state.pullOriginRootY = rootY
                        }
                        state.eligible = pressedChanges.size == 1 &&
                            scrollState.value == 0 &&
                            controller.isReadyAtTop()
                    }
                    if (pressedChanges.size > 1 && !state.pulling) {
                        state.eligible = false
                    }
                    if (primary != null && rootY != null) {
                        state.lastRootY = rootY
                        state.velocityTracker.addPosition(primary.uptimeMillis, Offset(0f, rootY))
                        if (isPressed && state.pulling) {
                            val nextRaw = rootY - state.pullOriginRootY
                            if (nextRaw > 0f) {
                                state.updatePull(nextRaw)
                                pressedChanges.forEach { it.consume() }
                            } else {
                                state.updatePull(0f)
                                state.pulling = false
                            }
                        }
                    }

                    if (wasPressed && !isPressed) {
                        state.isPointerDown = false
                        state.finishIfPulling()
                    }
                    wasPressed = isPressed

                    val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                    val stillPressed = finalEvent.changes.any { it.pressed }
                    if (wasPressed && !stillPressed) {
                        state.isPointerDown = false
                        state.finishIfPulling()
                        wasPressed = false
                    }
                }
            }
        }
        .nestedScroll(nestedConnection)
}

/**
 * 头部支持 1:1 连续跟手拖动与临界阻尼弹簧无停顿触顶；
 * 展开到顶且表单处于顶部时，支持大阈值下拉收起抽屉，兼顾单手操作与防误触。
 */
@Composable
internal fun GenerationSheetScaffold(
    inFlight: Boolean,
    sheetContent: @Composable (expanded: Boolean, headerDragModifier: Modifier) -> Unit,
    submissionRevision: Int = 0,
    expandSignal: Int = 0,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = rememberPocketHaptics()
    val handleDp = 48.dp
    val handlePx = with(density) { handleDp.toPx() }
    val fallbackPeekDp = 112.dp
    val fallbackPeekPx = with(density) { fallbackPeekDp.toPx() }
    val thresholdPx = with(density) { 48.dp.toPx() }
    val flingVelocityPx = with(density) { 420.dp.toPx() }
    val bodyMinCollapsePx = with(density) { 148.dp.toPx() }
    val bodyMinFlingDistPx = with(density) { 108.dp.toPx() }
    val bodyFlingVelocityPx = with(density) { 880.dp.toPx() }
    val bodyCancelUpVelocityPx = with(density) { 140.dp.toPx() }
    val bodyCushionPx = with(density) { 16.dp.toPx() }

    var targetExpanded by rememberSaveable { mutableStateOf(false) }
    var handledExpandSignal by rememberSaveable { mutableStateOf(expandSignal) }
    // 亚像素收敛阈值（0.0003 * 2400px < 0.8px），消除默认 0.01（约 20px）在触顶前的渐近爬行与末帧跳变。
    val progress = remember {
        Animatable(
            initialValue = if (targetExpanded) 1f else 0f,
            visibilityThreshold = 0.0003f,
        )
    }
    LaunchedEffect(expandSignal) {
        if (expandSignal > handledExpandSignal) {
            handledExpandSignal = expandSignal
            targetExpanded = true
            progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 750f))
        }
    }
    val backProgress = remember { Animatable(0f, visibilityThreshold = 0.0005f) }
    var scaffoldHeight by remember { mutableFloatStateOf(0f) }
    var sheetHeight by remember { mutableFloatStateOf(0f) }
    var measuredHeaderPx by remember { mutableFloatStateOf(0f) }

    val sheetSpring = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 750f,
        )
    }

    fun effectivePeekPx(): Float =
        if (measuredHeaderPx > 0f) handlePx + measuredHeaderPx else fallbackPeekPx

    fun expandedOffset(): Float =
        if (scaffoldHeight <= 0f || sheetHeight <= 0f) 0f
        else (scaffoldHeight - sheetHeight).coerceAtLeast(0f)

    fun collapsedOffset(): Float =
        if (scaffoldHeight <= 0f) 0f
        else (scaffoldHeight - effectivePeekPx()).coerceAtLeast(expandedOffset())

    fun sheetRange(): Float = (collapsedOffset() - expandedOffset()).coerceAtLeast(1f)

    // 一旦离开底部静止位（>0.002f）或目标为展开即保持表单启用，
    // 展开全程至触顶（0.002 -> 1.000）零布尔翻转、零大表单重组，彻底根治触顶前“顿一下”。
    val contentExpanded by remember {
        derivedStateOf { (targetExpanded || progress.value > 0.002f) && backProgress.value == 0f }
    }
    val handleExpandedLabel by remember {
        derivedStateOf { targetExpanded }
    }

    val bodyCollapseController = remember(
        bodyMinCollapsePx,
        bodyMinFlingDistPx,
        bodyFlingVelocityPx,
        bodyCancelUpVelocityPx,
        bodyCushionPx,
    ) {
        SheetBodyCollapseController(
            isReadyAtTop = {
                targetExpanded && progress.value >= 0.98f && backProgress.value == 0f
            },
            currentRange = ::sheetRange,
            snapProgress = { target ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    progress.snapTo(target)
                }
            },
            collapseDistanceForRange = { range ->
                min(max(bodyMinCollapsePx, range * 0.24f), range * 0.48f)
            },
            cushionPx = bodyCushionPx,
            onThresholdTick = { haptics.tick() },
            settleFromBodyPull = { pulledSheetPx, rootVy, alreadyTicked ->
                val range = sheetRange()
                val collapseThreshold = min(max(bodyMinCollapsePx, range * 0.24f), range * 0.48f)
                val minFlingDist = min(max(bodyMinFlingDistPx, range * 0.17f), collapseThreshold)
                val collapseByDistance = pulledSheetPx >= collapseThreshold && rootVy >= -bodyCancelUpVelocityPx
                val collapseByFling = pulledSheetPx >= minFlingDist && rootVy >= bodyFlingVelocityPx
                val shouldCollapse = collapseByDistance || collapseByFling

                if (shouldCollapse) {
                    if (!alreadyTicked) haptics.gestureEnd()
                    targetExpanded = false
                    val initialVelocity = (-rootVy / range).coerceIn(-12f, 0f)
                    scope.launch {
                        progress.animateTo(
                            targetValue = 0f,
                            animationSpec = sheetSpring,
                            initialVelocity = initialVelocity,
                        )
                    }
                } else {
                    val initialVelocity = (-rootVy / range).coerceIn(0f, 8f)
                    scope.launch {
                        progress.animateTo(
                            targetValue = 1f,
                            animationSpec = sheetSpring,
                            initialVelocity = initialVelocity,
                        )
                    }
                }
            },
        )
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
                    val initialRange = sheetRange()
                    trackedProgress = (trackedProgress - overSlop / initialRange).coerceIn(0f, 1f)
                    val initialSnap = trackedProgress
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(initialSnap) }

                    val completed = verticalDrag(drag.id) { change ->
                        val rootY = coords?.localToRoot(change.position)?.y ?: change.position.y
                        val deltaY = rootY - lastRootY
                        lastRootY = rootY
                        totalDragY += deltaY
                        velocityTracker.addPosition(change.uptimeMillis, Offset(0f, rootY))
                        change.consume()
                        val range = sheetRange()
                        trackedProgress = (trackedProgress - deltaY / range).coerceIn(0f, 1f)
                        val snapTarget = trackedProgress
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(snapTarget) }
                    }

                    if (!completed) {
                        val settle = targetExpanded
                        scope.launch { progress.animateTo(if (settle) 1f else 0f, sheetSpring) }
                    } else {
                        val vy = velocityTracker.calculateVelocity().y
                        val range = sheetRange()
                        val nextExpanded = when {
                            vy <= -flingVelocityPx -> true
                            vy >= flingVelocityPx -> false
                            !startExpanded && totalDragY <= -thresholdPx -> true
                            startExpanded && totalDragY >= thresholdPx -> false
                            else -> trackedProgress >= 0.5f
                        }
                        if (nextExpanded != startExpanded) haptics.gestureEnd()
                        targetExpanded = nextExpanded
                        val rawVelocity = -vy / range
                        val initialVelocity = if (nextExpanded) {
                            rawVelocity.coerceIn(0f, 12f)
                        } else {
                            rawVelocity.coerceIn(-12f, 0f)
                        }
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
        .onSizeChanged { measuredHeaderPx = it.height.toFloat() }

    LaunchedEffect(inFlight, submissionRevision) {
        if (inFlight) {
            targetExpanded = false
            progress.animateTo(0f, sheetSpring)
        }
    }

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
    val effectivePeekDp = if (measuredHeaderPx > 0f) {
        handleDp + with(density) { measuredHeaderPx.toDp() }
    } else {
        fallbackPeekDp
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { scaffoldHeight = it.height.toFloat() },
    ) {
        content(PaddingValues(bottom = effectivePeekDp))

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
                CompositionLocalProvider(LocalSheetBodyCollapse provides bodyCollapseController) {
                    sheetContent(contentExpanded, contentHeaderDrag)
                }
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
