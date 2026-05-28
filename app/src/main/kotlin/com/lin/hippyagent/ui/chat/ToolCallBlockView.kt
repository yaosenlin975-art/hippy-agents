package com.lin.hippyagent.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.agent.session.ToolCallStatus
import com.lin.hippyagent.core.chat.ToolCallBlock as ChatToolCallBlock
import com.lin.hippyagent.core.tools.BuiltinToolNames
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** 共享的 JSON 格式化实例，避免重复创建 */
private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

private val lenientJson = Json { ignoreUnknownKeys = true }

private val WORKSPACE_PATH_REGEX = Regex("""[^\s]*/workspace/(\S+)""")
private val WORKSPACE_FULL_PATH_REGEX = Regex("""/data/data/com\.lin\.hippyagent/files/workspaces/[^/]+/(.*)""")

/** 判断 arguments 是否有实际内容（非空、非空 JSON 对象 {}） */
private fun hasActualArguments(arguments: String): Boolean {
    if (arguments.isBlank()) return false
    val trimmed = arguments.trim()
    return trimmed != "{}"
}

/** 尝试格式化 JSON 字符串，失败时返回原文 */
private fun tryFormatJson(input: String): String {
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
private fun shortenWorkspacePaths(text: String): String {
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
private enum class ToolCategory(val icon: ImageVector, val label: String) {
    FILE(Icons.Default.Folder, "文件"),
    SHELL(Icons.Default.Computer, "终端"),
    SEARCH(Icons.Default.Search, "搜索"),
    WEB(Icons.Default.Language, "网络"),
    CODE(Icons.Default.Code, "代码"),
    MEMORY(Icons.Default.Storage, "记忆"),
    UNKNOWN(Icons.Default.Build, "工具");

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
private fun formatDuration(ms: Long): String = when {
    ms <= 0 -> ""
    ms < 1000 -> "${ms}ms"
    else -> "%.1fs".format(ms / 1000.0)
}

/** 从 JSON 参数中提取 key 列表，用作简要参数提示 */
private fun extractParamKeys(arguments: String): String? {
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ToolCallBlockView(
    block: ChatToolCallBlock,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showCopyMenu by remember { mutableStateOf(false) }
    var userManuallyExpanded by remember { mutableStateOf(false) }
    var wasRunning by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val resultText = block.result?.content?.takeIf { it.isNotBlank() } ?: block.toolCall.output
    val isRunning = block.toolCall.status == ToolCallStatus.RUNNING
    val isPending = block.toolCall.status == ToolCallStatus.PENDING
    val isFailed = block.toolCall.status == ToolCallStatus.FAILED
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxHeight = screenHeight * 0.4f
    val category = ToolCategory.fromName(block.toolCall.name)

    val collapseTrigger = LocalCollapseAll.current
    val expandTrigger = LocalExpandVisible.current

    LaunchedEffect(collapseTrigger) {
        if (collapseTrigger > 0) {
            expanded = false
            userManuallyExpanded = false
        }
    }
    LaunchedEffect(expandTrigger) {
        if (expandTrigger > 0) {
            expanded = true
        }
    }

    LaunchedEffect(isRunning) {
        if (isRunning && !wasRunning) {
            userManuallyExpanded = false
            expanded = true
        }
        if (!isRunning && wasRunning) {
            if (!isFailed) {
                delay(ToolCategory.AUTO_COLLAPSE_DELAY_MS)
                expanded = false
                userManuallyExpanded = false
            }
        }
        wasRunning = isRunning
    }

    val onLongClick: () -> Unit = { showCopyMenu = true }

            // 失败的工具用 OutlinedCard + 红色边框
        if (isFailed) {
            OutlinedCard(
                modifier = modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            expanded = !expanded
                            if (expanded) userManuallyExpanded = true
                        },
                        onLongClick = onLongClick
                    ),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                )
            ) {
                ToolCallContent(
                    block = block,
                    expanded = expanded,
                    onToggleExpand = { expanded = !expanded },
                    onLongClick = onLongClick,
                    category = category,
                    resultText = resultText,
                    context = context
                )
            }
        } else {
            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            expanded = !expanded
                            if (expanded) userManuallyExpanded = true
                        },
                        onLongClick = onLongClick
                    ),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (block.toolCall.status) {
                        ToolCallStatus.PENDING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                        ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                )
            ) {
                ToolCallContent(
                    block = block,
                    expanded = expanded,
                    onToggleExpand = { expanded = !expanded },
                    onLongClick = onLongClick,
                    category = category,
                    resultText = resultText,
                    context = context
                )
            }
        }

    // 长按复制菜单
    if (showCopyMenu) {
        DropdownMenu(
            expanded = showCopyMenu,
            onDismissRequest = { showCopyMenu = false }
        ) {
            if (hasActualArguments(block.toolCall.arguments)) {
                DropdownMenuItem(
                    text = { Text("复制参数", fontSize = 14.sp) },
                    onClick = {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("tool_args", "【${block.toolCall.name}】\n${block.toolCall.arguments}"))
                            Toast.makeText(context, "已复制参数", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                        showCopyMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }
                )
            }
            if (!resultText.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text("复制结果", fontSize = 14.sp) },
                    onClick = {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("tool_result", "【${block.toolCall.name}】\n$resultText"))
                            Toast.makeText(context, "已复制结果", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                        showCopyMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }
                )
                DropdownMenuItem(
                    text = { Text("复制参数和结果", fontSize = 14.sp) },
                    onClick = {
                        try {
                            val all = "【${block.toolCall.name}】\n参数:\n${block.toolCall.arguments}\n\n结果:\n$resultText"
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("tool_all", all))
                            Toast.makeText(context, "已复制参数和结果", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                        showCopyMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ToolCallContent(
    block: ChatToolCallBlock,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    category: ToolCategory,
    onLongClick: () -> Unit = {},
    resultText: String?,
    context: Context
) {
    val isRunning = block.toolCall.status == ToolCallStatus.RUNNING
    val isPending = block.toolCall.status == ToolCallStatus.PENDING
    val isFailed = block.toolCall.status == ToolCallStatus.FAILED
    val isFileToolSuccess = isFileTool(block.toolCall.name) && !isRunning && !isPending && !isFailed
    val isRead = isReadTool(block.toolCall.name)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxHeight = screenHeight * 0.4f

    if (isRead && !isRunning && !isPending) {
        val filePath = remember(block.toolCall.arguments) { extractFilePath(block.toolCall.arguments) }
        val fileName = filePath?.substringAfterLast('/') ?: ""
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .combinedClickable(
                    onClick = { onToggleExpand() },
                    onLongClick = onLongClick
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "👁  $fileName",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (block.durationMs > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatDuration(block.durationMs),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (filePath != null && filePath.contains("/")) {
                Text(
                    text = filePath,
                    fontSize = 10.sp,
                    fontStyle = FontStyle.Italic,
                    color = if (isFailed) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 读取失败时显示原因
            if (isFailed && !resultText.isNullOrBlank()) {
                Text(
                    text = "⚠ $resultText",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // 展开显示文件内容（纯文本，最多50行）
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    if (resultText != null && resultText.isNotBlank()) {
                        val lines = resultText.lines()
                        val displayLines = if (lines.size > 50) lines.take(50) + listOf("\n... (${lines.size - 50} 行已省略)") else lines
                        Text(
                            text = displayLines.joinToString("\n"),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
        return
    }

    // send_file / send_file_to_user: 渲染附件卡片
    if (isSendFileTool(block.toolCall.name) && !isRunning && !isPending) {
        val attachmentPaths = remember(resultText) {
            ATTACHMENT_CONTENT_REGEX.findAll(resultText ?: "")
                .map { it.groupValues[1].trim() }
                .toList()
        }
        val filePath = remember(block.toolCall.arguments) { extractFilePath(block.toolCall.arguments) }
        val fileName = filePath?.substringAfterLast('/') ?: ""
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "📎  $fileName",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (block.durationMs > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatDuration(block.durationMs),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    if (isFailed && !resultText.isNullOrBlank()) {
                        Text(
                            text = "⚠ $resultText",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    for (path in attachmentPaths) {
                        FileAttachmentCard(filePath = path, isUser = false)
                    }
                }
            }
            if (!expanded && attachmentPaths.isNotEmpty()) {
                Text(
                    text = attachmentPaths.joinToString(", ") { it.substringAfterLast("/") },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        return
    }

    // write_file / edit_file / append_file 紧凑卡片：🖊 前缀 + 文件名 + 斜体路径，展开显示 diff
    if (isFileTool(block.toolCall.name) && !isRunning && !isPending) {
        val filePath = remember(block.toolCall.arguments) { extractFilePath(block.toolCall.arguments) }
        val fileName = filePath?.substringAfterLast('/') ?: ""
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🖊  $fileName${if (isFailed && !resultText.isNullOrBlank()) " $resultText" else ""}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (block.durationMs > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatDuration(block.durationMs),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (filePath != null && filePath.contains("/")) {
                Text(
                    text = filePath,
                    fontSize = 10.sp,
                    fontStyle = FontStyle.Italic,
                    color = if (isFailed) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 展开时显示 diff（成功）或参数（失败）
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(modifier = Modifier.height(4.dp))
                    if (isFailed) {
                        if (hasActualArguments(block.toolCall.arguments)) {
                            val formattedArgs = remember(block.toolCall.arguments) { tryFormatJson(block.toolCall.arguments) }
                            Text(
                                text = formattedArgs,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 14.sp
                            )
                        }
                    } else {
                        val effectiveDiff = resultText?.let { buildPseudoDiffForResult(block.toolCall.name, it) }
                        if (effectiveDiff != null) {
                            DiffView(diffText = remember(effectiveDiff) { shortenWorkspacePaths(effectiveDiff) })
                        }
                    }
                }
            }
        }
        return
    }

    // delete_file 紧凑卡片：DEL 前缀 + 文件名 + 斜体路径，失败展开显示参数
    if (isDeleteTool(block.toolCall.name) && !isRunning && !isPending) {
        val filePath = remember(block.toolCall.arguments) { extractFilePath(block.toolCall.arguments) }
        val fileName = filePath?.substringAfterLast('/') ?: ""
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🗑️  $fileName${if (isFailed && !resultText.isNullOrBlank()) " $resultText" else ""}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (block.durationMs > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatDuration(block.durationMs),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (filePath != null && filePath.contains("/")) {
                Text(
                    text = filePath,
                    fontSize = 10.sp,
                    fontStyle = FontStyle.Italic,
                    color = if (isFailed) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 失败时展开显示参数
            AnimatedVisibility(
                visible = expanded && isFailed,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    if (hasActualArguments(block.toolCall.arguments)) {
                        val formattedArgs = remember(block.toolCall.arguments) { tryFormatJson(block.toolCall.arguments) }
                        Text(
                            text = formattedArgs,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
        return
    }

    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        // 第一行：类型图标 + 工具名 + 耗时/状态
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = category.label,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ToolCallStatusIcon(status = block.toolCall.status, expanded = expanded)
            Text(
                text = BuiltinToolNames.getDisplayName(block.toolCall.name).ifEmpty { block.toolCall.name },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!expanded && hasActualArguments(block.toolCall.arguments) && !isFileToolSuccess) {
                val formattedArgs = remember(block.toolCall.arguments) { tryFormatJson(block.toolCall.arguments) }
                val shortArgs = if (formattedArgs.length > 60) formattedArgs.take(60) + "..." else formattedArgs
                Text(
                    text = if (isRunning || isPending) "$shortArgs  ⏳等待结果" else "($shortArgs)",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isRunning || isPending)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true)
                )
            }
            if (isPending) {
                Text(
                    text = "调用中",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            if (isRunning) {
                val infiniteTransition = rememberInfiniteTransition(label = "tool-dots")
                val dotCount by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "tool-dots-count"
                )
                Text(
                    text = "执行中" + ".".repeat(dotCount.toInt().coerceIn(0, 3)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            if (block.durationMs > 0 && !isRunning) {
                Text(
                    text = formatDuration(block.durationMs),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace
                )
            }
            if (isFailed) {
                Text(
                    text = "失败",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 折叠时显示简短结果摘要（单行截断）
        // 文件工具成功时统一显示 diff 摘要格式
        AnimatedVisibility(
            visible = !expanded && !isRunning && !isPending,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            if (resultText != null && resultText.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                if (isFileToolSuccess) {
                    // 文件工具：统一显示 diff 摘要
                    val effectiveDiff = buildPseudoDiffForResult(block.toolCall.name, resultText)
                    if (effectiveDiff != null) {
                        val (_, diffLines) = parseDiffOutput(effectiveDiff)
                        val removed = diffLines.count { it.type == DiffLineType.REMOVED }
                        val added = diffLines.count { it.type == DiffLineType.ADDED }
                        Text(
                            text = "📝 ${if (removed > 0) "-${removed}行 " else ""}${if (added > 0) "+${added}行" else ""}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                } else if (isDiffOutput(resultText)) {
                    // Diff 摘要：只显示文件名 + 变更统计
                    val (_, diffLines) = parseDiffOutput(resultText)
                    val removed = diffLines.count { it.type == DiffLineType.REMOVED }
                    val added = diffLines.count { it.type == DiffLineType.ADDED }
                    Text(
                        text = "📝 变更: ${if (removed > 0) "-${removed}行 " else ""}${if (added > 0) "+${added}行" else ""}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                } else {
                    // 长按整个结果区域即可复制
                    val displayText = remember(resultText) { shortenWorkspacePaths(resultText) }
                    Text(
                        text = displayText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("tool_result", "【${block.toolCall.name}】\n$resultText"))
                                    Toast.makeText(context, "已复制结果", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {}
                            }
                        )
                    )
                }
            }
        }

        if (!expanded && isFailed && !resultText.isNullOrBlank()) {
            val failPreview = remember(resultText) {
                val newlineIdx = resultText.indexOf('\n')
                val firstLine = if (newlineIdx == -1) resultText else resultText.substring(0, newlineIdx)
                firstLine.take(40)
            }
            if (failPreview.isNotBlank()) {
                Text(
                    text = "⚠ $failPreview",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                if (isFileToolSuccess) {
                    // 文件工具成功：只显示 diff 视图
                    Spacer(modifier = Modifier.height(4.dp))
                    val effectiveDiff = resultText?.let { buildPseudoDiffForResult(block.toolCall.name, it) }
                    if (effectiveDiff != null) {
                        DiffView(diffText = remember(effectiveDiff) { shortenWorkspacePaths(effectiveDiff) })
                    }
                } else {
                    // 其他工具：正常显示参数+结果
                    if (hasActualArguments(block.toolCall.arguments)) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "参数:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val formattedArgs = remember(block.toolCall.arguments) { tryFormatJson(block.toolCall.arguments) }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = formattedArgs,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 14.sp,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                    if (resultText != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "结果:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        if (isDiffOutput(resultText)) {
                            DiffView(diffText = remember(resultText) { shortenWorkspacePaths(resultText) })
                        } else {
                            val formattedResult = remember(resultText) { shortenWorkspacePaths(tryFormatJson(resultText)) }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = formattedResult,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isFailed) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 14.sp,
                                    maxLines = Int.MAX_VALUE,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                        }
                    } else if (isRunning || isPending) {
                        // 工具运行中但结果尚未返回
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "⏳ 等待结果...",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }
                if (resultText != null && resultText.length > 200) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = {
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("tool_result", "【${block.toolCall.name}】\n$resultText"))
                                    Toast.makeText(context, "已复制结果", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "复制结果",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "收起",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onToggleExpand() }
                                .padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工具调用状态图标 — RUNNING 时旋转动画，其他状态用静态图标
 */
@Composable
private fun ToolCallStatusIcon(
    status: ToolCallStatus,
    expanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    when (status) {
        ToolCallStatus.PENDING -> {
            Text(text = "⏳", fontSize = 10.sp, modifier = modifier)
        }
        ToolCallStatus.RUNNING -> {
            val infiniteTransition = rememberInfiniteTransition(label = "gear-spin")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "gear-rotation"
            )
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "运行中",
                modifier = modifier
                    .size(12.dp)
                    .graphicsLayer { rotationZ = rotation },
                tint = MaterialTheme.colorScheme.primary
            )
        }
        ToolCallStatus.FAILED -> {
            Icon(Icons.Default.Close, contentDescription = null, modifier = modifier, tint = MaterialTheme.colorScheme.error)
        }
        ToolCallStatus.COMPLETED -> {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ========== Diff 视图相关 ==========

/**
 * Diff 行数据类
 */
@Immutable
private data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val lineNumber: Int = 0
)

private enum class DiffLineType {
    HEADER,   // --- file 或 +++ file
    HUNK,     // @@ ... @@
    REMOVED,  // - 行
    ADDED,    // + 行
    CONTEXT   // 上下文行
}

/**
 * 解析简易 diff 格式（--- / @@ / - / + 前缀）
 */
private fun parseDiffOutput(text: String): Pair<String, List<DiffLine>> {
    val lines = text.lines()
    if (lines.isEmpty()) return "" to emptyList()

    var filePath = ""
    val diffLines = mutableListOf<DiffLine>()

    for (line in lines) {
        when {
            line.startsWith("--- ") -> {
                filePath = line.removePrefix("--- ").trim()
                diffLines.add(DiffLine(DiffLineType.HEADER, line))
            }
            line.startsWith("+++ ") -> {
                diffLines.add(DiffLine(DiffLineType.HEADER, line))
            }
            line.startsWith("@@ ") -> {
                diffLines.add(DiffLine(DiffLineType.HUNK, line))
            }
            line.startsWith("- ") -> {
                diffLines.add(DiffLine(DiffLineType.REMOVED, line.removePrefix("- ")))
            }
            line.startsWith("+ ") -> {
                diffLines.add(DiffLine(DiffLineType.ADDED, line.removePrefix("+ ")))
            }
            line.startsWith("  ") -> {
                diffLines.add(DiffLine(DiffLineType.CONTEXT, line.removePrefix("  ")))
            }
        }
    }

    return filePath to diffLines
}

/** 判断工具是否为文件操作类（write_file / edit_file / append_file） */
private fun isFileTool(name: String): Boolean {
    return name.equals("write_file", ignoreCase = true) ||
           name.equals("edit_file", ignoreCase = true) ||
           name.equals("append_file", ignoreCase = true)
}

private fun isSendFileTool(name: String): Boolean {
    return name.equals("send_file", ignoreCase = true) ||
           name.equals("send_file_to_user", ignoreCase = true)
}

private fun isReadTool(name: String): Boolean {
    return name.equals("read_file", ignoreCase = true)
}

private fun isDeleteTool(name: String): Boolean {
    return name.equals("delete_file", ignoreCase = true)
}

private fun extractFilePath(arguments: String): String? {
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
private fun buildPseudoDiffForResult(toolName: String, resultText: String): String? {
    // 已经是 diff 格式则直接返回
    if (isDiffOutput(resultText)) return resultText
    // write_file / append_file 成功时，从结果中提取行数构造摘要
    val lineCount = resultText.lines().filter { it.isNotBlank() }.size
    if (lineCount == 0) return null
    val label = when {
        toolName.equals("write_file", ignoreCase = true) -> "新文件"
        toolName.equals("append_file", ignoreCase = true) -> "追加内容"
        else -> "变更"
    }
    // 构造简单的伪 diff：全部作为新增行
    val pseudoLines = resultText.lines()
        .filter { it.isNotBlank() }
        .joinToString("\n") { "+ $it" }
    return "--- /dev/null\n+++ $label\n@@ @@\n$pseudoLines"
}

/**
 * 判断文本是否为 diff 格式
 */
private fun isDiffOutput(text: String): Boolean {
    return text.contains("--- ") && text.contains("@@ ") &&
           (text.contains("- ") || text.contains("+ "))
}

/**
 * Diff 视图渲染 — 带行号和颜色标注的变更展示
 */
@Composable
private fun DiffView(
    diffText: String,
    modifier: Modifier = Modifier
) {
    val (filePath, diffLines) = remember(diffText) { parseDiffOutput(diffText) }
    val displayPath = shortenWorkspacePaths(filePath)
    if (diffLines.isEmpty()) {
        Text(text = diffText, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    Column(modifier = modifier) {

        Spacer(modifier = Modifier.height(4.dp))

        // Diff 行渲染 — 只显示变化部分（不含 CONTEXT 行）
        diffLines.filter { it.type != DiffLineType.CONTEXT }.forEach { line ->
            when (line.type) {
                DiffLineType.HUNK -> {
                    Text(
                        text = line.content,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                DiffLineType.REMOVED -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFCDD2).copy(alpha = 0.4f)) // 红色背景
                            .padding(start = 10.dp, end = 4.dp, top = 1.dp, bottom = 1.dp)
                    ) {
                        Text(
                            text = "-",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.width(10.dp)
                        )
                        Text(
                            text = line.content,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            textDecoration = TextDecoration.LineThrough // 文字划中线
                        )
                    }
                }
                DiffLineType.ADDED -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFC8E6C9).copy(alpha = 0.4f)) // 绿色背景
                            .padding(start = 10.dp, end = 4.dp, top = 1.dp, bottom = 1.dp)
                    ) {
                        Text(
                            text = "+",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50), // Green 500
                            modifier = Modifier.width(10.dp)
                        )
                        Text(
                            text = line.content,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF388E3C).copy(alpha = 0.85f) // Green 700
                        )
                    }
                }
                DiffLineType.HEADER -> { /* 已在标题处理 */ }
                DiffLineType.CONTEXT -> { /* 不显示上下文行 */ }
            }
        }
    }
}

