package net.pocketnai.data.task

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import net.pocketnai.PocketNaiApplication
import net.pocketnai.ui.MainActivity

/** Keeps already-authorized work alive; never recreates, resumes or retries a request. */
class GenerationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val host get() = (application as PocketNaiApplication).container.backgroundTasks
    private var sessionId: String? = null
    private var updates: Job? = null
    private var startId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "生成与绘伴任务", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "PocketNAI:Generation").apply { setReferenceCounted(false) }
        scope.launch {
            while (isActive) {
                delay(WAKE_TIMEOUT / 2)
                if (sessionId == host.session?.id && host.status.value.needsCpu) wakeLock?.acquire(WAKE_TIMEOUT)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        this.startId = startId
        val session = host.session
        if (session == null) { stopSelfResult(startId); return START_NOT_STICKY }
        sessionId = session.id
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(host.status.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            updateWakeLock(host.status.value.needsCpu)
            updates?.cancel()
            updates = scope.launch {
                host.status.collect { status ->
                    if (host.session?.id != session.id) return@collect
                    if (status.count == 0 && host.endIfIdle(session.id)) {
                        updateWakeLock(false)
                        ServiceCompat.stopForeground(this@GenerationForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelfResult(this@GenerationForegroundService.startId)
                    } else {
                        updateWakeLock(status.needsCpu)
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
                    }
                }
            }
            host.ready(session.id)
        } catch (_: RuntimeException) {
            host.serviceStopped(session.id)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun updateWakeLock(needed: Boolean) {
        wakeLock?.let { if (needed && !it.isHeld) it.acquire(WAKE_TIMEOUT) else if (!needed && it.isHeld) it.release() }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        sessionId?.let(host::serviceStopped)
        updateWakeLock(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sessionId?.let(host::serviceStopped)
        scope.cancel()
        updateWakeLock(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(status: AndroidBackgroundTasks.Status): Notification {
        val open = PendingIntent.getActivity(this, 1402, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (!status.needsCpu) "等待你确认" else if (status.images > 0)
            "图片生成 ${status.images} 项" + if (status.chats > 0) " · 绘伴处理中" else ""
            else "绘伴正在处理"
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("PocketNAI 任务进行中").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(0, 0, status.needsCpu).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
    }

    companion object {
        internal const val NOTIFICATION_ID = 1402
        private const val CHANNEL = "generation_tasks"
        private const val WAKE_TIMEOUT = 10 * 60 * 1000L
    }
}
