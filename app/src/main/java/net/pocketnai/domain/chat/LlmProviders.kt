package net.pocketnai.domain.chat

import kotlinx.serialization.Serializable

/** Provider/model configuration never contains authentication material. */
@Serializable
data class LlmModel(
    val id: String,
    val dialect: LlmDialect = LlmDialect.AUTO,
    val thinking: Boolean = true,
    val maxTokens: Int = 16384,
)

@Serializable
data class LlmProvider(
    val id: String,
    val name: String,
    val baseUrl: String = "https://api.openai.com/v1",
    val models: List<LlmModel> = emptyList(),
)

@Serializable
data class LlmProviderCatalog(
    val providers: List<LlmProvider> = emptyList(),
    val activeProviderId: String? = null,
    val activeModelId: String? = null,
)
