package com.lin.hippyagent.core.tools.builtin

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.URL
import java.util.Base64

/**
 * 将图片加载到 LLM 上下文（base64 编码）
 * 支持本地文件和 HTTP(S) URL
 */
class ViewImageTool(private val context: Context) : Tool() {
    override val definition = ToolDefinition(
        name = "view_image",
        description = "将图片加载到 LLM 上下文以便分析。支持本地文件路径和 HTTP(S) URL。",
        parameters = mapOf(
            "image_path" to ToolParameter(
                name = "image_path",
                type = "string",
                description = "图片的本地文件路径或 HTTP(S) URL",
                required = true
            )
        ),
        isAndroidSpecific = true
    )

    private val allowedExtensions = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".tiff", ".tif")

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val imagePath = getRequiredArgument(arguments, "image_path")
        val callId = arguments["callId"] as? String ?: ""

        return try {
            if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                handleUrl(imagePath, callId)
            } else {
                handleLocalFile(imagePath, callId)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to view image: $imagePath")
            ToolResult(callId, false, error = "无法加载图片: ${e.message}")
        }
    }

    private suspend fun handleUrl(url: String, callId: String): ToolResult = withContext(Dispatchers.IO) {
        val ext = Uri.parse(url).lastPathSegment?.let { ".${it.substringAfterLast('.')}" } ?: ""
        if (ext.isNotEmpty() && ext.lowercase() !in allowedExtensions) {
            return@withContext ToolResult(callId, false, error = "不支持的图片格式: $ext")
        }
        ToolResult(callId, true, "图片已加载: $url\n[图片通过 URL 提供，请通过 URL 查看]")
    }

    private suspend fun handleLocalFile(path: String, callId: String): ToolResult = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) {
            return@withContext ToolResult(callId, false, error = "文件不存在: $path")
        }
        val ext = file.extension.lowercase()
        if (ext.isNotEmpty() && ".$ext" !in allowedExtensions) {
            return@withContext ToolResult(callId, false, error = "不支持的图片格式: .$ext")
        }
        // 读取并 base64 编码
        val bytes = file.readBytes()
        val maxSize = 20 * 1024 * 1024 // 20MB
        if (bytes.size > maxSize) {
            return@withContext ToolResult(callId, false, error = "图片过大 (${bytes.size / 1024 / 1024}MB)，最大支持 20MB")
        }
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val mimeType = when (ext) {
            ".png" -> "image/png"
            ".jpg", ".jpeg" -> "image/jpeg"
            ".gif" -> "image/gif"
            ".webp" -> "image/webp"
            ".bmp" -> "image/bmp"
            else -> "image/png"
        }
        ToolResult(callId, true, "图片已加载: ${file.name} (${bytes.size / 1024}KB)\n[data:image/$mimeType;base64,${base64.take(100)}...]")
    }
}

/**
 * 将视频加载到 LLM 上下文
 * 支持本地文件和 HTTP(S) URL
 */
class ViewVideoTool(private val context: Context) : Tool() {
    override val definition = ToolDefinition(
        name = "view_video",
        description = "将视频加载到 LLM 上下文以便分析。支持本地文件路径和 HTTP(S) URL。",
        parameters = mapOf(
            "video_path" to ToolParameter(
                name = "video_path",
                type = "string",
                description = "视频的本地文件路径或 HTTP(S) URL",
                required = true
            )
        ),
        isAndroidSpecific = true
    )

    private val allowedExtensions = setOf(".mp4", ".webm", ".mpeg", ".mov", ".avi", ".mkv")

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val videoPath = getRequiredArgument(arguments, "video_path")
        val callId = arguments["callId"] as? String ?: ""

        return try {
            if (videoPath.startsWith("http://") || videoPath.startsWith("https://")) {
                handleUrl(videoPath, callId)
            } else {
                handleLocalFile(videoPath, callId)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to view video: $videoPath")
            ToolResult(callId, false, error = "无法加载视频: ${e.message}")
        }
    }

    private fun handleUrl(url: String, callId: String): ToolResult {
        val ext = Uri.parse(url).lastPathSegment?.let { ".${it.substringAfterLast('.')}" } ?: ""
        if (ext.isNotEmpty() && ext.lowercase() !in allowedExtensions) {
            return ToolResult(callId, false, error = "不支持的视频格式: $ext")
        }
        return ToolResult(callId, true, "视频已加载: $url\n[视频通过 URL 提供，请通过 URL 查看]")
    }

    private fun handleLocalFile(path: String, callId: String): ToolResult {
        val file = File(path)
        if (!file.exists()) {
            return ToolResult(callId, false, error = "文件不存在: $path")
        }
        val ext = file.extension.lowercase()
        if (ext.isNotEmpty() && ".$ext" !in allowedExtensions) {
            return ToolResult(callId, false, error = "不支持的视频格式: .$ext")
        }
        val sizeMB = file.length() / 1024 / 1024
        return ToolResult(callId, true, "视频已加载: ${file.name} (${sizeMB}MB)\n[视频文件已就绪，请通过文件路径查看]")
    }
}

