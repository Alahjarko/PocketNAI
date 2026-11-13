package net.pocketnai.data.mcp

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import net.pocketnai.domain.mcp.McpProtocol
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** IPv4 loopback only. No file serving, CORS, redirects, SSE stream or request-body logging. */
class LocalMcpServer(
    port: Int,
    private val token: String,
    private val protocol: McpProtocol,
    private val onRequest: () -> Unit,
) : NanoHTTPD("127.0.0.1", port) {
    init { setAsyncRunner(BoundedRunner()) }
    override fun serve(session: IHTTPSession): Response {
        fun response(status: Int, body: String? = null): Response {
            val code = Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR
            return newFixedLengthResponse(code, "application/json", body.orEmpty()).apply {
                setKeepAlive(false)
                addHeader("Cache-Control", "no-store")
                addHeader("X-Content-Type-Options", "nosniff")
                if (status == 405) addHeader("Allow", "POST")
            }
        }
        val headers = session.headers
        if (headers["host"] !in setOf("127.0.0.1:$listeningPort", "localhost:$listeningPort")) return response(403)
        val origin = headers["origin"]
        if (origin != null && origin !in setOf("http://127.0.0.1:$listeningPort", "http://localhost:$listeningPort")) return response(403)
        if (session.uri != "/mcp" || session.queryParameterString?.isNotEmpty() == true) return response(404)
        if (!MessageDigest.isEqual(headers["authorization"].orEmpty().toByteArray(), "Bearer $token".toByteArray())) return response(401)
        if (session.method != Method.POST) return response(405)
        if (!headers["content-type"].orEmpty().substringBefore(';').trim().equals("application/json", true)) return response(415)
        val accept = headers["accept"].orEmpty()
        if (!accept.contains("application/json") || !accept.contains("text/event-stream")) return response(406)
        if (headers.containsKey("transfer-encoding")) return response(400)
        val length = headers["content-length"]?.toLongOrNull() ?: return response(411)
        if (length !in 1..MAX_BODY.toLong()) return response(413)
        return try {
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val n = session.inputStream.read(bytes, offset, bytes.size - offset)
                if (n <= 0) return response(400)
                offset += n
            }
            val body = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            onRequest()
            val reply = runBlocking { protocol.handle(body, headers["mcp-protocol-version"]) }
            response(reply.status, reply.body)
        } catch (_: Exception) { response(400, "{\"error\":\"Request could not be processed\"}") }
    }
    private class BoundedRunner : AsyncRunner {
        private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
        private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, SynchronousQueue()) { task ->
            Thread(task, "pocketnai-mcp").apply { isDaemon = true }
        }
        override fun exec(clientHandler: ClientHandler) {
            clients += clientHandler
            try { workers.execute(clientHandler) }
            catch (_: RuntimeException) { clients -= clientHandler; clientHandler.close() }
        }
        override fun closed(clientHandler: ClientHandler) { clients -= clientHandler }
        override fun closeAll() { clients.toList().forEach { it.close() }; clients.clear(); workers.shutdownNow() }
    }
    companion object { const val MAX_BODY = 128 * 1024 }
}
