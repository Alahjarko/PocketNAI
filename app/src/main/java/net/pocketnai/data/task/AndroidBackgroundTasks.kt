package net.pocketnai.data.task

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import net.pocketnai.domain.task.BackgroundTasks
import net.pocketnai.domain.task.BackgroundTaskUnavailable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One process-wide service for independently owned image/chat tasks. No request data is passed to Android. */
class AndroidBackgroundTasks(context: Context) : BackgroundTasks {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    internal data class Session(val id: String, val ready: CompletableDeferred<Unit> = CompletableDeferred())
    internal data class Status(val images: Int = 0, val chats: Int = 0, val needsCpu: Boolean = false) {
        val count get() = images + chats
    }
    internal var session: Session? = null
        private set
    private val tasks = linkedMapOf<String, Ticket>()
    private val _status = MutableStateFlow(Status())
    internal val status = _status.asStateFlow()

    override fun begin(kind: BackgroundTasks.Kind, onServiceStopped: () -> Unit): BackgroundTasks.Task {
        check(Looper.myLooper() == Looper.getMainLooper())
        val startService = session == null
        val current = session ?: Session(UUID.randomUUID().toString()).also { session = it }
        val ticket = Ticket(UUID.randomUUID().toString(), current, kind, onServiceStopped)
        tasks[ticket.id] = ticket
        publish()
        if (startService) try {
            ContextCompat.startForegroundService(app, Intent(app, GenerationForegroundService::class.java))
        } catch (_: RuntimeException) {
            serviceStopped(current.id)
            throw BackgroundTaskUnavailable()
        }
        return ticket
    }

    internal fun ready(id: String) { session?.takeIf { it.id == id }?.ready?.complete(Unit) }

    internal fun endIfIdle(id: String): Boolean {
        val current = session?.takeIf { it.id == id && tasks.isEmpty() } ?: return false
        session = null
        current.ready.completeExceptionally(BackgroundTaskUnavailable())
        return true
    }

    internal fun serviceStopped(id: String) {
        val current = session?.takeIf { it.id == id } ?: return
        session = null
        val stopped = tasks.values.toList()
        tasks.clear()
        stopped.forEach { it.active.set(false) }
        publish()
        current.ready.completeExceptionally(BackgroundTaskUnavailable())
        stopped.forEach { runCatching(it.onStopped) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun publish() {
        val live = tasks.values.filter { it.active.get() }
        _status.value = Status(live.count { it.kind != BackgroundTasks.Kind.CHAT },
            live.count { it.kind == BackgroundTasks.Kind.CHAT }, live.any { !it.waiting })
    }

    private inner class Ticket(val id: String, val owner: Session, val kind: BackgroundTasks.Kind,
        val onStopped: () -> Unit) : BackgroundTasks.Task {
        val active = AtomicBoolean(true)
        var waiting = false
        override suspend fun awaitReady() {
            try { withTimeout(6_000) { owner.ready.await() }; ensureRunning() }
            catch (_: TimeoutCancellationException) { close(); throw BackgroundTaskUnavailable() }
            catch (e: CancellationException) { close(); throw e }
            catch (_: Exception) { close(); throw BackgroundTaskUnavailable() }
        }
        override fun ensureRunning() { if (!active.get()) throw BackgroundTaskUnavailable() }
        override fun waitingForUser(waiting: Boolean) = onMain {
            if (active.get() && session?.id == owner.id) { this.waiting = waiting; publish() }
        }
        override fun close() {
            if (!active.getAndSet(false)) return
            onMain { if (session?.id == owner.id) { tasks.remove(id); publish() } }
        }
    }
}
