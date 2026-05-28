package com.lin.hippyagent.core.tools.android

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class MediaControlTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "media_control",
        description = "控制媒体播放",
        parameters = mapOf(
            "action" to ToolParameter(name = "action", type = "string", description = "操作: play/pause/next/previous/stop", required = true)
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val action = getRequiredArgument(arguments, "action")
        val callId = arguments["callId"] as? String ?: ""

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return ToolResult(callId, false, error = "Requires Android 5.0+")
        }

        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(android.content.ComponentName(context, MediaControlService::class.java))
            if (sessions.isEmpty()) {
                return ToolResult(callId, false, error = "No active media sessions")
            }

            val controller = sessions[0]
            when (action) {
                "play" -> controller.transportControls.play()
                "pause" -> controller.transportControls.pause()
                "next" -> controller.transportControls.skipToNext()
                "previous" -> controller.transportControls.skipToPrevious()
                "stop" -> controller.transportControls.stop()
                else -> return ToolResult(callId, false, error = "Unknown action: $action")
            }
            ToolResult(callId, true, output = "Media $action")
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Media control error: ${e.message}")
        }
    }
}

class MediaControlService : android.service.notification.NotificationListenerService() {
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {}
}

class SearchMediaTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "search_media",
        description = "搜索媒体文件",
        parameters = mapOf(
            "type" to ToolParameter(name = "type", type = "string", description = "类型: image/video/audio", required = true),
            "query" to ToolParameter(name = "query", type = "string", description = "搜索关键词", required = false)
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val type = getRequiredArgument(arguments, "type")
        val query = getOptionalArgument(arguments, "query")
        val callId = arguments["callId"] as? String ?: ""

        val uri = when (type) {
            "image" -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> return ToolResult(callId, false, error = "Unknown type: $type")
        }

        val projection = arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.DATA)
        val selection = if (query != null) "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" else null
        val selectionArgs = if (query != null) arrayOf("%$query%") else null

        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        val results = buildString {
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0)
                    val path = it.getString(1)
                    appendLine("$name ($path)")
                }
            }
        }
        return ToolResult(callId, true, results.trimEnd().ifEmpty { "No media found" })
    }
}

