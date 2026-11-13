package net.pocketnai.data.mcp

import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.pocketnai.domain.mcp.McpProtocol
import net.pocketnai.domain.task.BackgroundTasks
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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
    val backend = object : McpImageBackend {
        override suspend fun capabilities() = buildJsonObject { put("offline", true) }
        override suspend fun prepare(arguments: JsonObject) = object : McpImagePlan {
            override val provider = "fixture"
            override val prompt = arguments["prompt"]!!.jsonPrimitive.content
            override val negative = ""
            override val description = "offline image"
            override val quote = "offline, no charge"
        }
        override suspend fun quote(plan: McpImagePlan) = plan.quote
        override suspend fun generate(plan: McpImagePlan, protection: BackgroundTasks.Task, onImage: suspend (String) -> Unit) {
            protection.ensureRunning(); generated.incrementAndGet(); entered.complete(Unit)
            gate.await(); onImage("offline-image")
        }
        override suspend fun image(imageId: String) = buildJsonObject { put("content", buildJsonArray {
            add(buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "offline-fixture") })
        }) }
    }
    val launch = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val manager = LocalMcpManager(context, backend, serviceStart = { launch.launch { it.attach() } })
    val client = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
    var token = ""
    suspend fun awaitState(predicate: () -> Boolean) { withTimeout(6000) { while (!predicate()) delay(20) } }
    fun rpc(method: String, params: JsonObject = buildJsonObject {}) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", UUID.randomUUID().toString()); put("method", method); put("params", params)
    }.toString()
    fun http(body: String, origin: String? = null, authorized: Boolean = true, host: String? = null): Pair<Int, String> {
        val b = Request.Builder().url(LocalMcpManager.URL).header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-11-25")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (authorized) b.header("Authorization", "Bearer $token")
        if (origin != null) b.header("Origin", origin)
        if (host != null) b.header("Host", host)
        return client.newCall(b.build()).execute().use { it.code to it.body!!.string() }
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
        val cancelled = info(tool("submit_image_task", args("cancel-task-001")))
        val cancelledId = cancelled["job_id"]!!.jsonPrimitive.content
        val duplicate = info(tool("submit_image_task", args("cancel-task-001")))
        assertThat(duplicate["job_id"]).isEqualTo(cancelled["job_id"])
        assertThat(generated.get()).isEqualTo(0)
        assertThat(tool("submit_image_task", args("cancel-task-001", "different prompt"))["isError"]!!.jsonPrimitive.boolean).isTrue()
        tool("cancel_image_task", buildJsonObject { put("job_id", cancelledId) })
        assertThat(info(tool("submit_image_task", args("cancel-task-001")))["state"]!!.jsonPrimitive.content).isEqualTo(McpImageJob.CANCELLED)
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
        assertThat(tool("get_image_result", buildJsonObject { put("job_id", id) })["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("image")
        info(tool("submit_image_task", args("working-task-002")))
        assertThat(generated.get()).isEqualTo(1)
        val store = McpJobStore(File(context.filesDir, "mcp-jobs"))
        assertThat(store.read(id)!!.imageIds).containsExactly("offline-image")
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
        launch.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        base.deleteSharedPreferences("${prefix}_pocketnai_mcp")
        context.filesDir.deleteRecursively()
    }
}
