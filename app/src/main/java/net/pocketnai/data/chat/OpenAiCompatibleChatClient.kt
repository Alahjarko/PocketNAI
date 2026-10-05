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

class OpenAiCompatibleChatClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build(),
    private val endpoint: (String, String) -> String = ChatProtocol::endpoint,
) : ChatClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun models(config: LlmConfig, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint(config.baseUrl, "models"))
            .header("User-Agent", "PocketNAI/Android")
            .header("Authorization", "Bearer $apiKey").get().build()
        execute(request) { response ->
            val body = response.body ?: throw ChatFailure("模型列表为空")
            val bytes = readBounded(body)
            val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw ChatFailure("模型列表格式不兼容")
            (root["data"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("id") }
                .filterNot { apiKey in it }.distinct().sorted().take(500)
        }
    }

    override suspend fun complete(
        config: LlmConfig, apiKey: String, messages: List<JsonObject>, tools: JsonArray,
        onPartial: (JsonObject) -> Unit,
    ): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint(config.baseUrl, "chat/completions"))
            .header("User-Agent", "PocketNAI/Android")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(ChatProtocol.request(config, messages, tools).toString().toRequestBody("application/json".toMediaType())).build()
        execute(request, hasImages = messages.any { (it["content"] as? JsonArray)?.any { part -> (part as? JsonObject)?.string("type") == "image_url" } == true }) { response ->
            val body = response.body ?: throw ChatFailure("供应商返回空响应")
            if (response.header("Content-Type").orEmpty().contains("application/json")) {
                val bytes = readBounded(body)
                val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: throw ChatFailure("回复格式不兼容")
                val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw ChatFailure("模型未返回回复")
                if (choice.string("finish_reason") !in setOf("stop", "tool_calls")) throw ChatFailure("模型回复未完成，未执行工具")
                ChatProtocol.assistant(choice["message"] as? JsonObject ?: throw ChatFailure("回复格式不兼容")).withoutSecret(apiKey)
            } else {
                val accumulator = ChatStreamAccumulator()
                val source = body.source()
                val event = StringBuilder()
                var done = false
                var lastEmission = 0L
                fun consume() {
                    val data = event.toString().trim()
                    event.clear()
                    if (data.isBlank()) return
                    if (data == "[DONE]") { done = true; return }
                    val chunk = json.parseToJsonElement(data) as? JsonObject ?: throw ChatFailure("流式回复格式不兼容")
                    if (chunk["error"] != null) throw ChatFailure("供应商中断了回复")
                    accumulator.append(chunk)
                    val now = System.nanoTime()
                    if (now - lastEmission >= 120_000_000L) {
                        onPartial(accumulator.message().withoutSecret(apiKey))
                        lastEmission = now
                    }
                }
                var totalBytes = 0L
                while (!source.exhausted() && !done) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8LineStrict(256 * 1024)
                    totalBytes += line.toByteArray(Charsets.UTF_8).size
                    if (totalBytes > MAX_RESPONSE_BYTES) throw ChatFailure("模型回复过长")
                    if (line.isEmpty()) consume()
                    else if (line.startsWith("data:")) {
                        if (event.isNotEmpty()) event.append('\n')
                        event.append(line.removePrefix("data:").trimStart())
                    }
                }
                if (event.isNotEmpty()) consume()
                if (!done) throw ChatFailure("流式连接中断，未自动重试或执行工具")
                accumulator.complete().withoutSecret(apiKey).also(onPartial)
            }
        }
    }

    private fun readBounded(body: okhttp3.ResponseBody): ByteArray {
        val buffer = okio.Buffer()
        val source = body.source()
        while (source.read(buffer, 8192) != -1L) {
            if (buffer.size > MAX_RESPONSE_BYTES) throw ChatFailure("供应商响应过长")
        }
        return buffer.readByteArray()
    }

    private suspend fun <T> execute(request: Request, hasImages: Boolean = false, read: suspend (okhttp3.Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw ChatFailure(when (response.code) {
                    401, 403 -> "API Key 无效或没有模型访问权限"
                    429 -> "供应商额度不足或请求限流，请稍后再试"
                    400 -> if (hasImages) "供应商拒绝图片或参数，请核对模型是否支持识图及思考协议" else "供应商拒绝参数，请核对模型和思考协议"
                    in 300..399 -> "API 地址发生重定向，请填写供应商最终 Base URL"
                    else -> "LLM 服务请求失败（HTTP ${response.code}）"
                })
                read(response)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: ChatFailure) { throw e }
        catch (_: IOException) { currentCoroutineContext().ensureActive(); throw ChatFailure("LLM 连接失败或超时，未自动重试") }
        catch (_: Exception) { throw ChatFailure("LLM 响应格式不兼容") }
        finally { cancellation.cancel() }
    }

    private companion object { const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024 }
}
