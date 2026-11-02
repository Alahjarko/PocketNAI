package net.pocketnai.domain.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** FIFO across explicit submissions; the cooldown starts after the whole preceding task finishes. */
class SerialGenerationGate(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var finishedAt: Long? = null
    var epoch: Long = 0
        private set

    fun stopPending() { epoch++ }

    class Stopped : IllegalStateException("前一任务未完成，排队任务未发送，请核对后手动提交")

    suspend fun <T> run(approvedEpoch: Long = epoch, block: suspend () -> T): T = mutex.withLock {
        if (approvedEpoch != epoch) throw Stopped()
        finishedAt?.let { previous ->
            val remaining = 1_000L - (clockMillis() - previous)
            if (remaining > 0) wait(remaining)
        }
        try { block() } finally { finishedAt = clockMillis() }
    }
}
