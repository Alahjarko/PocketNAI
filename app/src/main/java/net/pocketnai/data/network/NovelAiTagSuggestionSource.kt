package net.pocketnai.data.network

import net.pocketnai.core.Outcome
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.prompt.TagSuggestionSource

/**
 * 用 NovelAI 的 `GET /ai/generate-image/suggest-tags` 做标签补全。
 *
 * 这个端点**不消耗 Anlas**（它只查标签表，不生成图片），因此可以随着输入频繁调用；
 * 具体频率由调用方的防抖控制，这里不做节流，避免两处都藏着限流策略。
 *
 * 失败一律返回空列表：没有建议不影响用户继续手写提示词，也没有任何一种
 * 错误文案值得为它打断输入（与 [net.pocketnai.data.repo.GenerationRepository] 的
 * 失败上报形成对比 —— 那是用户主动发起的、有代价的操作）。
 */
class NovelAiTagSuggestionSource(
    private val api: NovelAiApi,
    private val credentialStore: CredentialStore,
) : TagSuggestionSource {

    override suspend fun suggest(tagFragment: String, model: ImageModel): List<String> {
        val token = credentialStore.load()?.token
        if (token.isNullOrEmpty()) return emptyList()

        return when (val outcome = api.suggestTags(token, model, tagFragment)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> emptyList()
        }
    }
}
