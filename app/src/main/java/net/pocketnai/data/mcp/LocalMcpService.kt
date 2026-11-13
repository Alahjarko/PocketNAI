package net.pocketnai.data.mcp

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import net.pocketnai.ui.MainActivity

/** User-started, local-only session. No boot receiver, sticky restart or continuous idle wake lock. */
class LocalMcpService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager get() = (application as LocalMcpOwner).localMcpManager
    private var observing: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) manager.stop() }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "本机助手连接", NotificationManager.IMPORTANCE_LOW))
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "PocketNAI:LocalMcp").apply { setReferenceCounted(false) }
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { manager.stop(); return START_NOT_STICKY }
        if (intent == null || !manager.state.value.starting) { stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, ID, notification(manager.state.value),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            if (observing == null) observing = scope.launch {
                manager.state.collect { state ->
                    if (state.active > 0) { if (wake?.isHeld != true) wake?.acquire(6 * 60_000L) }
                    else if (wake?.isHeld == true) wake?.release()
                    if (!state.running && !state.starting && state.active == 0) {
                        ServiceCompat.stopForeground(this@LocalMcpService, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
                    } else getSystemService(NotificationManager::class.java).notify(ID, notification(state))
                }
            }
            scope.launch { manager.attach() }
            scope.launch { while (isActive) {
                delay(3 * 60_000L)
                if (manager.state.value.active > 0) wake?.acquire(6 * 60_000L)
                manager.expireIfIdle()
            } }
        } catch (_: RuntimeException) { manager.detached(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        manager.detached()
        runCatching { unregisterReceiver(screenOff) }
        if (wake?.isHeld == true) wake?.release()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification(state: LocalMcpManager.State): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(this, 1701, launch
            .putExtra(OPEN, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1702, Intent(this, LocalMcpService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val status = when {
            state.active > 0 && !state.running -> "连接已关闭，正在保存已提交的任务"
            state.active > 0 -> "正在生成图片"
            state.pending > 0 -> "${state.pending} 项任务等待你确认"
            state.starting -> "正在开启本机连接"
            else -> "等待本机助手调用"
        }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("PocketNAI 本机 MCP").setContentText(status).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "停止连接", stop).build()
    }
    companion object {
        const val OPEN = "open_local_mcp"
        private const val STOP = "net.pocketnai.STOP_LOCAL_MCP"
        private const val ID = 1701
        private const val CHANNEL = "local_mcp"
    }
}
