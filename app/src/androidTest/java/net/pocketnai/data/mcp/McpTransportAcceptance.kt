package net.pocketnai.data.mcp

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.pocketnai.domain.mcp.McpProtocol
import net.pocketnai.domain.task.BackgroundTasks
import java.net.Socket
import java.net.InetSocketAddress
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Real loopback sockets with isolated credentials/files and an explicitly fake image backend. */
internal fun verifyLocalMcpTransport(base: Context) = runBlocking {
    val prefix = "mcp-test-${UUID.randomUUID()}"
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(base.cacheDir, prefix).also { it.mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("${prefix}_$name", mode)
    }
    val generated = AtomicInteger()
    val gate = CompletableDeferred<Unit>()
    val entered = CompletableDeferred<Unit>()
    var quoteLabel = "offline, no charge"
    var changeAtSend = false
    val blockImage = AtomicBoolean(false)
    val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff3e5c82.toInt()) }
    val imageBytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    bitmap.recycle()
    val backend = object : McpImageBackend {
        override suspend fun capabilities() = buildJsonObject { put("offline", true) }
        override suspend fun prepare(arguments: JsonObject) = object : McpImagePlan {
            override val provider = "fixture"
            override val prompt = arguments["prompt"]!!.jsonPrimitive.content
            override val negative = ""
            override val description = "offline image"
            override val quote = quoteLabel
            override val characterDetails = net.pocketnai.domain.mcp.McpNovelAiParams.characterDetails(
                net.pocketnai.domain.mcp.McpNovelAiParams.parse(arguments,
                    net.pocketnai.domain.model.GenerationParams.defaultsFor(net.pocketnai.domain.model.ModelCatalog.defaultProfile())))
        }
        override suspend fun quote(plan: McpImagePlan) = quoteLabel
        override suspend fun generate(plan: McpImagePlan, protection: BackgroundTasks.Task,
            beforeSend: suspend () -> Unit, onImage: suspend (String) -> Unit) {
            if (changeAtSend) quoteLabel = "changed quote, needs confirmation"
            beforeSend()
            protection.ensureRunning(); generated.incrementAndGet(); entered.complete(Unit)
            gate.await(); onImage("offline-image")
        }
        override suspend fun image(imageId: String): McpImageData {
            if (blockImage.get()) throw McpToolFailure("offline policy blocks image")
            return McpImageData(imageBytes, "image/png", 16, 24)
        }
    }
    val launch = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val manager = LocalMcpManager(context, backend, serviceStart = { launch.launch { it.attach() } })
    var token = ""
    suspend fun awaitState(predicate: () -> Boolean) { withTimeout(6000) { while (!predicate()) delay(20) } }
    fun rpc(method: String, params: JsonObject = buildJsonObject {}) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", UUID.randomUUID().toString()); put("method", method); put("params", params)
    }.toString()
    data class HttpImageReply(val status: Int, val headers: Map<String, String>, val bytes: ByteArray)
    fun request(body: String = "", origin: String? = null, authorized: Boolean = false, host: String? = null,
        path: String = "/mcp", method: String = "GET"): HttpImageReply {
        // Server behavior is tested over a real socket; the app is not an HTTP client.
        val payload = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("$method $path HTTP/1.1\r\nHost: ${host ?: "127.0.0.1:${LocalMcpManager.PORT}"}\r\n")
            append("Accept: application/json, text/event-stream\r\nMCP-Protocol-Version: 2025-11-25\r\n")
            append("Content-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n")
            if (authorized) append("Authorization: Bearer $token\r\n")
            if (origin != null) append("Origin: $origin\r\n")
            append("\r\n")
        }
        return Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", LocalMcpManager.PORT), 5000)
            socket.soTimeout = 5000
            socket.getOutputStream().write(headers.toByteArray())
            if (payload.size <= LocalMcpServer.MAX_BODY) socket.getOutputStream().write(payload)
            socket.getOutputStream().flush()
            // Read the declared response, not until EOF: early rejection can reset an unread POST body.
            val input = socket.getInputStream()
            val header = ByteArrayOutputStream()
            var ending = 0
            while (ending != 0x0d0a0d0a) {
                val byte = input.read(); check(byte >= 0 && header.size() < 65536)
                header.write(byte); ending = (ending shl 8) or byte
            }
            val lines = header.toString("UTF-8").removeSuffix("\r\n\r\n").split("\r\n")
            val responseHeaders = lines.drop(1).associate {
                it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
            }
            val length = if (method == "HEAD") 0 else responseHeaders.getValue("content-length").toInt()
            check(length in 0..8 * 1024 * 1024)
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) { val n = input.read(bytes, offset, length - offset); check(n > 0); offset += n }
            HttpImageReply(lines.first().split(' ')[1].toInt(), responseHeaders, bytes)
        }
    }
    fun http(body: String, origin: String? = null, authorized: Boolean = true, host: String? = null): Pair<Int, String> {
        val reply = request(body, origin, authorized, host, method = "POST")
        return reply.status to reply.bytes.toString(Charsets.UTF_8)
    }
    fun tool(name: String, args: JsonObject): JsonObject {
        val (code, body) = http(rpc("tools/call", buildJsonObject { put("name", name); put("arguments", args) }))
        assertThat(code).isEqualTo(200)
        return Json.parseToJsonElement(body).jsonObject["result"]!!.jsonObject
    }
    fun info(result: JsonObject) = Json.parseToJsonElement(result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content).jsonObject
    fun args(key: String, prompt: String = "sfw, blue geometric shape") = buildJsonObject {
        put("request_id", key); put("provider", "novelai"); put("prompt", prompt)
    }
    try {
        withContext(Dispatchers.Main) { manager.start(false) }
        awaitState { manager.state.value.running }
        token = manager.connectionToken()
        assertThat(http(rpc("ping"), authorized = false).first).isEqualTo(401)
        assertThat(http(rpc("ping"), origin = "https://untrusted.invalid").first).isEqualTo(403)
        assertThat(http(rpc("ping"), host = "untrusted.invalid").first).isEqualTo(403)
        assertThat(http("x".repeat(LocalMcpServer.MAX_BODY + 1)).first).isEqualTo(413)
        assertThat(http(rpc("initialize", buildJsonObject { put("protocolVersion", "2025-11-25") })).first).isEqualTo(200)
        val characterArgs = JsonObject(args("cancel-task-001") + ("characters" to Json.parseToJsonElement(
            """[{"prompt":"blue hair","negative_prompt":"hat","x":0.2,"y":0.3},{"prompt":"red hair","negative_prompt":"glasses","x":0.8,"y":0.7}]""")))
        val cancelled = info(tool("submit_image_task", characterArgs))
        val cancelledId = cancelled["job_id"]!!.jsonPrimitive.content
        assertThat(cancelled["character_details"]!!.jsonPrimitive.content).contains("角色 2")
        val saved = McpJobStore(File(context.filesDir, "mcp-jobs")).read(cancelledId)!!
        assertThat(saved.characterDetails).contains("排除词：glasses")
        assertThat(runCatching { McpJobStore(File(context.filesDir, "mcp-jobs")).write(
            saved.copy(characterDetails = "x".repeat(128 * 1024))) }.isFailure).isTrue()
        assertThat(McpJobStore(File(context.filesDir, "mcp-jobs")).read(cancelledId)).isEqualTo(saved)
        val duplicate = info(tool("submit_image_task", characterArgs))
        assertThat(duplicate["job_id"]).isEqualTo(cancelled["job_id"])
        assertThat(generated.get()).isEqualTo(0)
        assertThat(tool("submit_image_task", args("cancel-task-001", "different prompt"))["isError"]!!.jsonPrimitive.boolean).isTrue()
        tool("cancel_image_task", buildJsonObject { put("job_id", cancelledId) })
        assertThat(tool("submit_image_task", args("cancel-task-001"))["isError"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(info(tool("submit_image_task", characterArgs))["state"]!!.jsonPrimitive.content).isEqualTo(McpImageJob.CANCELLED)
        val job = info(tool("submit_image_task", args("working-task-002")))
        val id = job["job_id"]!!.jsonPrimitive.content
        withContext(Dispatchers.Main) { manager.approve(id) }
        withTimeout(5000) { entered.await() }
        assertThat(generated.get()).isEqualTo(1)
        info(tool("submit_image_task", args("working-task-002")))
        assertThat(generated.get()).isEqualTo(1)
        withContext(Dispatchers.Main) { manager.stop() }
        awaitState { !manager.state.value.running }
        assertThat(manager.state.value.active).isEqualTo(1)
        withContext(Dispatchers.Main) { manager.start(false) }
        awaitState { manager.state.value.running }
        assertThat(info(tool("get_image_task", buildJsonObject { put("job_id", id) }))["state"]!!.jsonPrimitive.content).isEqualTo(McpImageJob.RUNNING)
        gate.complete(Unit)
        awaitState { manager.state.value.jobs.any { it.id == id && it.state == McpImageJob.SUCCEEDED } }
        fun result() = tool("get_image_result", buildJsonObject { put("job_id", id) })
        val content = result()["content"]!!.jsonArray
        assertThat(content[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("image")
        assertThat(Base64.decode(content[0].jsonObject["data"]!!.jsonPrimitive.content, Base64.DEFAULT)).isEqualTo(imageBytes)
        val metadata = Json.parseToJsonElement(content[1].jsonObject["text"]!!.jsonPrimitive.content).jsonObject
        val url = metadata["image_url"]!!.jsonPrimitive.content
        assertThat(url).startsWith(LocalMcpManager.BASE_URL + "/mcp-image/")
        assertThat(url).doesNotContain(token)
        val markdown = metadata["markdown"]!!.jsonPrimitive.content
        assertThat(Regex("!\\[[^]]*]\\((http://127\\.0\\.0\\.1:[0-9]+/mcp-image/[0-9a-f]{64})\\)").matchEntire(markdown)!!.groupValues[1]).isEqualTo(url)
        val path = URI(url).rawPath
        val imageReply = request(path = path)
        assertThat(imageReply.status).isEqualTo(200)
        assertThat(imageReply.headers["content-type"]).isEqualTo("image/png")
        assertThat(imageReply.headers["cache-control"]).contains("private")
        assertThat(imageReply.bytes).isEqualTo(imageBytes)
        val decoded = BitmapFactory.decodeByteArray(imageReply.bytes, 0, imageReply.bytes.size)
        assertThat(decoded.width).isEqualTo(16); assertThat(decoded.height).isEqualTo(24); decoded.recycle()
        val head = request(path = path, method = "HEAD")
        assertThat(head.status).isEqualTo(200); assertThat(head.bytes).isEmpty()
        assertThat(head.headers["content-length"]).isEqualTo(imageBytes.size.toString())
        assertThat(request(path = "/mcp-image/" + "0".repeat(64)).status).isEqualTo(404)
        assertThat(request(path = path, origin = "https://untrusted.invalid").status).isEqualTo(403)
        assertThat(request(path = path, host = "untrusted.invalid").status).isEqualTo(403)
        assertThat(request(path = path + "?file=private").status).isEqualTo(404)
        assertThat(request(path = path, method = "POST").status).isEqualTo(405)
        assertThat(request(path = "/mcp").status).isEqualTo(401)
        blockImage.set(true)
        assertThat(request(path = path).status).isEqualTo(404)
        blockImage.set(false)
        withContext(Dispatchers.Main) { manager.stop() }
        awaitState { !manager.state.value.running }
        withContext(Dispatchers.Main) { manager.detached() }
        awaitState { !manager.state.value.stopping }
        withContext(Dispatchers.Main) { manager.start(false) }
        awaitState { manager.state.value.running }
        assertThat(request(path = path).status).isEqualTo(404)
        val renewed = Json.parseToJsonElement(result()["content"]!!.jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content).jsonObject["image_url"]!!.jsonPrimitive.content
        assertThat(renewed).isNotEqualTo(url)
        assertThat(request(path = URI(renewed).rawPath).bytes).isEqualTo(imageBytes)
        info(tool("submit_image_task", args("working-task-002")))
        assertThat(generated.get()).isEqualTo(1)
        val store = McpJobStore(File(context.filesDir, "mcp-jobs"))
        assertThat(store.read(id)!!.imageIds).containsExactly("offline-image")
        val priceJob = info(tool("submit_image_task", args("price-task-004")))["job_id"]!!.jsonPrimitive.content
        changeAtSend = true
        withContext(Dispatchers.Main) { manager.approve(priceJob) }
        awaitState { manager.state.value.jobs.any { it.id == priceJob && it.message.contains("发送前报价已变化") } }
        assertThat(generated.get()).isEqualTo(1)
        assertThat(store.read(priceJob)!!.state).isEqualTo(McpImageJob.PENDING)
        changeAtSend = false
        withContext(Dispatchers.Main) { manager.approve(priceJob) }
        awaitState { manager.state.value.jobs.any { it.id == priceJob && it.state == McpImageJob.SUCCEEDED } }
        assertThat(generated.get()).isEqualTo(2)
        val pendingId = McpJobStore.digest("recovered-task-003")
        store.write(McpImageJob(pendingId, "fixture", 0, "fixture", "test", description = "fixture", quote = "none"))
        store.recover()
        assertThat(store.read(pendingId)!!.state).isEqualTo(McpImageJob.CANCELLED)
        assertThat(store.read(id)!!.state).isEqualTo(McpImageJob.SUCCEEDED)
    } finally {
        gate.complete(Unit)
        withContext(Dispatchers.Main) { manager.stop() }
        awaitState { !manager.state.value.running && manager.state.value.active == 0 }
        withContext(Dispatchers.Main) { manager.detached() }
        launch.cancel()
        base.deleteSharedPreferences("${prefix}_pocketnai_mcp")
        context.filesDir.deleteRecursively()
    }
}
