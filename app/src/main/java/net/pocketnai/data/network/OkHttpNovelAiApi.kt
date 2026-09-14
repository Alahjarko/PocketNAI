package net.pocketnai.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.LogRedaction
import net.pocketnai.core.Outcome
import net.pocketnai.domain.billing.SubscriptionBalance
import net.pocketnai.domain.model.ImageModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * [NovelAiApi] 的 OkHttp 实现。
 *
 * 传输层只做三件事：鉴权、发请求、把响应流写到指定文件。
 * 所有 JSON 结构判断与图片校验都在别处，便于协议变化时只改一层。
 *
 * 关于主机：账户状态与图像生成**都**走 `image.novelai.net`。
 * `api.novelai.net` 会对 Persistent API Token 返回
 * 400 "If using a third-party tool, update to the image URL."，
 * 因此 [baseUrl] 只会是 image 主机。
 */
class OkHttpNovelAiApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json,
) : NovelAiApi {

    override suspend fun fetchAccountStatus(token: String): Outcome<AccountStatus> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/user/data")
                .get()
                .header(HEADER_AUTHORIZATION, bearer(token))
                .header(HEADER_ACCEPT, "application/json")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val correlationId = correlationIdOf(response)
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Outcome.Failure(
                            NovelAiErrorMapper.fromHttpStatus(response.code, body, correlationId),
                        )
                    }
                    Outcome.Success(AccountStatusParser.parse(body, json))
                }
            } catch (e: IOException) {
                Outcome.Failure(NovelAiErrorMapper.fromTransportError(e))
            }
        }

    /**
     * 读取余额。
     *
     * 错误映射走 [AccountReadErrorMapper] 而不是生成用的 [NovelAiErrorMapper]：
     * 余额查询超时不该被说成"服务端可能已经计费"。
     */
    override suspend fun fetchSubscriptionBalance(token: String): Outcome<SubscriptionBalance> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/user/subscription")
                .get()
                .header(HEADER_AUTHORIZATION, bearer(token))
                .header(HEADER_ACCEPT, "application/json")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val correlationId = correlationIdOf(response)
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Outcome.Failure(
                            AccountReadErrorMapper.fromHttpStatus(
                                statusCode = response.code,
                                body = body,
                                correlationId = correlationId,
                            ),
                        )
                    }
                    SubscriptionBalanceParser.parse(
                        body = body,
                        json = json,
                        fetchedAtMillis = System.currentTimeMillis(),
                    )
                }
            } catch (e: IOException) {
                Outcome.Failure(AccountReadErrorMapper.fromTransportError(e))
            }
        }

    override suspend fun generateImage(
        token: String,
        payload: JsonObject,
        destinationZip: File,
    ): Outcome<Unit> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/ai/generate-image")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header(HEADER_AUTHORIZATION, bearer(token))
            .header(HEADER_CONTENT_TYPE, "application/json")
            .header(HEADER_ACCEPT, "application/zip, application/json, */*")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val correlationId = correlationIdOf(response)
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Outcome.Failure(
                        NovelAiErrorMapper.fromHttpStatus(response.code, body, correlationId),
                    )
                }

                val contentType = response.header(HEADER_CONTENT_TYPE).orEmpty()
                if (contentType.contains("json", ignoreCase = true)) {
                    // 规划书 6.2 假定用 ZIP 传输。若官方改为 JSON/Base64，这里必须显式失败，
                    // 而不是把 JSON 当图片写进历史目录。
                    val body = response.body?.string().orEmpty()
                    return@withContext Outcome.Failure(
                        AppError.of(
                            code = ErrorCode.ZIP_INVALID,
                            correlationId = correlationId,
                            detail = "预期 ZIP 响应，实际 Content-Type=$contentType, body=${body.take(200)}",
                        ),
                    )
                }

                val responseBody = response.body
                    ?: return@withContext Outcome.Failure(
                        AppError.of(ErrorCode.SERVER_ERROR, correlationId, "响应体为空"),
                    )

                try {
                    writeBounded(responseBody.byteStream(), destinationZip, MAX_ARCHIVE_BYTES)
                } catch (e: ArchiveTooLargeException) {
                    destinationZip.delete()
                    return@withContext Outcome.Failure(
                        AppError.of(ErrorCode.ZIP_INVALID, correlationId, e.message.orEmpty()),
                    )
                }
                Outcome.Success(Unit)
            }
        } catch (e: IOException) {
            destinationZip.delete()
            Outcome.Failure(NovelAiErrorMapper.fromTransportError(e))
        }
    }

    override suspend fun suggestTags(        token: String,
        model: ImageModel,
        prompt: String,
    ): Outcome<List<String>> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/ai/generate-image/suggest-tags".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("prompt", prompt)
            ?.addQueryParameter("model", model.apiModelId)
            ?.build()
            ?: return@withContext Outcome.Failure(
                AppError.of(ErrorCode.UNKNOWN, detail = "标签建议 URL 非法"),
            )

        val request = Request.Builder()
            .url(url)
            .get()
            .header(HEADER_AUTHORIZATION, bearer(token))
            .header(HEADER_ACCEPT, "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val correlationId = correlationIdOf(response)
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Outcome.Failure(
                        NovelAiErrorMapper.fromHttpStatus(response.code, body, correlationId),
                    )
                }
                Outcome.Success(parseTags(body))
            }
        } catch (e: IOException) {
            Outcome.Failure(NovelAiErrorMapper.fromTransportError(e))
        }
    }

    /**
     * Vibe 编码。
     *
     * 响应是**二进制**（不是 JSON），因此这里只做传输与体积上限，不解析内容。
     * 用生成用的 client，但调用方保证它是用户动作触发的短请求。
     */
    override suspend fun encodeVibe(
        token: String,
        model: ImageModel,
        imageBase64: String,
        informationExtracted: Double,
    ): Outcome<ByteArray> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("image", imageBase64)
            put("model", model.apiModelId)
            put("information_extracted", informationExtracted)
        }
        val request = Request.Builder()
            .url("$baseUrl/ai/encode-vibe")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header(HEADER_AUTHORIZATION, bearer(token))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val correlationId = correlationIdOf(response)
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return@withContext Outcome.Failure(
                        NovelAiErrorMapper.fromHttpStatus(response.code, body, correlationId),
                    )
                }
                val body = response.body
                    ?: return@withContext Outcome.Failure(AppError.of(ErrorCode.VIBE_ENCODE_FAILED))
                if (body.contentLength() > MAX_VIBE_BYTES) {
                    return@withContext Outcome.Failure(AppError.of(ErrorCode.VIBE_ENCODE_FAILED))
                }
                val bytes = body.bytes()
                if (bytes.isEmpty() || bytes.size > MAX_VIBE_BYTES) {
                    return@withContext Outcome.Failure(AppError.of(ErrorCode.VIBE_ENCODE_FAILED))
                }
                Outcome.Success(bytes)
            }
        } catch (e: IOException) {
            Outcome.Failure(NovelAiErrorMapper.fromTransportError(e))
        }
    }

    private fun parseTags(body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val tags = root["tags"] as? JsonArray ?: return emptyList()
        return tags.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> (element["tag"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["name"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        }
    }

    private fun correlationIdOf(response: okhttp3.Response): String? =
        response.header("x-correlation-id")
            ?: response.header("x-request-id")
            ?: response.header("x-novelai-request-id")

    private class ArchiveTooLargeException(message: String) : IOException(message)

    private fun writeBounded(input: InputStream, destination: File, limit: Long) {
        destination.parentFile?.mkdirs()
        var total = 0L
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) {
                    throw ArchiveTooLargeException("ZIP 响应超过上限 $limit 字节")
                }
                output.write(buffer, 0, read)
            }
        }
    }

    private fun bearer(token: String): String = "Bearer $token"

    private companion object {
        const val BUFFER_SIZE = 64 * 1024

        /** ZIP 是已完成压缩的图片容器，体积上限按原始图片总量估算。 */
        const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024

        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_ACCEPT = "Accept"
        const val HEADER_CONTENT_TYPE = "Content-Type"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** `.vibe` 产物的体积上限。编码结果通常是几十 KB，1 MB 已经是很宽的上限。 */
        const val MAX_VIBE_BYTES = 1L * 1024 * 1024
    }
}
