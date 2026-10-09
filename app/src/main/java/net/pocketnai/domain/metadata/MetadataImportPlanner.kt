package net.pocketnai.domain.metadata

import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.Sampler

/**
 * 导入时勾了哪些项。
 *
 * 默认值与官方一致：提示词/反向词/设置都勾上，**Seed 与 Clean Imports 不勾**。
 * Seed 不勾是因为同 Seed 同参数会迅速产出几乎一样的图，而那些图是要花 Anlas 的；
 * Clean Imports 不勾是因为它会移除 `{}` / `[]`，即**改变提示词权重**。
 */
data class MetadataImportSelection(
    val prompt: Boolean = true,
    val negativePrompt: Boolean = true,
    val settings: Boolean = true,
    val seed: Boolean = false,
    /** 用 Randomizer 展开后的实际提示词，而不是模板原文。 */
    val useActualPrompt: Boolean = false,
    val cleanImports: Boolean = false,
)

/**
 * 逐项跳过的原因。
 *
 * 做成类型而不是字符串：文案归 strings.xml（与 `ParamViolation` 的处理一致），
 * 测试也就能断言"为什么跳过"而不是去比对一句中文。
 */
sealed interface MetadataImportNote {
    /** 导入了 N 条角色提示词（`v4_prompt.caption.char_captions`）。 */
    data class CharactersImported(val count: Int) : MetadataImportNote

    /** 角色数超过本机上限（[net.pocketnai.domain.model.CharacterPrompt.MAX_COUNT]），超出的部分未导入。 */
    data class CharactersTruncated(val requested: Int, val applied: Int) : MetadataImportNote

    /**
     * 有 N 条角色的坐标超出 0–1 范围，已限制到画布边界。
     *
     * 有效二维坐标原样保留；只有越界值才修正 ——
     * 这是**对原参数的改写**，按项目纪律必须如实说明，不能静默处理。
     */
    data class CharactersPositionSnapped(val count: Int) : MetadataImportNote

    /** 模型不在我们支持的四个之内；保留原始 `Source` 供界面说明。 */
    data class ModelUnsupported(val source: String?) : MetadataImportNote

    /**
     * 元数据尺寸不是内置预设、但合法：按**自定义尺寸**导入（不做任何取整）。
     *
     * 那张图本来就是按这个尺寸生成的：改成"最接近的预设"会复现不出原图，
     * 而它也不需要裁切 —— 复现它就得用完全一样的画布。
     */
    data class SizeImportedAsCustom(val width: Int, val height: Int) : MetadataImportNote

    /** 元数据尺寸连合法都算不上（不是 64 倍数 / 超面积 / 边长越界）：跳过尺寸并说明。 */
    data class SizeNotImportable(val width: Int, val height: Int) : MetadataImportNote

    data class StepsClamped(val requested: Int, val applied: Int) : MetadataImportNote
    data class GuidanceClamped(val requested: Double, val applied: Double) : MetadataImportNote
    data class CfgRescaleClamped(val requested: Double, val applied: Double) : MetadataImportNote

    data class SamplerUnsupported(val raw: String?) : MetadataImportNote
    data class NoiseScheduleUnsupported(val raw: String?) : MetadataImportNote

    data class SeedOutOfRange(val raw: Long) : MetadataImportNote

    /** 提示词末尾带质量标签，已剥掉并改成对应预设（避免再追加一次）。 */
    data class QualityTagsDetected(val option: QualityTagsOption) : MetadataImportNote

    /** 认不出质量标签，因此原样保留提示词并把质量标签设为 None。 */
    data object QualityTagsKeptVerbatim : MetadataImportNote

    /** 图片用过 Vibe Transfer，但原始 Vibe 编码不在图片里。 */
    data object VibeReferencesNotRestorable : MetadataImportNote

    /** 图片用过 Precise Reference，但原始参考图不在图片里。 */
    data object DirectorReferencesNotRestorable : MetadataImportNote

    /** 图片是图生图产物，但原始底图不在图片里。 */
    data object BaseImageNotRestorable : MetadataImportNote

    /** 勾了"实际提示词"但元数据里没有（这张图没有用 Randomizer）。 */
    data object ActualPromptUnavailable : MetadataImportNote

    /** 元数据里没有可导入的内容。 */
    data object NothingToImport : MetadataImportNote

    /** 反向提示词原样导入，但**服务端的 UC 预设无法从元数据恢复**。 */
    data object UndesiredContentPresetNotRestorable : MetadataImportNote
}

/**
 * 导入结果：一组"要写进 [GenerationParams] 的值"，null 表示该项不动。
 *
 * 刻意不给"直接改好的 params"：哪些字段动了要能被测试逐项断言，
 * 而"把整份 params 换掉"会把没读到的字段也一起冲掉（例如用户当前选的 UC 预设）。
 */
data class MetadataImportPlan(
    val prompt: String? = null,
    val negativePrompt: String? = null,
    val model: ImageModel? = null,
    val size: ImageSizePreset? = null,
    val steps: Int? = null,
    val guidance: Double? = null,
    val cfgRescale: Double? = null,
    val sampler: Sampler? = null,
    val noiseSchedule: NoiseSchedule? = null,
    val seed: Long? = null,
    val qualityTags: QualityTagsOption? = null,
    /** 导入的独立角色（正/负向词 + 位置）；null 表示不动现有角色。 */
    val characters: List<CharacterPrompt>? = null,
    val useCharacterCoordinates: Boolean? = null,
    val undesiredContentPresetIndex: Int? = null,
    val notes: List<MetadataImportNote> = emptyList(),
) {
    /** 有没有任何一项真的会改动状态。 */
    val changesAnything: Boolean
        get() = prompt != null || negativePrompt != null || model != null || size != null ||
            steps != null || guidance != null || cfgRescale != null || sampler != null ||
            noiseSchedule != null || seed != null || qualityTags != null || characters != null
}

/**
 * 把元数据 + 当前参数 + 勾选项，算成一份"要改什么"的清单。
 *
 * ## 三条纪律
 * 1. **只导入明确认识的字段**：解析器已经按白名单取值，这里不再从原始 JSON 里捞东西；
 * 2. **逐项降级，不整体失败**：尺寸不认识只跳尺寸，采样器不认识只跳采样器；
 * 3. **不静默替换**：任何跳过或夹取都进 [MetadataImportPlan.notes]，由界面如实说明。
 */
object MetadataImportPlanner {

    fun plan(
        metadata: NovelAiImageMetadata,
        current: GenerationParams,
        selection: MetadataImportSelection,
    ): MetadataImportPlan {
        val notes = mutableListOf<MetadataImportNote>()

        // ---- 模型：先定目标模型，后面所有参数的合法区间都按它算 ----
        val importedModel = metadata.settings.model
        val targetModel = if (selection.settings && importedModel != null) {
            importedModel
        } else {
            current.model
        }
        if (selection.settings && importedModel == null && metadata.settings.unsupportedModelSource != null) {
            notes += MetadataImportNote.ModelUnsupported(metadata.settings.unsupportedModelSource)
        }
        val profile = ModelCatalog.profileOf(targetModel)

        // ---- 提示词 ----
        var prompt: String? = null
        var qualityTags: QualityTagsOption? = null

        if (selection.prompt) {
            val actual = metadata.actualPrompt
            if (selection.useActualPrompt && actual.isNullOrBlank()) {
                notes += MetadataImportNote.ActualPromptUnavailable
            }
            val chosen = if (selection.useActualPrompt && !actual.isNullOrBlank()) {
                actual
            } else {
                metadata.prompt
            }

            if (chosen != null) {
                // 质量标签去重：认出来就剥掉并沿用对应预设，认不出来就原样保留 + 预设设为 None。
                // 这一步必须在 cleanImports 之前，与官方前端的顺序一致。
                val importedCharacters = metadata.characters.map { character -> CharacterPrompt(
                    prompt = if (selection.useActualPrompt) character.actualPrompt ?: character.prompt else character.prompt,
                    negativePrompt = character.negativePrompt.orEmpty(),
                    centerX = character.centerX ?: DEFAULT_CENTER, centerY = character.centerY ?: DEFAULT_CENTER,
                ) }
                val editable = net.pocketnai.domain.prompt.NovelAiTextPrompt.removeAutomatic(
                    chosen, importedCharacters, metadata.useCharacterCoordinates ?: false,
                )
                val deduped = stripKnownQualityTags(editable, model = targetModel, hint = metadata.qualityTagHint)
                val cleaned = if (selection.cleanImports) CleanImports.clean(deduped.text) else deduped.text

                prompt = cleaned
                if (deduped.option != null) {
                    qualityTags = deduped.option
                    notes += MetadataImportNote.QualityTagsDetected(deduped.option)
                } else {
                    qualityTags = QualityTagsOption.NONE
                    notes += MetadataImportNote.QualityTagsKeptVerbatim
                }
            }
        }

        // ---- 反向提示词 ----
        var negativePrompt: String? = null
        var undesiredContentPresetIndex: Int? = null
        if (selection.negativePrompt) {
            val uc = if (selection.useActualPrompt && !metadata.actualNegativePrompt.isNullOrBlank()) {
                metadata.actualNegativePrompt
            } else {
                metadata.negativePrompt
            }
            if (uc != null) {
                val restored = net.pocketnai.domain.prompt.NovelAiPromptPresets.restoreNegative(
                    targetModel, metadata.undesiredContentTagHint, metadata.prompt.orEmpty(), uc,
                )
                negativePrompt = restored.first
                undesiredContentPresetIndex = restored.second
            }
        }

        // ---- 设置 ----
        var size: ImageSizePreset? = null
        var steps: Int? = null
        var guidance: Double? = null
        var cfgRescale: Double? = null
        var sampler: Sampler? = null
        var noiseSchedule: NoiseSchedule? = null

        if (selection.settings) {
            val settings = metadata.settings

            val width = settings.width
            val height = settings.height
            if (width != null && height != null && width > 0 && height > 0) {
                val candidate = ImageSizePreset(width, height)
                // 三种情况，绝不悄悄改成"最接近的"：尺寸一变，即使 Seed 相同也复现不出原图，
                // 而用户不会知道我们换过。
                when {
                    profile.sizeOptions.any { it.size == candidate } -> size = candidate
                    profile.sizeConstraints.isValid(width, height) -> {
                        size = candidate
                        notes += MetadataImportNote.SizeImportedAsCustom(width, height)
                    }

                    else -> notes += MetadataImportNote.SizeNotImportable(width, height)
                }
            }

            steps = settings.steps?.let { requested ->
                val applied = profile.stepsRange.clamp(requested.toDouble()).toInt()
                if (applied != requested) {
                    notes += MetadataImportNote.StepsClamped(requested, applied)
                }
                applied
            }

            guidance = settings.guidance?.let { requested ->
                val applied = profile.guidanceRange.clamp(requested)
                if (applied != requested) {
                    notes += MetadataImportNote.GuidanceClamped(requested, applied)
                }
                applied
            }

            cfgRescale = settings.cfgRescale?.let { requested ->
                val applied = profile.cfgRescaleRange.clamp(requested)
                if (applied != requested) {
                    notes += MetadataImportNote.CfgRescaleClamped(requested, applied)
                }
                applied
            }

            sampler = settings.sampler?.takeIf { it in profile.samplerSchedules }
            if (sampler == null) {
                notes += MetadataImportNote.SamplerUnsupported(
                    settings.unsupportedSampler ?: settings.sampler?.apiValue,
                )
            } else {
                // 采样器换了以后调度组合可能不成立，按目标档案取该采样器的默认调度。
                val schedules = profile.availableSchedulesFor(sampler)
                noiseSchedule = settings.noiseSchedule?.takeIf { it in schedules }
                if (noiseSchedule == null) {
                    if (settings.noiseSchedule != null) {
                        notes += MetadataImportNote.NoiseScheduleUnsupported(
                            settings.unsupportedNoiseSchedule ?: settings.noiseSchedule.apiValue,
                        )
                    }
                    // 没读到调度时不必提示：沿用它自己的默认组合即可。
                }
            }
        }

        // ---- Seed ----
        var seed: Long? = null
        if (selection.seed) {
            val raw = metadata.settings.seed
            when {
                raw == null -> Unit
                raw < 0L || raw > GenerationParams.MAX_SEED ->
                    notes += MetadataImportNote.SeedOutOfRange(raw)

                else -> seed = raw
            }
        }

        // ---- 独立角色：跟着"提示词"那一项走（角色词也是提示词，不该拆成两个勾选项） ----
        var characters: List<CharacterPrompt>? = null
        if (selection.prompt && prompt != null) {
            var adjustedCount = 0
            val eligible = metadata.characters.filter { it.prompt.isNotEmpty() }
            val applied = eligible.take(CharacterPrompt.limitFor(targetModel)).map { character ->
                val rawX = character.centerX ?: DEFAULT_CENTER
                val rawY = character.centerY ?: DEFAULT_CENTER
                val x = rawX.coerceIn(0.0, 1.0)
                val y = rawY.coerceIn(0.0, 1.0)
                if (x != rawX || y != rawY) adjustedCount++
                CharacterPrompt(
                    prompt = cleanIfRequested(if (selection.useActualPrompt) character.actualPrompt ?: character.prompt else character.prompt, selection),
                    negativePrompt = cleanIfRequested(if (selection.useActualPrompt) character.actualNegativePrompt ?: character.negativePrompt.orEmpty() else character.negativePrompt.orEmpty(), selection),
                    centerX = x,
                    centerY = y,
                )
            }
            characters = applied
            if (applied.isNotEmpty()) {
                notes += MetadataImportNote.CharactersImported(applied.size)
                if (applied.size < eligible.size) {
                    notes += MetadataImportNote.CharactersTruncated(eligible.size, applied.size)
                }
                if (adjustedCount > 0) notes += MetadataImportNote.CharactersPositionSnapped(adjustedCount)
            }
        }
        // ---- 无法恢复的引用类信息：只提示，绝不建空引用 ----
        if (metadata.usedVibeReferences) {
            notes += MetadataImportNote.VibeReferencesNotRestorable
        }
        if (metadata.usedDirectorReferences) {
            notes += MetadataImportNote.DirectorReferencesNotRestorable
        }
        if (metadata.usedBaseImage) {
            notes += MetadataImportNote.BaseImageNotRestorable
        }

        val plan = MetadataImportPlan(
            prompt = prompt,
            negativePrompt = negativePrompt,
            // 与当前模型相同时不进"将改动"清单：那是噪音，用户会以为真换了模型。
            model = if (selection.settings) importedModel?.takeIf { it != current.model } else null,
            size = size,
            steps = steps,
            guidance = guidance,
            cfgRescale = cfgRescale,
            sampler = sampler,
            noiseSchedule = noiseSchedule,
            seed = seed,
            qualityTags = qualityTags,
            characters = characters,
            useCharacterCoordinates = if (characters != null) metadata.useCharacterCoordinates ?: false else null,
            undesiredContentPresetIndex = undesiredContentPresetIndex,
            notes = notes.toList(),
        )

        return if (plan.changesAnything) {
            plan
        } else {
            plan.copy(notes = notes + MetadataImportNote.NothingToImport)
        }
    }

    /** Only recognize an exact suffix in the base caption, preserving manual text and randomizers. */
    fun stripKnownQualityTags(
        prompt: String,
        candidates: List<QualityTagsOption> = QualityTagsOption.selectable,
        model: ImageModel = ImageModel.V5_FULL,
        hint: Int? = null,
    ): QualityTagStripResult {
        for (option in candidates.filter { hint == null || it.tagHint == hint }) {
            // Recognize current model suffixes and legacy App suffixes without rewriting unknown text.
            for (suffix in listOfNotNull(option.textFor(model), option.appendedText).distinct()) {
                val stripped = net.pocketnai.domain.prompt.NovelAiTextPrompt.stripQuality(prompt, suffix)
                if (stripped != null) return QualityTagStripResult(stripped, option)
            }
        }
        return QualityTagStripResult(text = prompt, option = null)
    }

    /** Clean Imports 作用于整份提示词，角色词也是提示词，同样处理。 */
    private fun cleanIfRequested(text: String, selection: MetadataImportSelection): String =
        if (selection.cleanImports) CleanImports.clean(text) else text

    data class QualityTagStripResult(val text: String, val option: QualityTagsOption?)

    /** 元数据里没写中心点时的默认值（与我们自己的默认站位一致）。 */
    private const val DEFAULT_CENTER = 0.5

}

/**
 * "Clean Imports"：官方前端的原样实现。
 *
 * ```js
 * C.replace(/[[\]{}]/g,"").replace(/,(?=[^ ])/g,", ").replace(/ ,/g,",")
 * ```
 *
 * ⚠️ **这不是无损整理**：`{}` / `[]` 在 NovelAI 里是强化与弱化语法，
 * 删掉它们会改变权重。因此界面上默认关闭，并且必须把这句话写出来。
 */
object CleanImports {

    private val BRACKETS = Regex("[\\[\\]{}]")
    private val COMMA_WITHOUT_SPACE = Regex(",(?=[^ ])")
    private val SPACE_BEFORE_COMMA = Regex(" ,")

    fun clean(prompt: String): String = prompt
        .replace(BRACKETS, "")
        .replace(COMMA_WITHOUT_SPACE, ", ")
        .replace(SPACE_BEFORE_COMMA, ",")
}
