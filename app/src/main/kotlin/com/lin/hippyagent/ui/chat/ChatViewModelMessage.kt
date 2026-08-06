package com.lin.hippyagent.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.lin.hippyagent.core.agent.Agent
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.agent.QueuedMessage
import com.lin.hippyagent.core.agent.collaboration.MentionParser
import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.session.SessionMessage
import com.lin.hippyagent.core.agent.processMessageStream
import com.lin.hippyagent.core.agent.processMessage
import com.lin.hippyagent.core.chat.ChatTurn
import com.lin.hippyagent.core.chat.SystemTurnType
import com.lin.hippyagent.core.chat.TurnStatus
import com.lin.hippyagent.core.command.CommandContext
import com.lin.hippyagent.core.command.CommandRegistry
import timber.log.Timber
import com.lin.hippyagent.R

/**
 * 模式解析结果: 后缀 + 是否需升级到复杂模型。
 * 升级标志会通过 [com.lin.hippyagent.core.agent.Agent.processMessageStream] 的
 * forceEscalate 形参传到 Agent,使复杂任务模型在本 turn 实际生效。
 */
internal data class ModeSuffixResult(
    val suffix: String?,
    val useComplexModel: Boolean,
)

/**
 * 解析模式并应用模式过滤;返回 (system prompt 后缀, useComplexModel)。
 * 失败 / orchestrator 不可用时返回 (null, false),不打断主流程。
 */
internal suspend fun ChatViewModel.resolveModeSuffix(
    content: String,
    agentId: String,
    agent: Agent,
    turnId: String? = null,
    sessionId: String? = null,
): ModeSuffixResult {
    val orchestrator = modeOrchestrator ?: return ModeSuffixResult(null, false)
    val nonNullSessionId = sessionId
    val modeOverride: com.lin.hippyagent.core.skill.AgentMode? = nonNullSessionId
        ?.let { agent.getSessionState(it).modeOverride }
        ?.let { runCatching { com.lin.hippyagent.core.skill.AgentMode.valueOf(it) }.getOrNull() }
    // modeOverride 非空 ⇒ 上面 sessionId?.let 一定走过 ⇒ nonNullSessionId 必非空
    if (modeOverride != null && nonNullSessionId != null) {
        agent.consumeModeOverride(nonNullSessionId)
        Timber.w("Consumed modeOverride=$modeOverride for session=$nonNullSessionId")
    }
    val effectiveMode = modeOverride ?: _selectedMode.value
    // 仅在 AUTO/WORK 模式下显示「决策中」状态 (USER_SELECTED/PROFILE_DEFAULT 不走 LLM 决策)
    val isAutoOrWork = effectiveMode == com.lin.hippyagent.core.skill.AgentMode.AUTO ||
        effectiveMode == com.lin.hippyagent.core.skill.AgentMode.WORK
    if (isAutoOrWork) {
        _uiState.update { it.copy(isModeDeciding = true) }
    }
    return try {
        val resolution = if (modeOverride != null) {
            com.lin.hippyagent.core.agent.mode.ModeOrchestrator.ModeResolution(
                mode = modeOverride,
                source = com.lin.hippyagent.core.agent.mode.ModeOrchestrator.ModeSource.AUTO_DECIDED,
                reasoning = "智能体声明切换 → ${modeOverride.name}",
            )
        } else {
            orchestrator.resolveMode(
                agentId = agentId,
                profile = agent.profileConfig,
                userMessage = content,
                sessionSelectedMode = _selectedMode.value.takeIf { it != com.lin.hippyagent.core.skill.AgentMode.AUTO },
            )
        }
        val suffix = orchestrator.applyForMode(agentId, agent.profileConfig, resolution)
        _uiState.update {
            it.copy(
                autoDecidedMode = resolution.mode.name,
                autoDecidedModeSource = resolution.source.name,
                autoDecidedModeReasoning = resolution.reasoning,
                autoDecidedModeTurnId = turnId,
            )
        }
        ModeSuffixResult(
            suffix = suffix.takeIf { s -> s.isNotBlank() },
            useComplexModel = resolution.useComplexModel,
        )
    } catch (e: Exception) {
        Timber.w(e, "ModeOrchestrator: failed to resolve mode, skipping")
        ModeSuffixResult(null, false)
    } finally {
        if (isAutoOrWork) {
            _uiState.update { it.copy(isModeDeciding = false) }
        }
    }
}

internal fun ChatViewModel.flushStreamingState(
    contentBuilder: StringBuilder,
    thinkingBuilder: StringBuilder,
    streamingTurnId: String,
    contentDirty: Boolean,
    thinkingDirty: Boolean
) {
    if (!contentDirty && !thinkingDirty) return
    _streamingState.update { state ->
        val newContent = if (contentDirty) contentBuilder.toString() else state.streamingContent
        val newThinking = if (thinkingDirty) thinkingBuilder.toString() else state.streamingThinkingContent
        if (newContent === state.streamingContent && newThinking === state.streamingThinkingContent && state.streamingTurnId == streamingTurnId) {
            state
        } else {
            state.copy(
                streamingContent = newContent,
                streamingThinkingContent = newThinking,
                streamingTurnId = streamingTurnId
            )
        }
    }
}

internal suspend fun ChatViewModel.deliverMessage(agent: Agent, sessionId: String, content: String, planContext: String? = null, skipUserMessage: Boolean = false) {
    // 重新获取最新的 agent 实例（可能已被配置界reload
    val freshAgent = agentFactory.getAgent(agent.profileConfig.agentId) ?: agent
    val selectedProviderId = _uiState.value.selectedProviderId
    val overrideModel = _uiState.value.selectedModel.takeIf { it.isNotEmpty() }
    if (overrideModel == null && freshAgent.profileConfig.modelName.isEmpty()) {
        sessionStore.addMessage(
            sessionId,
            MessageRole.ASSISTANT,
            context.getString(R.string.chat_model_not_configured)
        ).onSuccess { errorMsg ->
            _uiState.update {
                it.copy(turns = it.turns + ChatTurn.AgentTurn(
                    id = errorMsg.id,
                    response = errorMsg
                ))
            }
        }
        return
    }

    var streamingTurnId = "streaming_${System.currentTimeMillis()}"

    // turns 中插入一个空streaming turn，同时初始化 streaming 状
    val streamingTurn = ChatTurn.AgentTurn(
        id = streamingTurnId,
        response = SessionMessage(
            id = streamingTurnId,
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = "",
            timestamp = java.time.Instant.now()
        ),
        status = TurnStatus.STREAMING
    )
    _uiState.update { it.copy(turns = it.turns + streamingTurn) }
    _streamingState.update { StreamingState(streamingTurnId = streamingTurnId) }

    // 使用 StringBuilder 在外部累积，减少 String 对象创建
    val contentBuilder = StringBuilder(4096)
    val thinkingBuilder = StringBuilder(2048)
    var lastUpdateTime = 0L
    val updateIntervalMs = 200L
    var contentDirty = false
    var thinkingDirty = false

    var thinkingChunkCount = 0
    var messageCountBeforeCompaction = 0

    val modeResolution = resolveModeSuffix(content, freshAgent.profileConfig.agentId, freshAgent, turnId = streamingTurnId, sessionId = sessionId)

    try {
        agent.processMessageStream(
            sessionId = sessionId,
            channelId = "console",
            content = content,
            overrideModel = overrideModel,
            overrideProviderId = selectedProviderId,
            planContext = planContext,
            skipUserMessage = skipUserMessage,
            systemPromptSuffix = modeResolution.suffix,
            forceEscalate = modeResolution.useComplexModel,
        )
            .collect { chunk ->
                when (chunk) {
                    is com.lin.hippyagent.core.agent.StreamChunk.Content -> {
                        contentBuilder.append(chunk.text)
                        contentDirty = true
                        if (chunk.text.contains("迭代轮次已耗尽")) {
                            _uiState.update { it.copy(iterationExhausted = true) }
                        }
                    }
                    is com.lin.hippyagent.core.agent.StreamChunk.Thinking -> {
                        thinkingBuilder.append(chunk.text)
                        thinkingDirty = true
                        thinkingChunkCount++
                    }
                    is com.lin.hippyagent.core.agent.StreamChunk.Compaction -> {
                    }
                    is com.lin.hippyagent.core.agent.StreamChunk.NewIteration -> {
                        flushStreamingState(contentBuilder, thinkingBuilder, streamingTurnId, contentDirty, thinkingDirty)
                        contentBuilder.clear()
                        thinkingBuilder.clear()
                        contentDirty = false
                        thinkingDirty = false
                        lastUpdateTime = System.currentTimeMillis()
                        val newStreamingTurnId = "streaming_${System.currentTimeMillis()}"
                        val newStreamingTurn = ChatTurn.AgentTurn(
                            id = newStreamingTurnId,
                            response = SessionMessage(
                                id = newStreamingTurnId,
                                sessionId = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = "",
                                timestamp = java.time.Instant.now()
                            ),
                            status = TurnStatus.STREAMING
                        )
                        streamingTurnId = newStreamingTurnId
                        _uiState.update { it.copy(turns = it.turns + newStreamingTurn) }
                        _streamingState.update { StreamingState(streamingTurnId = newStreamingTurnId) }
                    }
                    is com.lin.hippyagent.core.agent.StreamChunk.CompactionStarted -> {
                        messageCountBeforeCompaction = chunk.messagesToCompress + chunk.messagesToKeep
                        flushStreamingState(contentBuilder, thinkingBuilder, streamingTurnId, contentDirty, thinkingDirty)
                        contentDirty = false
                        thinkingDirty = false
                        lastUpdateTime = System.currentTimeMillis()
                        val tokenPct = if (chunk.maxTokens > 0) (chunk.totalTokens * 100 / chunk.maxTokens) else 0
                        val totalK = if (chunk.totalTokens >= 1000) "${"%.1f".format(chunk.totalTokens / 1000.0)}k" else "${chunk.totalTokens}"
                        val maxK = if (chunk.maxTokens >= 1000) "${"%.1f".format(chunk.maxTokens / 1000.0)}k" else "${chunk.maxTokens}"
                        val content = context.getString(R.string.chat_compaction_started, totalK, maxK, tokenPct, chunk.messagesToCompress + chunk.messagesToKeep, chunk.messagesToCompress, chunk.messagesToKeep)
                        _uiState.update { state ->
                            val systemTurn = ChatTurn.SystemTurn(
                                id = "compaction_${System.currentTimeMillis()}",
                                content = content,
                                type = com.lin.hippyagent.core.chat.SystemTurnType.INFO
                            )
                            val lastUserTurnIndex = state.turns.indexOfLast { it is ChatTurn.UserTurn }
                            val insertIndex = (lastUserTurnIndex + 1).coerceIn(0, state.turns.size)
                            val newTurns = state.turns.toMutableList()
                            newTurns.add(insertIndex, systemTurn)
                            state.copy(turns = newTurns.toList())
                        }
                    }
                    is com.lin.hippyagent.core.agent.StreamChunk.CompactionCompleted -> {
                        flushStreamingState(contentBuilder, thinkingBuilder, streamingTurnId, contentDirty, thinkingDirty)
                        contentDirty = false
                        thinkingDirty = false
                        val newK = if (chunk.newTokenEstimate >= 1000) "${"%.1f".format(chunk.newTokenEstimate / 1000.0)}k" else "${chunk.newTokenEstimate}"
                        val maxK = if (chunk.maxTokens >= 1000) "${"%.1f".format(chunk.maxTokens / 1000.0)}k" else "${chunk.maxTokens}"
                        val beforeK = if (chunk.beforeTokens >= 1000) "${"%.1f".format(chunk.beforeTokens / 1000.0)}k" else "${chunk.beforeTokens}"
                        val savedK = if ((chunk.beforeTokens - chunk.newTokenEstimate) >= 1000) "${"%.1f".format((chunk.beforeTokens - chunk.newTokenEstimate) / 1000.0)}k" else "${(chunk.beforeTokens - chunk.newTokenEstimate).coerceAtLeast(0)}"
                        val newPct = if (chunk.maxTokens > 0) (chunk.newTokenEstimate * 100 / chunk.maxTokens) else 0
                        val content = context.getString(R.string.chat_compaction_completed, beforeK, newK, maxK, newPct, savedK, chunk.compressedCount, thinkingChunkCount, freshAgent.state.value.getSessionState(sessionId).toolCallCount, messageCountBeforeCompaction)
                        _uiState.update { state ->
                            val turns = state.turns.map { turn ->
                                if (turn is ChatTurn.SystemTurn && turn.id.startsWith("compaction_")) {
                                    turn.copy(content = content, type = com.lin.hippyagent.core.chat.SystemTurnType.SUCCESS)
                                } else turn
                            }
                            val lastAgentIdx = turns.indexOfLast { it is ChatTurn.AgentTurn }
                            val updatedTurns = if (lastAgentIdx >= 0) {
                                val lastTurn = turns[lastAgentIdx] as ChatTurn.AgentTurn
                                val updatedMetadata = lastTurn.metadata?.copy(
                                    contextTokens = chunk.newTokenEstimate.toLong(),
                                    maxContextTokens = chunk.maxTokens.toLong()
                                )
                                turns.toMutableList().apply { this[lastAgentIdx] = lastTurn.copy(metadata = updatedMetadata) }
                            } else turns
                            state.copy(turns = updatedTurns)
                        }
                        sessionStore.addMessage(sessionId, MessageRole.SYSTEM, content)
                    }
                    is com.lin.hippyagent.core.agent.StreamChunk.TaskCompleted -> {
                        flushStreamingState(contentBuilder, thinkingBuilder, streamingTurnId, contentDirty, thinkingDirty)
                        contentDirty = false
                        thinkingDirty = false
                    }
                }
                if (contentDirty || thinkingDirty) {
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= updateIntervalMs) {
                        flushStreamingState(contentBuilder, thinkingBuilder, streamingTurnId, contentDirty, thinkingDirty)
                        contentDirty = false
                        thinkingDirty = false
                        lastUpdateTime = now
                    }
                }
            }

        flushStreamingState(contentBuilder, thinkingBuilder, streamingTurnId, contentDirty, thinkingDirty)

        // 清除 streaming 状态并重新加载
        _streamingState.update { StreamingState() }
        reloadTurnsFromStore(sessionId)
        // 智能体可能通过文件工具修改Profile，刷新以保持 UI 同步
        agentRepository?.refreshProfiles()

        // 自动 flush 排队消息 Agent 回到 IDLE 时处理等待中的消
        if (!messageQueue.isEmpty()) {
            flushQueuedMessages()
        }

        val lastAssistantMsg = _uiState.value.turns
            .filterIsInstance<ChatTurn.AgentTurn>()
            .lastOrNull()?.response
        if (lastAssistantMsg != null && lastAssistantMsg.content.isNotEmpty()) {
            val name = _uiState.value.agentName.ifEmpty { context.getString(R.string.chat_agent_fallback_name) }
            val sessionName = _uiState.value.sessionTitle
        Timber.d("ChatViewModel: sending agent notification, sessionId=%s", sessionId)
            notificationService?.sendAgentMessageNotification(
                agentName = name,
                sessionName = sessionName,
                message = lastAssistantMsg.content,
                sessionId = sessionId
            )
        }
    } catch (e: CancellationException) {
        _streamingState.update { StreamingState() }
        _uiState.update { it.copy(agentStatus = AgentStatus.IDLE) }
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Agent failed to process stream message")

        _streamingState.update { StreamingState() }
        _uiState.update { it.copy(agentStatus = AgentStatus.IDLE) }

        // 同一会话正在处理 排队等待（多会话并行后此情况已少见，但保留兼容）
        if (e.message?.contains("Session") == true && e.message?.contains("busy") == true) {
            messageQueue.enqueue(QueuedMessage(content = content, sessionId = sessionId, channelId = "console"))
            _uiState.update { it.copy(messageQueueSize = messageQueue.size()) }
            _uiState.update { state ->
                state.copy(turns = state.turns.filter { it.id != streamingTurnId })
            }
            return
        }

        val isSseError = e.message?.contains("SSE") == true ||
                e.message?.contains("stream") == true ||
                e.message?.contains("connect") == true ||
                e.message?.contains("timeout") == true

        if (isSseError) {
            Timber.w(e, "SSE streaming failed, falling back to non-streaming mode")
            try {
                _uiState.update { state ->
                    state.copy(turns = state.turns.filter { it.id != streamingTurnId })
                }

                val result = agent.processMessage(sessionId, "console", content, overrideModel, skipUserMessage = true, overrideProviderId = selectedProviderId)
                if (result.isFailure) {
                    val fallbackError = result.exceptionOrNull()
                    Timber.e(fallbackError, "Non-streaming fallback failed")
                    throw fallbackError ?: Exception("Fallback failed")
                }
                reloadTurnsFromStore(sessionId)
                agentRepository?.refreshProfiles()

                val lastAssistantMsg = _uiState.value.turns
                    .filterIsInstance<ChatTurn.AgentTurn>()
                    .lastOrNull()?.response
                if (lastAssistantMsg != null && lastAssistantMsg.content.isNotEmpty()) {
                    val name = _uiState.value.agentName.ifEmpty { context.getString(R.string.chat_agent_fallback_name) }
            val sessionName = _uiState.value.sessionTitle
            notificationService?.sendAgentMessageNotification(
                        agentName = name,
                        sessionName = sessionName,
                        message = lastAssistantMsg.content,
                        sessionId = sessionId
                    )
                }
                // 自动 flush 排队消息
                if (!messageQueue.isEmpty()) {
                    flushQueuedMessages()
                }
                return
            } catch (fallbackError: Exception) {
                Timber.e(fallbackError, "Non-streaming fallback also failed")
            }
        }

        _uiState.update { state ->
            state.copy(turns = state.turns.filter { it.id != streamingTurnId })
        }
        val errorText = when {
            e.message?.contains("达到最大重") == true -> context.getString(R.string.chat_error_max_retries_reached)
            e.message?.contains("401") == true -> context.getString(R.string.chat_error_forbidden)
            e.message?.contains("403") == true -> context.getString(R.string.chat_error_forbidden)
            e.message?.contains("404") == true -> context.getString(R.string.chat_error_not_found)
            e.message?.contains("429") == true -> context.getString(R.string.chat_error_rate_limited)
            e.message?.contains("400") == true -> context.getString(R.string.chat_error_bad_request)
            e.message?.contains("502") == true -> context.getString(R.string.chat_error_bad_gateway)
            e.message?.contains("503") == true -> context.getString(R.string.chat_error_service_unavailable)
            e.message?.contains("connect") == true || e.message?.contains("timeout") == true ->
                context.getString(R.string.chat_error_network_connection)
            e.message?.contains("SSE failed") == true -> context.getString(R.string.chat_error_sse_failed)
            else -> context.getString(R.string.chat_error_generic)
        }
        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, errorText)
            .onSuccess { errorMsg ->
                _uiState.update {
                    it.copy(turns = it.turns + ChatTurn.AgentTurn(
                        id = errorMsg.id,
                        response = errorMsg,
                        status = TurnStatus.ERROR
                    ))
                }
            }

        val name = _uiState.value.agentName.ifEmpty { context.getString(R.string.chat_agent_fallback_name) }
        val sessionName = _uiState.value.sessionTitle
        notificationService?.sendAgentMessageNotification(
            agentName = name,
            sessionName = sessionName,
            message = errorText,
            sessionId = sessionId
        )
    }
}

/**
 * 群组消息投通过 AgentGroupManager 协调多智能体响应
 */
internal suspend fun ChatViewModel.deliverGroupMessage(sessionId: String, content: String, mentionChipIds: List<String> = emptyList()) {
    val groupId = sessionId
    val group = agentGroupManager?.getOrCreateAgentGroup(groupId)
    if (group == null) {
        _uiState.update { it.copy(agentStatus = AgentStatus.IDLE) }
        return
    }

    try {
        _uiState.update { it.copy(agentStatus = AgentStatus.THINKING) }

        group.onAgentReplied = { agentId, agentName, content, sid ->
            val name = agentName.ifEmpty { context.getString(R.string.chat_agent_fallback_name) }
            val sessionName = _uiState.value.sessionTitle
            notificationService?.sendAgentMessageNotification(
                agentName = name,
                sessionName = sessionName,
                message = content,
                sessionId = sid,
                agentId = agentId
            )
            if (sid != sessionId) {
                viewModelScope.launch { sessionStore.incrementUnread(sid) }
            }
        }

        group.agentIds.forEach { aid ->
            val groupAgent = agentFactory.getAgent(aid)
            if (groupAgent != null) {
                planViewModel?.registerPlanToolsIfNeeded(groupAgent)
            }
        }

        // 使用 MentionParser 统一解析 @提及（与 GroupMessageRouter 保持一致）
        val textMentions = MentionParser.parse(content)
        // 先精确匹agentId，再按显示名匹配
        val agentProfilesMap = agentRepository.getProfiles().first()
        val textMentionIds = textMentions.mapNotNull { mention ->
            group.agentIds.firstOrNull { it.equals(mention, ignoreCase = true) }
                ?: agentProfilesMap.entries
                    .firstOrNull { (_, profile) -> profile.name.equals(mention, ignoreCase = true) }
                    ?.key
                    ?.takeIf { it in group.agentIds }
        }
        val mentionedIds = (mentionChipIds + textMentionIds)
            .distinct()
            .filter { it in agentProfilesMap }
            .toList()

        Timber.d("deliverGroupMessage: calling group.processMessage for ${group.agentIds.size} agents")
        // 后台轮询刷新：agent 写入 session UI 即时看到新消息
        // 轮询持续到 group.isActive 为 false（包括异步 @传播完成）
        val pollJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(800)
                reloadTurnsFromStore(sessionId)
                if (!group.isActive.value) break
            }
        }
        val result = group.processMessage("user", content, mentionedIds)
        // processMessage 返回后异步 @传播可能仍在进行，等待 isActive 变为 false
        while (group.isActive.value) {
            kotlinx.coroutines.delay(500)
        }
        pollJob.cancel()
        reloadTurnsFromStore(sessionId)
        if (result.isSuccess) {
            Timber.d("deliverGroupMessage: success, response=${result.getOrDefault("").take(100)}")
            _uiState.update { it.copy(agentStatus = AgentStatus.IDLE) }
        } else {
            val error = result.exceptionOrNull()?.message ?: context.getString(R.string.chat_group_message_failed)
            Timber.w("deliverGroupMessage: failed: $error")
            _uiState.update { it.copy(agentStatus = AgentStatus.IDLE) }
            sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, context.getString(R.string.chat_group_message_failed_with_error, error))
                .onSuccess { errorMsg ->
                    _uiState.update {
                        it.copy(turns = it.turns + ChatTurn.AgentTurn(
                            id = errorMsg.id,
                            response = errorMsg,
                            status = TurnStatus.ERROR
                        ))
                    }
                }
        }
        reloadTurnsFromStore(sessionId)
        // 智能体可能通过文件工具修改Profile，刷新以保持 UI 同步
        agentRepository?.refreshProfiles()
    } catch (e: Exception) {
        _uiState.update { it.copy(agentStatus = AgentStatus.IDLE) }
        Timber.e(e, "Group message delivery failed")
        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, context.getString(R.string.chat_group_message_exception, e.message))
            .onSuccess { errorMsg ->
                _uiState.update {
                    it.copy(turns = it.turns + ChatTurn.AgentTurn(
                        id = errorMsg.id,
                        response = errorMsg,
                        status = TurnStatus.ERROR
                    ))
                }
            }
        // 异常情况下也尝试刷新
        agentRepository?.refreshProfiles()
    } finally {
        group.onAgentReplied = null
    }
}

internal suspend fun ChatViewModel.reloadTurnsFromStore(sessionId: String) {
    sessionStore.getMessages(sessionId, includeCompressed = true)
        .onSuccess { storeMessages ->
            val existingImageUris = _uiState.value.turns
                .filterIsInstance<com.lin.hippyagent.core.chat.ChatTurn.UserTurn>()
                .associate { it.id to it.originalImageUri }
            val existingImageUrisByContent = _uiState.value.turns
                .filterIsInstance<com.lin.hippyagent.core.chat.ChatTurn.UserTurn>()
                .filter { it.originalImageUri != null }
                .associate { it.message.content to it.originalImageUri }
            val existingSystemTurns = _uiState.value.turns
                .filterIsInstance<com.lin.hippyagent.core.chat.ChatTurn.SystemTurn>()
            val existingMetadata = _uiState.value.turns
                .filterIsInstance<com.lin.hippyagent.core.chat.ChatTurn.AgentTurn>()
                .mapNotNull { turn -> turn.metadata?.let { turn.id to it } }
                .toMap()
            val existingMetadataByContent = _uiState.value.turns
                .filterIsInstance<com.lin.hippyagent.core.chat.ChatTurn.AgentTurn>()
                .filter { it.metadata != null }
                .mapNotNull { turn ->
                    turn.metadata?.let { metadata ->
                        (turn.response?.content ?: "").take(200) to metadata
                    }
                }
                .toMap()
            turnConverter.invalidateCache()
            val newTurns = turnConverter.convertIncremental(storeMessages).map { turn ->
                if (turn is com.lin.hippyagent.core.chat.ChatTurn.UserTurn && turn.originalImageUri == null) {
                    val preservedUri = existingImageUris[turn.id]
                        ?: existingImageUrisByContent[turn.message.content]
                    if (preservedUri != null) turn.copy(originalImageUri = preservedUri) else turn
                } else if (turn is com.lin.hippyagent.core.chat.ChatTurn.AgentTurn && turn.metadata == null) {
                    val byId = existingMetadata[turn.id]
                    if (byId != null) {
                        turn.copy(metadata = byId)
                    } else {
                        val contentKey = (turn.response?.content ?: "").take(200)
                        val byContent = existingMetadataByContent[contentKey]
                        if (byContent != null) turn.copy(metadata = byContent) else turn
                    }
                } else turn
            }
            val mergedTurns = mergeSystemTurns(newTurns, existingSystemTurns)
            _uiState.update {
                it.copy(turns = mergedTurns)
            }
            sessionStore.resetUnread(sessionId)
        }
}

/**
 * SystemTurn 合并到从 store 加载turns 列表中
 * SystemTurn 有两个来源：
 * 1. 持久化的 SYSTEM 消息（CompactionCompleted 等）已由 ChatTurnConverter 自动转为 SystemTurn
 * 2. 临时 UI 状态（CompactionStarted 等）需要在此合
 * 策略：去重后追加临时 SystemTurn 到末
 */
internal fun ChatViewModel.mergeSystemTurns(
    storeTurns: List<ChatTurn>,
    systemTurns: List<com.lin.hippyagent.core.chat.ChatTurn.SystemTurn>
): List<ChatTurn> {
    if (systemTurns.isEmpty()) return storeTurns

    // 提取 storeTurns 中已有的 SystemTurn id（用于去重）
    val persistedIds = storeTurns
        .filterIsInstance<com.lin.hippyagent.core.chat.ChatTurn.SystemTurn>()
        .map { it.id }.toSet()

    // 只保留未持久化的临时 SystemTurn（如 CompactionStarted INFO 状态）
    val tempSystemTurns = systemTurns
        .filter { it.id !in persistedIds }
        .sortedBy { turn ->
            turn.id.substringAfter("_").toLongOrNull() ?: 0L
        }

    if (tempSystemTurns.isEmpty()) return storeTurns
    return storeTurns + tempSystemTurns
}

/**
 * 将附件复制到工作目录，返(目标文件绝对路径, 图片URI) 元组
 * content 参数已不再用于文本替换，保留签名兼容
 */

fun ChatViewModel.sendMessage(content: String, attachedFileUri: String? = null, chips: List<InputChip> = emptyList(), quotedMessage: QuotedMessage? = null) {
    if (_uiState.value.sessionPhase != SessionPhase.READY) {
        _uiState.update { it.copy(errorMessage = context.getString(R.string.chat_session_not_initialized)) }
        return
    }

    _uiState.update { it.copy(iterationExhausted = false) }

    val sessionId = _uiState.value.sessionId
    val agentId = _uiState.value.agentId

    if (commandRegistry.isSystemCommand(content)) {
        viewModelScope.launch {
            try {
                val result = commandRegistry.execute(content, CommandContext(sessionId, agentId))
                val resultText = result?.message ?: context.getString(R.string.chat_command_execution_failed)
                sessionStore.addMessage(sessionId, MessageRole.USER, content)
                sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, resultText)
                reloadTurnsFromStore(sessionId)
            } catch (e: Exception) {
                Timber.e(e, "Command execution failed")
                val errorText = context.getString(R.string.chat_command_execution_failed_with_msg, e.message)
                sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, errorText)
                reloadTurnsFromStore(sessionId)
            }
        }
        return
    }

    if (!hasDerivedTitle) {
        hasDerivedTitle = true
        val newTitle = deriveTitleFromFirstMessage(content)
        viewModelScope.launch {
            sessionStore.updateSessionTitle(sessionId, newTitle)
                .onSuccess {
                    _uiState.update { it.copy(sessionTitle = newTitle) }
                }
        }
    }

    viewModelScope.launch {
        // 处理所Chip（多附件 + 技能）
        val processedChips = chips.filter { it.uri != null }.mapNotNull { chip ->
            val result = copyAttachmentToWorkspace("", chip.uri, agentId)
            val copiedPath = result.first.ifBlank { null }
            val imageUri = result.second
            Triple(chip, copiedPath, imageUri)
        }

        // 拼接附件标签到内
        var finalContent = content
        val imageUris = mutableListOf<String>()
        for ((chip, copiedPath, imageUri) in processedChips) {
            if (imageUri != null) imageUris.add(imageUri)
            if (copiedPath != null) {
                if (!finalContent.contains("[附件: $copiedPath]")) {
                    finalContent = if (finalContent.isBlank()) "[附件: $copiedPath]" else "$finalContent\n[附件: $copiedPath]"
                }
            }
        }

        // 兼容旧的单附件路
        if (attachedFileUri != null && chips.isEmpty()) {
            val (copiedPath, oldImageUri) = copyAttachmentToWorkspace(content, attachedFileUri, agentId)
            if (copiedPath.isNotBlank()) {
                finalContent = if (finalContent.isBlank()) "[附件: $copiedPath]" else "$finalContent\n[附件: $copiedPath]"
            }
            if (oldImageUri != null) imageUris.add(oldImageUri)
        }

        val skillChips = chips.filter { it.type == InputChipType.SKILL }
        for (skillChip in skillChips) {
            val skillId = skillChip.id.removePrefix("skill_")
            val insert = "/$skillId"
            // 文本中已/skillname 则不再重复拼
            if (!finalContent.contains(insert)) {
                finalContent = if (finalContent.isBlank()) insert else "$finalContent $insert"
            }
        }

        val mentionChips = chips.filter { it.type == InputChipType.MENTION }
        Timber.d("ChatViewModel: processing %d mention chips, context=%s, finalContent=%s", mentionChips.size, finalContent, finalContent)

        val planContextText = planViewModel?.buildPlanContext()

        val primaryImageUri = imageUris.firstOrNull()

        if (imageUris.isNotEmpty()) {
            val hasVisionCapability = isCurrentModelVisionCapable()
            if (!hasVisionCapability) {
                android.widget.Toast.makeText(context, context.getString(R.string.chat_model_no_image_support), android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // 群组聊天：提前计算目标智能体 ID（用于已读状态精确展示）
        val mentionChipIds: List<String>
        val targetedAgentIds: List<String>?
        if (agentId == "group" && agentGroupManager != null) {
            mentionChipIds = chips.filter { it.type == InputChipType.MENTION }
                .map { it.id.removePrefix("mention_") }
            val groupInfo = agentGroupManager?.getGroup(sessionId)
            val allAgentIds = groupInfo?.agentIds ?: emptyList()
            val textMentions = MentionParser.parse(finalContent)
            val agentProfilesMap = agentRepository.getProfiles().first()
            val textMentionIds = textMentions.mapNotNull { mention ->
                // 先尝试精确匹agentId
                allAgentIds.firstOrNull { it.equals(mention, ignoreCase = true) }
                    // 再尝试按 displayName 匹配（用户可@显示名）
                    ?: agentProfilesMap.entries
                        .firstOrNull { (_, profile) -> profile.name.equals(mention, ignoreCase = true) }
                        ?.key
                        ?.takeIf { it in allAgentIds }
            }
            val ids = (mentionChipIds + textMentionIds).distinct()
            // null = @ 提及，目标为全部成员；emptyList = 开启了只接收@消息且无@提及，无目标
            val mentionOnly = groupInfo?.mentionOnlyAgentIds?.isNotEmpty() == true
            targetedAgentIds = if (ids.isEmpty()) {
                if (mentionOnly) emptyList() else null
            } else ids
        } else {
            mentionChipIds = emptyList()
            targetedAgentIds = null
        }

        val optimisticTurn = ChatTurn.UserTurn(
            id = "temp_${System.currentTimeMillis()}",
            message = SessionMessage(
                id = "temp_${System.currentTimeMillis()}",
                sessionId = sessionId,
                role = MessageRole.USER,
                content = finalContent,
                timestamp = java.time.Instant.now()
            ),
            originalImageUri = primaryImageUri,
            targetedAgentIds = targetedAgentIds
        )
        _uiState.update { it.copy(turns = it.turns + optimisticTurn) }

        // 群组聊天走独立的群组消息路径
        if (agentId == "group" && agentGroupManager != null) {
            // 取消上一轮未完成的群组投递，避免并发死循
            deliveryJob?.cancel()
            deliveryJob = deliveryScope.launch {
                val userMsg = sessionStore.addMessage(sessionId, MessageRole.USER, finalContent).getOrNull()
                // @提及目标存入消息元数据，供后reload 恢复 targetedAgentIds
                if (userMsg != null) {
                    val metaBuilder = kotlinx.serialization.json.buildJsonObject {
                        if (targetedAgentIds != null) {
                            put("targetedAgentIds", kotlinx.serialization.json.buildJsonArray {
                                targetedAgentIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            })
                        }
                        if (quotedMessage != null) {
                            put("quotedMessageId", kotlinx.serialization.json.JsonPrimitive(quotedMessage.messageId))
                            put("quotedContent", kotlinx.serialization.json.JsonPrimitive(quotedMessage.content))
                            put("quotedSenderName", kotlinx.serialization.json.JsonPrimitive(quotedMessage.senderName))
                        }
                    }
                    val metaStr = metaBuilder.toString()
                    if (metaStr != "{}") {
                        sessionStore.updateMessageMetadata(userMsg.id, metaStr)
                    }
                }
                if (targetedAgentIds?.isEmpty() == true) {
                    sessionStore.addMessage(sessionId, MessageRole.SYSTEM, context.getString(R.string.chat_group_mention_only_notice))
                    reloadTurnsFromStore(sessionId)
                    _uiState.update { it.copy(agentStatus = AgentStatus.IDLE) }
                } else {
                    deliverGroupMessage(sessionId, finalContent, mentionChipIds)
                }
                deliveryJob = null
            }
        } else {
            val agent = agentFactory.getAgent(agentId)
            if (agent != null) {
                planViewModel?.registerPlanToolsIfNeeded(agent)
                // 持久化用户消息并写入引用元数据
                if (quotedMessage != null) {
                    val userMsg = sessionStore.addMessage(sessionId, MessageRole.USER, finalContent).getOrNull()
                    if (userMsg != null) {
                        val meta = kotlinx.serialization.json.buildJsonObject {
                            put("quotedMessageId", kotlinx.serialization.json.JsonPrimitive(quotedMessage.messageId))
                            put("quotedContent", kotlinx.serialization.json.JsonPrimitive(quotedMessage.content))
                            put("quotedSenderName", kotlinx.serialization.json.JsonPrimitive(quotedMessage.senderName))
                        }
                        sessionStore.updateMessageMetadata(userMsg.id, meta.toString())
                    }
                }
                // 查询当前会话per-session 状
                val sessionState = agent.state.value.getSessionState(sessionId)
                val sessionStatus = sessionState.status
                when (sessionStatus) {
                    AgentStatus.IDLE, AgentStatus.ERROR, AgentStatus.STOPPED -> {
                        deliveryJob?.cancel()
                        deliveryJob = deliveryScope.launch {
                            deliverMessage(agent, sessionId, finalContent, planContextText, skipUserMessage = quotedMessage != null)
                            deliveryJob = null
                        }
                    }
                    AgentStatus.THINKING, AgentStatus.EXECUTING_TOOL -> {
                        messageQueue.enqueue(QueuedMessage(content = finalContent, sessionId = sessionId, channelId = "console"))
                        _uiState.update {
                            it.copy(
                                messageQueueSize = messageQueue.size(),
                                turns = it.turns.filter { turn -> turn.id != optimisticTurn.id }
                            )
                        }
                    }
                }
            } else {
                _uiState.update {
                    it.copy(turns = it.turns.filter { turn -> turn.id != optimisticTurn.id })
                }
                sessionStore.addMessage(
                    sessionId,
                    MessageRole.ASSISTANT,
                    context.getString(R.string.chat_agent_not_found, agentId)
                ).onSuccess { errorMsg ->
                    _uiState.update {
                        it.copy(turns = it.turns + ChatTurn.AgentTurn(
                            id = errorMsg.id,
                            response = errorMsg
                        ))
                    }
                }
            }
        }
    }
}

fun ChatViewModel.flushQueuedMessages() {
    viewModelScope.launch {
        if (messageQueue.isEmpty()) return@launch

        val agent = agentFactory.getAgent(_uiState.value.agentId) ?: return@launch
        val sessionId = _uiState.value.sessionId
        val sessionState = agent.state.value.getSessionState(sessionId)
        if (sessionState.status != AgentStatus.IDLE && sessionState.status != AgentStatus.STOPPED && sessionState.status != AgentStatus.ERROR) return@launch

        val queuedMessages = messageQueue.flushAll()
        if (queuedMessages.isEmpty()) return@launch

        _uiState.update { it.copy(messageQueueSize = messageQueue.size()) }

        val firstSessionId = queuedMessages.first().sessionId
        val combinedContent = messageQueue.combineMessages(queuedMessages)

        sessionStore.addMessage(firstSessionId, MessageRole.USER, combinedContent)
            .onSuccess { userMessage ->
                _uiState.update {
                    it.copy(turns = it.turns + ChatTurn.UserTurn(
                        id = userMessage.id,
                        message = userMessage
                    ))
                }
            }

        val currentPlanContext = planViewModel?.buildPlanContext()
        deliverMessage(agent, firstSessionId, combinedContent, currentPlanContext, skipUserMessage = true)
        deliveryJob = null
    }
}
