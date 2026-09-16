package net.pocketnai.ui.detail

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.launch
import net.pocketnai.R
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.domain.model.DirectorReferenceKind
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.CenteredHint
import net.pocketnai.ui.common.labelRes
import net.pocketnai.ui.common.messageRes
import java.text.DateFormat
import java.util.Date

/**
 * 图片详情页。
 *
 * 展示完整参数，并提供规划书 4.3 要求的四项操作：保存到相册、复制提示词、删除、复用参数。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    imageId: String,
    onBack: () -> Unit,
    onParamsReused: () -> Unit,
    onInpaint: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: DetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                DetailViewModel(
                    repository = container.generationRepository,
                    exporter = container.mediaStoreExporter,
                    draftStore = container.draftStore,
                    favorites = container.favoriteImageRepository,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(imageId) { viewModel.load(imageId) }

    // 删除完成后立刻返回列表，避免停在已经不存在的记录上。
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    val savedMessage = stringResource(R.string.detail_saved_to_gallery)
    var pendingSave by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingSave) viewModel.saveToSystemGallery()
        pendingSave = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                },
                actions = {
                    // 收藏放在顶栏：详情页是"这张图我喜不喜欢"的判断现场，
                    // 而收藏只是个开关，不值得在下面再占一整行按钮。
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            imageVector = if (state.favorite) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Filled.FavoriteBorder
                            },
                            contentDescription = stringResource(
                                if (state.favorite) {
                                    R.string.action_unfavorite
                                } else {
                                    R.string.action_favorite
                                },
                            ),
                            tint = if (state.favorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        val image = state.image
        val generation = state.generation

        if (image == null || generation == null) {
            CenteredHint(
                text = if (state.loading) "正在载入…" else stringResource(R.string.error_unknown),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        // 点图进入全屏查看（双指缩放 / 拖动），单击或返回键退出。
        var fullscreen by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = container.generationRepository.fileOfRelativePath(image.privateFilePath),
                contentDescription = generation.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        if (image.height > 0) image.width.toFloat() / image.height else 1f,
                    )
                    .clickable { fullscreen = true },
            )

            Text(text = generation.title, style = MaterialTheme.typography.titleMedium)

            val time = DateFormat.getDateTimeInstance().format(Date(generation.createdAt))
            DetailRow(stringResource(R.string.common_time), time)
            DetailRow(stringResource(R.string.common_model), generation.params.model.displayName)
            DetailRow(stringResource(R.string.common_size), generation.params.size.label)
            DetailRow(stringResource(R.string.generate_count), "${generation.params.sampleCount} 张（本张序号 ${image.ordinal}）")
            DetailRow(stringResource(R.string.generate_steps), generation.params.steps.toString())
            DetailRow(stringResource(R.string.generate_guidance), generation.params.guidance.toString())
            DetailRow(stringResource(R.string.generate_cfg_rescale), generation.params.cfgRescale.toString())
            DetailRow(stringResource(R.string.generate_sampler), generation.params.sampler.displayName)
            DetailRow(stringResource(R.string.generate_noise_schedule), generation.params.noiseSchedule.displayName)
            DetailRow(
                stringResource(R.string.generate_seed),
                image.seed?.toString() ?: "由 PNG 元数据决定（首版未解析）",
            )
            if (generation.errorCode != null) {
                DetailRow("错误", generation.errorMessage ?: generation.errorCode.name)
            }

            // 生成模式必须显示：图生图的尺寸、Strength 都只有在"这是一次改图"的前提下才说得通，
            // 否则用户回看历史时会以为那次生成的参数配错了。
            DetailRow(
                stringResource(R.string.common_mode),
                stringResource(generation.mode.labelRes()),
            )
            generation.references.forEach { reference ->
                ReferenceRow(reference = reference)
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("正向提示词", style = MaterialTheme.typography.labelMedium)
                    Text(generation.params.prompt, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Undesired Content",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(generation.params.negativePrompt, style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.errorCode?.let { code ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(code.messageRes()),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::dismissError) {
                            Text(stringResource(R.string.action_confirm))
                        }
                    }
                }
            }

            if (state.savedToGallery) {
                Text(
                    text = savedMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.saveToSystemGallery()
                    } else {
                        pendingSave = true
                        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save_to_gallery))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { copyToClipboard(context, generation.params.prompt) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_copy_prompt))
                }
                OutlinedButton(
                    onClick = {
                        viewModel.reuseParams()
                        onParamsReused()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_reuse_params))
                }
            }

            // 局部重绘：官方文档说可以从"任意一张已生成的图片"进入，
            // 而"这张图某处画坏了"正是用户点进详情页的常见理由。
            OutlinedButton(
                onClick = { onInpaint(image.privateFilePath) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_inpaint))
            }

            OutlinedButton(
                onClick = viewModel::deleteGeneration,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_delete))
            }

            Text(
                text = "删除只影响 PocketNAI 的本地副本与记录；已经保存到系统相册的图片不会被删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (fullscreen) {
            FullscreenImageViewer(
                imageFile = container.generationRepository.fileOfRelativePath(image.privateFilePath),
                contentDescription = generation.title,
                imageWidth = image.width,
                imageHeight = image.height,
                onDismiss = { fullscreen = false },
            )
        }
    }
}

/**
 * 一条参考图：缩略图 + 它的逐条参数。
 *
 * 缩略图直接是**提交时用的那张本地文件**，因此用户看到的就是当时真正发出去的图
 * （图生图的起点图在提交时会被裁切，这里显示的是原始素材，两者的裁切关系在生成页已说明）。
 */
@Composable
private fun ReferenceRow(reference: ReferenceImage) {
    val container = LocalAppContainer.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = container.fileStore.resolve(reference.relativePath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = when (reference.role) {
                    ReferenceRole.IMG2IMG -> stringResource(R.string.detail_reference_img2img)
                    ReferenceRole.VIBE -> stringResource(R.string.detail_reference_vibe)
                    ReferenceRole.DIRECTOR -> stringResource(R.string.detail_reference_director)
                    ReferenceRole.INPAINT_MASK -> stringResource(R.string.detail_reference_inpaint_mask)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${reference.width} × ${reference.height}",
                style = MaterialTheme.typography.bodyMedium,
            )
            reference.strength?.let { strength ->
                Text(
                    text = stringResource(R.string.generate_reference_strength) + "  $strength",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            reference.secondaryStrength?.let { fidelity ->
                Text(
                    text = stringResource(R.string.generate_reference_fidelity) + "  $fidelity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            reference.directorKind?.let { kind ->
                Text(
                    text = stringResource(
                        when (kind) {
                            DirectorReferenceKind.CHARACTER -> R.string.generate_reference_kind_character
                            DirectorReferenceKind.STYLE -> R.string.generate_reference_kind_style
                            DirectorReferenceKind.CHARACTER_AND_STYLE ->
                                R.string.generate_reference_kind_character_style
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PocketNAI prompt", text))
}
