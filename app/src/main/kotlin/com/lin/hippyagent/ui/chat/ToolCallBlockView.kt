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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lin.hippyagent.core.agent.session.ToolCallStatus
import com.lin.hippyagent.core.chat.ToolCallBlock as ChatToolCallBlock
import com.lin.hippyagent.core.tools.BuiltinToolNames
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R

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
        ToolCallCopyMenu(
            expanded = showCopyMenu,
            block = block,
            resultText = resultText,
            context = context,
            onDismiss = { showCopyMenu = false }
        )
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
                    style = MaterialTheme.typography.titleSmall,
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (filePath != null && filePath.contains("/")) {
                Text(
                    text = filePath,
                    style = MaterialTheme.typography.labelSmall,
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
                    style = MaterialTheme.typography.labelSmall,
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
                        val displayLines = if (lines.size > 50) lines.take(50) + listOf("\n" + context.getString(R.string.chat_lines_omitted, lines.size - 50)) else lines
                        Text(
                            text = displayLines.joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
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
                    style = MaterialTheme.typography.titleSmall,
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
                        style = MaterialTheme.typography.labelSmall,
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
                            style = MaterialTheme.typography.labelSmall,
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
                    style = MaterialTheme.typography.labelSmall,
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
                    style = MaterialTheme.typography.titleSmall,
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (filePath != null && filePath.contains("/")) {
                Text(
                    text = filePath,
                    style = MaterialTheme.typography.labelSmall,
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
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val effectiveDiff = resultText?.let { buildPseudoDiffForResult(block.toolCall.name, it, context) }
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
                    style = MaterialTheme.typography.titleSmall,
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (filePath != null && filePath.contains("/")) {
                Text(
                    text = filePath,
                    style = MaterialTheme.typography.labelSmall,
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
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                contentDescription = stringResource(category.labelResId),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ToolCallStatusIcon(status = block.toolCall.status, expanded = expanded)
            Text(
                text = BuiltinToolNames.getDisplayName(block.toolCall.name).ifEmpty { block.toolCall.name },
                style = MaterialTheme.typography.labelMedium,
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
                    text = if (isRunning || isPending) "$shortArgs  ${stringResource(R.string.chat_waiting_result_inline)}" else "($shortArgs)",
                    style = MaterialTheme.typography.labelSmall,
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
                    text = stringResource(R.string.chat_calling),
                    style = MaterialTheme.typography.labelSmall,
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
                    text = stringResource(R.string.chat_executing_with_dots) + ".".repeat(dotCount.toInt().coerceIn(0, 3)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            if (block.durationMs > 0 && !isRunning) {
                Text(
                    text = formatDuration(block.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace
                )
            }
            if (isFailed) {
                Text(
                    text = stringResource(R.string.chat_failed),
                    style = MaterialTheme.typography.labelSmall,
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
                    val effectiveDiff = buildPseudoDiffForResult(block.toolCall.name, resultText, context)
                    if (effectiveDiff != null) {
                        val (_, diffLines) = parseDiffOutput(effectiveDiff)
                        val removed = diffLines.count { it.type == DiffLineType.REMOVED }
                        val added = diffLines.count { it.type == DiffLineType.ADDED }
                        Text(
                            text = stringResource(
                                R.string.chat_diff_file_summary,
                                if (removed > 0) context.getString(R.string.chat_diff_removed_lines, removed) else "",
                                if (added > 0) context.getString(R.string.chat_diff_added_lines, added) else ""
                            ),
                            style = MaterialTheme.typography.labelSmall,
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
                            text = stringResource(
                                R.string.chat_diff_summary,
                                if (removed > 0) context.getString(R.string.chat_diff_removed_lines, removed) else "",
                                if (added > 0) context.getString(R.string.chat_diff_added_lines, added) else ""
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                } else {
                    // 长按整个结果区域即可复制
                    val displayText = remember(resultText) { shortenWorkspacePaths(resultText) }
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("tool_result", "${context.getString(R.string.chat_clipboard_tool_header, block.toolCall.name)}\n$resultText"))
                                    Toast.makeText(context, context.getString(R.string.chat_result_copied), Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    // 剪贴板服务不可用时复制失败，不影响主流程，静默降级
                                }
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
                    style = MaterialTheme.typography.labelSmall,
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
                    val effectiveDiff = resultText?.let { buildPseudoDiffForResult(block.toolCall.name, it, context) }
                    if (effectiveDiff != null) {
                        DiffView(diffText = remember(effectiveDiff) { shortenWorkspacePaths(effectiveDiff) })
                    }
                } else {
                    // 其他工具：正常显示参数+结果
                    if (hasActualArguments(block.toolCall.arguments)) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.chat_params_label),
                            style = MaterialTheme.typography.labelSmall,
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
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                    if (resultText != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.chat_result_label),
                            style = MaterialTheme.typography.labelSmall,
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
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isFailed) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            text = stringResource(R.string.chat_waiting_result),
                            style = MaterialTheme.typography.labelSmall,
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
                                    clipboard.setPrimaryClip(ClipData.newPlainText("tool_result", "${context.getString(R.string.chat_clipboard_tool_header, block.toolCall.name)}\n$resultText"))
                                    Toast.makeText(context, context.getString(R.string.chat_result_copied), Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    // 剪贴板服务不可用时复制失败，不影响主流程，静默降级
                                }
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.chat_copy_result),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = stringResource(R.string.common_collapse),
                            style = MaterialTheme.typography.labelSmall,
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
            Text(text = "⏳", style = MaterialTheme.typography.labelSmall, modifier = modifier)
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
                contentDescription = stringResource(R.string.chat_running),
                modifier = modifier
                    .size(12.dp)
                    .graphicsLayer { rotationZ = rotation },
                tint = MaterialTheme.colorScheme.primary
            )
        }
        ToolCallStatus.FAILED -> {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_failed), modifier = modifier, tint = MaterialTheme.colorScheme.error)
        }
        ToolCallStatus.COMPLETED -> {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand), modifier = modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

