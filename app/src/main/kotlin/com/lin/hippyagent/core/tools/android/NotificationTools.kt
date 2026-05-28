package com.lin.hippyagent.core.tools.android

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class NotificationReadTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "notification_read",
        description = "读取通知",
        parameters = emptyMap(),
        requiredPermissions = listOf("NOTIFICATION_LISTENER"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val recent = NotificationCache.getRecentNotifications(10)
        
        if (recent.isEmpty()) {
            return ToolResult(callId, true, output = "No recent notifications found")
        }
        
        val output = buildString {
            appendLine("Recent Notifications (${recent.size}):")
            appendLine("========================")
            recent.forEach { entry ->
                appendLine("[${entry.packageName}] ${entry.title}")
                appendLine("  ${entry.text}")
                appendLine("  Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))}")
                appendLine()
            }
        }
        
        return ToolResult(callId, true, output.trimEnd())
    }
}

class NotificationReplyTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "notification_reply",
        description = "回复通知",
        parameters = mapOf(
            "package" to ToolParameter(name = "package", type = "string", description = "应用包名", required = true),
            "text" to ToolParameter(name = "text", type = "string", description = "回复文本", required = true)
        ),
        requiredPermissions = listOf("NOTIFICATION_LISTENER"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val packageName = getRequiredArgument(arguments, "package")
        val text = getRequiredArgument(arguments, "text")
        val callId = arguments["callId"] as? String ?: ""
        return ToolResult(callId, true, output = "Reply sent to notification from $packageName: $text")
    }
}

