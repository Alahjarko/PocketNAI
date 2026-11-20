package net.pocketnai.domain.chat

import kotlinx.serialization.json.*
import net.pocketnai.domain.model.*
import net.pocketnai.domain.openai.OpenAiImageParams

object ImageChatTools {
    const val MAX_IMAGES = 12
    private fun text(description: String) = buildJsonObject { put("type", "string"); put("description", description) }
    private fun number(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }
    private fun function(name: String, description: String, properties: JsonObject, required: List<String>) = buildJsonObject {
        put("type", "function"); put("function", buildJsonObject {
            put("name", name); put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", properties)
                if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
            })
        })
    }
    private val singleSchema = buildJsonArray {
        add(function("get_generation_settings", "读取当前图像模型和画布设置，不读取任何密钥。", buildJsonObject {}, emptyList()))
        add(function("generate_image", "根据用户要求生成一张 NovelAI 图片；可能消耗 Anlas。", buildJsonObject {
            put("prompt", text("English positive tags")); put("negative_prompt", text("English negative tags"))
            put("model", buildJsonObject { put("type", "string"); put("enum", JsonArray(ImageModel.entries.map { JsonPrimitive(it.apiModelId) })) })
            put("width", number("integer", "画布宽度（256–2048，64 步长）")); put("height", number("integer", "画布高度（256–2048，64 步长）"))
            put("seed", number("integer", "固定随机种子（非负整数）"))
            put("use_coords", buildJsonObject { put("type", "boolean"); put("description", "false: AI 自动安排角色；true: 使用角色 x/y 坐标") })
            put("characters", buildJsonObject {
                put("type", "array"); put("description", "独立角色列表（最多 ${CharacterPrompt.MAX_COUNT} 个）")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("prompt", text("角色正向 tags")); put("negative_prompt", text("角色逆向 tags"))
                        put("x", number("number", "角色水平坐标（0.0–1.0）")); put("y", number("number", "角色垂直坐标（0.0–1.0）"))
                    }); put("required", JsonArray(listOf(JsonPrimitive("prompt"))))
                })
            })
        }, listOf("prompt", "negative_prompt")))
    }

    private val openAiSingleSchema = buildJsonArray {
        add(function("get_generation_settings", "读取当前图像模型和画布设置，不读取任何密钥。", buildJsonObject {}, emptyList()))
        add(function("generate_image", "根据用户要求生成一张 OpenAI（GPT Image）图片；按 OpenAI API 实际用量计费。", buildJsonObject {
            put("prompt", text("自然语言图片描述（无需 NovelAI 标签或逆向提示词）"))
            put("model", buildJsonObject { put("type", "string"); put("enum", JsonArray(OpenAiImageParams.MODELS.map(::JsonPrimitive))) })
            put("width", number("integer", "可选宽度（16 的倍数，省略则沿用当前画布）"))
            put("height", number("integer", "可选高度（16 的倍数，省略则沿用当前画布）"))
            put("quality", buildJsonObject { put("type", "string"); put("enum", JsonArray(OpenAiImageParams.QUALITIES.map(::JsonPrimitive))) })
        }, listOf("prompt")))
    }

    private val promptEditTool = function(
        "propose_prompt_edit",
        "提供可编辑的提示词修改卡，用户点应用后才写入生成面板，不生成图片。修改或优化提示词且尚未要求出图时优先用此工具。",
        buildJsonObject {
            put("title", text("简短说明本次调整")); put("prompt", text("修改后的完整正向提示词或OpenAI描述；不修改时省略"))
            put("negative_prompt", text("修改后的完整排除词；不修改时省略，OpenAI图片不得提供"))
        },
        emptyList(),
    )

    val schema = buildJsonArray {
        singleSchema.forEach(::add)
        add(promptEditTool)
        add(function("generate_images", "规划多张独立图片或漫画分镜，应用按用户选择的工具权限串行执行。每条消息最多 12 张，失败不重试。", buildJsonObject {
            put("images", buildJsonObject {
                put("type", "array"); put("description", "待生成图片列表（1–$MAX_IMAGES 张）")
                put("items", singleSchema[1].jsonObject["function"]!!.jsonObject["parameters"]!!)
            })
        }, listOf("images")))
    }

    val openAiSchema = buildJsonArray {
        openAiSingleSchema.forEach(::add)
        add(promptEditTool)
        add(function("generate_images", "规划多张 OpenAI（GPT Image）独立图片或分镜，应用按用户选择的工具权限串行执行。每条消息最多 12 张，失败不重试。", buildJsonObject {
            put("images", buildJsonObject {
                put("type", "array"); put("description", "待生成图片列表（1–$MAX_IMAGES 张）")
                put("items", openAiSingleSchema[1].jsonObject["function"]!!.jsonObject["parameters"]!!)
            })
        }, listOf("images")))
    }

    fun schemaFor(isOpenAi: Boolean): JsonArray = if (isOpenAi) openAiSchema else schema

    fun parseOpenAiPlan(call: ToolCall, base: OpenAiImageParams): List<OpenAiImageParams> {
        if (call.name == "generate_image") return listOf(parseOpenAi(call, base))
        if (call.name != "generate_images") throw ChatFailure("未知图片工具")
        if (call.arguments.length > 192 * 1024) throw ChatFailure("多图计划过长，请减少张数或提示词")
        val obj = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
            ?: throw ChatFailure("多图计划格式不正确")
        if (obj.keys != setOf("images")) throw ChatFailure("多图计划包含不支持的参数")
        val rows = obj["images"] as? JsonArray ?: throw ChatFailure("images 必须为数组")
        if (rows.size !in 1..MAX_IMAGES) throw ChatFailure("每次计划须包含 1–$MAX_IMAGES 张图片")
        return rows.mapIndexed { index, row -> parseOpenAi(ToolCall("${call.id}-$index", "generate_image", row.toString()), base) }
    }

    fun parseOpenAi(call: ToolCall, base: OpenAiImageParams): OpenAiImageParams {
        if (call.name != "generate_image") throw ChatFailure("未知图片工具")
        val obj = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
            ?: throw ChatFailure("模型提供的图片参数格式不正确")
        if (obj.keys.any { it !in setOf("prompt", "negative_prompt", "model", "width", "height", "quality") })
            throw ChatFailure("模型提供了不支持的图片参数")
        val prompt = obj.string("prompt")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 32_000 }
            ?: throw ChatFailure("图片描述为空或过长")
        if (childSafetyTags.containsMatchIn(prompt)) throw ChatFailure("不支持涉及未成年人的性内容")
        val model = obj.string("model")?.takeIf(String::isNotBlank)?.also {
            if (it !in OpenAiImageParams.MODELS) throw ChatFailure("不支持该 OpenAI 图像模型")
        } ?: base.model
        val quality = obj.string("quality")?.takeIf(String::isNotBlank)?.also {
            if (it !in OpenAiImageParams.QUALITIES) throw ChatFailure("不支持的图片质量设置")
        } ?: base.quality
        val hasW = "width" in obj
        val hasH = "height" in obj
        val size = if (!hasW && !hasH) base.size else {
            if (!hasW || !hasH) throw ChatFailure("图片宽度和高度需要同时提供")
            val w = (obj["width"] as? JsonPrimitive)?.intOrNull ?: throw ChatFailure("图片 width 必须为整数")
            val h = (obj["height"] as? JsonPrimitive)?.intOrNull ?: throw ChatFailure("图片 height 必须为整数")
            val candidate = "${w}x${h}"
            OpenAiImageParams.sizeError(candidate)?.let { throw ChatFailure(it) }
            candidate
        }
        val params = base.copy(prompt = prompt, model = model, size = size, quality = quality, count = 1)
        params.validate(0)?.let { throw ChatFailure(it) }
        return params
    }

    fun parsePlan(call: ToolCall, base: GenerationParams): List<GenerationParams> {
        if (call.name == "generate_image") return listOf(parse(call, base))
        if (call.name != "generate_images") throw ChatFailure("未知图片工具")
        if (call.arguments.length > 192 * 1024) throw ChatFailure("多图计划过长，请减少张数或提示词")
        val obj = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
            ?: throw ChatFailure("多图计划格式不正确")
        if (obj.keys != setOf("images")) throw ChatFailure("多图计划包含不支持的参数")
        val rows = obj["images"] as? JsonArray ?: throw ChatFailure("images 必须为数组")
        if (rows.size !in 1..MAX_IMAGES) throw ChatFailure("每次计划须包含 1–$MAX_IMAGES 张图片")
        // Validate the entire plan before asking for permission or issuing any request.
        return rows.mapIndexed { index, row -> parse(ToolCall("${call.id}-$index", "generate_image", row.toString()), base) }
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
