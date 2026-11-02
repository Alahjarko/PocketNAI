package net.pocketnai.ui.openai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.domain.openai.OpenAiImageParams
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.generate.HistoryImagePickerDialog

@Composable
fun OpenAiImageSheet(
    viewModel: OpenAiImageViewModel,
    expanded: Boolean,
    onSettings: () -> Unit,
    headerDragModifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasKey by LocalAppContainer.current.openAiImageSettings.hasKey.collectAsStateWithLifecycle()
    val params = state.draft.params
    val scroll = rememberScrollState()
    val keyboard = LocalSoftwareKeyboardController.current
    var history by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(16)) { uris ->
        viewModel.addReferences(uris.map { ReferenceSource.PickedUri(it.toString()) })
    }
    val container = LocalAppContainer.current
    Column {
        Row(Modifier.fillMaxWidth().then(headerDragModifier).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("OpenAI 生图", style = MaterialTheme.typography.titleMedium)
                Text(if (state.busy) "生成中 · 已保存 ${state.completed}/${params.count} 张"
                    else (if (params.model == OpenAiImageParams.FLARE) "Flare" else "Sunburst") + " · ${params.size} · ${params.quality}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = {
                if (!hasKey) onSettings()
                else { keyboard?.hide(); confirm = true }
            }, enabled = !state.busy && !state.importing && (params.prompt.isNotBlank() || !hasKey),
                modifier = Modifier.height(56.dp)) {
                if (state.busy) {
                    net.pocketnai.ui.motion.GenerationOrb(Modifier.size(20.dp), color = LocalContentColor.current, description = null)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (!hasKey) "配置密钥" else if (state.busy) "生成中" else "生成")
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(top = 16.dp).verticalScroll(scroll, enabled = expanded)
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(value = params.prompt, onValueChange = { viewModel.update(params.copy(prompt = it)) },
                label = { Text("描述你想要的图片") }, placeholder = { Text("可以用中文描述主体、构图、文字、光线和风格") },
                minLines = 4, maxLines = 10, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            OpenAiOption("模型", params.model, OpenAiImageParams.MODELS,
                { if (it == OpenAiImageParams.FLARE) "GPT Image 2.5 Flare" else "GPT Image 2.5 Sunburst" },
                !state.busy) { viewModel.update(params.copy(model = it)) }
            Text(if (params.model == OpenAiImageParams.FLARE) "适合快速、日常的高质量生成" else "适合注重精确度的参考图编辑",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val presets = listOf("1024x1024", "1536x1024", "1024x1536", "1536x864", "864x1536", "auto")
            var custom by remember(state.revision) { mutableStateOf(params.size !in presets) }
            OpenAiOption("尺寸", if (custom) "custom" else params.size, presets + "custom", {
                when (it) { "auto" -> "自动"; "custom" -> "自定义"; else -> it.replace("x", " × ") }
            }, !state.busy) {
                custom = it == "custom"
                if (!custom) viewModel.update(params.copy(size = it))
                else if (params.size == "auto") viewModel.update(params.copy(size = "1024x1024"))
            }
            if (custom) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val parts = params.size.split('x')
                    OutlinedTextField(parts.getOrElse(0) { "" }, { value ->
                        viewModel.update(params.copy(size = value.filter(Char::isDigit).take(4) + "x" + parts.getOrElse(1) { "1024" }))
                    }, label = { Text("宽度") }, singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f))
                    OutlinedTextField(parts.getOrElse(1) { "" }, { value ->
                        viewModel.update(params.copy(size = parts.getOrElse(0) { "1024" } + "x" + value.filter(Char::isDigit).take(4)))
                    }, label = { Text("高度") }, singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f))
                }
                Text("宽高须为16的倍数；超过2560×1440的像素量属于实验性分辨率。", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { OpenAiOption("质量", params.quality, OpenAiImageParams.QUALITIES, {
                    when (it) { "low" -> "低"; "medium" -> "中"; "high" -> "高"; "xhigh" -> "极高"; "max" -> "最高"; else -> "自动" }
                }, !state.busy) { viewModel.update(params.copy(quality = it)) } }
                Box(Modifier.weight(1f)) { OpenAiOption("张数", params.count.toString(), (1..10).map(Int::toString), { "$it 张" }, !state.busy) {
                    viewModel.update(params.copy(count = it.toInt()))
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { OpenAiOption("格式", params.outputFormat, OpenAiImageParams.FORMATS, String::uppercase, !state.busy) {
                    viewModel.update(params.copy(outputFormat = it))
                } }
                Box(Modifier.weight(1f)) { OpenAiOption("背景", params.background, OpenAiImageParams.BACKGROUNDS, {
                    when (it) { "transparent" -> "透明"; "opaque" -> "不透明"; else -> "自动" }
                }, !state.busy) { viewModel.update(params.copy(background = it)) } }
            }
            if (params.outputFormat != "png") {
                Text("压缩质量 ${params.compression}", style = MaterialTheme.typography.labelLarge)
                Slider(params.compression.toFloat(), { viewModel.update(params.copy(compression = it.toInt())) },
                    valueRange = 0f..100f, enabled = !state.busy)
            }
            OpenAiOption("内容审核", params.moderation, listOf("auto", "low"),
                { if (it == "auto") "自动" else "较低过滤" }, !state.busy) { viewModel.update(params.copy(moderation = it)) }
            Text("参考图（${state.draft.references.size}/16）", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.draft.references, key = { it.id }) { ref ->
                    Column(Modifier.width(96.dp)) {
                        AsyncImage(model = container.fileStore.resolve(ref.relativePath), contentDescription = "参考图",
                            modifier = Modifier.size(96.dp))
                        TextButton(onClick = { viewModel.removeReference(ref.id) }, enabled = !state.busy) { Text("移除") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !state.busy && !state.importing && state.draft.references.size < 16, modifier = Modifier.weight(1f)) { Text("从相册选择") }
                OutlinedButton(onClick = { history = true }, enabled = !state.busy && !state.importing && state.draft.references.size < 16,
                    modifier = Modifier.weight(1f)) { Text("从历史选择") }
            }
            if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("添加参考图后按描述进行编辑。按实际用量扣除 OpenAI Platform 额度；质量、尺寸、张数和参考图都会影响费用。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (history) HistoryImagePickerDialog(onPick = { path ->
        history = false; viewModel.addReference(ReferenceSource.LocalPath(path))
    }, onDismiss = { history = false })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认使用 OpenAI 额度") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${params.modelLabel}\n${params.size} · ${params.quality} · ${params.count} 张")
            Text("费用取决于实际 token 用量，生成前无法保证固定金额。完成后会在详情中记录可取得的用量估算。")
            params.validate()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = params.validate() == null, onClick = {
        confirm = false; viewModel.generate()
    }) { Text("生成 ${params.count} 张") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable
private fun OpenAiOption(label: String, value: String, options: List<String>, display: (String) -> String,
    enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(display(value), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(display(option)) }, onClick = { open = false; onSelect(option) }) }
            }
        }
    }
}
