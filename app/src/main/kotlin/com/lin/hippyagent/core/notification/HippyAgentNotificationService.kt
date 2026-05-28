package com.lin.hippyagent.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lin.hippyagent.R
import timber.log.Timber

class HippyAgentNotificationService(
    private val context: Context
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val settingsManager = NotificationSettingsManager(context)

    init {
        createNotificationChannels()
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = listOf(
                NotificationChannel(
                    CHANNEL_HEARTBEAT,
                    "心跳提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "定时心跳状态通知"
                },
                NotificationChannel(
                    CHANNEL_TASK_COMPLETE,
                    "任务完成",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Agent 任务完成通知"
                },
                NotificationChannel(
                    CHANNEL_ERROR,
                    "错误告警",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Agent 运行错误通知"
                },
                NotificationChannel(
                    CHANNEL_AGENT_MESSAGE,
                    "智能体消息",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "智能体新消息提醒"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 200, 100, 200)
                    setShowBadge(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                    setSound(
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                        android.app.Notification.AUDIO_ATTRIBUTES_DEFAULT
                    )
                },
                NotificationChannel(
                    CHANNEL_PERMISSION_REQUEST,
                    "权限请求",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "后台无障碍权限请求通知"
                }
            )

            channels.forEach { channel ->
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 发送心跳提醒
     */
    fun sendHeartbeatNotification(title: String, message: String) {
        if (!settingsManager.shouldNotify(NotificationType.HEARTBEAT)) {
            Timber.d("Heartbeat notification skipped (quiet hours or disabled)")
            return
        }

        sendNotification(
            channelId = CHANNEL_HEARTBEAT,
            title = title,
            message = message,
            notificationId = NOTIFICATION_HEARTBEAT
        )
    }

    /**
     * 发送任务完成通知
     */
    fun sendTaskCompleteNotification(title: String, message: String) {
        if (!settingsManager.shouldNotify(NotificationType.TASK_COMPLETE)) {
            Timber.d("Task complete notification skipped (quiet hours or disabled)")
            return
        }

        sendNotification(
            channelId = CHANNEL_TASK_COMPLETE,
            title = title,
            message = message,
            notificationId = NOTIFICATION_TASK_COMPLETE
        )
    }

    /**
     * 发送错误告警
     */
    fun sendErrorNotification(title: String, message: String) {
        if (!settingsManager.shouldNotify(NotificationType.ERROR)) {
            Timber.d("Error notification skipped (quiet hours or disabled)")
            return
        }

        sendNotification(
            channelId = CHANNEL_ERROR,
            title = title,
            message = message,
            notificationId = NOTIFICATION_ERROR
        )
    }

    /**
     * 发送 Mission 进度通知
     */
    fun sendMissionProgressNotification(
        taskId: String,
        taskName: String,
        current: Int,
        total: Int,
        status: String
    ) {
        val progress = if (total > 0) (current * 100 / total) else 0
        val notificationId = NOTIFICATION_MISSION_PROGRESS + taskId.hashCode()

        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_TASK_COMPLETE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Mission: $taskName")
                .setContentText("$status ($current/$total)")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setProgress(total, current, total == 0)
                .build()

            notificationManager.notify(notificationId, notification)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send mission progress notification")
        }
    }

    /**
     * 发送 Mission 完成通知
     */
    fun sendMissionCompleteNotification(taskId: String, taskName: String, success: Boolean) {
        val notificationId = NOTIFICATION_MISSION_PROGRESS + taskId.hashCode()

        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_TASK_COMPLETE)
                .setSmallIcon(if (success) android.R.drawable.ic_dialog_info else android.R.drawable.ic_dialog_alert)
                .setContentTitle("Mission 完成")
                .setContentText("$taskName - ${if (success) "成功" else "失败"}")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(notificationId, notification)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send mission complete notification")
        }
    }

    /**
     * 发送智能体消息通知
     * 通知标题格式：智能体名-会话名
     */
    fun sendAgentMessageNotification(agentName: String, sessionName: String = "", message: String, sessionId: String, agentId: String? = null) {
        if (!settingsManager.shouldNotify(NotificationType.AGENT_MESSAGE)) {
            Timber.d("Agent message notification skipped (quiet hours or disabled)")
            return
        }

        if (ForegroundSessionTracker.isForeground(sessionId)) {
            Timber.d("Agent message notification skipped (session $sessionId is in foreground)")
            return
        }

        val notificationTitle = if (sessionName.isNotEmpty() && sessionName != agentName) {
            "$agentName-$sessionName"
        } else {
            agentName
        }

        val notificationId = NOTIFICATION_AGENT_MESSAGE + sessionId.hashCode() + (agentId?.hashCode() ?: 0)
        val truncated = if (message.length > 100) message.take(100) + "..." else message

        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                putExtra("deep_link_session_id", sessionId)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val messagingStyle = NotificationCompat.MessagingStyle(
                    androidx.core.app.Person.Builder().setName("我").build()
                )
                .addMessage(NotificationCompat.MessagingStyle.Message(
                    truncated,
                    System.currentTimeMillis(),
                    androidx.core.app.Person.Builder().setName(agentName).build()
                ))
                .setConversationTitle(notificationTitle)

            val notification = NotificationCompat.Builder(context, CHANNEL_AGENT_MESSAGE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(messagingStyle)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(notificationId, notification)
            Timber.i("Agent message notification sent: $agentName for session $sessionId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send agent message notification")
        }
    }

    /**
     * 发送工具审批请求通知（后台时使用，带操作按钮）
     */
    fun sendToolApprovalNotification(
        requestId: String,
        toolName: String,
        agentId: String,
        severity: String,
        findingsSummary: String
    ) {
        val requestIdHash = requestId.hashCode()

        val allowOnceIntent = createToolApprovalActionIntent(requestId, "allow_once")
        val alwaysAllowIntent = createToolApprovalActionIntent(requestId, "allow_always")
        val denyIntent = createToolApprovalActionIntent(requestId, "deny_once")
        val denyAlwaysIntent = createToolApprovalActionIntent(requestId, "deny_always")

        val severityLabel = when (severity) {
            "critical" -> "🔴 严重"
            "high" -> "🟠 高"
            else -> "🟡 中"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_PERMISSION_REQUEST)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$severityLabel 工具审批: $toolName")
            .setContentText(findingsSummary.ifBlank { "智能体 $agentId 请求执行 $toolName" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_view, "允许本次", allowOnceIntent)
            .addAction(android.R.drawable.ic_menu_view, "始终允许", alwaysAllowIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒绝", denyIntent)
            .addAction(android.R.drawable.ic_delete, "不再允许", denyAlwaysIntent)
            .build()

        notificationManager.notify(NOTIFICATION_PERMISSION_REQUEST + requestIdHash, notification)
        Timber.i("Tool approval notification sent for $requestId tool=$toolName")
    }

    /**
     * 创建工具审批操作 PendingIntent
     */
    private fun createToolApprovalActionIntent(requestId: String, action: String): PendingIntent {
        val intent = Intent(context, com.lin.hippyagent.core.security.ToolApprovalReceiver::class.java).apply {
            setAction("com.lin.hippyagent.TOOL_APPROVAL_ACTION")
            putExtra("request_id", requestId)
            putExtra("action", action)
        }
        return PendingIntent.getBroadcast(
            context,
            (requestId + action).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 发送权限请求通知（后台时使用，带操作按钮）
     */
    fun sendPermissionRequestNotification(request: com.lin.hippyagent.core.accessibility.ApprovalRequest) {
        val requestIdHash = request.requestId.hashCode()

        val allowOnceIntent = createPermissionActionIntent(request.requestId, "allow_once", false)
        val alwaysAllowIntent = createPermissionActionIntent(request.requestId, "always_allow", true)
        val denyIntent = createPermissionActionIntent(request.requestId, "deny", false)

        val notification = NotificationCompat.Builder(context, CHANNEL_PERMISSION_REQUEST)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("权限请求")
            .setContentText("智能体请求执行操作：${request.action}" + (request.target?.let { " → $it" } ?: ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_view, "允许本次", allowOnceIntent)
            .addAction(android.R.drawable.ic_menu_view, "一直允许", alwaysAllowIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒绝", denyIntent)
            .build()

        notificationManager.notify(NOTIFICATION_PERMISSION_REQUEST + requestIdHash, notification)
        Timber.i("Permission request notification sent for ${request.requestId}")
    }

    /**
     * 创建权限操作 PendingIntent
     */
    private fun createPermissionActionIntent(requestId: String, action: String, alwaysAllow: Boolean): PendingIntent {
        val intent = Intent(context, com.lin.hippyagent.core.accessibility.PermissionActionReceiver::class.java).apply {
            setAction("com.lin.hippyagent.PERMISSION_ACTION")
            putExtra("request_id", requestId)
            putExtra("action", action)
            putExtra("always_allow", alwaysAllow)
        }
        return PendingIntent.getBroadcast(
            context,
            (requestId + action).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 发送通知
     */
    private fun sendNotification(
        channelId: String,
        title: String,
        message: String,
        notificationId: Int
    ) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(notificationId, notification)
            Timber.i("Notification sent: $title")
        } catch (e: Exception) {
            Timber.e(e, "Failed to send notification")
        }
    }

    /**
     * 取消所有通知
     */
    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }

    /**
     * 取消特定通知
     */
    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    companion object {
        const val CHANNEL_HEARTBEAT = "heartbeat"
        const val CHANNEL_TASK_COMPLETE = "task_complete"
        const val CHANNEL_ERROR = "error_alert"
        const val CHANNEL_AGENT_MESSAGE = "agent_message_v2"
        const val CHANNEL_PERMISSION_REQUEST = "permission_request"

        private const val NOTIFICATION_HEARTBEAT = 1001
        private const val NOTIFICATION_TASK_COMPLETE = 1002
        private const val NOTIFICATION_ERROR = 1003
        const val NOTIFICATION_AGENT_MESSAGE = 2000
        private const val NOTIFICATION_MISSION_PROGRESS = 3000
        const val NOTIFICATION_PERMISSION_REQUEST = 4000
    }
}

