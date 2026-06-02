package com.lin.hippyagent.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lin.hippyagent.R
import com.lin.hippyagent.ui.MainActivity
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

class HippyAgentNotificationService(
    private val context: Context
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val settingsManager = NotificationSettingsManager(context)
    private val activeSessionNotifications = ConcurrentHashMap<String, MutableSet<Int>>()

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
                    context.getString(R.string.notification_channel_heartbeat),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_channel_heartbeat_desc)
                },
                NotificationChannel(
                    CHANNEL_TASK_COMPLETE,
                    context.getString(R.string.notification_channel_task_complete),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_task_complete_desc)
                },
                NotificationChannel(
                    CHANNEL_ERROR,
                    context.getString(R.string.notification_channel_error),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_error_desc)
                },
                NotificationChannel(
                    CHANNEL_AGENT_MESSAGE,
                    context.getString(R.string.notification_channel_agent_message),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_agent_message_desc)
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
                    context.getString(R.string.notification_channel_permission_request),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_permission_request_desc)
                }
            )

            channels.forEach { channel ->
                notificationManager.createNotificationChannel(channel)
            }

            notificationManager.deleteNotificationChannel("agent_message")
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
                .setContentText("$taskName - ${if (success) context.getString(R.string.notification_mission_success) else context.getString(R.string.notification_mission_fail)}")
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
     * 触发策略（用户已选 三合一）：
     *  - 后台 / 锁屏   → 系统 Heads-up + FullScreenIntent(锁屏时拉起 LockScreenMessageActivity)
     *  - App 前台但不在此会话 → InAppMessageBus 气泡(同时仍发送通知以便状态栏)
     *  - App 在此会话   → 跳过
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

        if (ForegroundSessionTracker.isAppForegroundFlow.value) {
            InAppMessageBus.emit(
                InAppMessageBus.InAppMessage(
                    agentName = agentName,
                    sessionName = sessionName,
                    message = message,
                    sessionId = sessionId,
                    agentId = agentId
                )
            )
        }

        val notificationId = NOTIFICATION_AGENT_MESSAGE + sessionId.hashCode() + (agentId?.hashCode() ?: 0)
        val truncated = if (message.length > 100) message.take(100) + "..." else message

        try {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("deep_link_session_id", sessionId)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val fullScreenIntent = Intent(context, LockScreenMessageActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(LockScreenMessageActivity.EXTRA_AGENT_NAME, agentName)
                putExtra(LockScreenMessageActivity.EXTRA_SESSION_NAME, sessionName)
                putExtra(LockScreenMessageActivity.EXTRA_MESSAGE, message)
                putExtra(LockScreenMessageActivity.EXTRA_SESSION_ID, sessionId)
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                notificationId xor 0x1,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val canUseFullScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.canUseFullScreenIntent()
            } else true

            val messagingStyle = NotificationCompat.MessagingStyle(
                    androidx.core.app.Person.Builder().setName("我").build()
                )
                .addMessage(NotificationCompat.MessagingStyle.Message(
                    truncated,
                    System.currentTimeMillis(),
                    androidx.core.app.Person.Builder().setName(agentName).build()
                ))
                .setConversationTitle(notificationTitle)

            val builder = NotificationCompat.Builder(context, CHANNEL_AGENT_MESSAGE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(messagingStyle)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            if (canUseFullScreen) {
                builder.setFullScreenIntent(fullScreenPendingIntent, true)
            }

            notificationManager.notify(notificationId, builder.build())
            activeSessionNotifications.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }.add(notificationId)
            Timber.i("Agent message notification sent: $agentName for session $sessionId (fullScreen=$canUseFullScreen)")
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
            "critical" -> context.getString(R.string.notification_severity_critical)
            "high" -> context.getString(R.string.notification_severity_high)
            else -> context.getString(R.string.notification_severity_medium)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_PERMISSION_REQUEST)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notification_tool_approval_title, severityLabel, toolName))
            .setContentText(findingsSummary.ifBlank { context.getString(R.string.notification_tool_approval_desc, agentId, toolName) })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_view, context.getString(R.string.notification_allow_once), allowOnceIntent)
            .addAction(android.R.drawable.ic_menu_view, context.getString(R.string.notification_always_allow), alwaysAllowIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notification_deny), denyIntent)
            .addAction(android.R.drawable.ic_delete, context.getString(R.string.notification_deny_always), denyAlwaysIntent)
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
            .setContentTitle(context.getString(R.string.notification_permission_request_title))
            .setContentText(context.getString(R.string.notification_permission_request_desc, request.action, request.target?.let { " → $it" } ?: ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_view, context.getString(R.string.notification_allow_once), allowOnceIntent)
            .addAction(android.R.drawable.ic_menu_view, context.getString(R.string.notification_always_allow_permission), alwaysAllowIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notification_deny), denyIntent)
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

    fun cancelSessionNotifications(sessionId: String) {
        activeSessionNotifications.remove(sessionId)?.forEach { notificationId ->
            notificationManager.cancel(notificationId)
        }
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

