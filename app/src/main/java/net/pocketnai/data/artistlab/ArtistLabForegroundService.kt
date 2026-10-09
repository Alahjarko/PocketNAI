package net.pocketnai.data.artistlab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import net.pocketnai.PocketNaiApplication
import net.pocketnai.domain.artistlab.ArtistLabProgress
import net.pocketnai.ui.MainActivity

/** 保持已确认批次运行；服务被杀后不重启，不自动发送或恢复任何请求。 */
class ArtistLabForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val host get() = (application as PocketNaiApplication).container.artistLabBackgroundExecution
    private var sessionId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var progressJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val session = host.session
        if (session == null) { stopSelf(); return }
        sessionId = session.id
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "抽卡进度", NotificationManager.IMPORTANCE_LOW),
            )
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(session.progress.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "PocketNAI:ArtistLab",
            ).apply { setReferenceCounted(false); acquire(WAKE_TIMEOUT) }
            scope.launch {
                // 每次只持有有限时长，运行中定期续期；暂停/结束/服务停止即取消并释放。
                while (isActive) {
                    delay(WAKE_TIMEOUT / 2)
                    wakeLock?.acquire(WAKE_TIMEOUT)
                }
            }
            progressJob = scope.launch {
                session.progress.collect {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(it))
                }
            }
            session.ready.complete(Unit)
        } catch (_: Exception) {
            host.serviceStopped(session.id)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) host.session?.pause?.invoke()
        if (host.session == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 的 dataSync 后台时限到达时立即停止服务；当前已发送请求不重试。
        sessionId?.let(host::serviceStopped)
        stopSelf()
    }

    override fun onDestroy() {
        sessionId?.let(host::serviceStopped)
        progressJob?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(progress: ArtistLabProgress): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(if (progress.pausing) "抽卡正在暂停" else "抽卡进行中")
            .setContentText("本次完成 ${progress.completed} / ${progress.total}")
            .setProgress(progress.total, progress.completed, progress.total == 0)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "暂停", pauseIntent(this)).build()
    }

    companion object {
        const val ACTION_PAUSE = "net.pocketnai.artistlab.PAUSE"
        internal fun pauseIntent(context: Context): PendingIntent = PendingIntent.getService(context, 1,
            Intent(context, ArtistLabForegroundService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private const val CHANNEL = "artist_lab_progress"
        private const val NOTIFICATION_ID = 1401
        private const val WAKE_TIMEOUT = 10 * 60 * 1000L
    }
}
