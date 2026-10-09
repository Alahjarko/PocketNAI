package net.pocketnai.data.artistlab

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import net.pocketnai.domain.artistlab.ArtistLabBackgroundExecution
import net.pocketnai.domain.artistlab.ArtistLabBackgroundUnavailable
import net.pocketnai.domain.artistlab.ArtistLabProgress
import java.util.UUID

/** 只持有应用 Context；服务不接收提示词、账号凭据或自动恢复指令。调用均在主线程。 */
class AndroidArtistLabBackgroundExecution(context: Context) : ArtistLabBackgroundExecution {
    private val app = context.applicationContext
    internal data class Session(
        val id: String,
        val progress: StateFlow<ArtistLabProgress>,
        val pause: () -> Unit,
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
    )
    internal var session: Session? = null
        private set

    override suspend fun start(progress: StateFlow<ArtistLabProgress>, pause: () -> Unit) {
        check(session == null)
        val pending = Session(UUID.randomUUID().toString(), progress, pause)
        session = pending
        try {
            ContextCompat.startForegroundService(app, Intent(app, ArtistLabForegroundService::class.java))
            withTimeout(6_000) { pending.ready.await() }
        } catch (_: Exception) {
            stop()
            throw ArtistLabBackgroundUnavailable()
        }
    }

    override fun stop() {
        session = null
        app.stopService(Intent(app, ArtistLabForegroundService::class.java))
    }

    internal fun serviceStopped(id: String) {
        val current = session?.takeIf { it.id == id } ?: return
        current.pause()
        current.ready.completeExceptionally(ArtistLabBackgroundUnavailable())
    }
}
