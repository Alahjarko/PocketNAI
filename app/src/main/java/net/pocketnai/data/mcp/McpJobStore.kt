package net.pocketnai.data.mcp

import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

@Serializable
data class McpImageJob(
    val id: String,
    val fingerprint: String,
    val createdAt: Long,
    val provider: String,
    val prompt: String,
    val negative: String = "",
    val description: String,
    val quote: String,
    val state: String = PENDING,
    val message: String = "请在 PocketNAI 确认生成",
    val imageIds: List<String> = emptyList(),
) {
    companion object {
        const val PENDING = "awaiting_confirmation"
        const val RUNNING = "generating"
        const val SUCCEEDED = "succeeded"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
        const val UNCERTAIN = "uncertain"
    }
}

/** Durable idempotency tombstones are never evicted; only recent jobs are projected into UI. */
class McpJobStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun file(id: String): File {
        require(Regex("[0-9a-f]{64}").matches(id)) { "任务编号无效" }
        return File(directory, "$id.json")
    }
    @Synchronized fun read(id: String): McpImageJob? {
        val f = file(id)
        if (!f.exists() && !File(f.path + ".bak").exists()) return null
        val bytes = AtomicFile(f).readFully()
        require(bytes.size <= 128 * 1024) { "任务记录无法读取" }
        return json.decodeFromString<McpImageJob>(bytes.toString(Charsets.UTF_8)).also { require(it.id == id) }
    }
    @Synchronized fun write(job: McpImageJob) {
        directory.mkdirs()
        val atomic = AtomicFile(file(job.id))
        val out = atomic.startWrite()
        try { out.write(json.encodeToString(job).toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    @Synchronized fun recover(): List<McpImageJob> {
        val recent = mutableListOf<McpImageJob>()
        directory.listFiles().orEmpty().mapNotNull { file ->
            Regex("([0-9a-f]{64})\\.json(?:\\.bak)?").matchEntire(file.name)?.groupValues?.get(1)
        }.distinct().forEach { id ->
            // Corrupt records remain reserved: read errors never become a new submission.
            val old = runCatching { read(id) }.getOrNull() ?: return@forEach
            val recovered = when (old.state) {
                McpImageJob.PENDING -> old.copy(state = McpImageJob.CANCELLED, message = "连接已结束，未发送；不会自动恢复")
                McpImageJob.RUNNING -> old.copy(state = McpImageJob.UNCERTAIN, message = "进程曾中断，请核对画廊与费用；没有重试")
                else -> old
            }
            if (recovered != old) write(recovered)
            recent += recovered
        }
        return recent.sortedByDescending { it.createdAt }.take(40)
    }
    companion object {
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
