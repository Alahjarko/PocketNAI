package net.pocketnai.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.pocketnai.data.security.CredentialType
import net.pocketnai.ui.gallery.GalleryPane
import net.pocketnai.ui.generate.GenerateSheet
import net.pocketnai.ui.generate.GenerateViewModel

/**
 * 首页：画廊 + 可上拉下滑的生成悬浮层。
 *
 * 合并原本的“生成”和“画廊”两个页面（规划书原本把它们分成两个 Tab）。
 * 这样在生成时把悬浮层收起，就能直接看着图片一张张出现在瀑布流里，
 * 不用来回切页。
 *
 * `skipHiddenState = true` 保证悬浮层永远不会被完全收起 ——
 * 用户不会因为一次误拖就把生成入口弄丢。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    generateViewModel: GenerateViewModel,
    state: GenerateViewModel.UiState,
    connected: Boolean,
    credentialType: CredentialType?,
    onOpenImage: (String) -> Unit,
    onInpaintImage: (net.pocketnai.domain.model.GalleryItem) -> Unit,
    onOpenInpaintEditor: () -> Unit,
    onRequestConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true,
        ),
    )

    // 生成一开始就把悬浮层收起来：用户此刻想看的是图片陆续出现，而不是参数表单。
    LaunchedEffect(state.inFlight) {
        if (state.inFlight) {
            scaffoldState.bottomSheetState.partialExpand()
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        // 收起时必须能完整显示头部（标题 + 参数摘要一行、状态/余额一行、生成按钮）
        // 与自带的拖拽横条。头部两行化（2026-09-21）后从 146dp 降下来，
        // 否则会多露一截表单空白。
        sheetPeekHeight = 112.dp,
        sheetContent = {
            GenerateSheet(
                viewModel = generateViewModel,
                state = state,
                expanded = scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded,
                connected = connected,
                credentialType = credentialType,
                onRequestConnect = onRequestConnect,
                onOpenInpaintEditor = onOpenInpaintEditor,
            )
        },
        modifier = modifier,
    ) { contentPadding ->
        GalleryPane(
            onOpenImage = onOpenImage,
            onInpaintImage = onInpaintImage,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        )
    }
}
