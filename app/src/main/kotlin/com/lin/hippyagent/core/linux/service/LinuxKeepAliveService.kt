package com.lin.hippyagent.core.linux.service

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
import com.lin.hippyagent.core.linux.LinuxManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Linux 保活服务：在后台运行时保持 Linux 环境活跃。
 */
class LinuxKeepAliveService : Service() {

    private val linuxManager: LinuxManager by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.d("LinuxKeepAliveService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification("Linux environment starting..."))
                initializeLinux()
            }
            ACTION_STOP -> {
                stopSelf()
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification("Linux environment active"))
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Timber.d("LinuxKeepAliveService destroyed")
    }

    private fun initializeLinux() {
        serviceScope.launch {
            try {
                linuxManager.initialize()
                updateNotification("Linux environment ready")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize Linux environment")
                updateNotification("Linux environment failed: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Linux Environment",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Linux environment running in background"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LinuxKeepAliveService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hippy Linux")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止 Linux", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "linux_environment"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.lin.hippyagent.linux.START"
        private const val ACTION_STOP = "com.lin.hippyagent.linux.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LinuxKeepAliveService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LinuxKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

