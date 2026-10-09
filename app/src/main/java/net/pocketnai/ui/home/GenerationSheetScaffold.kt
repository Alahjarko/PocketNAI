package net.pocketnai.ui.home

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

    LaunchedEffect(inFlight) {
        if (inFlight) sheetState.partialExpand()
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 112.dp,
        sheetSwipeEnabled = false,
        sheetContainerColor = Color.Transparent,
        sheetDragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .imageMotionChrome(zIndex = 2f)
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
            Box(Modifier.fillMaxWidth().imageMotionChrome(zIndex = 2f).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                sheetContent(expanded, headerDrag)
            }
        },
        content = content,
    )
}
