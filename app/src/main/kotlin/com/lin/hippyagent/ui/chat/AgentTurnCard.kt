package com.lin.hippyagent.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.chat.ChatTurn
import com.lin.hippyagent.core.chat.TurnElement
import com.lin.hippyagent.core.chat.TurnMetadata
import com.lin.hippyagent.core.chat.TurnStatus
import com.lin.hippyagent.ui.components.PulsingStatusDot
import com.lin.hippyagent.ui.components.getAvatarIcon
import androidx.compose.ui.res.stringResource
import com.lin.hippyagent.R

private const val STREAMING_CURSOR = "▎"

private fun isEmptyJsonObject(arguments: String): Boolean {
    if (arguments.isBlank()) return true
    val trimmed = arguments.trim()
    return trimmed == "{}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentTurnCard(
    turn: ChatTurn.AgentTurn,
    streamingOverride: String? = null,
    streamingThinkingContent: String? = null,
    agentName: String = "",
    agentAvatarUrl: String? = null,
    isGroupedWithPrevious: Boolean = false,
    isAgentRunning: Boolean = false,
    isGloballyActive: Boolean = false,
    agentStatus: AgentStatus? = null,
    isGroupChat: Boolean = false,
    agentProfiles: Map<String, String> = emptyMap(),
    agentReadStates: Map<String, String> = emptyMap(),
    onImageClick: ((String) -> Unit)? = null,
    onRegenerate: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSpeak: (() -> Unit)? = null,
    onQuote: ((messageId: String, content: String, senderName: String) -> Unit)? = null,
    onLongClickAvatar: ((agentName: String) -> Unit)? = null,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onEnterMultiSelect: ((String) -> Unit)? = null,
    collapseProcess: Boolean = false,
    collapsedGroupTurns: List<ChatTurn.AgentTurn> = emptyList(),
    onExpandGroup: () -> Unit = {},
    isProcessExpanded: Boolean = false,
    showProcessHeader: Boolean = false,
    showProcessFooter: Boolean = false,
    groupProcessStepCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showActions by remember { mutableStateOf(false) }
    var showProcessDialog by remember { mutableStateOf(false) }
    val bubbleOnClick: () -> Unit = if (isMultiSelectMode) onToggleSelection else ({})
    val bubbleOnLongClick: () -> Unit = if (!isMultiSelectMode) ({
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); showActions = true
    }) else ({})
    val isStreaming = turn.status == TurnStatus.STREAMING
    val isError = turn.status == TurnStatus.ERROR

    val avatarIcon = getAvatarIcon(turn.senderAgentId ?: "")

    val hasChatWithAgent = turn.toolCalls.any { it.toolCall.name == "chat_with_agent" }
    val displayElements = remember(collapseProcess, isGroupChat, isProcessExpanded, hasChatWithAgent, turn.elements) {
        when {
            collapseProcess -> {
                val lastText = turn.elements.lastOrNull { it is TurnElement.TextSegment && it.content.isNotBlank() }
                if (lastText != null) listOf(lastText) else emptyList()
            }
            isGroupChat && !isProcessExpanded -> turn.elements.filter { it is TurnElement.TextSegment }
            isGroupChat || hasChatWithAgent -> turn.elements.filter { it is TurnElement.TextSegment }
            else -> turn.elements
        }
    }

    // 根据发送者 agentId hash 生成 HSL 颜色（固定饱和度 0.6f，亮度 0.75f）
    val agentBarColor = turn.senderAgentId?.let { senderId ->
        val hue = (senderId.hashCode().and(0x7FFFFFFF) % 360).toFloat()
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.75f)))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        if (isMultiSelectMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    if (isError) MaterialTheme.colorScheme.error
                    else agentBarColor ?: MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(1.5.dp)
                )
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
                .then(
                    if (isMultiSelectMode && isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    else Modifier
                ),
            verticalArrangement = Arrangement.spacedBy(if (isGroupedWithPrevious) 2.dp else 8.dp)
        ) {
        if (turn.elements.isNotEmpty()) {
            val hasVisibleContent = turn.elements.any { it is TurnElement.TextSegment && it.content.isNotBlank() }
            val showAgentHeader = agentName.isNotBlank() && (isGroupChat && hasVisibleContent || !isGroupChat && !isGroupedWithPrevious)
            if (showAgentHeader) {
                AgentHeader(
                    agentName = agentName,
                    avatarIcon = avatarIcon,
                    agentAvatarUrl = agentAvatarUrl,
                    onAvatarClick = if (isGroupChat) ({ showProcessDialog = true }) else null,
                    onAvatarLongClick = onLongClickAvatar?.let { cb -> { cb(agentName) } }
                )
            }

            if (collapseProcess && !isGroupChat) {
                val processStepCount = remember(turn.id, collapsedGroupTurns.size) {
                    val allGroupTurns = collapsedGroupTurns + listOf(turn)
                    allGroupTurns.sumOf { t ->
                        t.elements.count { it is TurnElement.ThinkingSegment || it is TurnElement.ToolCallSegment }
                    } + allGroupTurns.sumOf { t ->
                        val textSegments = t.elements.filterIsInstance<TurnElement.TextSegment>()
                        (textSegments.size - if (t == turn) 1 else 0).coerceAtLeast(0)
                    }
                }
                val collapseStats = remember(turn.id, collapsedGroupTurns.size) {
                    aggregateTurnProcessStats(collapsedGroupTurns + listOf(turn))
                }
                ProcessDrawer(
                    stepCount = processStepCount,
                    isExpanded = false,
                    onToggleExpand = onExpandGroup,
                    stats = collapseStats
                )
            }

            if (showProcessHeader && groupProcessStepCount > 0) {
                val headerStats = remember(turn.elements, turn.metadata?.latencyMs) {
                    computeTurnProcessStats(turn)
                }
                ProcessDrawer(
                    stepCount = groupProcessStepCount,
                    isExpanded = true,
                    onToggleExpand = onExpandGroup,
                    stats = headerStats
                )
            }

            if (turn.quotedContent != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    tonalElevation = 1.dp
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = turn.quotedSenderName ?: stringResource(R.string.chat_quoted_message),
                            fontSize = 11.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = turn.quotedContent ?: "",
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val lastMetadataElementIndex = displayElements.indexOfLast { it is TurnElement.TextSegment && it.content.isNotBlank() }

            displayElements.forEachIndexed { index, element ->
                when (element) {
                    is TurnElement.ThinkingSegment -> {
                        val displayBlock = if (streamingThinkingContent != null && streamingThinkingContent.isNotBlank() && element == turn.elements.last { it is TurnElement.ThinkingSegment }) {
                            com.lin.hippyagent.core.chat.ThinkingBlock(content = streamingThinkingContent)
                        } else {
                            element.block
                        }
                        ThinkingBlockView(block = displayBlock, isStreaming = isStreaming)
                    }
                    is TurnElement.TextSegment -> {
                        val content = if (isStreaming && element == turn.elements.last()) {
                            streamingOverride ?: element.content
                        } else {
                            element.content
                        }
                        if (content.isNotBlank()) {
                            // 折叠按钮绘制在最后一条消息上方，而非轮次最底部
                            if (showProcessFooter && groupProcessStepCount > 0 && index == lastMetadataElementIndex) {
                                ProcessCollapseButton(onToggleExpand = onExpandGroup)
                            }
                            if (isError) {
                                ErrorBubble(
                                    content = content,
                                    isStreaming = isStreaming,
                                    metadata = if (index == lastMetadataElementIndex) turn.metadata else null,
                                    onImageClick = onImageClick,
                                    agentProfiles = agentProfiles,
                                    onLongClick = bubbleOnLongClick,
                                    onClick = bubbleOnClick,
                                    metadataJson = turn.response?.metadataJson
                                )
                            } else {
                                ContentBubble(
                                    content = content,
                                    isStreaming = isStreaming,
                                    isGroupChat = isGroupChat,
                                    showStreamingCursor = isStreaming && !isGroupChat && streamingOverride != null && element == turn.elements.last(),
                                    metadata = if (index == lastMetadataElementIndex) turn.metadata else null,
                                    onImageClick = onImageClick,
                                    agentProfiles = agentProfiles,
                                    onLongClick = bubbleOnLongClick,
                                    onClick = bubbleOnClick,
                                    metadataJson = turn.response?.metadataJson
                                )
                            }
                        }
                    }
                    is TurnElement.ToolCallSegment -> {
                        ToolCallBlockView(
                            block = element.block,
                            isStreaming = isStreaming,
                            modifier = Modifier
                                .widthIn(max = screenWidth * 0.85f)
                                .align(Alignment.Start)
                        )
                    }
                }
            }
        } else {
            val hasVisibleContentLegacy = turn.response?.content?.isNotBlank() == true
            val showAgentHeaderLegacy = agentName.isNotBlank() && (isGroupChat && hasVisibleContentLegacy || !isGroupChat && !isGroupedWithPrevious)
            if (showAgentHeaderLegacy) {
                AgentHeader(
                    agentName = agentName,
                    avatarIcon = avatarIcon,
                    agentAvatarUrl = agentAvatarUrl,
                    showStatusDot = !isGroupChat,
                    agentStatus = agentStatus,
                    onAvatarLongClick = onLongClickAvatar?.let { cb -> { cb(agentName) } }
                )
            }
            if (collapseProcess && !isGroupChat) {
                val legacyStepCount = collapsedGroupTurns.size + 1
                val collapseStats = remember(turn.id, collapsedGroupTurns.size) {
                    aggregateTurnProcessStats(collapsedGroupTurns + listOf(turn))
                }
                ProcessDrawer(
                    stepCount = legacyStepCount,
                    isExpanded = false,
                    onToggleExpand = onExpandGroup,
                    stats = collapseStats
                )
            }
            if (showProcessHeader && groupProcessStepCount > 0) {
                val headerStats = remember(turn.elements, turn.metadata?.latencyMs) {
                    computeTurnProcessStats(turn)
                }
                ProcessDrawer(
                    stepCount = groupProcessStepCount,
                    isExpanded = true,
                    onToggleExpand = onExpandGroup,
                    stats = headerStats
                )
            }
            if (!isGroupChat && !hasChatWithAgent && !collapseProcess) {
                val fallbackThinkingBlock = when {
                    streamingThinkingContent != null && streamingThinkingContent.isNotBlank() ->
                        com.lin.hippyagent.core.chat.ThinkingBlock(content = streamingThinkingContent)
                    turn.thinking != null -> turn.thinking
                    else -> null
                }
                if (fallbackThinkingBlock != null) {
                    ThinkingBlockView(block = fallbackThinkingBlock, isStreaming = isStreaming)
                }

                turn.toolCalls.forEach { toolCallBlock ->
                    ToolCallBlockView(
                        block = toolCallBlock,
                        isStreaming = isStreaming,
                        modifier = Modifier
                            .widthIn(max = screenWidth * 0.85f)
                            .align(Alignment.Start)
                    )
                }
            }

            if (turn.quotedContent != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    tonalElevation = 1.dp
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = turn.quotedSenderName ?: stringResource(R.string.chat_quoted_message),
                            fontSize = 11.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = turn.quotedContent ?: "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }

            val responseContent = streamingOverride ?: turn.response?.content ?: ""
            if (responseContent.isNotBlank()) {
                if (isError) {
                    ErrorBubble(
                        content = responseContent,
                        isStreaming = isStreaming,
                        metadata = turn.metadata,
                        onImageClick = onImageClick,
                        agentProfiles = agentProfiles,
                        onLongClick = bubbleOnLongClick,
                        onClick = bubbleOnClick,
                        metadataJson = turn.response?.metadataJson
                    )
                } else {
                    ContentBubble(
                        content = responseContent,
                        isStreaming = isStreaming,
                        isGroupChat = isGroupChat,
                        showStreamingCursor = isStreaming && !isGroupChat && streamingOverride != null,
                        metadata = turn.metadata,
                        onImageClick = onImageClick,
                        agentProfiles = agentProfiles,
                        barKey = turn.senderAgentId ?: agentName,
                        onLongClick = bubbleOnLongClick,
                        onClick = bubbleOnClick,
                        metadataJson = turn.response?.metadataJson
                    )
                }
            }

        }

        if (agentReadStates.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp, top = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                agentReadStates.forEach { (agentId, state) ->
                    val displayName = agentProfiles[agentId] ?: agentId
                    val (stateText, stateColor) = when (state) {
                        "已回复" -> stringResource(R.string.chat_replied) to MaterialTheme.colorScheme.primary
                        "工作中" -> stringResource(R.string.chat_working) to MaterialTheme.colorScheme.tertiary
                        else -> stringResource(R.string.chat_read) to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = "$displayName $stateText",
                        fontSize = 10.sp,
                        color = stateColor,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
        }
    }

    if (showProcessDialog) {
        val processSegments = if (turn.elements.isNotEmpty()) {
            turn.elements.filter { it is TurnElement.ThinkingSegment || it is TurnElement.ToolCallSegment }
        } else {
            val legacyThinking = when {
                streamingThinkingContent != null && streamingThinkingContent.isNotBlank() ->
                    com.lin.hippyagent.core.chat.ThinkingBlock(content = streamingThinkingContent)
                turn.thinking != null -> turn.thinking
                else -> null
            }
            val fallbackElements = mutableListOf<TurnElement>()
            if (legacyThinking != null) {
                fallbackElements.add(TurnElement.ThinkingSegment(
                    block = legacyThinking, timestamp = java.time.Instant.now()
                ))
            }
            turn.toolCalls.forEach { tc ->
                fallbackElements.add(TurnElement.ToolCallSegment(
                    block = tc, timestamp = java.time.Instant.now()
                ))
            }
            fallbackElements
        }

        AlertDialog(
            onDismissRequest = { showProcessDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = avatarIcon,
                        contentDescription = agentName,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.chat_agent_workflow, agentName), fontSize = 16.sp)
                }
            },
            text = {
                if (processSegments.isEmpty()) {
                    Text(stringResource(R.string.chat_no_process_info), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = screenHeight * 0.6f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        processSegments.forEach { element ->
                            when (element) {
                                is TurnElement.ThinkingSegment -> {
                                    ThinkingBlockView(block = element.block, isStreaming = false)
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                is TurnElement.ToolCallSegment -> {
                                    ToolCallBlockView(
                                        block = element.block,
                                        isStreaming = false,
                                        modifier = Modifier.widthIn(max = screenWidth * 0.85f)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                is TurnElement.TextSegment -> {}
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProcessDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }

    if (showActions) {
        val content = turn.response?.content ?: ""
        MessageActionsSheet(
            isAgent = true,
            onCopy = {
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("message", content))
                } catch (_: Exception) {}
            },
            onRegenerate = onRegenerate,
            onSpeak = onSpeak,
            onQuote = onQuote?.let { cb ->
                {
                    val quoteContent = content.take(200)
                    cb(turn.response?.id ?: turn.id, quoteContent, agentName)
                }
            },
            onDelete = onDelete,
            onMultiSelect = onEnterMultiSelect?.let { cb -> { cb(turn.response?.id ?: "") } },
            onDismiss = { showActions = false }
        )
    }
}

@Composable
private fun ProcessDrawer(
    stepCount: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    stats: TurnProcessStats? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(0.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
                        RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                    )
            )
            Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isExpanded) "▼" else "▶",
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (stats != null) {
                            stringResource(
                                R.string.chat_step_process_stats,
                                stepCount,
                                stats.thinkingCount,
                                stats.toolCount,
                                stats.messageCount,
                                stats.durationSec
                            )
                        } else {
                            stringResource(R.string.chat_step_process, stepCount)
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        }
    }
}

@Immutable
data class TurnProcessStats(
    val thinkingCount: Int,
    val toolCount: Int,
    val messageCount: Int,
    val durationSec: Int
)

private fun computeTurnProcessStats(turn: ChatTurn.AgentTurn): TurnProcessStats {
    val thinkingCount = turn.elements.count { it is TurnElement.ThinkingSegment }
    val toolCount = turn.elements.count { it is TurnElement.ToolCallSegment }
    val textSegments = turn.elements.filterIsInstance<TurnElement.TextSegment>()
    val messageCount = (textSegments.size - 1).coerceAtLeast(0)
    val durationSec = ((turn.metadata?.latencyMs ?: 0L) / 1000L).toInt()
    return TurnProcessStats(thinkingCount, toolCount, messageCount, durationSec)
}

private fun aggregateTurnProcessStats(turns: List<ChatTurn.AgentTurn>): TurnProcessStats {
    if (turns.isEmpty()) return TurnProcessStats(0, 0, 0, 0)
    var thinking = 0
    var tool = 0
    var message = 0
    var durationMs = 0L
    for (t in turns) {
        val s = computeTurnProcessStats(t)
        thinking += s.thinkingCount
        tool += s.toolCount
        message += s.messageCount
        durationMs += (t.metadata?.latencyMs ?: 0L)
    }
    return TurnProcessStats(thinking, tool, message, (durationMs / 1000L).toInt())
}

@Composable
private fun ProcessCollapseButton(
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "▲", fontSize = 10.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.chat_collapse_process),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentHeader(
    agentName: String,
    avatarIcon: androidx.compose.ui.graphics.vector.ImageVector,
    agentAvatarUrl: String?,
    onAvatarClick: (() -> Unit)? = null,
    onAvatarLongClick: (() -> Unit)? = null,
    showStatusDot: Boolean = false,
    agentStatus: AgentStatus? = null,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 4.dp, bottom = 2.dp)
    ) {
        if (agentAvatarUrl != null) {
            val imageModel: Any = when {
                agentAvatarUrl.startsWith("/") -> java.io.File(agentAvatarUrl)
                agentAvatarUrl.startsWith("content://") -> android.net.Uri.parse(agentAvatarUrl)
                else -> agentAvatarUrl
            }
            coil.compose.AsyncImage(
                model = imageModel,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (onAvatarClick != null) Modifier.clickable { onAvatarClick() } else Modifier)
                    .then(if (onAvatarLongClick != null) Modifier.combinedClickable(
                        onClick = { onAvatarClick?.invoke() },
                        onLongClick = { onAvatarLongClick?.invoke() }
                    ) else Modifier),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(4.dp))
        } else {
            Icon(
                imageVector = avatarIcon,
                contentDescription = agentName,
                modifier = Modifier
                    .size(28.dp)
                    .then(if (onAvatarClick != null) Modifier.clickable { onAvatarClick() } else Modifier)
                    .then(if (onAvatarLongClick != null) Modifier.combinedClickable(
                        onClick = { onAvatarClick?.invoke() },
                        onLongClick = { onAvatarLongClick?.invoke() }
                    ) else Modifier),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = agentName,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = if (onAvatarLongClick != null) Modifier.combinedClickable(
                onClick = {},
                onLongClick = { onAvatarLongClick() }
            ) else Modifier
        )
        if (showStatusDot && agentStatus != null && (agentStatus == AgentStatus.THINKING || agentStatus == AgentStatus.EXECUTING_TOOL)) {
            Spacer(modifier = Modifier.width(6.dp))
            PulsingStatusDot(
                isThinking = agentStatus == AgentStatus.THINKING,
                label = if (agentStatus == AgentStatus.THINKING) stringResource(R.string.thinking) else stringResource(R.string.chat_executing)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ErrorBubble(
    content: String,
    isStreaming: Boolean,
    metadata: TurnMetadata?,
    onImageClick: ((String) -> Unit)?,
    agentProfiles: Map<String, String>,
    onLongClick: () -> Unit,
    onClick: () -> Unit = {},
    metadataJson: String? = null,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        )
    ) {
        AgentBubbleContent(
            responseContent = content,
            isStreaming = isStreaming,
            isError = true,
            metadata = metadata,
            onImageClick = onImageClick,
            agentProfiles = agentProfiles,
            showStreamingCursor = false,
            metadataJson = metadataJson,
            modifier = Modifier.padding(8.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContentBubble(
    content: String,
    isStreaming: Boolean,
    isGroupChat: Boolean,
    showStreamingCursor: Boolean,
    metadata: TurnMetadata?,
    onImageClick: ((String) -> Unit)?,
    agentProfiles: Map<String, String>,
    barKey: String? = null,
    onLongClick: () -> Unit,
    onClick: () -> Unit = {},
    metadataJson: String? = null,
    modifier: Modifier = Modifier
) {
    val agentBarColor = if (isGroupChat && barKey != null && barKey.isNotBlank()) {
        val hue = (barKey.hashCode().and(0x7FFFFFFF) % 360).toFloat()
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.75f)))
    } else null

    if (agentBarColor != null) {
        Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(agentBarColor, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
            ) {
                AgentBubbleContent(
                    responseContent = content,
                    isStreaming = isStreaming,
                    isError = false,
                    metadata = metadata,
                    onImageClick = onImageClick,
                    agentProfiles = agentProfiles,
                    showStreamingCursor = showStreamingCursor,
                    metadataJson = metadataJson
                )
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            AgentBubbleContent(
                responseContent = content,
                isStreaming = isStreaming,
                isError = false,
                metadata = metadata,
                onImageClick = onImageClick,
                agentProfiles = agentProfiles,
                showStreamingCursor = showStreamingCursor,
                metadataJson = metadataJson
            )
        }
    }
}

@Composable
private fun AgentBubbleContent(
    responseContent: String,
    isStreaming: Boolean,
    isError: Boolean,
    metadata: TurnMetadata?,
    onImageClick: ((String) -> Unit)? = null,
    agentProfiles: Map<String, String> = emptyMap(),
    showStreamingCursor: Boolean = false,
    metadataJson: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        val displayContent = if (showStreamingCursor && responseContent.isNotBlank()) {
            responseContent + STREAMING_CURSOR
        } else {
            responseContent
        }
        if (displayContent.isNotBlank()) {
            MessageContentWithAttachments(
                content = displayContent,
                isUser = false,
                onImageClick = onImageClick,
                agentProfiles = agentProfiles,
                metadataJson = metadataJson
            )
        }

        if (isError) {
            Text(
                text = stringResource(R.string.chat_error_label),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (metadata != null) {
            AnimatedVisibility(
                visible = !isStreaming,
                enter = fadeIn() + expandVertically()
            ) {
                TurnMetadataBar(metadata = metadata)
            }
        }
    }
}

@Composable
private fun TurnMetadataBar(
    metadata: TurnMetadata,
    modifier: Modifier = Modifier
) {
    val items = mutableListOf<String>()
    if (metadata.totalTokens > 0) {
        if (metadata.inputTokens > 0 || metadata.outputTokens > 0) {
            items.add("↑${formatTokenCount(metadata.inputTokens)} ↓${formatTokenCount(metadata.outputTokens)}")
        } else {
            items.add("${formatTokenCount(metadata.totalTokens)} tok")
        }
    }
    if (metadata.cacheReadTokens > 0 || metadata.cacheWriteTokens > 0) {
        val cacheParts = mutableListOf<String>()
        if (metadata.cacheReadTokens > 0) cacheParts.add("CR${formatTokenCount(metadata.cacheReadTokens)}")
        if (metadata.cacheWriteTokens > 0) cacheParts.add("CW${formatTokenCount(metadata.cacheWriteTokens)}")
        items.add(cacheParts.joinToString(" "))
    }
    if (metadata.apiCalls > 0) {
        items.add(stringResource(R.string.chat_api_calls, metadata.apiCalls))
    }
    if (metadata.model.isNotBlank()) {
        items.add(if (metadata.isFallback) "🔄 ${metadata.model} (fallback)" else metadata.model)
    }
    if (metadata.latencyMs > 0) {
        items.add(if (metadata.latencyMs < 1000) "${metadata.latencyMs}ms" else "%.1fs".format(metadata.latencyMs / 1000.0))
    }
    if (items.isEmpty() && metadata.contextTokens <= 0) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) {
        if (items.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = items.joinToString(" · "),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    lineHeight = 12.sp
                )
            }
        }
        if (metadata.contextTokens > 0 && metadata.maxContextTokens > 0) {
            val ratio = (metadata.contextTokens.toFloat() / metadata.maxContextTokens.toFloat()).coerceIn(0f, 1f)
            val pct = (ratio * 100).toInt()
            val barColor = if (ratio > 0.5f) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatTokenCount(metadata.contextTokens)}/${formatTokenCount(metadata.maxContextTokens)} $pct%",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(3.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(1.5.dp)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(48.dp * ratio)
                            .background(barColor, RoundedCornerShape(1.5.dp))
                    )
                }
            }
        }
    }
}

private fun formatTokenCount(count: Long): String {
    return when {
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1fK".format(count / 1_000.0)
        else -> count.toString()
    }
}
