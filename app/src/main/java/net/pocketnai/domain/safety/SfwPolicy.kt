package net.pocketnai.domain.safety

import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.GenerationParams
import java.text.Normalizer
import java.util.Locale

/** 用户选择启用的提示词模式，不是图片内容识别器；负向排除词不参与判定。 */
object SfwPolicy {
    const val BLOCK_MESSAGE = "SFW 模式已开启：正向提示词含敏感内容，未发送生成请求。可调整提示词或关闭 SFW 模式。"
    private val terms = "nsfw|r18|r-18|rating\\s*:\\s*explicit|explicit sexual|nude|nudity|naked|topless|bottomless|porn|pornographic|hentai|erotic|sex|intercourse|masturbation|orgasm|cum|semen|penis|vagina|pussy|nipples|areola|spread legs|bare breasts|exposed breasts|exposed genitals"
    private val excluded = Regex("\\b(?:no|without|avoid|non)[ -]+(?:$terms)\\b")
    private val english = Regex("(?<![a-z0-9])(?:$terms)(?![a-z0-9])")
    private val chinese = Regex("色情|裸体|全裸|赤裸|露点|性交|生殖器|阴茎|阴道|自慰|射精")
    private val excludedChinese = Regex("(?:不要|避免|禁止|没有|不含|无)(?:色情|裸体|全裸|赤裸|露点|性交|生殖器|阴茎|阴道|自慰|射精)")

    fun containsSensitivePrompt(prompt: String): Boolean {
        val normalized = Normalizer.normalize(prompt, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('_', ' ')
        val positive = excludedChinese.replace(excluded.replace(normalized, ""), "")
        return english.containsMatchIn(positive) || chinese.containsMatchIn(positive)
    }

    fun isSensitive(params: GenerationParams): Boolean = containsSensitivePrompt(params.prompt) ||
        params.characters.any { containsSensitivePrompt(it.prompt) }

    fun isSensitive(generation: Generation): Boolean = generation.openAi?.params?.let {
        containsSensitivePrompt(it.prompt)
    } ?: isSensitive(generation.params)

    fun blocks(enabled: Boolean, params: GenerationParams): Boolean = enabled && isSensitive(params)
    fun blocks(enabled: Boolean, prompt: String): Boolean = enabled && containsSensitivePrompt(prompt)

    fun isSensitive(context: net.pocketnai.domain.chat.ChatImageContext): Boolean = containsSensitivePrompt(context.prompt) ||
        context.characters.any { containsSensitivePrompt(it.prompt) }
}
