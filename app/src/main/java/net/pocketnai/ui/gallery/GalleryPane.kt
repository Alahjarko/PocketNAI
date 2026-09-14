package net.pocketnai.ui.gallery

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.pocketnai.R
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.export.MediaStoreExporter
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.prompt.PromptTitle
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.CenteredHint
import net.pocketnai.ui.common.messageRes
import java.io.File

/**
 * 瀑布流画廊内容（规划书 4.3）。
 *
 * 这是**内容**而不是整页：它由 `BottomSheetScaffold` 承载，
 * 生成悬浮层浮在它上面；正在生成的任务在顶部显示为占位卡片，
 * 因此把悬浮层收起来就能看着图片陆续出现。
 *
 * 长按图片弹出操作菜单：保存到系统相册、删除本地记录、复制正向提示词。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryPane(
    onOpenImage: (String) -> Unit,
    /**
     * 对这张图做局部重绘。
     *
     * 官方文档明确说局部重绘可以从"任意一张已生成的图片"进入，因此这里是画廊的入口：
     * 用户看到一张构图不错但某处画坏的图，最自然的动作就是在这里直接改它。
     */
    onInpaintImage: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val viewModel: GalleryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { GalleryViewModel(container.generationRepository) }
        },
    )
    val items by viewModel.items.collectAsStateWithLifecycle()
    val generating by viewModel.generatingCards.collectAsStateWithLifecycle()
    val undoGenerationId by viewModel.undoGenerationId.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var actionTarget by remember { mutableStateOf<GalleryItem?>(null) }
    var pendingSave by remember { mutableStateOf<GalleryItem?>(null) }

    // Android 9 及以下需要写外部存储权限才能保存到相册；Android 10+ 走 MediaStore 不需要。
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pendingSave
        pendingSave = null
        if (granted && target != null) {
            saveToSystemGallery(
                scope = scope,
                context = context,
                repository = container.generationRepository,
                exporter = container.mediaStoreExporter,
                snackbarHostState = snackbarHostState,
                item = target,
            )
        }
    }

    val deletedMessage = stringResource(R.string.action_delete)
    val undoLabel = stringResource(R.string.action_cancel)
    LaunchedEffect(undoGenerationId) {
        if (undoGenerationId == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedMessage,
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        } else {
            viewModel.clearUndo()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (items.isEmpty() && generating.isEmpty()) {
            CenteredHint(
                text = stringResource(R.string.gallery_empty),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                contentPadding = PaddingValues(8.dp),
                verticalItemSpacing = 8.dp,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = generating,
                    key = { summary -> "generating-${summary.generation.id}" },
                ) { summary ->
                    GeneratingCard(title = summary.generation.title)
                }

                items(
                    items = items,
                    key = { item -> item.imageId },
                ) { item ->
                    GalleryCard(
                        item = item,
                        imageFile = container.generationRepository.fileOf(item),
                        onClick = { onOpenImage(item.imageId) },
                        onLongClick = { actionTarget = item },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(target.title) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            actionTarget = null
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                saveToSystemGallery(
                                    scope = scope,
                                    context = context,
                                    repository = container.generationRepository,
                                    exporter = container.mediaStoreExporter,
                                    snackbarHostState = snackbarHostState,
                                    item = target,
                                )
                            } else {
                                pendingSave = target
                                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.action_save_to_gallery))
                    }

                    TextButton(
                        onClick = {
                            actionTarget = null
                            // 只复制用户看到的正向提示词，绝不复制 Token 或隐藏请求字段（规划书第 10 节）。
                            copyToClipboard(context, target.prompt)
                            scope.launch { snackbarHostState.showSnackbar("已复制提示词") }
                        },
                    ) {
                        Text(stringResource(R.string.action_copy_prompt))
                    }

                    TextButton(
                        onClick = {
                            actionTarget = null
                            onInpaintImage(target)
                        },
                    ) {
                        Text(stringResource(R.string.action_inpaint))
                    }

                    TextButton(
                        onClick = {
                            actionTarget = null
                            viewModel.deleteGeneration(target.generationId)
                        },
                    ) {
                        Text(stringResource(R.string.action_delete))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCard(
    item: GalleryItem,
    imageFile: File,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 用记录的宽高先占位，图片解码完成前就能排版，避免滚动时跳动。
            .aspectRatio(item.aspectRatio)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = imageFile,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 生成中的占位卡片：不给进度条造假数据，只用明确的文字状态。 */
@Composable
private fun GeneratingCard(title: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.HourglassTop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.gallery_status_generating),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun saveToSystemGallery(
    scope: CoroutineScope,
    context: Context,
    repository: GenerationRepository,
    exporter: MediaStoreExporter,
    snackbarHostState: SnackbarHostState,
    item: GalleryItem,
) {
    scope.launch {
        val source = repository.fileOf(item)
        val displayName = PromptTitle.exportFileName(
            title = item.title,
            timestampMillis = item.createdAt,
            ordinal = item.ordinal,
        )
        when (val outcome = exporter.export(source, displayName)) {
            is Outcome.Success -> {
                repository.markExported(item.imageId, outcome.value.toString())
                snackbarHostState.showSnackbar(context.getString(R.string.detail_saved_to_gallery))
            }

            is Outcome.Failure -> {
                snackbarHostState.showSnackbar(messageFor(context, outcome.error.code))
            }
        }
    }
}

private fun messageFor(context: Context, code: ErrorCode): String =
    context.getString(code.messageRes())

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PocketNAI prompt", text))
}
