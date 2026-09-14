package net.pocketnai.ui.generate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.favorites.FavoritePickerDialog
import net.pocketnai.ui.favorites.PromptFavoritesViewModel
import net.pocketnai.ui.favorites.SaveFavoriteDialog
import net.pocketnai.domain.model.PromptFavorite
import net.pocketnai.domain.model.PromptFavoriteKind
import net.pocketnai.domain.model.PromptTarget
import net.pocketnai.domain.prompt.PromptComposition
import net.pocketnai.domain.prompt.PromptTagEditing
import net.pocketnai.domain.prompt.PromptTitle
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.data.security.CredentialType
import net.pocketnai.domain.billing.GenerationCostEstimate
import net.pocketnai.ui.billing.BalanceDetailDialog
import net.pocketnai.ui.billing.compactLabel
import net.pocketnai.ui.billing.detailLabel
import net.pocketnai.ui.billing.shortLabel
import net.pocketnai.ui.billing.summaryLabel
import net.pocketnai.ui.common.credentialInvalidMessageRes
import net.pocketnai.R
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.ResolutionTier
import net.pocketnai.domain.model.SeedMode
import net.pocketnai.domain.model.shortDisplayName
import net.pocketnai.domain.prompt.EmphasisSyntax
import net.pocketnai.ui.common.DropdownSelector
import net.pocketnai.ui.common.LabeledSlider
import net.pocketnai.ui.common.ResolutionSelector
import net.pocketnai.ui.common.SectionHeader
import net.pocketnai.ui.common.messageRes

/**
 * 生成设置悬浮层的内容。
 *
 * 头部（含生成按钮与状态行）固定在可滚动表单之外，因此表单滚到哪个位置，
 * 按钮与当前状态都停在原地。
 *
 * [expanded] 为 false 时不挂载内部滚动，让拖拽手势交给悬浮层本身去展开 —— 否则
 * 手指在表单上往上拖只会滚动内容，永远拉不开悬浮层。
 */
@Composable
fun GenerateSheet(
    viewModel: GenerateViewModel,
    state: GenerateViewModel.UiState,
    expanded: Boolean,
    connected: Boolean,
    credentialType: CredentialType?,
    onRequestConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val profile = state.profile

    // 两个输入框都用 TextFieldValue 跟踪：只有拿到选区，才能判断"收藏选中片段"
    // 还是"收藏整条提示词"。负面提示词也同样处理，否则两个长得一样的框行为不一致。
    var promptField by rememberSyncedField(state.promptTemplate)
    var negativeField by rememberSyncedField(state.negativeTemplate)

    // 标签补全只作用于光标所在的那一个标签。有选区时不参与 ——
    // 此时"当前标签"是哪一个说不清楚，替换目标不明确。
    val suggestionFragment = if (promptField.selection.collapsed) {
        PromptTagEditing
            .tagSpanAt(promptField.text, promptField.selection.end)
            .textIn(promptField.text)
            .trim()
    } else {
        ""
    }

    // 刚填入的建议不重复建议。填入后光标正停在这个标签末尾，片段恰好等于建议本身，
    // 不拦住的话会立刻又弹出同一批。片段变空（例如末尾补了逗号）时这个记录就失效。
    var filledFragment by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(suggestionFragment) {
        if (suggestionFragment.isEmpty()) {
            filledFragment = null
            viewModel.onSuggestionFragmentChange("")
            return@LaunchedEffect
        }
        if (suggestionFragment == filledFragment) return@LaunchedEffect
        viewModel.onSuggestionFragmentChange(suggestionFragment)
    }

    // 只在建议确实对应光标当前所在标签时才显示：请求在途时用户又改了字，
    // 那批建议已经过期，显示出来只会让人点到一个跟自己刚打的字不匹配的词。
    val visibleSuggestions = state.suggestions
        .takeIf { state.suggestionQuery == suggestionFragment }
        .orEmpty()

    val container = LocalAppContainer.current
    val favoritesViewModel: PromptFavoritesViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PromptFavoritesViewModel(container.promptFavoriteRepository) }
        },
    )
    val favoritesState by favoritesViewModel.state.collectAsStateWithLifecycle()

    // 待保存的收藏内容；非空时弹出命名对话框。
    var saveRequest by remember { mutableStateOf<SaveRequest?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var balanceDialogOpen by remember { mutableStateOf(false) }

    // 费用预估由 ViewModel 从"参数 + 余额"派生，这里是纯展示。
    val costEstimate by viewModel.costEstimate.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth()) {
        SheetHeader(
            state = state,
            expanded = expanded,
            connected = connected,
            credentialType = credentialType,
            costEstimate = costEstimate,
            onGenerate = viewModel::generate,
            onRequestConnect = onRequestConnect,
            onOpenBalance = { balanceDialogOpen = true },
        )
        HorizontalDivider()

        Column(
            modifier = Modifier
                .then(if (expanded) Modifier.verticalScroll(scrollState) else Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!connected) {
                NotConnectedCard(onRequestConnect = onRequestConnect)
            }

            // 费用状态的展开态说明（按钮上只有短文案）。
            CostDetailLine(costEstimate = costEstimate)

            // 参考图放在最上面：它是"这次生成用什么模式"的前提，
            // 而模式会决定下面的尺寸与计费预期。
            ReferenceImageSection(
                state = state,
                connected = connected,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(stringResource(R.string.generate_section_prompt))

            OutlinedTextField(
                value = promptField,
                onValueChange = { newValue ->
                    promptField = newValue
                    viewModel.onPromptChange(newValue.text)
                },
                label = { Text(stringResource(R.string.generate_prompt_label)) },
                minLines = 4,
                trailingIcon = {
                    FavoriteSaveButton(
                        field = promptField,
                        target = PromptTarget.POSITIVE,
                        onRequest = { saveRequest = it },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (visibleSuggestions.isNotEmpty()) {
                TagSuggestions(
                    suggestions = visibleSuggestions,
                    onPick = { suggestion ->
                        val span = PromptTagEditing
                            .tagSpanAt(promptField.text, promptField.selection.end)
                        val replacement = PromptTagEditing
                            .applySuggestion(promptField.text, span, suggestion)

                        promptField = TextFieldValue(
                            text = replacement.text,
                            selection = TextRange(replacement.cursor),
                        )
                        viewModel.onPromptChange(replacement.text)
                        filledFragment = suggestion
                    },
                )
            }

            // 软上限只提示，不阻断提交（规划书 3.1）。
            if (state.exceedsPromptSoftLimit) {
                Text(
                    text = "提示词长度 ${state.promptLength} 已超过该模型建议上限 " +
                        "${profile.promptSoftLimitChars}，仍可提交但可能被服务端截断。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        promptField = applyEmphasis(promptField, viewModel, EmphasisSyntax::strengthen)
                    },
                    enabled = !promptField.selection.collapsed,
                ) {
                    Text(stringResource(R.string.generate_emphasis_strong))
                }
                OutlinedButton(
                    onClick = {
                        promptField = applyEmphasis(promptField, viewModel, EmphasisSyntax::weaken)
                    },
                    enabled = !promptField.selection.collapsed,
                ) {
                    Text(stringResource(R.string.generate_emphasis_weak))
                }
                TextButton(onClick = { pickerOpen = true }) {
                    Icon(Icons.Default.Bookmarks, contentDescription = null)
                    Text(
                        text = stringResource(R.string.favorites_button) +
                            " (${favoritesState.promptCount + favoritesState.tagCount})",
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            if (promptField.selection.collapsed) {
                Text(
                    text = "选中一段文字后：可用上面两个按钮包裹 NovelAI 的 {} / [] 语法，" +
                        "或点输入框右上角的书签把它收藏成标签；不选中则收藏整条提示词。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.randomizerCombinations > 1) {
                Text(
                    text = "检测到随机选项模板：本次将展开为 1 / ${state.randomizerCombinations} 种组合，" +
                        "实际使用的提示词会随快照一起保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            OutlinedTextField(
                value = negativeField,
                onValueChange = { newValue ->
                    negativeField = newValue
                    viewModel.onNegativePromptChange(newValue.text)
                },
                label = { Text(stringResource(R.string.generate_negative_label)) },
                minLines = 2,
                trailingIcon = {
                    FavoriteSaveButton(
                        field = negativeField,
                        target = PromptTarget.NEGATIVE,
                        onRequest = { saveRequest = it },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // 规划书 3.4：V4.5 对多语言提示词理解较弱，只做非阻断说明。
            if (!profile.supportsMultilingualPrompt) {
                Text(
                    text = "${profile.displayName} 对非英语提示词的理解弱于 V5，" +
                        "可以继续输入，但英文标签通常更稳定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader(stringResource(R.string.generate_model))

            DropdownSelector(
                label = stringResource(R.string.generate_model),
                selectedText = profile.displayName,
                options = ModelCatalog.models,
                optionLabel = { ModelCatalog.profileOf(it).displayName },
                onSelect = viewModel::onModelSelected,
                modifier = Modifier.fillMaxWidth(),
            )

            val tier = profile.tierOf(state.params.size) ?: ResolutionTier.NORMAL
            ResolutionSelector(
                tier = tier,
                size = state.params.size,
                availableTiers = profile.availableTiers(),
                availableOrientations = profile.availableOrientations(tier),
                onTierChange = viewModel::onResolutionTierChange,
                onOrientationChange = viewModel::onOrientationChange,
                modifier = Modifier.fillMaxWidth(),
            )

            DropdownSelector(
                label = stringResource(R.string.generate_count),
                selectedText = state.params.sampleCount.toString(),
                options = (1..profile.maxSampleCount).toList(),
                optionLabel = { "$it 张" },
                onSelect = viewModel::onSampleCountChange,
                modifier = Modifier.fillMaxWidth(),
            )

            // 质量标签：官方是三档下拉，并把实际追加的文本明确告诉用户。
            DropdownSelector(
                label = stringResource(R.string.generate_quality_tags),
                selectedText = state.params.qualityTags.displayName,
                options = QualityTagsOption.selectable,
                optionLabel = { it.displayName },
                onSelect = viewModel::onQualityTagsChange,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = state.params.qualityTags.appendedText?.let {
                    stringResource(R.string.generate_quality_tags_appended, it)
                } ?: stringResource(R.string.generate_quality_tags_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            DropdownSelector(
                label = "Undesired Content 预设",
                selectedText = profile.undesiredContentPresets
                    .firstOrNull { it.index == state.params.undesiredContentPresetIndex }
                    ?.displayName
                    ?: "Heavy",
                options = profile.undesiredContentPresets,
                optionLabel = { it.displayName },
                onSelect = { viewModel.onUndesiredContentPresetChange(it.index) },
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- 高级参数 ----
            var advancedExpanded by remember { mutableStateOf(false) }
            TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                Icon(
                    imageVector = if (advancedExpanded) {
                        Icons.Default.ExpandLess
                    } else {
                        Icons.Default.ExpandMore
                    },
                    contentDescription = null,
                )
                Text(
                    text = stringResource(R.string.generate_advanced),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            if (advancedExpanded) {
                LabeledSlider(
                    label = stringResource(R.string.generate_steps),
                    value = state.params.steps.toDouble(),
                    onValueChange = { viewModel.onStepsChange(it.toInt()) },
                    valueRange = 1.0..50.0,
                    steps = 48,
                    decimals = 0,
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledSlider(
                    label = stringResource(R.string.generate_guidance),
                    value = state.params.guidance,
                    onValueChange = viewModel::onGuidanceChange,
                    valueRange = profile.guidanceRange.min..profile.guidanceRange.max,
                    decimals = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledSlider(
                    label = stringResource(R.string.generate_cfg_rescale),
                    value = state.params.cfgRescale,
                    onValueChange = viewModel::onCfgRescaleChange,
                    valueRange = profile.cfgRescaleRange.min..profile.cfgRescaleRange.max,
                    decimals = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                DropdownSelector(
                    label = stringResource(R.string.generate_sampler),
                    selectedText = state.params.sampler.displayName,
                    options = profile.availableSamplers(),
                    optionLabel = { it.displayName },
                    onSelect = viewModel::onSamplerSelected,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 只展示当前采样器真正支持的调度，避免构造已知无效请求（规划书 3.1）。
                val schedules: List<NoiseSchedule> =
                    profile.availableSchedulesFor(state.params.sampler).toList()
                DropdownSelector(
                    label = stringResource(R.string.generate_noise_schedule),
                    selectedText = state.params.noiseSchedule.displayName,
                    options = schedules,
                    optionLabel = { it.displayName },
                    onSelect = viewModel::onNoiseScheduleSelected,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.generate_seed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.params.seedMode == SeedMode.RANDOM,
                        onClick = { viewModel.onSeedModeChange(SeedMode.RANDOM) },
                        label = { Text(stringResource(R.string.generate_seed_random)) },
                    )
                    FilterChip(
                        selected = state.params.seedMode == SeedMode.FIXED,
                        onClick = { viewModel.onSeedModeChange(SeedMode.FIXED) },
                        label = { Text(stringResource(R.string.generate_seed_fixed)) },
                    )
                }
                if (state.params.seedMode == SeedMode.FIXED) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = state.params.baseSeed.toString(),
                            onValueChange = viewModel::onSeedChange,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = viewModel::onRandomizeSeed) {
                            Icon(Icons.Default.Casino, contentDescription = "随机生成一个 Seed")
                        }
                    }
                }

                OutlinedButton(
                    onClick = viewModel::restoreModelDefaults,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.generate_restore_model_defaults))
                }
            }

            state.modelSwitchNotice?.let { notice ->
                NoticeCard(text = notice, onDismiss = viewModel::dismissModelSwitchNotice)
            }

            state.error?.let { error ->
                NoticeCard(
                    text = stringResource(error.messageResFor(credentialType)),
                    detail = error.detail,
                    isError = true,
                    // 凭据失效时给一个直达连接页的入口。不自动重登、不自动刷新。
                    actionLabel = if (error.code == ErrorCode.TOKEN_INVALID) {
                        stringResource(R.string.action_go_connect)
                    } else {
                        null
                    },
                    onAction = onRequestConnect,
                    onDismiss = viewModel::dismissError,
                )
            }

            // 生成后核对：只报"观察到的余额变化"，不说成账单（余额规划 §3.2）。
            state.lastObservedChange?.let { change ->
                NoticeCard(
                    text = stringResource(R.string.observed_change_title) + "：" + change.summaryLabel(),
                    onDismiss = viewModel::dismissObservedChange,
                )
            }

            Spacer(Modifier.size(8.dp))
        }
    }

    saveRequest?.let { request ->
        SaveFavoriteDialog(
            kind = request.kind,
            content = request.content,
            initialName = request.defaultName,
            target = request.target,
            onConfirm = { name, category ->
                favoritesViewModel.save(
                    kind = request.kind,
                    content = request.content,
                    name = name,
                    category = category,
                    target = request.target,
                )
                saveRequest = null
                // 存完顺势把收藏夹摊开：既确认存进去了，也顺手能看到刚存的那条。
                pickerOpen = true
            },
            onDismiss = { saveRequest = null },
        )
    }

    if (pickerOpen) {
        FavoritePickerDialog(
            state = favoritesState,
            onQueryChange = favoritesViewModel::onQueryChange,
            onKindChange = favoritesViewModel::onKindChange,
            onAppend = { favorite ->
                applyFavorite(favorite, replace = false, state = state, viewModel = viewModel)
                favoritesViewModel.markUsed(favorite)
            },
            onReplace = { favorite ->
                applyFavorite(favorite, replace = true, state = state, viewModel = viewModel)
                favoritesViewModel.markUsed(favorite)
            },
            onDelete = favoritesViewModel::delete,
            onDismiss = { pickerOpen = false },
            onDismissNotice = favoritesViewModel::dismissNotice,
        )
    }

    if (balanceDialogOpen) {
        BalanceDetailDialog(
            state = state.balanceState,
            onRefresh = viewModel::refreshBalance,
            onDismiss = { balanceDialogOpen = false },
        )
    }

    // 本地预估余额不足时的**非阻断**确认：用户仍可继续（余额规划 §11.3）。
    // 绝不用本地数字禁用生成 —— 额度是否够用以服务端 402 为准。
    if (state.pendingCostConfirmation) {
        val total = (costEstimate as? GenerationCostEstimate.EstimatedAnlas)?.batchTotal ?: 0L
        val balance = state.balanceState.knownBalance?.totalAnlas ?: 0L
        AlertDialog(
            onDismissRequest = viewModel::dismissCostConfirmation,
            title = { Text(stringResource(R.string.cost_insufficient_title)) },
            text = { Text(stringResource(R.string.cost_insufficient_message, total, balance)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmCostAndGenerate) {
                    Text(stringResource(R.string.cost_insufficient_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCostConfirmation) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * 标签建议。点一下就把光标所在的标签替换成建议的词。
 *
 * 用 [FlowRow] 而不是横向滚动：一次最多 5 条，换行能全部看见 ——
 * 横向滚动会把后面的建议藏在屏幕外，用户不知道还有没有别的。
 *
 * 这里没有"关闭"按钮：建议随输入片段自动出现和消失，多一个开关只会多一处状态。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSuggestions(
    suggestions: List<String>,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.generate_suggestions_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { suggestion ->
                AssistChip(
                    onClick = { onPick(suggestion) },
                    label = { Text(suggestion) },
                )
            }
        }
    }
}

/**
 * 悬浮层的头部：标题、参数摘要、状态行，以及右侧的生成按钮。
 *
 * 收起状态下这就是用户看到的全部内容，所以摘要与状态行都是必要的 ——
 * 让用户不用展开也知道现在会用哪个模型、什么尺寸，以及按钮为什么是灰的。
 *
 * 这里刻意**不画拖拽提示**：`BottomSheetScaffold` 已经自带了拖拽横条，
 * 再画一个会白占一行高度，把摘要挤出收起态的可视区域。
 */
@Composable
private fun SheetHeader(
    state: GenerateViewModel.UiState,
    expanded: Boolean,
    connected: Boolean,
    credentialType: CredentialType?,
    costEstimate: GenerationCostEstimate,
    onGenerate: () -> Unit,
    onRequestConnect: () -> Unit,
    onOpenBalance: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.generate_sheet_title),
                style = MaterialTheme.typography.titleSmall,
            )
            // 摘要只放参数：提示词的内容属于编辑区，塞进来只会把这一行挤到截断。
            // 图生图必须在这里体现：否则收起悬浮层后用户看不出这次是在改图，
            // 会以为出图尺寸或风格出了错。
            // 注意 stringResource 只能调在 composable 作用域里，不能塞进 buildString。
            val img2imgMarker = stringResource(R.string.generate_mode_img2img)
            Text(
                text = buildString {
                    append(state.profile.model.shortDisplayName)
                    append(" · ")
                    append(state.params.size.label)
                    append(" · ")
                    append(state.params.qualityTags.displayName)
                    if (state.mode == GenerationMode.IMG2IMG) {
                        append(" · ")
                        append(img2imgMarker)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SheetStatusLine(
                state = state,
                expanded = expanded,
                connected = connected,
                credentialType = credentialType,
            )
            // 余额与费用独立成行，**不并入状态行**：状态行有自己的错误优先级，
            // 余额不该把它顶掉（余额规划 §11.1）。点它打开余额详情。
            BalanceLine(state = state, onClick = onOpenBalance)
        }

        GenerateButton(
            inFlight = state.inFlight,
            enabled = connected && state.canGenerate,
            costLabel = costEstimate.shortLabel(),
            costDetail = costEstimate.detailLabel(),
            onClick = { if (connected) onGenerate() else onRequestConnect() },
        )
    }
}

/**
 * 头部最后一行：余额（点击查看详情）。
 *
 * 只放余额：费用已经在生成按钮上，这里再写一遍既重复又会被截断；
 * 费用的详细说明（批量总价、平均每张、"以 NovelAI 为准"）放在表单顶部的费用行里，
 * 那里有足够宽度写清楚。
 */
@Composable
private fun BalanceLine(
    state: GenerateViewModel.UiState,
    onClick: () -> Unit,
) {
    val balanceLabel = state.balanceState.compactLabel()
    val text = balanceLabel ?: stringResource(R.string.balance_unavailable_short)

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * 表单顶部的费用说明行。
 *
 * 它承担的是费用状态的**展开态说明**：`预计不消耗 Anlas`、`将消耗 V5 免费额度`、
 * `本次预计 68 Anlas，平均约 17/张`、`当前组合的费用以 NovelAI 为准`。
 * 按钮上只放得下短文案，解释放在这里。
 */
@Composable
private fun CostDetailLine(costEstimate: GenerationCostEstimate) {
    Text(
        text = stringResource(R.string.cost_prefix) + costEstimate.detailLabel(),
        style = MaterialTheme.typography.bodySmall,
        color = if (costEstimate is GenerationCostEstimate.Unknown) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
}

/**
 * 头部第三行：只说一件事，并且按重要性排优先级。
 *
 * 这行同时承担了收起态的状态展示，所以顺序是
 * 未连接 → 生成中 → 失败 → 提示词为空 → 拖动提示。
 */
@Composable
private fun SheetStatusLine(
    state: GenerateViewModel.UiState,
    expanded: Boolean,
    connected: Boolean,
    credentialType: CredentialType?,
) {
    val text: String
    val color: Color
    when {
        !connected -> {
            text = stringResource(R.string.generate_no_token)
            color = MaterialTheme.colorScheme.error
        }

        state.inFlight -> {
            text = stringResource(
                R.string.generate_fab_in_progress,
                state.completedImages,
                state.params.sampleCount,
            )
            color = MaterialTheme.colorScheme.primary
        }

        state.error != null -> {
            text = stringResource(state.error.messageResFor(credentialType))
            color = MaterialTheme.colorScheme.error
        }

        state.promptTemplate.isBlank() -> {
            text = stringResource(R.string.generate_sheet_no_prompt)
            color = MaterialTheme.colorScheme.error
        }

        else -> {
            text = stringResource(
                if (expanded) {
                    R.string.generate_sheet_collapse_hint
                } else {
                    R.string.generate_sheet_expand_hint
                },
            )
            color = MaterialTheme.colorScheme.primary
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 把强调语法作用在用户选中的片段上，并保持选中状态，方便连续点击叠加层级。
 * 所有改动都立刻写回编辑框，且只通过一次文本替换完成，用户可以正常撤销（规划书 8.2）。
 */
private fun applyEmphasis(
    field: TextFieldValue,
    viewModel: GenerateViewModel,
    transform: (String) -> String,
): TextFieldValue {
    val selection = field.selection
    if (selection.collapsed) return field
    val selected = field.text.substring(selection.start, selection.end)
    if (selected.isBlank()) return field

    val replaced = transform(selected)
    val newText = field.text.replaceRange(selection.start, selection.end, replaced)
    viewModel.onPromptChange(newText)
    return TextFieldValue(
        text = newText,
        selection = TextRange(selection.start, selection.start + replaced.length),
    )
}

@Composable
private fun NotConnectedCard(onRequestConnect: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.generate_no_token),
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = onRequestConnect) {
                Text(stringResource(R.string.connect_title))
            }
        }
    }
}

@Composable
private fun NoticeCard(
    text: String,
    onDismiss: () -> Unit,
    detail: String? = null,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
            // 服务端返回的具体说明。参数被拒绝时这是最有用的信息，
            // 只显示笼统文案会让人无从下手。
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        }
    }
}

/**
 * 错误文案：凭据失效要按凭据类型给不同引导，其余错误走通用映射。
 *
 * 放在这里而不是 ViewModel：ViewModel 只产出 [net.pocketnai.core.AppError]，
 * 不依赖 R，也不该知道"账号会话"和"PST"在界面上叫什么。
 */
@Composable
private fun AppError.messageResFor(credentialType: CredentialType?): Int =
    if (code == ErrorCode.TOKEN_INVALID) {
        credentialInvalidMessageRes(credentialType)
    } else {
        code.messageRes()
    }

/** 待保存的收藏内容；由输入框右上角的书签按钮构造，交给命名对话框确认。 */
private data class SaveRequest(
    val kind: PromptFavoriteKind,
    val content: String,
    val defaultName: String,
    val target: PromptTarget,
)

/**
 * 让 `TextFieldValue` 跟随外部的字符串状态。
 *
 * 需要 `TextFieldValue` 而不是普通字符串，是因为只有它带选区信息 ——
 * 而"有选中就存成标签、没选中就存整条"这条判断完全依赖选区。
 */
@Composable
private fun rememberSyncedField(text: String): MutableState<TextFieldValue> {
    val state = remember { mutableStateOf(TextFieldValue(text)) }
    LaunchedEffect(text) {
        if (state.value.text != text) {
            state.value = TextFieldValue(text, TextRange(text.length))
        }
    }
    return state
}

/** 输入框右上角的收藏按钮：有选中就存标签，没选中就存整条提示词。 */
@Composable
private fun FavoriteSaveButton(
    field: TextFieldValue,
    target: PromptTarget,
    onRequest: (SaveRequest) -> Unit,
) {
    IconButton(
        onClick = {
            val selection = field.selection
            val selected = if (selection.collapsed) {
                ""
            } else {
                field.text.substring(selection.start, selection.end).trim()
            }
            onRequest(
                if (selected.isNotEmpty()) {
                    SaveRequest(
                        kind = PromptFavoriteKind.TAG,
                        content = selected,
                        defaultName = PromptComposition.defaultName(selected).orEmpty(),
                        target = target,
                    )
                } else {
                    SaveRequest(
                        kind = PromptFavoriteKind.PROMPT,
                        content = field.text,
                        defaultName = PromptTitle.titleFromPrompt(field.text).orEmpty(),
                        target = target,
                    )
                },
            )
        },
    ) {
        Icon(
            imageVector = Icons.Default.BookmarkAdd,
            contentDescription = stringResource(R.string.favorite_button_hint),
        )
    }
}

/**
 * 把收藏填回对应输入框：默认追加到末尾，[replace] 为真时整条替换。
 *
 * 追加是默认动作，因为它是"拼装提示词"时最常用的行为，也不会毁掉用户已经写好的内容。
 */
private fun applyFavorite(
    favorite: PromptFavorite,
    replace: Boolean,
    state: GenerateViewModel.UiState,
    viewModel: GenerateViewModel,
) {
    val current = when (favorite.target) {
        PromptTarget.POSITIVE -> state.promptTemplate
        PromptTarget.NEGATIVE -> state.negativeTemplate
    }
    val next = if (replace) {
        favorite.content
    } else {
        PromptComposition.append(current, favorite.content)
    }
    when (favorite.target) {
        PromptTarget.POSITIVE -> viewModel.onPromptChange(next)
        PromptTarget.NEGATIVE -> viewModel.onNegativePromptChange(next)
    }
}
