package com.lin.hippyagent.core.linux.tools

import android.content.Context
import android.os.Environment
import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import timber.log.Timber
import java.io.File

/**
 * 文件传输工具：在 Android 宿主和 Linux 容器之间传输文件
 */
class FileTransferTool(
    private val context: Context,
    private val linuxManager: LinuxManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "file_transfer",
        description = "Transfer files between Android host and Linux container",
        parameters = mapOf(
            "action" to ToolParameter(
                name = "action",
                type = "string",
                description = "Action: push (host to container), pull (container to host), list (list shared directory)",
                required = true
            ),
            "source" to ToolParameter(
                name = "source",
                type = "string",
                description = "Source file path",
                required = false
            ),
            "destination" to ToolParameter(
                name = "destination",
                type = "string",
                description = "Destination file path",
                required = false
            )
        ),
        requiredPermissions = listOf("FILE_TRANSFER"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val action = getRequiredArgument(arguments, "action")
        val source = getOptionalArgument(arguments, "source")
        val destination = getOptionalArgument(arguments, "destination")

        if (!linuxManager.isReady.value) {
            return ToolResult(callId, false, error = "Linux environment not ready")
        }

        return try {
            when (action) {
                "push" -> pushFile(source, destination, callId)
                "pull" -> pullFile(source, destination, callId)
                "list" -> listSharedDirectory(callId)
                else -> ToolResult(callId, false, error = "Unknown action: $action. Use push/pull/list.")
            }
        } catch (e: Exception) {
            Timber.e(e, "File transfer failed")
            ToolResult(callId, false, error = "File transfer failed: ${e.message}")
        }
    }

    /**
     * 推送文件到容器
     */
    private suspend fun pushFile(source: String?, destination: String?, callId: String): ToolResult {
        if (source.isNullOrBlank()) {
            return ToolResult(callId, false, error = "Source path is required for push action")
        }

        val sourceFile = File(source)
        if (!sourceFile.exists()) {
            return ToolResult(callId, false, error = "Source file not found: $source")
        }

        val destPath = destination ?: "/mnt/shared/${sourceFile.name}"
        val sharedDir = linuxManager.getSharedPath()
        val destFile = File(sharedDir, destPath.removePrefix("/"))

        // 确保目标目录存在
        destFile.parentFile?.mkdirs()

        // 复制文件
        sourceFile.copyTo(destFile, overwrite = true)

        Timber.d("File pushed: $source -> $destPath")
        return ToolResult(callId, true, output = "File pushed successfully: $source -> $destPath")
    }

    /**
     * 从容器拉取文件
     */
    private suspend fun pullFile(source: String?, destination: String?, callId: String): ToolResult {
        if (source.isNullOrBlank()) {
            return ToolResult(callId, false, error = "Source path is required for pull action")
        }

        val sharedDir = linuxManager.getSharedPath()
        val sourceFile = File(sharedDir, source.removePrefix("/"))

        if (!sourceFile.exists()) {
            return ToolResult(callId, false, error = "Source file not found in container: $source")
        }

        val destPath = destination ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sourceFile.name).absolutePath
        val destFile = File(destPath)

        // 确保目标目录存在
        destFile.parentFile?.mkdirs()

        // 复制文件
        sourceFile.copyTo(destFile, overwrite = true)

        Timber.d("File pulled: $source -> $destPath")
        return ToolResult(callId, true, output = "File pulled successfully: $source -> $destPath")
    }

    /**
     * 列出共享目录内容
     */
    private fun listSharedDirectory(callId: String): ToolResult {
        val sharedDirPath = linuxManager.getSharedPath()
        val sharedDir = java.io.File(sharedDirPath)
        if (!sharedDir.exists()) {
            return ToolResult(callId, false, error = "Shared directory not found")
        }

        val files = sharedDir.listFiles()
        if (files.isNullOrEmpty()) {
            return ToolResult(callId, true, output = "Shared directory is empty")
        }

        val sb = StringBuilder("# Shared Directory Contents\n\n")
        files.sortedBy { it.name }.forEach { file ->
            val type = if (file.isDirectory) "📁" else "📄"
            val size = if (file.isFile) formatFileSize(file.length()) else ""
            sb.appendLine("$type ${file.name} $size")
        }

        return ToolResult(callId, true, output = sb.toString())
    }

    /**
     * 格式化文件大小
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}

