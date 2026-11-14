package net.pocketnai.data.mcp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import net.pocketnai.di.AppContainer
import net.pocketnai.domain.chat.ChatFailure
import net.pocketnai.domain.mcp.McpNovelAiParams
import net.pocketnai.domain.model.*
import net.pocketnai.domain.openai.*
import net.pocketnai.domain.task.BackgroundTasks
import net.pocketnai.domain.safety.SfwPolicy
import net.pocketnai.data.repo.GenerationEvent
import java.io.ByteArrayOutputStream

class PocketMcpBackend(private val container: AppContainer) : McpImageBackend {
    private val previews = Semaphore(1)
    private data class Plan(
        override val provider: String, override val prompt: String, override val negative: String,
        override val description: String, override val quote: String,
        override val characterDetails: String,
        val nai: GenerationParams? = null, val openai: OpenAiImageParams? = null, val account: String,
    ) : McpImagePlan
    override suspend fun capabilities() = buildJsonObject {
        put("providers", buildJsonArray {
            add(buildJsonObject {
                put("id", "novelai"); put("models", JsonArray(ImageModel.entries.map { JsonPrimitive(it.apiModelId) }))
                put("characters", buildJsonObject {
                    put("max_by_model", buildJsonObject { ImageModel.entries.forEach { put(it.apiModelId, CharacterPrompt.limitFor(it)) } })
                    put("fields", "characters: [{prompt, negative_prompt?, x?, y?}], use_coords: boolean")
                    put("layout", "x/y are normalized canvas centers in 0..1. Explicit x/y enable coordinates unless use_coords=false; omitted axes default to 0.5. V4.5 uses a 5x5 grid; V5 preserves free coordinates. Array order is preserved.")
                    put("weights", "Put NovelAI emphasis syntax inside each character prompt, e.g. 1.2::blue hair::. No separate weight field. A single | in the base prompt is not a structured character array.")
                    put("negative_prompts", "V5 Medium uses fixed negatives. Switch to High in PocketNAI before requesting per-character negatives; unsupported negatives are rejected, never silently dropped.")
                    put("defaults", "Omitted characters means no characters; never inherits the current draft's roles or references.")
                })
            })
            add(buildJsonObject { put("id", "openai"); put("models", JsonArray(OpenAiImageParams.MODELS.map(::JsonPrimitive)))
                put("sizes", JsonArray(OpenAiImageParams.SIZE_PRESETS.filter { it != "auto" }.map(::JsonPrimitive)))
                put("qualities", JsonArray(OpenAiImageParams.QUALITIES.map(::JsonPrimitive))) })
        })
        put("mode", "single text-to-image; current draft references are never attached")
        put("workflow", "submit_image_task once -> get_image_task -> get_image_result; confirmation in PocketNAI unless explicitly allowed for this connection")
        put("result", "MCP image plus image_url and markdown for a large picture in the final reply. Copy the returned markdown exactly. Preview up to 1536px; full original remains in PocketNAI gallery.")
    }
    private fun secrets() = listOfNotNull(container.credentialStore.load()?.token, container.openAiImageSettings.apiKey()).filter { it.isNotBlank() }
    private fun rejectSecrets(text: String) { if (secrets().any { text.contains(it) }) throw McpToolFailure("生成内容不能包含账户凭据") }
    override suspend fun prepare(arguments: JsonObject): McpImagePlan = withContext(Dispatchers.Main.immediate) {
        fun string(name: String): String? = arguments[name]?.let {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: throw McpToolFailure("参数类型不正确")
        }
        rejectSecrets(arguments.toString())
        val provider = string("provider")
        val prompt = string("prompt")?.trim()?.takeIf { it.isNotBlank() } ?: throw McpToolFailure("图片描述不能为空")
        if (SfwPolicy.blocks(container.settingsStore.sfwMode.value, prompt)) throw McpToolFailure(SfwPolicy.BLOCK_MESSAGE)
        if (provider == "novelai") {
            if (arguments.keys.any { it in setOf("size", "quality") }) throw McpToolFailure("NovelAI 请使用 width、height，质量沿用当前生图设置")
            val account = container.generateViewModel.artistLabAccount() ?: throw McpToolFailure("请先连接 NovelAI 账户")
            val base = container.generateViewModel.state.value.params
            val parsed = try { McpNovelAiParams.parse(arguments, base) }
                catch (e: ChatFailure) { throw McpToolFailure(e.message ?: "NovelAI 参数不合法") }
            val params = ModelCatalog.profileOf(parsed.model).normalize(net.pocketnai.domain.prompt.PromptTemplates.resolve(
                parsed, container.promptFavoriteRepository.snapshot(), kotlin.random.Random.Default))
            if (params.isMediumEffort && params.characters.any { it.negativePrompt.isNotBlank() })
                throw McpToolFailure("V5 Medium 不支持角色排除词，请在 PocketNAI 生图面板切换 High 后提交")
            rejectSecrets(naiTexts(params))
            if (SfwPolicy.blocks(container.settingsStore.sfwMode.value, params)) throw McpToolFailure(SfwPolicy.BLOCK_MESSAGE)
            val quote = container.generateViewModel.quoteForChat(params).label
            Plan(provider, params.prompt, params.negativePrompt,
                "${params.model.displayName} · ${params.size.width} × ${params.size.height} · ${params.steps} 步 · Guidance ${params.guidance} · Seed ${if (params.seedMode == SeedMode.FIXED) params.baseSeed.toString() else "随机"}" +
                    if (params.characters.isEmpty()) "" else " · ${params.characters.size} 个独立角色 · ${if (params.useCharacterCoordinates) "手动站位" else "AI 布局"}",
                quote, McpNovelAiParams.characterDetails(params), nai = params, account = account)
        } else if (provider == "openai") {
            if (arguments.keys.any { it in setOf("negative_prompt", "width", "height", "seed", "characters", "use_coords") })
                throw McpToolFailure("OpenAI 请使用 size 和 quality；独立角色、排除词和 Seed 仅适用于 NovelAI")
            val key = container.openAiImageSettings.apiKey() ?: throw McpToolFailure("请先配置 OpenAI 生图密钥")
            val base = container.openAiImageViewModel.state.value.draft.params
            val params = base.copy(prompt = prompt, model = string("model") ?: base.model,
                size = string("size") ?: base.size.takeIf { it != "auto" } ?: "1024x1024",
                quality = string("quality") ?: base.quality, count = 1, outputFormat = "png")
            params.validate()?.let { throw McpToolFailure(it) }
            Plan(provider, prompt, "", "${params.modelLabel} · ${params.size} · ${params.quality} · PNG · 1 张",
                openAiQuote(), "", openai = params, account = McpJobStore.digest(key))
        } else throw McpToolFailure("请选择 novelai 或 openai")
    }
    private fun openAiQuote() = "OpenAI 按实际用量计费（USD），生成前金额无法确定"
    private fun naiTexts(params: GenerationParams) = (listOf(params.prompt, params.negativePrompt) +
        params.characters.flatMap { listOf(it.prompt, it.negativePrompt) }).joinToString("\n")
    override suspend fun quote(plan: McpImagePlan): String = withContext(Dispatchers.Main.immediate) {
        val p = plan as Plan
        rejectSecrets(p.nai?.let(::naiTexts) ?: p.prompt)
        if (p.nai?.let { SfwPolicy.blocks(container.settingsStore.sfwMode.value, it) }
                ?: SfwPolicy.blocks(container.settingsStore.sfwMode.value, p.prompt)) throw McpToolFailure(SfwPolicy.BLOCK_MESSAGE)
        if (p.nai != null) {
            if (container.generateViewModel.artistLabAccount() != p.account) throw McpToolFailure("账户已改变，请重新提交新任务")
            container.generateViewModel.quoteForChat(p.nai).label
        } else {
            val key = container.openAiImageSettings.apiKey() ?: throw McpToolFailure("生图密钥已移除")
            if (McpJobStore.digest(key) != p.account) throw McpToolFailure("生图密钥已改变，请重新提交新任务")
            openAiQuote()
        }
    }
    override suspend fun generate(plan: McpImagePlan, protection: BackgroundTasks.Task,
        beforeSend: suspend () -> Unit, onImage: suspend (String) -> Unit) = withContext(Dispatchers.Main.immediate) {
        val p = plan as Plan
        if (p.nai != null) {
            container.generateViewModel.generateForMcp(p.nai, p.account, protection, beforeSend) { onImage(it.imageId) }
        } else {
            if (!container.generateViewModel.reserveExternalGeneration()) throw McpToolFailure("另一项图片任务正在执行，请等待完成")
            try {
                container.awaitStartup()
                beforeSend()
                container.openAiImageRepository.generate(OpenAiImageDraft(p.openai!!), beforeSend = {
                    protection.ensureRunning()
                    if (McpJobStore.digest(container.openAiImageSettings.apiKey().orEmpty()) != p.account) throw McpToolFailure("生图密钥已改变")
                }).collect { event -> when (event) {
                    is GenerationEvent.Final -> onImage(event.image.id)
                    is GenerationEvent.FatalError, is GenerationEvent.ItemError -> throw McpToolFailure("生成未完成，请核对画廊；没有重试")
                    else -> Unit
                } }
            } finally { container.generateViewModel.releaseExternalGeneration() }
        }
        Unit
    }
    override suspend fun image(imageId: String): McpImageData = previews.withPermit { withContext(Dispatchers.IO) {
        val detail = container.generationRepository.loadDetail(imageId) ?: throw McpToolFailure("图片已从画廊移除")
        if (container.settingsStore.sfwMode.value && SfwPolicy.isSensitive(detail.generation)) throw McpToolFailure("当前 SFW 模式下请在 PocketNAI 详情中查看此图")
        val file = container.fileStore.resolve(detail.image.privateFilePath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw McpToolFailure("图片无法读取")
        val options = BitmapFactory.Options().apply { inSampleSize = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 2048) inSampleSize *= 2 }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: throw McpToolFailure("图片无法读取")
        val factor = (1536f / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
        val preview = if (factor < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * factor).toInt().coerceAtLeast(1),
            (decoded.height * factor).toInt().coerceAtLeast(1), true) else decoded
        try {
            val bytes = ByteArrayOutputStream().also { preview.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            if (bytes.size > 4 * 1024 * 1024) throw McpToolFailure("图片预览过大，请在 PocketNAI 画廊查看")
            McpImageData(bytes, "image/jpeg", preview.width, preview.height)
        } finally { if (preview !== decoded) preview.recycle(); decoded.recycle() }
    } }
}
