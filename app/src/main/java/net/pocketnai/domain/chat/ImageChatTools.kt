package net.pocketnai.domain.chat

import kotlinx.serialization.json.*
import net.pocketnai.domain.model.*

object ImageChatTools {
    private fun text(description: String) = buildJsonObject { put("type", "string"); put("description", description) }
    private fun number(type: String, min: Double, max: Double) = buildJsonObject { put("type", type); put("minimum", min); put("maximum", max) }
    private fun function(name: String, description: String, properties: JsonObject, required: List<String>) = buildJsonObject {
        put("type", "function"); put("function", buildJsonObject {
            put("name", name); put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object"); put("properties", properties); put("additionalProperties", false)
                put("required", JsonArray(required.map(::JsonPrimitive)))
            })
        })
    }
    val schema = buildJsonArray {
        add(function("get_generation_settings", "读取当前图像模型和画布设置，不读取任何密钥。", buildJsonObject {}, emptyList()))
        add(function("generate_image", "根据用户要求生成一张 NovelAI 图片；可能消耗 Anlas。", buildJsonObject {
            put("prompt", text("English positive tags")); put("negative_prompt", text("English negative tags"))
            put("model", buildJsonObject { put("type", "string"); put("enum", JsonArray(ImageModel.entries.map { JsonPrimitive(it.apiModelId) })) })
            put("width", number("integer", 256.0, 2048.0)); put("height", number("integer", 256.0, 2048.0))
            put("seed", number("integer", 0.0, GenerationParams.MAX_SEED.toDouble()))
            put("use_coords", buildJsonObject { put("type", "boolean"); put("description", "false: AI 自动安排角色；true: 使用角色 x/y 坐标") })
            put("characters", buildJsonObject {
                put("type", "array"); put("maxItems", CharacterPrompt.MAX_COUNT)
                put("items", buildJsonObject {
                    put("type", "object"); put("additionalProperties", false)
                    put("properties", buildJsonObject {
                        put("prompt", text("角色正向 tags")); put("negative_prompt", text("角色逆向 tags"))
                        put("x", number("number", 0.0, 1.0)); put("y", number("number", 0.0, 1.0))
                    }); put("required", JsonArray(listOf(JsonPrimitive("prompt"))))
                })
            })
        }, listOf("prompt", "negative_prompt")))
    }

    // 工具对提示词内容不预设立场：NovelAI 本身支持 NSFW，是否放行由服务端与账号策略决定，
    // 应用不做题材拦截（2026-10-10 起）。唯一的底线是涉及未成年人的性内容 ——
    // 这也为 NovelAI 服务条款所禁止，不属于"立场"。
    private val childSafetyTags = Regex("(?i)(?<![a-z])(?:child sexual|underage sexual)(?![a-z])")

    fun parse(call: ToolCall, base: GenerationParams): GenerationParams {
        if (call.name != "generate_image") throw ChatFailure("未知图片工具")
        val obj = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
            ?: throw ChatFailure("模型提供的图片参数格式不正确")
        if (obj.keys.any { it !in setOf("prompt", "negative_prompt", "model", "width", "height", "seed", "characters", "use_coords") })
            throw ChatFailure("模型提供了不支持的图片参数")
        val prompt = obj.string("prompt")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 16000 }
            ?: throw ChatFailure("图片正向提示词为空或过长")
        val negative = obj.string("negative_prompt") ?: throw ChatFailure("图片缺少逆向提示词")
        if (negative.length > 16000) throw ChatFailure("图片逆向提示词过长")
        if (childSafetyTags.containsMatchIn(prompt)) throw ChatFailure("不支持涉及未成年人的性内容")
        val model = obj.string("model")?.let { ImageModel.fromApiModelId(it) ?: throw ChatFailure("不支持该图像模型") } ?: base.model
        val profile = ModelCatalog.profileOf(model)
        val start = if (model == base.model) base else GenerationParams.defaultsFor(profile)
        fun integer(key: String, fallback: Int): Int = if (key !in obj) fallback else
            (obj[key] as? JsonPrimitive)?.intOrNull ?: throw ChatFailure("图片 $key 必须为整数")
        val size = ImageSizePreset(integer("width", start.size.width), integer("height", start.size.height))
        val seed = if ("seed" !in obj) null else (obj["seed"] as? JsonPrimitive)?.longOrNull
            ?.takeIf { it in 0..GenerationParams.MAX_SEED } ?: throw ChatFailure("Seed 超出范围")
        val characters = if ("characters" !in obj) emptyList() else {
            val rows = obj["characters"] as? JsonArray ?: throw ChatFailure("角色参数必须是数组")
            if (rows.size > CharacterPrompt.limitFor(model)) throw ChatFailure("当前模型最多支持 ${CharacterPrompt.limitFor(model)} 个角色")
            rows.mapIndexed { index, row ->
                val role = row as? JsonObject ?: throw ChatFailure("角色参数格式错误")
                val text = role.string("prompt")?.takeIf { it.isNotBlank() && it.length <= 8000 } ?: throw ChatFailure("角色提示词为空或过长")
                if (childSafetyTags.containsMatchIn(text)) throw ChatFailure("不支持涉及未成年人的性内容")
                fun coord(key: String): Double = if (key !in role) 0.5 else
                    (role[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..1.0 }
                        ?: throw ChatFailure("角色位置必须在 0–1 之间")
                CharacterPrompt("${call.id}-$index", text, role.string("negative_prompt").orEmpty(), coord("x"), coord("y"))
            }
        }
        val explicitCoordinates = (obj["characters"] as? JsonArray)?.any { row ->
            (row as? JsonObject)?.let { "x" in it || "y" in it } == true
        } ?: false
        val useCoordinates = if ("use_coords" in obj) (obj["use_coords"] as? JsonPrimitive)?.booleanOrNull
            ?: throw ChatFailure("use_coords 必须为布尔值") else explicitCoordinates
        val params = start.copy(prompt = prompt, negativePrompt = negative, size = size, outputSize = null,
            sampleCount = 1, qualityTags = QualityTagsOption.NONE, characters = characters,
            useCharacterCoordinates = useCoordinates,
            seedMode = if (seed == null) SeedMode.RANDOM else SeedMode.FIXED, baseSeed = seed ?: 0L)
        if (profile.validate(params).any { it !is ParamViolation.PromptTooLong }) throw ChatFailure("图片尺寸或参数不合法，请按 64 像素步长选择画布")
        return params
    }
}
