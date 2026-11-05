package net.pocketnai.data.chat

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.pocketnai.domain.chat.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebSearchClient(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
    private val endpoint: (ChatSearchConfig) -> String = ::searchEndpoint,
) : ChatSearchClient {
    override suspend fun search(config: ChatSearchConfig, key: String, query: String): List<ChatSearchSource> = withContext(Dispatchers.IO) {
        if (!config.enabled || key.isBlank()) throw ChatFailure("请先配置并开启搜索服务")
        if (query.contains(key)) throw ChatFailure("检索词不能包含 API Key")
        val payload = buildJsonObject {
            put("query", query)
            if (config.provider == ChatSearchProvider.DEEPSEEK) {
                put("count", 5); put("need_content", false)
            } else {
                put("topic", "general"); put("search_depth", "basic"); put("max_results", 5)
                put("auto_parameters", false); put("include_answer", false); put("include_raw_content", false); put("include_images", false)
            }
        }
        val request = Request.Builder().url(endpoint(config)).header("Authorization", "Bearer $key")
            .header("User-Agent", "PocketNAI/Android").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw ChatFailure(when (response.code) {
                        401, 403 -> "搜索 API Key 无效或无权限"
                        402, 429, 432, 433 -> "搜索额度不足或受到限流，未自动重试"
                        in 300..399 -> "搜索地址发生重定向，请配置最终服务地址"
                        else -> "搜索服务失败（HTTP ${response.code}），未自动重试"
                    })
                    val body = response.body ?: throw ChatFailure("搜索响应为空")
                    val source = body.source()
                    val buffer = okio.Buffer()
                    while (source.read(buffer, 8192) != -1L) {
                        currentCoroutineContext().ensureActive()
                        if (buffer.size > 512 * 1024) throw ChatFailure("搜索响应过大")
                    }
                    val root = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.withoutSecret(key)
                    val rows = root["results"] as? JsonArray ?: throw ChatFailure("搜索响应格式不兼容")
                    rows.take(5).mapNotNull { row ->
                        val item = row as? JsonObject ?: return@mapNotNull null
                        val url = item.string("url")?.let(ChatSearchTool::safeUrl) ?: return@mapNotNull null
                        val snippet = if (config.provider == ChatSearchProvider.DEEPSEEK)
                            item.string("summary")?.takeIf { it.isNotBlank() } ?: item.string("snippet").orEmpty()
                        else item.string("content").orEmpty()
                        ChatSearchSource(item.string("title").orEmpty().take(200), url, snippet.take(2000))
                    }.distinctBy { it.url }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: ChatFailure) { throw e }
            catch (_: IOException) { currentCoroutineContext().ensureActive(); throw ChatFailure("搜索连接失败或超时，未自动重试") }
            catch (_: Exception) { throw ChatFailure("搜索响应格式不兼容") }
            finally { cancellation.cancel() }
        }
    }

    companion object {
        fun searchEndpoint(config: ChatSearchConfig): String = ChatProtocol.endpoint(config.baseUrl,
            if (config.provider == ChatSearchProvider.TAVILY) "search"
            else if (config.baseUrl.trim().trimEnd('/').endsWith("/v1")) "tools/search" else "v1/tools/search")
    }
}
