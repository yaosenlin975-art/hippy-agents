package com.lin.hippyagent.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.chat.ChatTurn
import com.lin.hippyagent.core.chat.TurnElement
import com.lin.hippyagent.core.chat.TurnStatus
import com.lin.hippyagent.core.notification.ForegroundSessionTracker
import com.lin.hippyagent.core.voice.TTSService
import com.lin.hippyagent.core.voice.TtsCallback
import com.lin.hippyagent.core.voice.TtsRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val LocalCollapseAll = staticCompositionLocalOf { 0 }
val LocalExpandVisible = staticCompositionLocalOf { 0 }

private val MENTION_REGEX = Regex("@([\\w\\u4e00-\\u9fff-]+)")

private data class AgentTurnGroup(
    val agentIndices: List<Int>,
    val groupKey: String
)

private fun computeAgentTurnGroups(turns: List<ChatTurn>): List<AgentTurnGroup> {
    val groups = mutableListOf<AgentTurnGroup>()
    var currentAgentIndices = mutableListOf<Int>()
    for (i in turns.indices) {
        if (turns[i] is ChatTurn.AgentTurn) {
            currentAgentIndices.add(i)
        } else {
            if (currentAgentIndices.isNotEmpty()) {
                groups.add(AgentTurnGroup(
                    agentIndices = currentAgentIndices.toList(),
                    groupKey = turns[currentAgentIndices.first()].id
                ))
            }
            currentAgentIndices = mutableListOf()
        }
    }
    if (currentAgentIndices.isNotEmpty()) {
        groups.add(AgentTurnGroup(
            agentIndices = currentAgentIndices.toList(),
            groupKey = turns[currentAgentIndices.first()].id
        ))
    }
    return groups
}

@Stable
class ChatTtsState(val ttsService: TTSService) {
    val isSpeaking: kotlinx.coroutines.flow.StateFlow<Boolean> = ttsService.isSpeaking

    fun initialize() {
        ttsService.initialize()
    }

    fun stop() {
        ttsService.stop()
    }
}

@Composable
fun rememberChatTtsState(context: android.content.Context): ChatTtsState {
    val ttsService = remember(context) { TTSService(context) }
    return remember(ttsService) { ChatTtsState(ttsService) }
}

@Stable
class ChatSessionState(
    val viewModel: ChatViewModel,
    val inputViewModel: ChatInputViewModel,
    private val ttsService: TTSService
) {
    fun initialize(effectiveSessionId: String, agentId: String) {
        viewModel.initSession(effectiveSessionId, agentId)
        if (effectiveSessionId.isNotBlank()) {
            inputViewModel.restoreDraft(effectiveSessionId)
        }
    }

    fun setupForeground(sessionId: String) {
        ForegroundSessionTracker.setForeground(sessionId)
    }

    fun cleanup() {
        ForegroundSessionTracker.setForeground(null)
        inputViewModel.saveDraft()
        ttsService.stop()
    }
}

@Composable
fun rememberChatSessionState(
    viewModel: ChatViewModel,
    inputViewModel: ChatInputViewModel,
    ttsService: TTSService
): ChatSessionState {
    return remember(viewModel, inputViewModel, ttsService) {
        ChatSessionState(viewModel, inputViewModel, ttsService)
    }
}

@Stable
class ChatAutoScrollState {
    var isAtBottom by mutableStateOf(false)
        private set
    var userPinnedToBottom by mutableStateOf(false)
        internal set
    var wasAtBottom by mutableStateOf(true)
        internal set
    var scrollToBottom by mutableStateOf<() -> Unit>({})
        internal set

    internal fun updateIsAtBottom(value: Boolean) {
        isAtBottom = value
    }
}

@Composable
fun rememberChatAutoScrollState(
    listState: LazyListState,
    turns: List<ChatTurn>,
    streamingContent: String,
    agentStatus: AgentStatus,
    streamingThinkingContent: String = "",
    imeVisible: Boolean = false,
    sessionId: String = ""
): ChatAutoScrollState {
    val coroutineScope = rememberCoroutineScope()

    val state = remember { ChatAutoScrollState() }

    state.scrollToBottom = {
        state.wasAtBottom = true
        state.userPinnedToBottom = true
        coroutineScope.launch {
            try { listState.scrollToItem(turns.size + 1) } catch (_: Exception) {}
        }
    }

    val isAtBottom by remember { derivedStateOf { !listState.canScrollForward } }
    state.updateIsAtBottom(isAtBottom)

    LaunchedEffect(listState) {
        snapshotFlow { !listState.canScrollForward }
            .distinctUntilChanged()
            .collect { atBottom ->
                state.wasAtBottom = atBottom
                if (atBottom) state.userPinnedToBottom = true
            }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .collect { canScroll ->
                if (canScroll && !listState.isScrollInProgress) {
                    val firstVisible = listState.firstVisibleItemIndex
                    val totalItems = listState.layoutInfo.totalItemsCount
                    if (firstVisible + listState.layoutInfo.visibleItemsInfo.size < totalItems) {
                        state.userPinnedToBottom = false
                    }
                }
            }
    }

    val isStreamingActive = streamingContent.isNotEmpty() || streamingThinkingContent.isNotEmpty()
    val isAgentRunning = agentStatus == AgentStatus.THINKING || agentStatus == AgentStatus.EXECUTING_TOOL

    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty() && state.userPinnedToBottom) {
            try { listState.animateScrollToItem(turns.size + 1) } catch (_: Exception) {}
        }
    }

    LaunchedEffect(state.wasAtBottom, isStreamingActive, isAgentRunning) {
        if (state.userPinnedToBottom && (isStreamingActive || isAgentRunning)) {
            snapshotFlow {
                streamingContent.length + streamingThinkingContent.length
            }.collect {
                try { listState.scrollToItem(turns.size + 2) } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(agentStatus) {
        if (agentStatus == AgentStatus.IDLE) {
            if (!state.isAtBottom) {
                state.userPinnedToBottom = false
            }
        }
    }

    LaunchedEffect(sessionId) {
        if (turns.isNotEmpty()) {
            try { listState.scrollToItem(turns.size + 1) } catch (_: Exception) {}
        }
        state.wasAtBottom = true
        state.userPinnedToBottom = true
    }

    LaunchedEffect(Unit) {
        snapshotFlow { turns.size }.first { it > 0 }
        try { listState.scrollToItem(turns.size + 1) } catch (_: Exception) {}
        state.wasAtBottom = true
        state.userPinnedToBottom = true
    }

    LaunchedEffect(imeVisible) {
        if (imeVisible && turns.isNotEmpty()) {
            kotlinx.coroutines.delay(100)
            try { listState.animateScrollToItem(turns.size + 1) } catch (_: Exception) {}
        }
    }

    return state
}

@Composable
fun ChatTurnList(
    turns: List<ChatTurn>,
    listState: LazyListState,
    streamingState: StreamingState,
    agentStatus: AgentStatus,
    agentName: String,
    agentProfiles: Map<String, String>,
    disabledAgentIds: Set<String> = emptySet(),
    agentAvatarUrls: Map<String, String?> = emptyMap(),
    isGroupChat: Boolean,
    currentAgentId: String? = null,
    workingAgentIds: Set<String> = emptySet(),
    groupMemberIds: List<String> = emptyList(),
    showAvatars: Boolean = true,
    viewModel: ChatViewModel,
    permissionViewModel: PermissionViewModel,
    ttsService: TTSService,
    onImageClick: (String) -> Unit,
    onQuote: ((messageId: String, content: String, senderName: String) -> Unit)? = null,
    onLongClickAgentAvatar: ((agentName: String) -> Unit)? = null,
    isMultiSelectMode: Boolean = false,
    selectedMessageIds: Set<String> = emptySet(),
    onToggleSelection: (String) -> Unit = {},
    onEnterMultiSelect: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lastAgentTurnId by remember {
        derivedStateOf { turns.filterIsInstance<ChatTurn.AgentTurn>().lastOrNull()?.id }
    }

    val turnGroups = remember(turns) { computeAgentTurnGroups(turns) }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val indexToGroup by remember(turnGroups) {
        derivedStateOf {
            val map = mutableMapOf<Int, AgentTurnGroup>()
            for (group in turnGroups) {
                for (idx in group.agentIndices) {
                    map[idx] = group
                }
            }
            map
        }
    }

    LaunchedEffect(turnGroups, turns) {
        for (group in turnGroups) {
            val isStreaming = group.agentIndices.any { idx ->
                (turns[idx] as? ChatTurn.AgentTurn)?.status == TurnStatus.STREAMING
            }
            if (isStreaming) {
                expandedGroups.remove(group.groupKey)
            }
        }
    }

    LaunchedEffect(agentStatus) {
        if (agentStatus == AgentStatus.IDLE) {
            val keysToRemove = expandedGroups.keys.filter { key ->
                turnGroups.any { group ->
                    group.groupKey == key && group.agentIndices.any { idx ->
                        (turns[idx] as? ChatTurn.AgentTurn)?.status == TurnStatus.STREAMING
                    }
                }
            }
            keysToRemove.forEach { expandedGroups.remove(it) }
        }
    }

    val readStateMap by remember(turns, workingAgentIds, groupMemberIds, disabledAgentIds, agentProfiles) {
        derivedStateOf {
            if (!isGroupChat || groupMemberIds.isEmpty()) emptyMap()
            else {
                val map = mutableMapOf<String, Map<String, String>>()
                val mentionRegex = MENTION_REGEX
                for (i in turns.indices) {
                    val turn = turns[i]
                    val targetIds: List<String> = when (turn) {
                        is ChatTurn.UserTurn -> (turn.targetedAgentIds ?: groupMemberIds).filter { it !in disabledAgentIds && it in agentProfiles }
                        is ChatTurn.AgentTurn -> {
                            val content = turn.response?.content ?: continue
                            val mentionedIds = mutableListOf<String>()
                            mentionRegex.findAll(content).forEach { match ->
                                val name = match.groupValues[1]
                                val matchedId = agentProfiles.entries.firstOrNull { it.value == name }?.key
                                if (matchedId != null && matchedId != turn.senderAgentId && matchedId !in disabledAgentIds) mentionedIds.add(matchedId)
                            }
                            if (mentionedIds.isEmpty()) continue
                            mentionedIds
                        }
                        else -> continue
                    }
                    if (targetIds.isEmpty()) continue
                    val replied = mutableSetOf<String>()
                    for (j in i + 1 until turns.size) {
                        val laterTurn = turns[j]
                        if (laterTurn is ChatTurn.AgentTurn) {
                            laterTurn.senderAgentId?.let { replied.add(it) }
                        }
                    }
                    val statusMap = mutableMapOf<String, String>()
                    replied.forEach { aid -> if (aid in targetIds) statusMap[aid] = "已回复" }
                    targetIds.forEach { aid ->
                        if (aid !in statusMap) {
                            statusMap[aid] = if (aid in workingAgentIds) "工作中" else "已读"
                        }
                    }
                    map[turn.id] = statusMap
                }
                map
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        items(
            count = turns.size,
            key = { index -> turns[index].id },
            contentType = { index ->
                when (turns[index]) {
                    is ChatTurn.UserTurn -> "user"
                    is ChatTurn.AgentTurn -> "agent"
                    is ChatTurn.SystemTurn -> "system"
                    is ChatTurn.PermissionTurn -> "permission"
                    is ChatTurn.ClarificationTurn -> "clarification"
                    is ChatTurn.PrivateTurn -> "private"
                }
            }
        ) { index ->
            val turn = turns[index]
            val isGroupedWithPrevious = index > 0 &&
                when {
                    turn is ChatTurn.AgentTurn && turns[index - 1] is ChatTurn.AgentTurn -> {
                        if (isGroupChat) {
                            val prev = turns[index - 1] as ChatTurn.AgentTurn
                            prev.senderAgentId == (turn as ChatTurn.AgentTurn).senderAgentId
                        } else true
                    }
                    turn is ChatTurn.UserTurn && turns[index - 1] is ChatTurn.UserTurn -> true
                    else -> false
                }
            when (turn) {
                is ChatTurn.UserTurn -> {
                    val readStates = if (isGroupChat) {
                        readStateMap[turn.id] ?: emptyMap()
                    } else emptyMap()
                    UserTurnCard(
                        turn = turn,
                        onDelete = { viewModel.deleteMessage(turn.message.id) },
                        onEdit = { newContent -> viewModel.editMessage(turn.message.id, newContent) },
                        onQuote = onQuote,
                        isGroupedWithPrevious = isGroupedWithPrevious,
                        onImageClick = onImageClick,
                        agentReadStates = readStates,
                        agentProfiles = agentProfiles,
                        isMultiSelectMode = isMultiSelectMode,
                        isSelected = turn.message.id in selectedMessageIds,
                        onToggleSelection = { onToggleSelection(turn.message.id) },
                        onEnterMultiSelect = onEnterMultiSelect
                    )
                }
                is ChatTurn.AgentTurn -> {
                    val group = indexToGroup[index]
                    val isGroupStreaming = group != null && group.agentIndices.any { idx ->
                        (turns[idx] as? ChatTurn.AgentTurn)?.status == TurnStatus.STREAMING
                    }

                    if (group == null || isGroupStreaming) {
                        val isStreamingTarget = turn.id == streamingState.streamingTurnId
                        val streamingText = if (isStreamingTarget) streamingState.streamingContent else null
                        val isAgentRunningGlobal = agentStatus == AgentStatus.THINKING ||
                            agentStatus == AgentStatus.EXECUTING_TOOL
                        val isLastAgentTurn = turn.id == lastAgentTurnId
                        val effectiveIsAgentRunning = isAgentRunningGlobal && (isStreamingTarget || isLastAgentTurn)
                        val senderId = turn.senderAgentId
                        val turnAgentName = if (senderId != null) {
                            agentProfiles[senderId]?.takeIf { it.isNotBlank() } ?: senderId
                        } else agentName
                        val senderAvatarUrl = if (senderId != null) agentAvatarUrls[senderId] else (currentAgentId?.let { agentAvatarUrls[it] })
                        val isImageAvatar = !senderAvatarUrl.isNullOrBlank()
                        val shouldShowAvatar = isGroupChat || showAvatars
                        val turnAvatarUrl = if (shouldShowAvatar && isImageAvatar) senderAvatarUrl else null
                        AgentTurnCard(
                            turn = turn,
                            streamingOverride = streamingText,
                            streamingThinkingContent = if (isStreamingTarget) streamingState.streamingThinkingContent else null,
                            agentName = turnAgentName,
                            agentAvatarUrl = turnAvatarUrl,
                            isGroupedWithPrevious = isGroupedWithPrevious,
                            isAgentRunning = effectiveIsAgentRunning,
                            isGloballyActive = isAgentRunningGlobal,
                            isGroupChat = isGroupChat,
                            agentProfiles = agentProfiles,
                            agentReadStates = if (isGroupChat) readStateMap[turn.id] ?: emptyMap() else emptyMap(),
                            agentStatus = if (isLastAgentTurn && isAgentRunningGlobal) agentStatus else null,
                            onImageClick = onImageClick,
                            onRegenerate = { viewModel.regenerateLastResponse() },
                            onDelete = { viewModel.deleteMessage(turn.response?.id ?: return@AgentTurnCard) },
                            onSpeak = {
                                val content = turn.response?.content ?: return@AgentTurnCard
                                if (content.isNotBlank()) {
                                    ttsService.speak(
                                        TtsRequest(text = content),
                                        object : TtsCallback {
                                            override fun onComplete() {}
                                            override fun onError(error: Throwable) {
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    Toast.makeText(context, "语音播报失败: ${error.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    )
                                }
                            },
                            onQuote = onQuote,
                            onLongClickAvatar = onLongClickAgentAvatar,
                            isMultiSelectMode = isMultiSelectMode,
                            isSelected = turn.response?.id in selectedMessageIds,
                            onToggleSelection = { onToggleSelection(turn.response?.id ?: "") },
                            onEnterMultiSelect = onEnterMultiSelect
                        )
                    } else {
                        val isCollapsed = expandedGroups[group.groupKey] != true
                        val isLastInGroup = index == group.agentIndices.last()
                        val isFirstInGroup = index == group.agentIndices.first()

                        if (isCollapsed && !isLastInGroup) {
                            Box(modifier = Modifier.height(0.dp))
                        } else {
                            val collapseProcess = isCollapsed && isLastInGroup
                            val collapsedGroupTurns = if (collapseProcess) {
                                group.agentIndices.dropLast(1).mapNotNull { idx ->
                                    turns[idx] as? ChatTurn.AgentTurn
                                }
                            } else emptyList()
                            val onToggleExpandGroup: () -> Unit = {
                                val current = expandedGroups[group.groupKey] == true
                                expandedGroups[group.groupKey] = !current
                            }

                            val groupStepCount = remember(isCollapsed, group, turns) {
                                if (!isCollapsed) {
                                    group.agentIndices.sumOf { idx ->
                                        val t = turns[idx] as? ChatTurn.AgentTurn ?: return@sumOf 0
                                        t.elements.count { it is TurnElement.ThinkingSegment || it is TurnElement.ToolCallSegment } +
                                        (t.elements.filterIsInstance<TurnElement.TextSegment>().size - 1).coerceAtLeast(0)
                                    }
                                } else 0
                            }

                            val effectiveIsGroupedWithPrevious = if (isLastInGroup && collapseProcess) {
                                false
                            } else {
                                isGroupedWithPrevious
                            }

                            val isStreamingTarget = turn.id == streamingState.streamingTurnId
                            val streamingText = if (isStreamingTarget) streamingState.streamingContent else null
                            val isAgentRunningGlobal = agentStatus == AgentStatus.THINKING ||
                                agentStatus == AgentStatus.EXECUTING_TOOL
                            val isLastAgentTurn = turn.id == lastAgentTurnId
                            val effectiveIsAgentRunning = isAgentRunningGlobal && (isStreamingTarget || isLastAgentTurn)
                            val senderId = turn.senderAgentId
                            val turnAgentName = if (senderId != null) {
                                agentProfiles[senderId]?.takeIf { it.isNotBlank() } ?: senderId
                            } else agentName
                            val senderAvatarUrl = if (senderId != null) agentAvatarUrls[senderId] else (currentAgentId?.let { agentAvatarUrls[it] })
                            val isImageAvatar = !senderAvatarUrl.isNullOrBlank()
                            val shouldShowAvatar = isGroupChat || showAvatars
                            val turnAvatarUrl = if (shouldShowAvatar && isImageAvatar) senderAvatarUrl else null
                            AgentTurnCard(
                                turn = turn,
                                streamingOverride = streamingText,
                                streamingThinkingContent = if (isStreamingTarget) streamingState.streamingThinkingContent else null,
                                agentName = turnAgentName,
                                agentAvatarUrl = turnAvatarUrl,
                                isGroupedWithPrevious = effectiveIsGroupedWithPrevious,
                                isAgentRunning = effectiveIsAgentRunning,
                                isGloballyActive = isAgentRunningGlobal,
                                isGroupChat = isGroupChat,
                                agentProfiles = agentProfiles,
                                agentReadStates = if (isGroupChat) readStateMap[turn.id] ?: emptyMap() else emptyMap(),
                                agentStatus = if (isLastAgentTurn && isAgentRunningGlobal) agentStatus else null,
                                onImageClick = onImageClick,
                                onRegenerate = { viewModel.regenerateLastResponse() },
                                onDelete = { viewModel.deleteMessage(turn.response?.id ?: return@AgentTurnCard) },
                                onSpeak = {
                                    val content = turn.response?.content ?: return@AgentTurnCard
                                    if (content.isNotBlank()) {
                                        ttsService.speak(
                                            TtsRequest(text = content),
                                            object : TtsCallback {
                                                override fun onComplete() {}
                                                override fun onError(error: Throwable) {
                                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        Toast.makeText(context, "语音播报失败: ${error.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        )
                                    }
                                },
                                onQuote = onQuote,
                                onLongClickAvatar = onLongClickAgentAvatar,
                                isMultiSelectMode = isMultiSelectMode,
                                isSelected = turn.response?.id in selectedMessageIds,
                                onToggleSelection = { onToggleSelection(turn.response?.id ?: "") },
                                onEnterMultiSelect = onEnterMultiSelect,
                                collapseProcess = collapseProcess,
                                collapsedGroupTurns = collapsedGroupTurns,
                                onExpandGroup = onToggleExpandGroup,
                                isProcessExpanded = !isCollapsed,
                                showProcessHeader = !isCollapsed && isFirstInGroup,
                                showProcessFooter = !isCollapsed && isLastInGroup,
                                groupProcessStepCount = groupStepCount
                            )
                        }
                    }
                }
                is ChatTurn.SystemTurn -> SystemTurnCard(turn = turn)
                is ChatTurn.PermissionTurn -> {
                    val sessionId = viewModel.uiState.value.sessionId
                    val agentId = viewModel.uiState.value.agentId
                    PermissionTurnCard(
                    turn = turn,
                    onApproveOnce = { permissionViewModel.approvePermissionOnce(sessionId, agentId) },
                    onApproveAlways = { permissionViewModel.approvePermissionAlways(sessionId, agentId) },
                    onDenyOnce = { permissionViewModel.denyPermissionOnce(sessionId, agentId) },
                    onDenyAlways = { permissionViewModel.denyPermissionAlways(sessionId, agentId) }
                )
                }
                is ChatTurn.ClarificationTurn -> ClarificationTurnCard(
                    turn = turn,
                    onSelectOption = { option -> viewModel.resolveClarification(turn.id, option) },
                    onSkip = { viewModel.resolveClarification(turn.id, null) }
                )
                is ChatTurn.PrivateTurn -> {
                    if (!isGroupChat) {
                        PrivateTurnCard(
                            turn = turn,
                            agentProfiles = agentProfiles
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
fun BoxScope.ChatOverlays(
    turns: List<ChatTurn>,
    isAtBottom: Boolean,
    scrollToBottom: () -> Unit,
    ttsSpeaking: Boolean,
    onStopTts: () -> Unit,
    showFreeModelWarning: Boolean,
    onDismissFreeModelWarning: () -> Unit,
    onSuppressFreeModelWarning: () -> Unit
) {
    if (turns.isNotEmpty() && !isAtBottom) {
        SmallFloatingActionButton(
            onClick = { scrollToBottom() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 8.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                "回到底部"
            )
        }
    }

    if (ttsSpeaking) {
        SmallFloatingActionButton(
            onClick = { onStopTts() },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 8.dp),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ) {
            Icon(
                Icons.Default.Stop,
                "停止播放"
            )
        }
    }

    if (showFreeModelWarning) {
        AlertDialog(
            onDismissRequest = { onDismissFreeModelWarning() },
            title = { Text("隐私提醒", fontWeight = FontWeight.Bold) },
            text = {
                Text("你正在使用免费模型。免费模型可能将你的数据用于训练或分析，存在信息泄露风险。建议仅用于非敏感内容。")
            },
            confirmButton = {
                Button(onClick = { onDismissFreeModelWarning() }) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { onSuppressFreeModelWarning() }) {
                    Text("永不提醒")
                }
            }
        )
    }
}

class ChatFilePickers(
    val launchImagePicker: () -> Unit,
    val launchFilePicker: () -> Unit
)

@Composable
fun rememberChatFilePickers(
    onImagePicked: (InputChip) -> Unit,
    onFilePicked: (InputChip) -> Unit
): ChatFilePickers {
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(context, it)
            val isImage = fileName.substringAfterLast(".").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
            onImagePicked(InputChip(
                type = if (isImage) InputChipType.IMAGE else InputChipType.FILE,
                label = fileName,
                uri = it.toString()
            ))
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(context, it)
            val isImage = fileName.substringAfterLast(".").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
            onFilePicked(InputChip(
                type = if (isImage) InputChipType.IMAGE else InputChipType.FILE,
                label = fileName,
                uri = it.toString()
            ))
        }
    }

    return remember {
        ChatFilePickers(
            launchImagePicker = { imagePickerLauncher.launch("image/*") },
            launchFilePicker = { filePickerLauncher.launch(arrayOf("*/*")) }
        )
    }
}
