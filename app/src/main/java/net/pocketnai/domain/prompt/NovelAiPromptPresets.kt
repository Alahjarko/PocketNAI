package net.pocketnai.domain.prompt

import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.UndesiredContentPreset

/** Public web-client preset snapshot, 2026-10-09. Indices 0..3 retain existing App semantics. */
object NovelAiPromptPresets {
    const val NONE_INDEX = 3
    private val presets = mapOf(
        ImageModel.V4_5_CURATED to listOf(
            UndesiredContentPreset(0, "Heavy", "heavy", 2, "blurry, lowres, upscaled, artistic error, film grain, scan artifacts, worst quality, bad quality, jpeg artifacts, very displeasing, chromatic aberration, halftone, multiple views, logo, too many watermarks, negative space, blank page"),
            UndesiredContentPreset(1, "Light", "light", 3, "blurry, lowres, upscaled, artistic error, scan artifacts, jpeg artifacts, logo, too many watermarks, negative space, blank page"),
            UndesiredContentPreset(2, "Human Focus", "humanFocus", 4, "blurry, lowres, upscaled, artistic error, film grain, scan artifacts, bad anatomy, bad hands, worst quality, bad quality, jpeg artifacts, very displeasing, chromatic aberration, halftone, multiple views, logo, too many watermarks, @_@, mismatched pupils, glowing eyes, negative space, blank page"),
            UndesiredContentPreset(3, "None", "none", 0, ""),
        ),
        ImageModel.V5_CURATED to listOf(
            UndesiredContentPreset(0, "Heavy", "heavy", 2, "lowres, artistic error, film grain, scan artifacts, worst quality, bad quality, jpeg artifacts, very displeasing, chromatic aberration, dithering, halftone, screentone, multiple views, logo, too many watermarks, negative space, blank page"),
            UndesiredContentPreset(1, "Light", "light", 3, "lowres, bad hands, bad anatomy, artistic error, sepia, white haze, worst quality, very displeasing, jpeg artifacts, 0::ai-generated::"),
            UndesiredContentPreset(4, "Furry Focus", "furryFocus", 5, "{worst quality}, distracting watermark, unfinished, bad quality, {widescreen}, upscale, {sequence}, {{grandfathered content}}, blurred foreground, chromatic aberration, sketch, everyone, [sketch background], simple, [flat colors], ych (character), outline, multiple scenes, [[horror (theme)]], comic"),
            UndesiredContentPreset(2, "Human Focus", "humanFocus", 4, "lowres, artistic error, film grain, scan artifacts, worst quality, bad quality, jpeg artifacts, very displeasing, chromatic aberration, dithering, halftone, screentone, multiple views, logo, too many watermarks, negative space, blank page, @_@, mismatched pupils, glowing eyes, bad anatomy"),
            UndesiredContentPreset(3, "None", "none", 0, ""),
        ),
        ImageModel.V5_FULL to listOf(
            UndesiredContentPreset(0, "Heavy", "heavy", 2, "lowres, artistic error, film grain, scan artifacts, worst quality, bad quality, jpeg artifacts, very displeasing, chromatic aberration, dithering, halftone, screentone, multiple views, logo, too many watermarks, negative space, blank page"),
            UndesiredContentPreset(1, "Light", "light", 3, "lowres, bad hands, bad anatomy, artistic error, sepia, white haze, worst quality, very displeasing, jpeg artifacts, 0::ai-generated::"),
            UndesiredContentPreset(4, "Furry Focus", "furryFocus", 5, "{worst quality}, distracting watermark, unfinished, bad quality, {widescreen}, upscale, {sequence}, {{grandfathered content}}, blurred foreground, chromatic aberration, sketch, everyone, [sketch background], simple, [flat colors], ych (character), outline, multiple scenes, [[horror (theme)]], comic"),
            UndesiredContentPreset(2, "Human Focus", "humanFocus", 4, "lowres, artistic error, film grain, scan artifacts, worst quality, bad quality, jpeg artifacts, very displeasing, chromatic aberration, dithering, halftone, screentone, multiple views, logo, too many watermarks, negative space, blank page, @_@, mismatched pupils, glowing eyes, bad anatomy"),
            UndesiredContentPreset(3, "None", "none", 0, ""),
        ),
        ImageModel.V4_5_FULL to listOf(
            UndesiredContentPreset(0, "Heavy", "heavy", 2, "lowres, artistic error, film grain, scan artifacts, worst quality, bad quality, jpeg artifacts, very displeasing, chromatic aberration, dithering, halftone, screentone, multiple views, logo, too many watermarks, negative space, blank page"),
            UndesiredContentPreset(1, "Light", "light", 3, "lowres, artistic error, scan artifacts, worst quality, bad quality, jpeg artifacts, multiple views, very displeasing, too many watermarks, negative space, blank page"),
            UndesiredContentPreset(4, "Furry Focus", "furryFocus", 5, "{worst quality}, distracting watermark, unfinished, bad quality, {widescreen}, upscale, {sequence}, {{grandfathered content}}, blurred foreground, chromatic aberration, sketch, everyone, [sketch background], simple, [flat colors], ych (character), outline, multiple scenes, [[horror (theme)]], comic"),
            UndesiredContentPreset(2, "Human Focus", "humanFocus", 4, "lowres, artistic error, film grain, scan artifacts, worst quality, bad quality, jpeg artifacts, very displeasing, chromatic aberration, dithering, halftone, screentone, multiple views, logo, too many watermarks, negative space, blank page, @_@, mismatched pupils, glowing eyes, bad anatomy"),
            UndesiredContentPreset(3, "None", "none", 0, ""),
        ),
    )

    fun forModel(model: ImageModel): List<UndesiredContentPreset> = presets.getValue(model)

    fun preset(model: ImageModel, index: Int): UndesiredContentPreset =
        forModel(model).firstOrNull { it.index == index } ?: forModel(model).first { it.index == NONE_INDEX }

    fun resolveNegative(model: ImageModel, index: Int, positive: String, custom: String): String {
        val preset = preset(model, index)
        if (preset.prefix.isEmpty()) return custom
        val expanded = PromptChunks.mapBase(custom) { base ->
            if (base.isEmpty()) preset.prefix else "${preset.prefix}, $base"
        }
        val full = model == ImageModel.V4_5_FULL || model == ImageModel.V5_FULL
        return if (full && !positive.contains("nsfw", ignoreCase = true)) "nsfw, $expanded" else expanded
    }

    /** Only strip a prefix proven by metadata's stable preset hint; unknown text stays literal. */
    fun restoreNegative(model: ImageModel, hint: Int?, positive: String, text: String): Pair<String, Int> {
        val preset = forModel(model).firstOrNull { it.tagHint == hint }
            ?: return text to NONE_INDEX
        if (preset.prefix.isEmpty()) return text to NONE_INDEX
        val prefix = resolveNegative(model, preset.index, positive, "")
        val parts = PromptChunks.split(text).toMutableList()
        val base = parts[0]
        parts[0] = when {
            base == prefix -> ""
            base.startsWith("$prefix, ") -> base.removePrefix("$prefix, ")
            else -> return text to NONE_INDEX
        }
        return parts.joinToString("|") to preset.index
    }
}
