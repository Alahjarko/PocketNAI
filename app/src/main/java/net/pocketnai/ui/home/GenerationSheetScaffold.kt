package net.pocketnai.ui.home

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.max
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import net.pocketnai.ui.motion.imageMotionChrome
import kotlinx.coroutines.launch

/** 内容滚动与面板切换分开：仅把手/标题接受明确拖动，不接收内容的剩余滚动或 fling。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenerationSheetScaffold(
    inFlight: Boolean,
    sheetContent: @Composable (expanded: Boolean, headerDragModifier: Modifier) -> Unit,
    submissionRevision: Int = 0,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true,
        ),
    )
    val sheetState = scaffoldState.bottomSheetState
    val scope = rememberCoroutineScope()
    val backProgress = remember { Animatable(0f) }
    var backStartOffset by remember { mutableFloatStateOf(0f) }
    var scaffoldHeight by remember { mutableFloatStateOf(0f) }
    val peekPx = with(LocalDensity.current) { 112.dp.toPx() }
    val backShift = Modifier.graphicsLayer {
        val current = runCatching { sheetState.requireOffset() }.getOrDefault(backStartOffset)
        val destination = max(backStartOffset, scaffoldHeight - peekPx)
        val visual = backStartOffset + (destination - backStartOffset) * backProgress.value
        translationY = if (backProgress.value > 0f) (visual - current).coerceAtLeast(0f) else 0f
    }
    val expanded = sheetState.currentValue == SheetValue.Expanded
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val headerDrag = Modifier.pointerInput(sheetState, threshold) {
        var distance = 0f
        detectVerticalDragGestures(
            onDragStart = { distance = 0f },
            onVerticalDrag = { change, amount ->
                change.consume()
                distance += amount
            },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                val drag = distance
                distance = 0f
                // 只按净位移判断意图；短促甩动不会因为速度大而切换面板。
                if (drag <= -threshold) scope.launch { sheetState.expand() }
                if (drag >= threshold) scope.launch { sheetState.partialExpand() }
            },
        )
    }

    LaunchedEffect(inFlight, submissionRevision) {
        if (inFlight) sheetState.partialExpand()
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        modifier = Modifier.onSizeChanged { scaffoldHeight = it.height.toFloat() },
        sheetPeekHeight = 112.dp,
        sheetSwipeEnabled = false,
        sheetContainerColor = Color.Transparent,
        sheetDragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .imageMotionChrome(zIndex = 2f)
                    .then(backShift)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .then(headerDrag)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起生成设置" else "展开生成设置",
                    ) {
                        scope.launch {
                            if (sheetState.targetValue == SheetValue.Expanded) sheetState.partialExpand()
                            else sheetState.expand()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp)),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (expanded) "收起生成设置" else "展开生成设置",
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                )
            }
        },
        sheetContent = {
            Box(Modifier.fillMaxWidth().imageMotionChrome(zIndex = 2f).then(backShift).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                sheetContent(expanded, headerDrag)
            }
        },
        content = content,
    )
    // Register after the gallery's handlers: the expanded foreground panel closes first.
    // Includes an opening animation, so back cannot leave halfway through expanding.
    PredictiveBackHandler(enabled = expanded || sheetState.targetValue == SheetValue.Expanded) { events ->
        backStartOffset = sheetState.requireOffset()
        try {
            events.collect { event -> backProgress.snapTo(event.progress) }
            backProgress.animateTo(1f, tween(120))
            sheetState.partialExpand()
            backProgress.snapTo(0f)
        } catch (cancelled: CancellationException) {
            // A cancelled gesture preserves the expanded panel and its editor/scroll state.
            withContext(NonCancellable) { backProgress.animateTo(0f, tween(160)) }
            throw cancelled
        }
    }
}
