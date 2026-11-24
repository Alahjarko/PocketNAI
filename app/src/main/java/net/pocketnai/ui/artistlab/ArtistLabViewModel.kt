package net.pocketnai.ui.artistlab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import net.pocketnai.data.artistlab.ArtistLabStore
import net.pocketnai.data.local.*
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.data.repo.FavoriteImageRepository
import net.pocketnai.data.settings.GenerationDraftCodec
import net.pocketnai.domain.artistlab.*
import net.pocketnai.domain.billing.GenerationCostEstimate
import net.pocketnai.domain.model.*
import net.pocketnai.domain.prompt.ArtistPromptSplitter
import net.pocketnai.ui.generate.GenerateViewModel
import java.util.UUID
import kotlin.random.Random

data class LabCard(val draw: ArtistLabDrawEntity, val mix: ArtistMix, val image: GalleryItem?, val favorite: Boolean)
data class LabApproval(val config: ArtistLabConfig, val params: GenerationParams, val quote: GenerationCostEstimate,
                       val account: String, val remaining: Int, val runId: String? = null,
                       val artistPool: List<String> = emptyList())

fun GenerationCostEstimate.labDescription(count: Int): String = when (this) {
    is GenerationCostEstimate.Free -> "当前单张报价免费 · $count 次独立请求"
    is GenerationCostEstimate.UsesV5Allowance -> "将使用 V5 额度 · $count 次独立请求，额度耗尽后暂停"
    is GenerationCostEstimate.EstimatedAnlas -> "单张预计 $batchTotal Anlas · 共预计 ${batchTotal * count} Anlas"
    is GenerationCostEstimate.Unknown -> "费用待确认 · $count 次请求，以服务端实际计费为准"
}

private fun GenerationCostEstimate.approvalKey(): String = when (this) {
    is GenerationCostEstimate.Free -> "free:$policyVersion"
    is GenerationCostEstimate.UsesV5Allowance -> "allowance:$policyVersion"
    is GenerationCostEstimate.EstimatedAnlas -> "paid:$batchTotal:$policyVersion"
    is GenerationCostEstimate.Unknown -> "unknown:$reason:$policyVersion"
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ArtistLabViewModel(
    private val store: ArtistLabStore,
    private val repository: GenerationRepository,
    favorites: FavoriteImageRepository,
    private val generate: GenerateViewModel,
    private val enabled: StateFlow<Boolean>,
    private val background: ArtistLabBackgroundExecution,
) : ViewModel() {
    val runs = store.dao.observeRuns().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val mixFavorites = store.dao.observeMixFavorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // 与结果卡片的解码缓存分开，避免不同后台 Flow 同时操作同一 LinkedHashMap。
    private val favoriteMixCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 10_000
    }
    private val mixImageLinks = store.dao.observeMixImageLinks().map { links ->
        links.mapNotNull { link ->
            runCatching {
                val prompt = favoriteMixCache.getOrPut(link.mixJson) {
                    store.json.decodeFromString<ArtistMix>(link.mixJson).prompt
                }
                prompt to link.imageId
            }.getOrNull()
        }.groupBy({ it.first }, { it.second })
    }.flowOn(Dispatchers.Default)
    val favoriteMixGallery = combine(store.dao.observeMixFavorites(), mixImageLinks,
        repository.observeGallery(), favorites.observeFavoriteIds(), ArtistMixFavoriteGallery::build)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val selected = MutableStateFlow<String?>(null)
    val selectedRun = selected.asStateFlow()
    private val _approval = MutableStateFlow<LabApproval?>(null)
    val approval = _approval.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _pause = MutableStateFlow(false)
    val pausing = _pause.asStateFlow()
    private val _progress = MutableStateFlow(ArtistLabProgress(0, 0))
    val progress = _progress.asStateFlow()
    private val _message = MutableStateFlow("")
    val message = _message.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _catalogCount = MutableStateFlow(0)
    val catalogCount = _catalogCount.asStateFlow()
    private val _catalogEntries = MutableStateFlow<List<ArtistCatalogEntry>>(emptyList())
    val catalogEntries = _catalogEntries.asStateFlow()
    private val _excludedArtists = MutableStateFlow(store.excludedArtists())
    val excludedArtists = _excludedArtists.asStateFlow()
    private val _catalogImporting = MutableStateFlow(false)
    val catalogImporting = _catalogImporting.asStateFlow()
    private val _catalogMessage = MutableStateFlow("")
    val catalogMessage = _catalogMessage.asStateFlow()
    private val _form = MutableStateFlow(store.loadForm())
    val form = _form.asStateFlow()
    fun updateForm(form: ArtistLabForm) { _form.value = form }
    fun clearMessage() { _message.value = "" }
    fun showMessage(text: String) { _message.value = text }

    private fun ensureForm(): ArtistLabForm {
        _form.value?.let { return it }
        val params = generate.state.value.params
        val cleanPrompt = ArtistPromptSplitter.split(params.prompt, extractOnlyCompleted = false).mainPrompt
        val seedStr = if (params.baseSeed > 0) params.baseSeed.toString()
            else Random.nextLong(0, GenerationParams.MAX_SEED + 1).toString()
        return ArtistLabForm(
            prompt = cleanPrompt,
            negative = params.negativePrompt,
            seed = seedStr,
            count = "100",
            artists = 3,
            minTicks = 10,
            maxTicks = 30,
        )
    }

    fun pinArtistToForm(tag: String) {
        val normalized = tag.trim()
        if (!normalized.startsWith("artist: ")) return
        val current = ensureForm()
        val display = normalized.removePrefix("artist: ")
        if (normalized in current.lockedArtists) {
            _message.value = "已在锁定基底画师中：$display"
            return
        }
        if (current.lockedArtists.size >= ArtistLabConfig.MAX_LOCKED_ARTISTS) {
            _message.value = "最多锁定 ${ArtistLabConfig.MAX_LOCKED_ARTISTS} 位基底画师，请先在新建实验配置中移除已有锁定"
            return
        }
        val updatedLocked = (current.lockedArtists + normalized).distinct().take(ArtistLabConfig.MAX_LOCKED_ARTISTS)
        val updated = current.copy(
            lockedArtists = updatedLocked,
            artists = maxOf(current.artists, updatedLocked.size),
        )
        _form.value = updated
        store.saveForm(updated)
        _message.value = "已设为锁定基底画师（${updatedLocked.size}/${ArtistLabConfig.MAX_LOCKED_ARTISTS}）：$display"
    }

    fun unpinArtistFromForm(tag: String) {
        val current = _form.value ?: return
        val normalized = tag.trim()
        if (normalized !in current.lockedArtists) return
        val updated = current.copy(lockedArtists = current.lockedArtists - normalized)
        _form.value = updated
        store.saveForm(updated)
        _message.value = "已取消锁定基底画师：${normalized.removePrefix("artist: ")}"
    }
    private var pool: List<String> = emptyList()
    private var allArtists: List<String> = emptyList()
    private var hash: String = ""
    private var catalogRevision = 0
    private val mixCache = object : LinkedHashMap<String, ArtistMix>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtistMix>?) = size > 10_000
    }
    val cards = selected.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else store.dao.observeDraws(id) }
        .combine(repository.observeGallery()) { draws, images -> draws to images.associateBy { it.imageId } }
        .combine(favorites.observeFavoriteIds()) { (draws, images), ids ->
            draws.map { draw -> LabCard(draw, mixCache.getOrPut(draw.id) { store.json.decodeFromString(draw.mixJson) },
                images[draw.imageId], draw.imageId in ids) }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { _form.filterNotNull().debounce(350).collect { store.saveForm(it) } }
        viewModelScope.launch {
            try {
                store.recover()
                val catalog = store.catalog(); allArtists = catalog.first; hash = catalog.second
                val entries = store.catalogEntries()
                check(entries.map { it.tag } == allArtists)
                _catalogEntries.value = entries
                pool = allArtists.filterNot { it in _excludedArtists.value }
                _catalogCount.value = pool.size
                selected.value = store.dao.observeRuns().first().firstOrNull()?.id
                _ready.value = true
            } catch (_: Exception) { _message.value = "画师词库或实验记录无法加载，请重新打开实验室" }
        }
        viewModelScope.launch { enabled.collect { if (!it) pause() } }
    }

    fun select(id: String) { if (!_busy.value) { selected.value = id; _message.value = "" } }
    fun pause() { if (_busy.value) {
        _pause.value = true
        _progress.update { it.copy(pausing = true) }
        _message.value = "完成当前张后暂停，不会中断已发送的请求"
    } }
    fun dismissApproval() { _approval.value = null }

    fun setArtistEnabled(tag: String, enabled: Boolean) {
        if (_catalogImporting.value || tag !in allArtists) return
        val excluded = if (enabled) _excludedArtists.value - tag else _excludedArtists.value + tag
        store.saveExcludedArtists(excluded)
        catalogRevision++
        _excludedArtists.value = excluded
        pool = allArtists.filterNot { it in excluded }
        _catalogCount.value = pool.size
        if (!enabled) {
            val current = _form.value
            if (current != null && tag in current.lockedArtists) {
                val updated = current.copy(lockedArtists = current.lockedArtists - tag)
                _form.value = updated
                store.saveForm(updated)
            }
        }
        // 词库修改只作用于下一次新建实验；当前已确认/已规划的批次不重抽。
        if (!_busy.value) _approval.value = null
    }

    fun importCatalog(selection: ArtistCatalogSelection, applySelection: Boolean = selection.fromJsonConfig) {
        if (!_ready.value || _busy.value || _catalogImporting.value) return
        _catalogImporting.value = true
        catalogRevision++
        _catalogMessage.value = ""
        _approval.value = null
        viewModelScope.launch {
            try {
                val previousCount = allArtists.size
                val previousExcluded = _excludedArtists.value
                val merged = store.importCatalog(selection, applySelection = applySelection)
                allArtists = merged.artists.map { it.tag }
                hash = net.pocketnai.core.Hashing.sha256(allArtists.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                _catalogEntries.value = merged.artists
                val nextExcluded = merged.excluded.toSet()
                _excludedArtists.value = nextExcluded
                pool = allArtists.filterNot { it in nextExcluded }
                _catalogCount.value = pool.size
                if (applySelection) {
                    val currentForm = _form.value
                    val hasLockedSync = selection.lockedArtists.isNotEmpty()
                    val hasTieredSync = selection.tieredWeights != null
                    if (currentForm != null) {
                        val cleanedLocked = if (hasLockedSync) merged.lockedArtists else currentForm.lockedArtists.filterNot { it in nextExcluded }
                        val updatedForm = currentForm.copy(
                            lockedArtists = cleanedLocked,
                            tieredWeights = selection.tieredWeights ?: currentForm.tieredWeights,
                        )
                        if (updatedForm != currentForm) {
                            _form.value = updatedForm
                            store.saveForm(updatedForm)
                        }
                    } else if (hasLockedSync || hasTieredSync) {
                        val seeded = ArtistLabForm(
                            prompt = "",
                            negative = "",
                            seed = "",
                            count = "100",
                            artists = 3,
                            minTicks = 10,
                            maxTicks = 30,
                            lockedArtists = merged.lockedArtists,
                            tieredWeights = selection.tieredWeights ?: false,
                        )
                        _form.value = seeded
                        store.saveForm(seeded)
                    }
                }
                val addedCount = allArtists.size - previousCount
                val newlyExcluded = (nextExcluded - previousExcluded).size
                val reenabled = (previousExcluded - nextExcluded).size
                _catalogMessage.value = if (applySelection) {
                    buildList {
                        if (addedCount > 0) add("新增 $addedCount 位")
                        if (newlyExcluded > 0) add("已停用 $newlyExcluded 位")
                        if (reenabled > 0) add("已重新启用 $reenabled 位")
                        if (selection.lockedArtists.isNotEmpty()) add("同步 ${merged.lockedArtists.size} 位基底画师")
                    }.ifEmpty { listOf("当前配置已与文件一致") }.joinToString(" · ")
                } else {
                    "已加入 $addedCount 位画师，已有画师的启用状态已保留"
                }
            } catch (e: IllegalArgumentException) { _catalogMessage.value = e.message ?: "画师文件格式不正确" }
            catch (_: Exception) { _catalogMessage.value = "画师库保存未完成，请重试" }
            finally { _catalogImporting.value = false }
        }
    }

    fun prepare(
        basePrompt: String,
        negative: String,
        seed: String,
        count: String,
        artistCount: Int,
        minTicks: Int,
        maxTicks: Int,
        lockedArtists: List<String> = emptyList(),
        tieredWeights: Boolean = false,
    ) {
        if (_busy.value || _catalogImporting.value || !_ready.value || !enabled.value) return
        viewModelScope.launch {
            try {
                val revision = catalogRevision
                val fixedSeed = seed.toLongOrNull()?.takeIf { it in 0..GenerationParams.MAX_SEED }
                    ?: throw IllegalArgumentException("Seed 请填写 0～4294967295 的整数")
                val drawCount = count.toIntOrNull()?.takeIf { it in 1..10_000 }
                    ?: throw IllegalArgumentException("抽卡次数请填写 1～10000")
                require(basePrompt.isNotBlank()) { "请填写固定的基础提示词" }
                val effectiveLocked = lockedArtists
                    .map { it.trim() }
                    .filter { it.startsWith("artist: ") }
                    .distinct()
                    .take(minOf(ArtistLabConfig.MAX_LOCKED_ARTISTS, artistCount))
                val remainingPool = if (effectiveLocked.isEmpty()) pool else pool.filterNot { it in effectiveLocked.toSet() }
                val remainingSlots = artistCount - effectiveLocked.size
                require(remainingPool.size >= remainingSlots) {
                    "当前可用 ${remainingPool.size} 位画师，请减少每张画师数量或重新启用画师"
                }
                require(!Regex("artist\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(negative)) { "负面提示词中也请移除 artist:，保持画师对比一致" }
                val base = repository.freezePromptTemplates(generate.state.value.params.copy(prompt = basePrompt.trim(), negativePrompt = negative.trim(),
                    baseSeed = fixedSeed, seedMode = SeedMode.FIXED, sampleCount = 1, characters = emptyList(), useCharacterCoordinates = false))
                ArtistLabPlanner.params(base, ArtistMix(emptyList()))
                require(ModelCatalog.profileOf(base.model).normalize(base) == base) { "首页参数需要调整，请检查尺寸、采样器与步数" }
                val config = ArtistLabConfig(
                    paramsJson = GenerationDraftCodec.encode(GenerationDraft(base, base.prompt, base.negativePrompt)),
                    artistCount = artistCount,
                    minTicks = minTicks,
                    maxTicks = maxTicks,
                    drawCount = drawCount,
                    catalogHash = hash,
                    templatesResolved = true,
                    lockedArtists = effectiveLocked,
                    tieredWeights = tieredWeights,
                )
                val account = generate.artistLabAccount() ?: throw IllegalArgumentException("请先在设置中连接 NovelAI 账号")
                val quote = generate.quoteForArtistLab(base)
                if (revision != catalogRevision || _catalogImporting.value) return@launch
                _approval.value = LabApproval(config, base, quote, account, drawCount, artistPool = pool.toList())
                _message.value = ""
            } catch (e: IllegalArgumentException) { _message.value = e.message ?: "请检查参数" }
        }
    }

    fun prepareResume() {
        val id = selected.value ?: return
        if (_busy.value || !_ready.value || !enabled.value) return
        viewModelScope.launch {
            try {
                val run = store.dao.run(id) ?: return@launch
                val config = store.json.decodeFromString<ArtistLabConfig>(run.configJson)
                val draft = GenerationDraftCodec.decode(config.paramsJson) ?: error("参数无法恢复")
                // Codec 会降级未知模型；抽卡复现不能默默接受这种变化。
                check(GenerationDraftCodec.matchesSnapshot(config.paramsJson))
                val count = store.dao.draws(id).count { it.status == "PLANNED" }
                if (count == 0) { _message.value = "没有待执行的抽卡；失败或待确认的请求不会重试"; return@launch }
                val account = generate.artistLabAccount() ?: error("未连接")
                _approval.value = LabApproval(config, draft.params, generate.quoteForArtistLab(draft.params), account, count, id)
            } catch (_: Exception) { _message.value = "无法继续：请检查账号或使用新参数创建批次" }
        }
    }

    fun confirm() {
        val approved = _approval.value ?: return
        if (_busy.value || _catalogImporting.value || !enabled.value) return
        _approval.value = null
        if (generate.artistLabAccount() != approved.account ||
            generate.quoteForArtistLab(approved.params).approvalKey() != approved.quote.approvalKey()) {
            _message.value = "账号或费用已改变，请重新确认"; return
        }
        if (!generate.reserveArtistLab()) { _message.value = "另一个图片任务正在执行，请等待完成"; return }
        _busy.value = true; _pause.value = false
        _progress.value = ArtistLabProgress(0, approved.remaining)
        viewModelScope.launch {
            var runId: String? = approved.runId
            try {
                background.start(progress, ::pause)
                // 等服务就绪时用户仍可暂停或关闭实验功能；尚未发送任何请求。
                if (_pause.value || !enabled.value) return@launch
                if (runId == null) {
                    runId = UUID.randomUUID().toString()
                    val id = runId
                    val mixes = withContext(Dispatchers.Default) { ArtistLabPlanner.plan(approved.artistPool, approved.config, Random.Default) }
                    val rows = withContext(Dispatchers.Default) { mixes.mapIndexed { index, mix -> ArtistLabDrawEntity("$id:$index", id, index,
                        store.json.encodeToString(mix), "PLANNED") } }
                    store.dao.create(ArtistLabRunEntity(id, System.currentTimeMillis(), store.json.encodeToString(approved.config), "PAUSED", ""), rows)
                }
                val id = runId
                selected.value = id
                store.dao.updateRun(id, "RUNNING", "")
                _message.value = "按顺序生成，每次完成后随机等待 1～2 秒"
                val draws = store.dao.draws(id).filter { it.status == "PLANNED" }
                ArtistLabQueue().run(draws, { _pause.value || !enabled.value }) { draw ->
                    if (generate.artistLabAccount() != approved.account) error("账号改变")
                    if (generate.quoteForArtistLab(approved.params).approvalKey() != approved.quote.approvalKey()) error("费用改变")
                    val mix = store.json.decodeFromString<ArtistMix>(draw.mixJson)
                    var current = draw.copy(status = "RUNNING")
                    store.dao.updateDraw(current)
                    // 已发送的一张完成并落库后才允许取消协程；下一张永远不自动重试。
                    withContext(NonCancellable) {
                        try {
                            val image = generate.generateForArtistLab(ArtistLabPlanner.params(approved.params, mix), approved.account,
                                templatesResolved = approved.config.templatesResolved) { generationId ->
                                if (generate.artistLabAccount() != approved.account ||
                                    generate.quoteForArtistLab(approved.params).approvalKey() != approved.quote.approvalKey()) throw ArtistLabPreflightChanged()
                                current = current.copy(generationId = generationId); store.dao.updateDraw(current)
                            }
                            store.dao.updateDraw(current.copy(status = "SUCCEEDED", imageId = image.imageId))
                            _progress.update { it.copy(completed = it.completed + 1) }
                        } catch (e: Exception) {
                            val known = e as? ArtistLabRequestFailure
                            if (e is ArtistLabPreflightChanged) store.dao.updateDraw(draw.copy(message = "发送前参数/费用已改变，本张尚未发送"))
                            else store.dao.updateDraw(current.copy(status = if (known?.uncertain == false) "FAILED" else "UNCERTAIN",
                                message = known?.userMessage ?: "本张未完成，可能已计费；请核对图库和余额，不会重试"))
                            throw IllegalStateException("请求未完成")
                        }
                    }
                }
                val remaining = store.dao.draws(id).count { it.status == "PLANNED" }
                val msg = if (remaining == 0) "本批抽卡已完成" else "已暂停，剩余 $remaining 张；继续前需要重新确认"
                store.dao.updateRun(id, if (remaining == 0) "COMPLETED" else "PAUSED", msg)
                _message.value = msg
            } catch (e: Exception) {
                withContext(NonCancellable) { runId?.let { store.dao.updateRun(it, "PAUSED", "已暂停：账号、费用或请求状态改变，请核对后继续") } }
                _message.value = if (e is ArtistLabBackgroundUnavailable) "后台抽卡未能启动，请回到应用后重新确认"
                    else "已暂停：账号、费用或请求状态改变，请核对后继续。未重试任何请求。"
            } finally {
                generate.releaseArtistLab(); _busy.value = false; _pause.value = false
                background.stop()
            }
        }
    }

    fun favorite(card: LabCard) { viewModelScope.launch { runCatching { store.favorite(card.draw, card.mix.prompt, !card.favorite) }
        .onFailure { _message.value = "收藏未成功，请稍后再试" } } }
    fun removeMix(prompt: String) { viewModelScope.launch { store.dao.removeMix(prompt) } }
    fun removeMix(mix: FavoriteArtistMix) { viewModelScope.launch { store.dao.removeMixes(mix.keys) } }
    fun cleanup() {
        val id = selected.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val ids = store.dao.draws(id).mapNotNull { it.imageId }
                val deleted = repository.cleanupLabImages(ids)
                _message.value = "已清理本批 $deleted 张未收藏图片，收藏图片与画师串已保留"
            } catch (_: Exception) { _message.value = "清理未完成，请稍后再试" }
            finally { _busy.value = false }
        }
    }
}
