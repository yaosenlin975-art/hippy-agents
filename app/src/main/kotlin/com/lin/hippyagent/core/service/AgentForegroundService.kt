package com.lin.hippyagent.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import timber.log.Timber

class AgentForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "agent_foreground_channel"
        const val CHANNEL_NAME = "Agent 运行服务"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.lin.hippyagent.action.START_AGENT"
        const val ACTION_STOP = "com.lin.hippyagent.action.STOP_AGENT"
        const val ACTION_REFRESH_NOTIFICATION = "com.lin.hippyagent.action.REFRESH_NOTIFICATION"
        const val EXTRA_AGENT_ID = "agent_id"

        /** 前台服务运行状态。onCreate 置 true，onDestroy 置 false。供 TileService / Widget 查询。 */
        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set

        fun start(context: Context, agentId: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_AGENT_ID, agentId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** 切换运行状态：运行中→停止，停止→启动（用 default agentId） */
        fun toggle(context: Context, agentId: String = "default") {
            if (isRunning) stop(context) else start(context, agentId)
        }

        /** 供外部（Tile/Widget）调用的静态刷新入口：通过 startForegroundService 触发 onStartCommand */
        fun refreshNotification(context: Context) {
            if (!isRunning) return
            runCatching {
                val intent = Intent(context, AgentForegroundService::class.java).apply {
                    action = ACTION_REFRESH_NOTIFICATION
                }
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    private var runningAgentId: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
        Timber.i("AgentForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val agentId = intent.getStringExtra(EXTRA_AGENT_ID) ?: return START_NOT_STICKY
                runningAgentId = agentId
                // 同步路径：用 fallback 立即 startForeground（避免主线程阻塞 / ANR）
                startForeground(NOTIFICATION_ID, buildSimpleFallbackNotification(agentId))
                // 异步刷新为 enhanced 通知（含 model / cron 多行）
                launchRefreshEnhanced(agentId)
                Timber.i("Agent foreground service started for: $agentId")
            }
            ACTION_STOP -> {
                stopSelf()
                Timber.i("Agent foreground service stopped")
                return START_NOT_STICKY
            }
            ACTION_REFRESH_NOTIFICATION -> {
                val agentId = runningAgentId ?: "default"
                // 维持前台状态（避免 race：refreshNotification 期间服务被降级）
                startForeground(NOTIFICATION_ID, buildSimpleFallbackNotification(agentId))
                launchRefreshEnhanced(agentId)
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runningAgentId = null
        isRunning = false
        super.onDestroy()
        Timber.i("AgentForegroundService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Agent 后台运行通知"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 异步刷新为 enhanced 通知（含 model / cron 多行）。
     *
     * 协程合规（coding.md）：复用 Koin 注册的 applicationScope，不自建 CoroutineScope；
     * buildEnhancedForegroundNotification 内部已改为 suspend，主线程不阻塞。
     */
    private fun launchRefreshEnhanced(agentId: String) {
        runCatching {
            val appScope = GlobalContext.get().get<CoroutineScope>(named("applicationScope"))
            appScope.launch {
                runCatching {
                    val ns = GlobalContext.get().get<com.lin.hippyagent.core.notification.HippyAgentNotificationService>()
                    val notification = ns.buildEnhancedForegroundNotification(this@AgentForegroundService, agentId)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, notification)
                }.onFailure { Timber.w(it, "launchRefreshEnhanced failed") }
            }
        }.onFailure { Timber.w(it, "applicationScope not resolved") }
    }

    /** fallback 简化通知：仅显示运行状态，不查 ModelManager（避免冷启动阻塞） */
    private fun buildSimpleFallbackNotification(agentId: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_fallback_title))
            .setContentText(getString(R.string.notification_fallback_content, agentId))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_fallback_stop),
                stopPendingIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * 主动刷新前台通知（供 Tile/Widget 触发状态变化后调用）。
     *
     * 协程合规：fire-and-forget，由 applicationScope 异步刷新 enhanced；
     * 主线程立即返回，避免阻塞调用方（Tile/Widget onUpdate 在主线程）。
     */
    fun refreshNotification() {
        if (!isRunning) return
        val agentId = runningAgentId ?: "default"
        launchRefreshEnhanced(agentId)
    }
}

