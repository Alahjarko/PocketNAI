package net.pocketnai.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.chat.LlmProvider
import net.pocketnai.domain.chat.LlmProviderCatalog
import net.pocketnai.ui.settings.*

/** Presentation only: provider credentials and draft persistence remain owned by ChatViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatConnectionOverview(
    catalog: LlmProviderCatalog,
    providerKeys: Set<String>,
    assistantName: String,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (LlmProvider) -> Unit,
    onDelete: (LlmProvider) -> Unit,
    onPick: () -> Unit,
    onPersona: () -> Unit,
    onSearch: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val selected = catalog.providers.firstOrNull { it.id == catalog.activeProviderId }
    Scaffold(modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(title = { Text("连接与人格") }, navigationIcon = {
                FilledTonalIconButton(onClick = onBack, shape = CircleShape) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置")
                }
            }, scrollBehavior = scroll, colors = TopAppBarDefaults.largeTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background))
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) item(key = "busy") {
                Text("正在回复，结束或停止后可修改连接配置", Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = "current-model") {
                SettingsCard {
                    SettingsNavigationRow("当前模型", if (selected != null && !catalog.activeModelId.isNullOrBlank())
                        "${selected.name} · ${catalog.activeModelId}" else "尚未选择模型",
                        Icons.Default.AutoAwesome, onPick, enabled = !busy && catalog.providers.any { it.models.isNotEmpty() },
                        active = selected != null, trailing = { Icon(Icons.Default.SwapHoriz, null) })
                }
            }
            item(key = "providers-heading") {
                SettingsSection("模型服务") {
                    TextButton(onClick = onAdd, enabled = !busy) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加提供商")
                    }
                }
            }
            if (catalog.providers.isEmpty()) item(key = "empty-providers") {
                SettingsCard {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsIcon(Icons.Default.Hub)
                        Text("连接你的 AI 服务", style = MaterialTheme.typography.titleMedium)
                        Text("添加提供商，配置模型后即可在对话中切换。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(catalog.providers, key = { "provider:${it.id}" }) { provider ->
                SettingsCard(Modifier.animateItem(fadeInSpec = tween(160), fadeOutSpec = tween(100),
                    placementSpec = spring(dampingRatio = 1f, stiffness = 600f))) {
                    SettingsNavigationRow(provider.name,
                        "${provider.models.size} 个模型 · ${if (provider.id in providerKeys) "密钥已保存" else "未配置密钥"}",
                        Icons.Default.Dns, { onEdit(provider) }, enabled = !busy, active = provider.id == catalog.activeProviderId,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (provider.id == catalog.activeProviderId) {
                                    Icon(Icons.Default.CheckCircle, "当前提供商", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { onDelete(provider) }, enabled = !busy) {
                                    Icon(Icons.Default.DeleteOutline, "删除 ${provider.name}", Modifier.size(21.dp))
                                }
                            }
                        })
                }
            }
            item(key = "preferences-heading") { SettingsSection("对话偏好") }
            item(key = "preferences") {
                SettingsCard {
                    SettingsNavigationRow("人格与工具权限", assistantName, Icons.Default.Face,
                        onPersona, enabled = !busy)
                    HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    SettingsNavigationRow("联网搜索", "DeepSeek / Tavily", Icons.Default.TravelExplore,
                        onSearch, enabled = !busy)
                }
            }
            error?.let { message -> item(key = "error") {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                    Text(message, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium)
                }
            } }
        }
    }
}
