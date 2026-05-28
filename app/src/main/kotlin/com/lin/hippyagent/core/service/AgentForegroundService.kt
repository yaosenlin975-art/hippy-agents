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
import timber.log.Timber

class AgentForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "agent_foreground_channel"
        const val CHANNEL_NAME = "Agent 运行服务"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.lin.hippyagent.action.START_AGENT"
        const val ACTION_STOP = "com.lin.hippyagent.action.STOP_AGENT"
        const val EXTRA_AGENT_ID = "agent_id"

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
    }

    private var runningAgentId: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.i("AgentForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val agentId = intent.getStringExtra(EXTRA_AGENT_ID) ?: return START_NOT_STICKY
                runningAgentId = agentId
                startForeground(NOTIFICATION_ID, buildNotification(agentId))
                Timber.i("Agent foreground service started for: $agentId")
            }
            ACTION_STOP -> {
                stopSelf()
                Timber.i("Agent foreground service stopped")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runningAgentId = null
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

    private fun buildNotification(agentId: String): Notification {
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
            .setContentTitle("Hippy")
            .setContentText("Agent $agentId 正在运行")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                stopPendingIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

