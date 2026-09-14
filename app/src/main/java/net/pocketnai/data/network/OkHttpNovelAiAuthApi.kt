package net.pocketnai.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.pocketnai.core.Outcome
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * [NovelAiAuthApi] 的 OkHttp 实现。
 *
 * 只做一件事：把本地派生出来的 Access Key 发到 `${baseUrl}/user/login`，取回 Access Token。
 *
 * ## 有意为之的几个约束
 * - **请求体只有 `key`**：不发邮箱、不发密码、不发设备标识。邮箱对基础 Access Key
 *   登录不是必需的，少发一项就少一处凭据外泄面。
 * - **不带 `Authorization` 头**：登录就是为了拿到 Token，带上没有意义。
 * - **零次自动重试**：401/403 是确定性的凭据或风控结果，重试只会加重风控。
 *   客户端由 `AppContainer` 用 `retryOnConnectionFailure(false)` 的基客户端派生，
 *   这里也不写任何重试循环。
 * - **响应体读取有上限**：避免畸形或恶意的大响应把内存吃满。
 * - **不记录任何请求或响应内容**：日志仍走 [RedactingHttpLogger]（只记方法、路径、状态码、耗时）。
 *
 * `baseUrl` 固定为 `https://image.novelai.net`（与生成链路同一个主机）。
 */
class OkHttpNovelAiAuthApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json,
) : NovelAiAuthApi {

    override suspend fun login(accessKey: String): Outcome<AccountSession> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/user/login")
                .post(buildLoginBody(accessKey).toString().toRequestBody(JSON_MEDIA_TYPE))
                .header(HEADER_CONTENT_TYPE, JSON_CONTENT_TYPE)
                .header(HEADER_ACCEPT, JSON_CONTENT_TYPE)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val correlationId = correlationIdOf(response)
                    if (!response.isSuccessful) {
                        return@withContext Outcome.Failure(
                            NovelAiAuthErrorMapper.fromHttpStatus(response.code, correlationId),
                        )
                    }

                    val body = readBoundedText(response)
                        ?: return@withContext Outcome.Failure(
                            NovelAiAuthErrorMapper.forInvalidResponse(
                                detail = "登录响应超过 ${MAX_RESPONSE_BYTES} 字节上限或无法读取",
                                correlationId = correlationId,
                            ),
                        )

                    parseAccessToken(body)?.let { token ->
                        Outcome.Success(AccountSession(accessToken = token))
                    } ?: Outcome.Failure(
                        NovelAiAuthErrorMapper.forInvalidResponse(
                            detail = "登录响应缺少合法的 accessToken",
                            correlationId = correlationId,
                        ),
                    )
                }
            } catch (e: IOException) {
                Outcome.Failure(NovelAiAuthErrorMapper.fromTransportError(e))
            }
        }

    /** 请求体只包含 `key`，这一点有用例专门断言。 */
    internal fun buildLoginBody(accessKey: String): JsonObject =
        buildJsonObject { put("key", accessKey) }

    /** 严格解析：`accessToken` 必须存在、是非空字符串。 */
    private fun parseAccessToken(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return null

        // 类型错误（数字、对象、数组、null）一律视为协议错误，不做字符串化兜底。
        val primitive = root["accessToken"] as? JsonPrimitive ?: return null
        if (primitive.isString.not()) return null
        return primitive.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /**
     * 有界读取响应体。超过 [MAX_RESPONSE_BYTES] 时返回 null 而不是继续读，
     * 这样异常大的响应不会把内存吃光。
     */
    private fun readBoundedText(response: Response): String? {
        val body = response.body ?: return null
        // 先看声明的长度，能提前拒绝就不读。
        if (body.contentLength() > MAX_RESPONSE_BYTES) return null

        // 注意不能用 `source.readByteArray(n)`：Okio 的这个方法在不足 n 字节时会抛
        // EOFException，正常长度的响应会被当成网络错误。这里逐块读到 EOF，超限即拒绝。
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, MAX_RESPONSE_BYTES + 1 - total)
            if (read == -1L) break
            total += read
            if (total > MAX_RESPONSE_BYTES) return null
        }
        return buffer.readUtf8()
    }

    private fun correlationIdOf(response: Response): String? =
        response.header("x-correlation-id")
            ?: response.header("x-request-id")

    private companion object {
        const val MAX_RESPONSE_BYTES = 64L * 1024

        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_ACCEPT = "Accept"
        const val JSON_CONTENT_TYPE = "application/json"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
