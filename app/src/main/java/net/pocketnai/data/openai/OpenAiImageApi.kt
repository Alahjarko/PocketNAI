package net.pocketnai.data.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import net.pocketnai.domain.openai.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.FilterInputStream
import java.util.concurrent.TimeUnit

data class OpenAiImageResponse(val data: List<String>, val snapshot: OpenAiImageSnapshot)
class OpenAiImageFailure(val uncertain: Boolean, override val message: String) : IOException(message)
fun interface OpenAiImagesTransport {
    suspend fun generate(key: String, params: OpenAiImageParams, images: List<String>): OpenAiImageResponse
}

@Serializable
private data class ImageWireResult(
    val data: List<ImageWireData> = emptyList(),
    val usage: JsonObject? = null,
    val size: String? = null,
    val quality: String? = null,
    val background: String? = null,
)
@Serializable
private data class ImageWireData(val b64_json: String? = null)

/** Single POST, with no redirects, fallback or transport retries. No raw response errors are surfaced. */
@OptIn(ExperimentalSerializationApi::class)
class OpenAiImageApi(
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS).readTimeout(600, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
    private val baseUrl: String = "https://api.openai.com/v1",
) : OpenAiImagesTransport {
    private val client = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    override suspend fun generate(key: String, params: OpenAiImageParams, images: List<String>): OpenAiImageResponse = withContext(Dispatchers.IO) {
        params.validate()?.let { throw OpenAiImageFailure(false, it) }
        require(images.size <= 16)
        val payload = payload(params, images)
        val encoded = payload.toString().toRequestBody("application/json".toMediaType())
        // OkHttp may otherwise follow Retry-After: 0 on HTTP 503 even with connection retries off.
        val singleUseBody = object : RequestBody() {
            override fun contentType() = encoded.contentType()
            override fun contentLength() = encoded.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) = encoded.writeTo(sink)
        }
        val request = Request.Builder().url(baseUrl.trimEnd('/') + if (images.isEmpty()) "/images/generations" else "/images/edits")
            .header("Authorization", "Bearer $key")
            .post(singleUseBody).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw OpenAiImageFailure(response.code >= 500, when (response.code) {
                    400 -> "OpenAI 拒绝了参数或图片内容，请检查描述、尺寸及参考图"
                    401 -> "OpenAI API 密钥无效或已失效"
                    403 -> "当前项目没有该模型的访问权限，或需要完成组织验证"
                    429 -> "OpenAI 配额不足或请求频率受限，请在平台检查额度"
                    in 500..599 -> "OpenAI 服务异常，结果及计费待确认，本次请求未重试"
                    else -> "OpenAI 请求失败（HTTP ${response.code}），未重试"
                })
                val body = response.body ?: throw OpenAiImageFailure(true, "OpenAI 未返回图片，可能已计费")
                // Decode JSON from the stream: never duplicate the entire base64 response as a second String.
                val bounded = object : FilterInputStream(body.byteStream()) {
                    private var total = 0L
                    private fun account(count: Int): Int {
                        if (count > 0) total += count
                        if (total > 64L * 1024 * 1024) throw OpenAiImageFailure(true,
                            "图片响应超过本机读取上限，可能已计费，请减少张数或尺寸")
                        return count
                    }
                    override fun read(): Int = super.read().also { if (it >= 0) account(1) }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = account(`in`.read(bytes, offset, length))
                }
                val obj = OpenAiImageCodec.json.decodeFromStream<ImageWireResult>(bounded)
                val data = obj.data.mapNotNull { it.b64_json }
                if (data.isEmpty() || data.size > params.count) throw OpenAiImageFailure(true, "OpenAI 返回的图片数量异常，可能已计费")
                val usage = runCatching { obj.usage?.let { u ->
                    val details = u["input_tokens_details"] as? JsonObject
                    val text = details?.get("text_tokens")?.jsonPrimitive?.longOrNull
                        ?: if (images.isEmpty()) u["input_tokens"]?.jsonPrimitive?.longOrNull else null
                    val image = details?.get("image_tokens")?.jsonPrimitive?.longOrNull
                        ?: if (images.isEmpty()) 0L else null
                    val output = u["output_tokens_details"]?.jsonObject?.get("image_tokens")?.jsonPrimitive?.longOrNull
                        ?: u["output_tokens"]?.jsonPrimitive?.longOrNull
                    if (text != null && image != null && output != null && minOf(text, image, output) >= 0)
                        OpenAiImageUsage(text, image, output) else null
                } }.getOrNull()
                OpenAiImageResponse(data, OpenAiImageSnapshot(params, usage, obj.size, obj.quality, obj.background))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: OpenAiImageFailure) { throw e }
        catch (_: IOException) { throw OpenAiImageFailure(true, "OpenAI 请求超时或连接中断，可能已计费，未自动重试") }
        catch (_: Exception) { throw OpenAiImageFailure(true, "OpenAI 响应无法完整读取，可能已计费，未自动重试") }
    }

    companion object {
        fun payload(params: OpenAiImageParams, images: List<String> = emptyList()): JsonObject = buildJsonObject {
            put("model", params.model); put("prompt", params.prompt); put("n", params.count)
            put("size", params.size); put("quality", params.quality); put("output_format", params.outputFormat)
            put("background", params.background); put("moderation", params.moderation)
            if (params.outputFormat != "png") put("output_compression", params.compression)
            if (images.isNotEmpty()) put("images", buildJsonArray {
                images.forEach { add(buildJsonObject { put("image_url", it) }) }
            })
        }
    }
}
