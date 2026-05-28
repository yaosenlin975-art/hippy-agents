package com.lin.hippyagent.core.linux.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import timber.log.Timber

/**
 * 剪贴板同步工具：在 Android 宿主和 Linux 容器之间同步剪贴板
 */
class ClipboardSyncTool(
    private val context: Context,
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "clipboard_sync",
        description = "Synchronize clipboard between Android host and Linux container",
        parameters = mapOf(
            "action" to ToolParameter(
                name = "action",
                type = "string",
                description = "Action: get (get clipboard content), set (set clipboard content), sync (sync container clipboard)",
                required = true
            ),
            "content" to ToolParameter(
                name = "content",
                type = "string",
                description = "Content to set to clipboard (required for set action)",
                required = false
            )
        ),
        requiredPermissions = listOf("CLIPBOARD_ACCESS"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val action = getRequiredArgument(arguments, "action")
        val content = getOptionalArgument(arguments, "content")

        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }

        return try {
            when (action) {
                "get" -> getClipboardContent(callId)
                "set" -> setClipboardContent(content, callId)
                "sync" -> syncContainerClipboard(callId)
                else -> ToolResult(callId, false, error = "Unknown action: $action. Use get/set/sync.")
            }
        } catch (e: Exception) {
            Timber.e(e, "Clipboard sync failed")
            ToolResult(callId, false, error = "Clipboard sync failed: ${e.message}")
        }
    }

    /**
     * 获取 Android 剪贴板内容
     */
    private fun getClipboardContent(callId: String): ToolResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip

        if (clip == null || clip.itemCount == 0) {
            return ToolResult(callId, true, output = "Clipboard is empty")
        }

        val item = clip.getItemAt(0)
        val text = item.text?.toString() ?: "No text in clipboard"

        return ToolResult(callId, true, output = text)
    }

    /**
     * 设置 Android 剪贴板内容
     */
    private fun setClipboardContent(content: String?, callId: String): ToolResult {
        if (content.isNullOrBlank()) {
            return ToolResult(callId, false, error = "Content is required for set action")
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Linux Clipboard", content)
        clipboard.setPrimaryClip(clip)

        Timber.d("Clipboard set: ${content.take(50)}...")
        return ToolResult(callId, true, output = "Clipboard content set successfully")
    }

    /**
     * 同步容器剪贴板到 Android
     * 通过读取容器中的剪贴板文件实现
     */
    private suspend fun syncContainerClipboard(callId: String): ToolResult {
        // 在容器中执行命令读取剪贴板文件
        val (exitCode, output) = linuxManager.exec("cat /tmp/clipboard.txt 2>/dev/null || echo ''")

        if (exitCode == 0 && output.isNotBlank()) {
            // 设置到 Android 剪贴板
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Container Clipboard", output.trim())
            clipboard.setPrimaryClip(clip)

            return ToolResult(callId, true, output = "Container clipboard synced to Android:\n${output.take(200)}")
        }

        return ToolResult(callId, true, output = "No clipboard content in container")
    }
}

