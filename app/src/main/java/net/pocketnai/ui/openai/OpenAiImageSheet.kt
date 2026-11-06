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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.domain.openai.OpenAiImageParams
import net.pocketnai.domain.openai.OpenAiImagePricing
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
    val gifEnabled by LocalAppContainer.current.openAiImageSettings.gifEnabled.collectAsStateWithLifecycle()
    val validation = params.validate(state.draft.references.size)
        ?: if (params.isAnimated && !gifEnabled) "请先在生图服务中开启 GIF 制作（实验）" else null
    val unknownCost = when (params.outputFormat) {
        "gif" -> "GIF 费用待返回用量后核算"
        "svg" -> "SVG 含矢量编写费用，以账单为准"
        else -> "待确定尺寸后估算"
    }
    val estimate = remember(params, state.draft.references.size) {
        OpenAiImagePricing.estimate(params, state.draft.references.size)
    }
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
                Text(if (state.busy) "生成中 · ${state.activeTasks} 项任务"
                    else params.modelLabel.removePrefix("GPT Image ") + " · " +
                        (if (params.isAnimated) "GIF ${params.frameCount} 帧" else "${params.size} · ${params.quality}"),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = {
                if (!hasKey) onSettings()
                else { keyboard?.hide(); confirm = true }
            }, enabled = !state.importing && (!hasKey ||
                validation == null),
                modifier = Modifier.height(56.dp)) {
                Text(if (!hasKey) "配置密钥" else "生成")
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(top = 16.dp).verticalScroll(scroll, enabled = expanded)
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(value = params.prompt, onValueChange = { viewModel.update(params.copy(prompt = it)) },
                label = { Text("描述你想要的图片") }, placeholder = { Text("可以用中文描述主体、构图、文字、光线和风格") },
                trailingIcon = {
                    net.pocketnai.ui.common.ClearPromptButton(params.prompt, "图片描述", state.revision) {
                        viewModel.update(viewModel.state.value.draft.params.copy(prompt = it))
                        viewModel.state.value.revision
                    }
                },
                minLines = 4, maxLines = 10, enabled = true, modifier = Modifier.fillMaxWidth())
            OpenAiOption("模型", params.model, OpenAiImageParams.MODELS,
                OpenAiImageParams::labelOf, true,
                info = "Flare 适合快速迭代，Sunburst 适合精细画面与编辑。") {
                val next = params.copy(model = it)
                viewModel.update(if (next.outputFormat in next.formats) next else next.copy(outputFormat = "png"))
            }
            if (params.supportsComplexity) OpenAiOption("复杂度", params.complexity, OpenAiImageParams.COMPLEXITIES,
                OpenAiImageParams::complexityLabelOf, true,
                info = "控制绘制前的规划程度，与渲染质量分开。局部修改可选低，密集文字或复杂构图可选高；高复杂度可能增加等待时间。") {
                viewModel.update(params.copy(complexity = it))
            }
            var customSize by rememberSaveable(state.revision) { mutableStateOf(params.size !in OpenAiImageParams.SIZE_PRESETS) }
            if (!params.isAnimated) OpenAiResolutionSelector(params.size, customSize, true, { customSize = it }) {
                viewModel.update(params.copy(size = it))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { OpenAiOption("质量", params.quality, OpenAiImageParams.QUALITIES, {
                    when (it) { "low" -> "低"; "medium" -> "中"; "high" -> "高"; "xhigh" -> "极高"; "max" -> "最高"; else -> "自动" }
                }, true) { viewModel.update(params.copy(quality = it)) } }
                Box(Modifier.weight(1f)) { OpenAiOption("张数", params.count.toString(), (1..if (params.outputFormat in listOf("gif", "svg")) 1 else 10).map(Int::toString), { "$it 张" }, true) {
                    viewModel.update(params.copy(count = it.toInt()))
                } }
            }
            OpenAiCostEstimate(estimate, unknownLabel = unknownCost)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { OpenAiOption("格式", params.outputFormat, params.formats.filter { it != "gif" || gifEnabled }, String::uppercase, true) {
                    if (it == "gif" || params.size == "auto") customSize = false
                    viewModel.update(params.copy(outputFormat = it, count = if (it in listOf("gif", "svg")) 1 else params.count,
                        size = if (it == "gif") "auto" else params.size))
                } }
                Box(Modifier.weight(1f)) { OpenAiOption("背景", params.background, OpenAiImageParams.BACKGROUNDS, {
                    when (it) { "transparent" -> "透明"; "opaque" -> "不透明"; else -> "自动" }
                }, true) { viewModel.update(params.copy(background = it)) } }
            }
            if (params.isAnimated) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { OpenAiOption("帧数", params.frameCount.toString(), (2..16).map(Int::toString), { "$it 帧" }, true) {
                        viewModel.update(params.copy(frameCount = it.toInt()))
                    } }
                    Box(Modifier.weight(1f)) { OpenAiOption("每帧时长", params.frameDurationMs.toString(), listOf(20,50,100,200,500,1000,2000).map(Int::toString), { "$it ms" }, true) {
                        viewModel.update(params.copy(frameDurationMs = it.toInt()))
                    } }
                }
            }
            if (params.hasCompression) {
                Text("压缩质量 ${params.compression}", style = MaterialTheme.typography.labelLarge)
                Slider(params.compression.toFloat(), { viewModel.update(params.copy(compression = it.toInt())) },
                    valueRange = 0f..100f, enabled = true)
            }
            OpenAiOption("内容审核", params.moderation, listOf("auto", "low"),
                { if (it == "auto") "自动" else "较低过滤" }, true) { viewModel.update(params.copy(moderation = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("参考图（${state.draft.references.size}/16）", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                net.pocketnai.ui.common.InfoButton("添加参考图后按描述进行编辑。按实际用量扣除 OpenAI Platform 额度；质量、尺寸、张数和参考图都会影响费用。")
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.draft.references, key = { it.id }) { ref ->
                    Column(Modifier.width(96.dp)) {
                        AsyncImage(model = container.fileStore.resolve(ref.relativePath), contentDescription = "参考图",
                            modifier = Modifier.size(96.dp))
                        TextButton(onClick = { viewModel.removeReference(ref.id) }, enabled = true) { Text("移除") }
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.draft.references.filter { it.sourceImageId != null }.distinctBy { it.sourceImageId }, key = { it.id }) { ref ->
                    net.pocketnai.ui.generate.ReferenceOriginLink(ref, Modifier.width(208.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !state.importing && state.draft.references.size < 16, modifier = Modifier.weight(1f)) { Text("从相册选择") }
                OutlinedButton(onClick = { history = true }, enabled = !state.importing && state.draft.references.size < 16,
                    modifier = Modifier.weight(1f)) { Text("从历史选择") }
            }
            if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            validation?.takeIf { params.prompt.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (history) HistoryImagePickerDialog(onPick = { path ->
        history = false; viewModel.addReference(ReferenceSource.LocalPath(path))
    }, onDismiss = { history = false })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认使用 OpenAI 额度") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${params.modelLabel}\n${params.size} · ${params.quality} · ${params.count} 张")
            if (params.supportsComplexity) Text("复杂度 · ${params.complexityLabel}", style = MaterialTheme.typography.bodySmall)
            if (params.isAnimated) Text("${params.frameCount} 帧 · ${params.frameDurationMs} ms / 帧")
            OpenAiCostEstimate(estimate, unknownLabel = unknownCost)
            Text(if (estimate == null) "实际扣费以账单为准；费用未知不代表免费。"
                else "这是本次请求的粗估，实际扣费以账单为准。完成后可在详情查看用量折算金额。",
                style = MaterialTheme.typography.bodySmall)
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = validation == null, onClick = {
        confirm = false; viewModel.generate()
    }) { Text("生成 ${params.count} 张") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable
internal fun OpenAiOption(label: String, value: String, options: List<String>, display: (String) -> String,
    enabled: Boolean, info: String? = null, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (info != null) net.pocketnai.ui.common.InfoButton(info)
        }
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
