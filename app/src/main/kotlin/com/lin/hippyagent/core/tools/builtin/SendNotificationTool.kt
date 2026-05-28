package com.lin.hippyagent.core.tools.builtin

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.lin.hippyagent.core.notification.NotificationOverlayService
import com.lin.hippyagent.core.inbox.InboxStore
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import java.util.concurrent.atomic.AtomicInteger

class SendNotificationTool(
    private val context: Context,
    private val inboxStore: InboxStore? = null
) : Tool() {
    private val notificationIdCounter = AtomicInteger(1000)

    override val definition = ToolDefinition(
        name = "send_notification",
        description = "向用户发送通知。支持系统通知栏和App内提示两种方式。",
        parameters = mapOf(
            "title" to ToolParameter(
                name = "title",
                type = "string",
                description = "通知标题",
                required = true
            ),
            "message" to ToolParameter(
                name = "message",
                type = "string",
                description = "通知内容",
                required = true
            ),
            "type" to ToolParameter(
                name = "type",
                type = "string",
                description = "通知类型: system(系统通知栏) 或 toast(App内提示)",
                required = false,
                defaultValue = "system"
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val callId = args["callId"] as? String ?: ""
        val title = getRequiredArgument(args, "title")
        val message = getRequiredArgument(args, "message")
        val type = getOptionalArgument(args, "type", "system") ?: "system"

        val result = when (type) {
            "system" -> sendSystemNotification(title, message, callId)
            "toast" -> sendToastNotification(title, message, callId)
            else -> ToolResult(
                callId = callId,
                success = false,
                error = "Unknown notification type: $type. Supported: system, toast"
            )
        }

        if (result.success) {
            inboxStore?.appendEvent(
                agentId = ctx.agentId ?: "default",
                sourceType = "notification",
                sourceId = callId,
                eventType = type,
                status = "success",
                severity = "info",
                title = title,
                body = message,
                payload = "{}"
            )
        }

        return result
    }

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        return execute(ToolContext(), arguments)
    }

    private fun sendSystemNotification(title: String, message: String, callId: String): ToolResult {
        return try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channelId = "agent_notification"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "智能体通知",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationManager.createNotificationChannel(channel)
            }

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationIdCounter.getAndIncrement(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(notificationIdCounter.get(), notification)

            NotificationOverlayService.show(context, title, message)

            ToolResult(
                callId = callId,
                success = true,
                forLLM = "系统通知已发送: $title",
                forUser = "已发送系统通知: $title",
                silent = true
            )
        } catch (e: Exception) {
            ToolResult(
                callId = callId,
                success = false,
                error = "发送系统通知失败: ${e.message}"
            )
        }
    }

    private fun sendToastNotification(title: String, message: String, callId: String): ToolResult {
        return ToolResult(
            callId = callId,
            success = true,
            forLLM = "App内提示已发送: $title - $message",
            forUser = "$title: $message"
        )
    }
}
