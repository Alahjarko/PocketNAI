package net.pocketnai.domain.task

/** Protect a user-started operation before its first suspension or network request. */
interface BackgroundTasks {
    enum class Kind { NOVEL_AI_IMAGE, OPEN_AI_IMAGE, CHAT }
    fun begin(kind: Kind, onServiceStopped: () -> Unit): Task

    interface Task : AutoCloseable {
        suspend fun awaitReady()
        fun ensureRunning()
        fun waitingForUser(waiting: Boolean)
        override fun close()
    }
}

class BackgroundTaskUnavailable : IllegalStateException(MESSAGE) {
    companion object {
        const val MESSAGE = "后台任务未能继续，未自动重试；请核对画廊与对话后再操作。"
    }
}
