package net.pocketnai.testing

import net.pocketnai.domain.task.BackgroundTasks
import net.pocketnai.domain.task.BackgroundTaskUnavailable

/** Explicit offline task host for tests that do not exercise Android's service. */
class FakeBackgroundTasks : BackgroundTasks {
    var failStart = false
    var opened = 0
    var closed = 0
    override fun begin(kind: BackgroundTasks.Kind, onServiceStopped: () -> Unit): BackgroundTasks.Task {
        if (failStart) throw BackgroundTaskUnavailable()
        opened++
        return object : BackgroundTasks.Task {
            var active = true
            override suspend fun awaitReady() = ensureRunning()
            override fun ensureRunning() { if (!active) throw BackgroundTaskUnavailable() }
            override fun waitingForUser(waiting: Boolean) = Unit
            override fun close() { if (active) { active = false; closed++ } }
        }
    }
}
