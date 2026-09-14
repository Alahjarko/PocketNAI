package net.pocketnai.ui.detail

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlinx.coroutines.launch
import net.pocketnai.R
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.CenteredHint
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
) {
    val container = LocalAppContainer.current
    val viewModel: DetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                DetailViewModel(
                    repository = container.generationRepository,
                    exporter = container.mediaStoreExporter,
                    draftStore = container.draftStore,
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
                    ),
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
