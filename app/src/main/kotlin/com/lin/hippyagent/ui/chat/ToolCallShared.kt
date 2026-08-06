package com.lin.hippyagent.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lin.hippyagent.R
import com.lin.hippyagent.core.chat.ToolCallBlock as ChatToolCallBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** 共享的 JSON 格式化实例，避免重复创建 */
internal val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

internal val lenientJson = Json { ignoreUnknownKeys = true }

internal val WORKSPACE_PATH_REGEX = Regex("""[^\s]*/workspace/(\S+)""")
internal val WORKSPACE_FULL_PATH_REGEX = Regex("""/data/data/com\.lin\.hippyagent/files/workspaces/[^/]+/(.*)""")

/** 判断 arguments 是否有实际内容（非空、非空 JSON 对象 {}） */
internal fun hasActualArguments(arguments: String): Boolean {
    if (arguments.isBlank()) return false
    val trimmed = arguments.trim()
    return trimmed != "{}"
}

/** 尝试格式化 JSON 字符串，失败时返回原文 */
internal fun tryFormatJson(input: String): String {
    if (input.isBlank()) return input
    return try {
        val element = lenientJson.parseToJsonElement(input)
        if (element is JsonObject) {
            element.entries.joinToString("\n") { (key, value) ->
                val displayValue = when {
                    value is JsonPrimitive && value.isString -> value.content
                    else -> value.toString()
                }
                "$key: $displayValue"
            }
        } else {
            prettyJson.encodeToString(JsonElement.serializer(), element)
        }
    } catch (_: Exception) {
        input
    }
}

/**
 * 将工具结果中的工作区完整文件路径替换为仅文件名
 * 例如：/data/data/com.lin.hippyagent/files/workspace/xxx/MEMORY.md → MEMORY.md
 */
internal fun shortenWorkspacePaths(text: String): String {
    // 使用非回溯模式：[^\s]+ 确保贪婪不回溯，避免灾难性回溯导致 ANR
    return text.replace(WORKSPACE_PATH_REGEX) {
        val path = it.groupValues[1]
        // 只取最后一段文件名
        path.substringAfterLast('/')
    }
}

/**
 * 工具类型分类 — 根据名称前缀推断类型和显示样式
 */
internal enum class ToolCategory(val icon: ImageVector, val labelResId: Int) {
    FILE(Icons.Default.Folder, R.string.tool_cat_file),
    SHELL(Icons.Default.Computer, R.string.tool_cat_terminal),
    SEARCH(Icons.Default.Search, R.string.tool_cat_search),
    WEB(Icons.Default.Language, R.string.tool_cat_network),
    CODE(Icons.Default.Code, R.string.tool_cat_code),
    MEMORY(Icons.Default.Storage, R.string.tool_cat_memory),
    UNKNOWN(Icons.Default.Build, R.string.tool_cat_tool);

    companion object {
        const val AUTO_COLLAPSE_DELAY_MS = 800L

        fun fromName(name: String): ToolCategory = when {
            name.startsWith("file", ignoreCase = true) ||
            name.startsWith("read", ignoreCase = true) ||
            name.startsWith("write", ignoreCase = true) ||
            name.contains("file", ignoreCase = true) -> FILE
            name.startsWith("shell", ignoreCase = true) ||
            name.startsWith("bash", ignoreCase = true) ||
            name.startsWith("exec", ignoreCase = true) ||
            name.startsWith("p_root", ignoreCase = true) -> SHELL
            name.startsWith("search", ignoreCase = true) ||
            name.startsWith("grep", ignoreCase = true) ||
            name.startsWith("find", ignoreCase = true) -> SEARCH
            name.startsWith("web", ignoreCase = true) ||
            name.startsWith("http", ignoreCase = true) ||
            name.startsWith("fetch", ignoreCase = true) -> WEB
            name.startsWith("code", ignoreCase = true) ||
            name.startsWith("python", ignoreCase = true) ||
            name.startsWith("compile", ignoreCase = true) -> CODE
            name.startsWith("memory", ignoreCase = true) ||
            name.startsWith("remember", ignoreCase = true) ||
            name.startsWith("recall", ignoreCase = true) -> MEMORY
            else -> UNKNOWN
        }
    }
}

/** 格式化耗时 */
internal fun formatDuration(ms: Long): String = when {
    ms <= 0 -> ""
    ms < 1000 -> "${ms}ms"
    else -> "%.1fs".format(ms / 1000.0)
}

/** 从 JSON 参数中提取 key 列表，用作简要参数提示 */
internal fun extractParamKeys(arguments: String): String? {
    if (arguments.isBlank() || arguments.length < 2) return null
    return try {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(arguments)
        if (element is JsonObject) {
            val keys = element.keys.toList()
            if (keys.isEmpty()) null
            else keys.joinToString(", ")
        } else null
    } catch (_: Exception) {
        null
    }
}

/** 判断工具是否为文件操作类（write_file / edit_file / append_file） */
internal fun isFileTool(name: String): Boolean {
    return name.equals("write_file", ignoreCase = true) ||
           name.equals("edit_file", ignoreCase = true) ||
           name.equals("append_file", ignoreCase = true)
}

internal fun isSendFileTool(name: String): Boolean {
    return name.equals("send_file", ignoreCase = true) ||
           name.equals("send_file_to_user", ignoreCase = true)
}

internal fun isReadTool(name: String): Boolean {
    return name.equals("read_file", ignoreCase = true)
}

internal fun isDeleteTool(name: String): Boolean {
    return name.equals("delete_file", ignoreCase = true)
}

internal fun extractFilePath(arguments: String): String? {
    return try {
        val element = lenientJson.parseToJsonElement(arguments)
        if (element is JsonObject) {
            element["file_path"]?.let { path ->
                var p = path.jsonPrimitive.content
                val match = WORKSPACE_FULL_PATH_REGEX.find(p)
                if (match != null) {
                    p = "./${match.groupValues[1]}"
                }
                p
            }
        } else null
    } catch (_: Exception) {
        null
    }
}

/**
 * 为 write_file / append_file 的非 diff 结果构造伪 diff 文本
 * 使其可以复用 DiffView 渲染
 */
internal fun buildPseudoDiffForResult(toolName: String, resultText: String, context: Context): String? {
    // 已经是 diff 格式则直接返回
    if (isDiffOutput(resultText)) return resultText
    // write_file / append_file 成功时，从结果中提取行数构造摘要
    val lineCount = resultText.lines().filter { it.isNotBlank() }.size
    if (lineCount == 0) return null
    val label = when {
        toolName.equals("write_file", ignoreCase = true) -> context.getString(R.string.chat_new_file_label)
        toolName.equals("append_file", ignoreCase = true) -> context.getString(R.string.chat_append_content_label)
        else -> context.getString(R.string.chat_changes_label)
    }
    // 构造简单的伪 diff：全部作为新增行
    val pseudoLines = resultText.lines()
        .filter { it.isNotBlank() }
        .joinToString("\n") { "+ $it" }
    return "--- /dev/null\n+++ $label\n@@ @@\n$pseudoLines"
}

/**
 * 工具调用长按复制菜单（参数 / 结果 / 全部）
 */
@Composable
internal fun ToolCallCopyMenu(
    expanded: Boolean,
    block: ChatToolCallBlock,
    resultText: String?,
    context: Context,
    onDismiss: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        if (hasActualArguments(block.toolCall.arguments)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy_params), style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    try {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("tool_args", "${context.getString(R.string.chat_clipboard_tool_header, block.toolCall.name)}\n${block.toolCall.arguments}"))
                        Toast.makeText(context, context.getString(R.string.chat_params_copied), Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        // 剪贴板服务不可用时复制失败，不影响主流程，静默降级
                    }
                    onDismiss()
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }
            )
        }
        if (!resultText.isNullOrBlank()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy_result), style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    try {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("tool_result", "${context.getString(R.string.chat_clipboard_tool_header, block.toolCall.name)}\n$resultText"))
                        Toast.makeText(context, context.getString(R.string.chat_result_copied), Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        // 剪贴板服务不可用时复制失败，不影响主流程，静默降级
                    }
                    onDismiss()
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy_params_and_result), style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    try {
                        val header = context.getString(R.string.chat_clipboard_tool_header, block.toolCall.name)
                        val all = "$header\n${context.getString(R.string.chat_copy_all_body, block.toolCall.arguments, resultText)}"
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("tool_all", all))
                        Toast.makeText(context, context.getString(R.string.chat_params_and_result_copied), Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        // 剪贴板服务不可用时复制失败，不影响主流程，静默降级
                    }
                    onDismiss()
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }
            )
        }
    }
}
