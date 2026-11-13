package net.pocketnai.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.pocketnai.data.mcp.*
import net.pocketnai.ui.common.MotionButton
import net.pocketnai.ui.common.MotionOutlinedButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMcpScreen(manager: LocalMcpManager, onBack: () -> Unit) {
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var direct by rememberSaveable { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.running, state.starting) { if (!state.running && !state.starting) direct = false }
    LaunchedEffect(copied) { if (copied != null) { kotlinx.coroutines.delay(1600); copied = null } }
    fun copy(label: String, text: String, secret: Boolean = false) {
        val clip = ClipData.newPlainText(label, text)
        if (secret && Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        copied = label
    }
    Scaffold(topBar = { TopAppBar(title = { Text("本机助手连接") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("local-mcp-page"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SettingsCard {
                    ListItem(headlineContent = { Text("本机 MCP", fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(when {
                            state.starting -> "正在开启连接"
                            state.running -> "已开启 · 仅此手机可连接"
                            state.active > 0 -> "连接已关闭 · 已提交的任务继续保存"
                            state.stopping -> "正在结束连接"
                            else -> "即用即开，用完即关"
                        }) }, leadingContent = { SettingsIcon(Icons.Default.Hub, state.running) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                    if (!state.running && !state.starting) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("本次连接直接生成", style = MaterialTheme.typography.bodyLarge)
                                Text("开启后，已配对的助手可直接生图并产生费用", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(direct, { direct = it }, enabled = !state.stopping || state.active > 0,
                                modifier = Modifier.semantics { contentDescription = "本次连接直接生成" })
                        }
                    } else if (state.directGeneration) Text("本次连接已允许直接生成", Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    MotionButton(onClick = { if (state.running) manager.stop() else manager.start(direct) },
                        enabled = !state.starting && (!state.stopping || state.active > 0),
                        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("local-mcp-toggle")) {
                        Icon(if (state.running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp)); Text(if (state.running) "停止连接" else "开启连接")
                    }
                }
            }
            state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) } }
            if (state.jobs.isNotEmpty()) item { SettingsSection("连接任务") }
            items(state.jobs, key = { it.id }) { job ->
                SettingsCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(job.description, style = MaterialTheme.typography.titleSmall)
                        Text(job.quote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text(job.message, style = MaterialTheme.typography.bodySmall)
                        if (job.state == McpImageJob.PENDING) {
                            MotionButton(onClick = { manager.approve(job.id) }, enabled = state.running && state.active == 0,
                                modifier = Modifier.fillMaxWidth().testTag("mcp-approve-${job.id}")) { Text("确认生成 1 张") }
                            TextButton(onClick = { manager.reject(job.id) }, modifier = Modifier.fillMaxWidth()) { Text("取消此任务") }
                        }
                        var expanded by rememberSaveable(job.id) { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起提示词" else "查看提示词") }
                        net.pocketnai.ui.common.AnimatedDisclosure(expanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(job.prompt, style = MaterialTheme.typography.bodyMedium)
                                if (job.negative.isNotBlank()) Text("排除词：${job.negative}", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                    }
                }
            }
            item {
                SettingsSection("连接到 RikkaHub")
                SettingsCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("添加 Streamable HTTP 类型的 MCP 服务", style = MaterialTheme.typography.bodyMedium)
                        Text(LocalMcpManager.URL, style = MaterialTheme.typography.bodyMedium)
                        MotionOutlinedButton(onClick = { copy("服务地址", LocalMcpManager.URL) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text(if (copied == "服务地址") "已复制地址" else "复制地址")
                        }
                        Text("请求头：Authorization（认证值已包含 Bearer）", style = MaterialTheme.typography.bodyMedium)
                        MotionOutlinedButton(onClick = { scope.launch { runCatching {
                            copy("认证值", "Bearer ${manager.connectionToken()}", true)
                        } } }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text(if (copied == "认证值") "已复制认证值" else "复制认证值")
                        }
                        Text("配置一次即可。服务在锁屏或闲置后关闭，下次使用重新开启。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { reset = true }) { Text("重置连接令牌") }
                    }
                }
            }

        }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("重置连接令牌？") },
        text = { Text("当前连接将停止，客户端需要重新配置认证值。已发送的任务继续尽力保存。") },
        confirmButton = { TextButton(onClick = { reset = false; manager.resetConnection() }) { Text("重置") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}
