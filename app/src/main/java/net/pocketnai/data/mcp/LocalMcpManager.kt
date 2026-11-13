package net.pocketnai.data.mcp

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import net.pocketnai.domain.mcp.McpProtocol
import net.pocketnai.domain.task.BackgroundTasks
import java.io.File

interface McpImagePlan {
    val provider: String
    val prompt: String
    val negative: String
    val description: String
    val quote: String
}
interface McpImageBackend {
    suspend fun capabilities(): JsonObject
    suspend fun prepare(arguments: JsonObject): McpImagePlan
    suspend fun quote(plan: McpImagePlan): String
    suspend fun generate(plan: McpImagePlan, protection: BackgroundTasks.Task,
        beforeSend: suspend () -> Unit, onImage: suspend (String) -> Unit)
    suspend fun image(imageId: String): JsonObject
}
class McpToolFailure(message: String) : IllegalStateException(message)
interface LocalMcpOwner { val localMcpManager: LocalMcpManager }

/** Process-owned tasks outlive HTTP calls. Stopping the listener never cancels a sent POST. */
class LocalMcpManager(
    context: Context,
    private val backend: McpImageBackend,
    private val store: McpJobStore = McpJobStore(File(context.filesDir, "mcp-jobs")),
    private val access: McpAccess = McpAccess(context),
    private val serviceStart: ((LocalMcpManager) -> Unit)? = null,
) : McpProtocol.McpTools {
    data class State(
        val running: Boolean = false, val starting: Boolean = false, val stopping: Boolean = false,
        val directGeneration: Boolean = false, val jobs: List<McpImageJob> = emptyList(),
        val message: String? = null, val executing: Boolean = false,
    ) { val active get() = if (executing) 1 else 0
        val pending get() = jobs.count { it.state == McpImageJob.PENDING } }
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ ->
        operationFailed()
    })
    private val mutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var server: LocalMcpServer? = null
    private var attached = false
    private var serial = 0L
    @Volatile private var lastActivity = SystemClock.elapsedRealtime()
    private val plans = mutableMapOf<String, McpImagePlan>()
    private val ready = scope.async {
        val recovered = withContext(Dispatchers.IO) { store.recover() }
        _state.update { it.copy(jobs = recovered) }
    }

    fun start(directGeneration: Boolean) {
        if (_state.value.running || _state.value.starting) return
        if (attached && _state.value.active == 0) return
        serial++
        _state.update { it.copy(starting = true, stopping = false, directGeneration = directGeneration, message = null) }
        if (attached) { scope.launch { attach() }; return }
        try {
            if (serviceStart != null) serviceStart.invoke(this)
            else ContextCompat.startForegroundService(app, Intent(app, LocalMcpService::class.java))
        }
        catch (_: RuntimeException) { _state.update { it.copy(starting = false, directGeneration = false, message = "本机服务未能启动，请在应用内重试") } }
    }
    internal suspend fun attach() = mutex.withLock { attachLocked() }
    private suspend fun attachLocked() {
        ready.await()
        if (!_state.value.starting) return
        val session = serial
        attached = true
        try {
            val listener = withContext(Dispatchers.IO) {
                LocalMcpServer(PORT, access.token(), McpProtocol(this@LocalMcpManager)) {
                    lastActivity = SystemClock.elapsedRealtime()
                }.also { it.start(5_000, true) }
            }
            if (session != serial || !_state.value.starting) { withContext(Dispatchers.IO) { listener.stop() }; return }
            server = listener
            lastActivity = SystemClock.elapsedRealtime()
            _state.update { it.copy(running = true, starting = false) }
        } catch (_: Exception) {
            attached = false
            _state.update { it.copy(starting = false, directGeneration = false, message = "本机服务启动失败，端口可能已被占用") }
        }
    }
    fun stop() { scope.launch { stopListening() } }
    private fun operationFailed() { _state.update { it.copy(message = "本机操作未完成，请检查存储和任务状态；没有自动重试") } }
    private suspend fun stopListening() = mutex.withLock {
        serial++
        _state.update { it.copy(running = false, starting = false, stopping = attached, directGeneration = false) }
        withContext(Dispatchers.IO) { server?.stop() }; server = null
        _state.value.jobs.filter { it.state == McpImageJob.PENDING }.forEach { job ->
            save(job.copy(state = McpImageJob.CANCELLED, message = "连接已结束，未发送")); plans.remove(job.id)
        }
    }
    internal fun detached() {
        attached = false
        scope.launch { stopListening(); _state.update { it.copy(stopping = false) } }
    }
    internal fun expireIfIdle() {
        if (_state.value.active == 0 && SystemClock.elapsedRealtime() - lastActivity > 20 * 60_000L) stop()
    }
    suspend fun connectionToken(): String = withContext(Dispatchers.IO) { access.token() }
    fun resetConnection() { scope.launch { stopListening(); withContext(Dispatchers.IO) { access.reset() }; _state.update { it.copy(message = "连接令牌已重置，请重新配置客户端") } } }
    fun approve(id: String) { scope.launch { approveNow(id, false) } }
    fun reject(id: String) { scope.launch { cancel(id) } }

    override suspend fun call(name: String, arguments: JsonObject): JsonObject {
        ready.await()
        if (!_state.value.running) return McpProtocol.failure("本机服务已关闭，请先在 PocketNAI 开启")
        return try {
            val allowed = when (name) {
                "submit_image_task" -> setOf("request_id", "provider", "prompt", "negative_prompt", "model", "width", "height", "seed", "size", "quality")
                "get_image_task" -> setOf("job_id", "wait_seconds")
                "get_image_result", "cancel_image_task" -> setOf("job_id")
                else -> emptySet()
            }
            if (arguments.keys.any { it !in allowed }) throw McpToolFailure("工具参数包含不支持的字段")
            when (name) {
                "get_image_capabilities" -> McpProtocol.text(backend.capabilities())
                "submit_image_task" -> McpProtocol.text(submit(arguments).publicInfo())
                "get_image_task" -> {
                    val id = jobId(arguments)
                    val seconds = arguments["wait_seconds"]?.let { (it as? JsonPrimitive)?.intOrNull?.takeIf { n -> n in 0..20 }
                        ?: throw McpToolFailure("wait_seconds 须为 0–20 的整数") } ?: 0
                    val initial = read(id)
                    if (seconds > 0 && initial.state in setOf(McpImageJob.PENDING, McpImageJob.RUNNING)) {
                        withTimeoutOrNull(seconds * 1000L) { state.first { s -> s.jobs.firstOrNull { it.id == id }?.state != initial.state } }
                    }
                    McpProtocol.text(read(id).publicInfo())
                }
                "get_image_result" -> {
                    val job = read(jobId(arguments))
                    val imageId = job.imageIds.firstOrNull() ?: throw McpToolFailure("还没有可取回的图片，请查询任务状态，不要重复提交")
                    backend.image(imageId)
                }
                "cancel_image_task" -> McpProtocol.text(cancel(jobId(arguments)).publicInfo())
                else -> McpProtocol.failure("未知工具")
            }
        } catch (e: McpToolFailure) { McpProtocol.failure(e.message ?: "任务未完成") }
        catch (_: Exception) { McpProtocol.failure("操作未完成，请核对任务状态；没有自动重试") }
    }
    private suspend fun submit(args: JsonObject): McpImageJob {
        val requestId = (args["request_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { Regex("[A-Za-z0-9_-]{8,128}").matches(it) } ?: throw McpToolFailure("请提供唯一且可复用的 request_id")
        val id = McpJobStore.digest(requestId)
        val fingerprint = McpJobStore.digest(JsonObject(args.filterKeys { it != "request_id" }.toSortedMap()).toString())
        suspend fun previous(): McpImageJob? = withContext(Dispatchers.IO) { store.read(id) }?.also {
            if (it.fingerprint != fingerprint) throw McpToolFailure("request_id 已用于不同参数，未发送新请求")
        }
        mutex.withLock { previous()?.let { return it } }
        if (args.toString().contains(connectionToken())) throw McpToolFailure("生成参数不能包含连接令牌")
        val plan = backend.prepare(args)
        val job = mutex.withLock {
            previous()?.let { return it }
            if (!_state.value.running) throw McpToolFailure("连接已结束，未发送")
            if (_state.value.pending + _state.value.active >= 4) throw McpToolFailure("待处理任务较多，请先完成或取消已有任务")
            McpImageJob(id, fingerprint, System.currentTimeMillis(), plan.provider, plan.prompt, plan.negative,
                plan.description, plan.quote).also { save(it); plans[id] = plan }
        }
        if (_state.value.directGeneration) scope.launch { approveNow(id, true) }
        return job
    }
    private suspend fun approveNow(id: String, automatic: Boolean) {
        ready.await()
        data class Approved(val plan: McpImagePlan, val quote: String)
        val work = mutex.withLock {
            val job = read(id)
            if (job.state != McpImageJob.PENDING || !_state.value.running || !attached) return
            if (automatic && !_state.value.directGeneration) return
            if (_state.value.active > 0) { _state.update { it.copy(message = "请等待正在生成的任务完成") }; return }
            val plan = plans[id] ?: return
            try {
                val quote = backend.quote(plan)
                if (quote != job.quote && !automatic) { save(job.copy(quote = quote, message = "报价已变化，请核对后再次确认")); return }
                save(job.copy(state = McpImageJob.RUNNING, quote = quote, message = "正在生成；已发送后不会重试"))
                _state.update { it.copy(executing = true) }
                Approved(plan, quote)
            } catch (_: Exception) { save(job.copy(state = McpImageJob.FAILED, message = "参数、账号或费用校验未通过，未自动重试")); plans.remove(id); return }
        }
        val executionSerial = serial
        var changedQuote: String? = null
        val protection = object : BackgroundTasks.Task {
            override suspend fun awaitReady() { ensureRunning() }
            override fun ensureRunning() { if (!attached || !_state.value.running || serial != executionSerial) throw McpToolFailure("连接已结束，未再发送请求") }
            override fun waitingForUser(waiting: Boolean) = Unit
            override fun close() = Unit
        }
        try {
            withContext(NonCancellable) {
                backend.generate(work.plan, protection, beforeSend = {
                    val latestQuote = backend.quote(work.plan)
                    if (!automatic && latestQuote != work.quote) {
                        changedQuote = latestQuote
                        throw McpToolFailure("报价已变化，未发送")
                    }
                    protection.ensureRunning()
                }) { imageId -> mutex.withLock {
                    val latest = read(id); save(latest.copy(imageIds = (latest.imageIds + imageId).distinct()))
                } }
                mutex.withLock {
                    val latest = read(id)
                    if (latest.imageIds.isEmpty()) throw McpToolFailure("没有最终图片")
                    save(latest.copy(state = McpImageJob.SUCCEEDED, message = "已保存到 PocketNAI 画廊，可取回图片"))
                }
            }
        } catch (_: Exception) {
            mutex.withLock {
                val latest = read(id)
                save(if (changedQuote != null) latest.copy(state = McpImageJob.PENDING, quote = changedQuote!!,
                    message = "发送前报价已变化，图片请求未发送；请重新核对确认")
                else latest.copy(state = McpImageJob.UNCERTAIN, message = "任务未完成，请核对画廊与费用；没有重试"))
            }
        } finally {
            _state.update { it.copy(executing = false) }
            mutex.withLock { if (read(id).state != McpImageJob.PENDING) plans.remove(id) }
            if (_state.value.running && _state.value.directGeneration) {
                _state.value.jobs.lastOrNull { it.state == McpImageJob.PENDING }?.let { scope.launch { approveNow(it.id, true) } }
            }
        }
    }
    private suspend fun cancel(id: String): McpImageJob = mutex.withLock {
        val job = read(id)
        if (job.state == McpImageJob.RUNNING) throw McpToolFailure("请求可能已发送，不能取消；请等待并核对结果，不要重试")
        if (job.state != McpImageJob.PENDING) return job
        job.copy(state = McpImageJob.CANCELLED, message = "已取消，未发送").also { save(it); plans.remove(id) }
    }
    private fun jobId(args: JsonObject) = (args["job_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: throw McpToolFailure("任务编号无效")
    private suspend fun read(id: String) = withContext(Dispatchers.IO) { store.read(id) } ?: throw McpToolFailure("没有此任务；不会自动重新提交")
    private suspend fun save(job: McpImageJob) {
        withContext(Dispatchers.IO) { store.write(job) }
        _state.update {
            val all = (it.jobs.filterNot { old -> old.id == job.id } + job).sortedByDescending { row -> row.createdAt }
            val live = all.filter { row -> row.state in setOf(McpImageJob.PENDING, McpImageJob.RUNNING) }
            it.copy(jobs = (live + all.filterNot { row -> row in live }.take(40)).sortedByDescending { row -> row.createdAt })
        }
    }
    private fun McpImageJob.publicInfo() = buildJsonObject {
        put("job_id", id); put("state", state); put("provider", provider); put("parameters", description)
        put("quote", quote); put("message", message); put("has_image", imageIds.isNotEmpty())
    }
    companion object { const val PORT = 47761; const val URL = "http://127.0.0.1:47761/mcp" }
}
