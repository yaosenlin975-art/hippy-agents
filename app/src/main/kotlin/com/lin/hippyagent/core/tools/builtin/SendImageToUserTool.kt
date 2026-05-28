package com.lin.hippyagent.core.tools.builtin

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import java.io.File

class SendImageToUserTool(
    private val context: Context
) : Tool() {

    override val definition = ToolDefinition(
        name = "send_image_to_user",
        description = "向用户发送图片。图片会在聊天界面中展示，同时用户可以通过长按图片进行分享。",
        parameters = mapOf(
            "image_path" to ToolParameter(
                name = "image_path",
                type = "string",
                description = "要发送的图片文件路径",
                required = true
            ),
            "description" to ToolParameter(
                name = "description",
                type = "string",
                description = "图片的简要描述（可选）",
                required = false
            ),
            "share" to ToolParameter(
                name = "share",
                type = "boolean",
                description = "是否同时通过系统分享功能发送图片，默认 false",
                required = false,
                defaultValue = "false"
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val callId = args["callId"] as? String ?: ""
        val imagePath = getRequiredArgument(args, "image_path")
        val description = getOptionalArgument(args, "description")
        val share = getOptionalArgument(args, "share", "false")?.toBoolean() ?: false

        val file = File(imagePath)
        if (!file.exists()) {
            return ToolResult(
                callId = callId,
                success = false,
                error = "图片文件不存在: $imagePath"
            )
        }

        val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        val ext = file.extension.lowercase()
        if (ext !in imageExtensions) {
            return ToolResult(
                callId = callId,
                success = false,
                error = "不是图片文件: $imagePath (支持的格式: ${imageExtensions.joinToString(", ")})"
            )
        }

        if (share) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/${if (ext == "jpg") "jpeg" else ext}"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "分享图片")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                return ToolResult(
                    callId = callId,
                    success = false,
                    error = "分享图片失败: ${e.message}"
                )
            }
        }

        val descText = if (!description.isNullOrBlank()) " ($description)" else ""
        val attachmentTag = "[附件: $imagePath]"

        return ToolResult(
            callId = callId,
            success = true,
            output = attachmentTag,
            forLLM = "图片已发送给用户$descText。图片路径: $imagePath",
            forUser = "$descText\n$attachmentTag",
            media = listOf(imagePath)
        )
    }

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        return execute(ToolContext(), arguments)
    }
}
