package com.lin.hippyagent.ui.chat

import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.session.SessionMessage
import com.lin.hippyagent.core.agent.session.Session
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.chat.ChatTurn
import com.lin.hippyagent.core.chat.SystemTurnType
import com.lin.hippyagent.core.chat.TurnStatus
import com.lin.hippyagent.core.mission.MissionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import com.lin.hippyagent.R

internal fun ChatViewModel.initSession(sessionId: String, agentId: String) {
        viewModelScope.launch {
            // 重置状
            hasDerivedTitle = false
            _uiState.update {
                it.copy(
                    sessionPhase = SessionPhase.LOADING,
                    isLoading = true
                )
            }
            _streamingState.update { StreamingState() }

            val sid = if (sessionId.isEmpty()) {
                sessionStore.createSession(agentId, context.getString(R.string.chat_new_session)).getOrNull()?.id ?: run {
                    _uiState.update { it.copy(sessionPhase = SessionPhase.ERROR, isLoading = false, errorMessage = context.getString(R.string.chat_create_session_failed)) }
                    return@launch
                }
            } else {
                sessionId
            }

            // 更新 deliveryScope，取消旧scope 避免内存泄漏
            currentSessionId = sid
            deliveryScope = DeliveryScopeManager.getOrCreateScope(sid)

            _uiState.update { it.copy(sessionId = sid, agentId = agentId) }

            planViewModel?.initPlanManager(agentId)

            var currentSession: Session? = null
            sessionStore.getSession(sid)
                .onSuccess { session ->
                    currentSession = session
                    if (session != null) {
                        if (agentId == "group" && groupRegistry != null) {
                            val group = groupRegistry.getGroup(sid)
                            if (group != null) {
                                _uiState.update { it.copy(sessionTitle = group.groupName, agentName = group.groupName) }
                            } else {
                                _uiState.update { it.copy(sessionTitle = session.title) }
                            }
                        } else {
                            _uiState.update { it.copy(sessionTitle = session.title) }
                        }
                        _uiState.update { it.copy(privacyMode = session.privacyMode) }
                        if (session.title != context.getString(R.string.chat_new_session)) {
                            hasDerivedTitle = true
                        }
                    } else {
                        // session 不存在（首次进入群聊，sid 即为 groupId
                        if (agentId == "group" && groupRegistry != null) {
                            val group = groupRegistry.getGroup(sid)
                            if (group != null) {
                                _uiState.update { it.copy(sessionTitle = group.groupName, agentName = group.groupName) }
                                sessionStore.createSession(agentId, group.groupName, sid)
                                hasDerivedTitle = true
                            }
                        }
                    }
                }
                .onFailure {
                    if (agentId == "group" && groupRegistry != null) {
                        val group = groupRegistry.getGroup(sid)
                        if (group != null) {
                            _uiState.update { it.copy(sessionTitle = group.groupName, agentName = group.groupName) }
                            sessionStore.createSession(agentId, group.groupName, sid)
                        }
                    }
                }

            sessionStore.getMessages(sid)
                .onSuccess { messages ->
                    val turns = turnConverter.convertIncremental(messages)
                    val summaryTurn = currentSession?.compressedSummary?.let { summary ->
                        if (summary.isNotBlank()) ChatTurn.SystemTurn(
                            id = "compressed_summary_$sid",
                            content = context.getString(R.string.chat_compressed_summary, summary),
                            type = SystemTurnType.INFO
                        ) else null
                    }
                    val finalTurns = if (summaryTurn != null) listOf(summaryTurn) + turns else turns
                    _uiState.update {
                        it.copy(
                            turns = finalTurns,
                            isLoading = false,
                            sessionPhase = SessionPhase.READY
                        )
                    }
                    sessionStore.resetUnread(sid)
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to load session messages")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            sessionPhase = SessionPhase.ERROR,
                            errorMessage = context.getString(R.string.chat_load_messages_failed, e.message)
                        )
                    }
                }

            registerAgentSpecificCommands(agentId)

            viewModelScope.launch {
                sessionStore.observeSessions().collect { sessions ->
                    _uiState.update { it.copy(allSessions = sessions) }
                }
            }
            viewModelScope.launch {
                sessionStore.observeUnreadSummary().collect { summary ->
                    _uiState.update { it.copy(sessionBadges = summary.sessionBadges, sessionUnreadCounts = summary.sessionUnreadCounts) }
                }
            }

            missionManager?.getActiveMission()?.let { mission ->
                if (mission.status == MissionStatus.RUNNING) {
                    _uiState.update { it.copy(activeMission = mission) }
                }
            }

            val agent = agentFactory.getAgent(agentId)
            if (agent != null) {
                val currentSessionState = agent.state.value.getSessionState(sid)
                if (currentSessionState.status == AgentStatus.THINKING ||
                    currentSessionState.status == AgentStatus.EXECUTING_TOOL) {
                    Timber.w("Agent $agentId session $sid stuck in ${currentSessionState.status}, force resetting")
                    agent.stopSession(sid)
                    agent.cleanupSessionState(sid)
                }
                val agentDisplayName = agent.profileConfig.name.ifBlank { agentId }
                val agentModel = agent.profileConfig.modelName
                _uiState.update {
                    it.copy(
                        agentName = agentDisplayName,
                        selectedModeLocked = agent.profileConfig.modeLocked,
                    )
                }
                viewModelScope.launch {
                    agentRepository.getProfiles().collect { profiles ->
                        val currentId = _uiState.value.agentId
                        val name = profiles[currentId]?.name?.ifBlank { currentId } ?: currentId
                        val locked = profiles[currentId]?.modeLocked ?: _uiState.value.selectedModeLocked
                        _uiState.update { it.copy(agentName = name, selectedModeLocked = locked) }
                    }
                }
                val matchedModel = _uiState.value.availableModels.find { model ->
                    model.first == agentModel || model.first.endsWith("/$agentModel")
                }
                // 优先从会话持久化模型恢复，若会话模型与智能体主模型相同则连锁更新
                val sessionModel = currentSession?.model?.takeIf { it.isNotEmpty() }
                val effectiveModel = if (sessionModel != null) {
                    // 会话有持久化模型：如果和智能体主模型相同，连锁更新为新主模型；否则保留会话选择
                    if (sessionModel == agentModel || sessionModel.endsWith("/$agentModel")) {
                        matchedModel?.first ?: agentModel
                    } else {
                        // 用户手动切换过模型，保留会话级选择
                        val sessionMatched = _uiState.value.availableModels.find { model ->
                            model.first == sessionModel || model.first.endsWith("/$sessionModel")
                        }
                        sessionMatched?.first ?: sessionModel
                    }
                } else {
                    matchedModel?.first ?: agentModel
                }
                val effectiveProviderId = _uiState.value.availableModels.find { it.first == effectiveModel }?.second
                    ?: agent.profileConfig.modelProvider
                _uiState.update { it.copy(selectedModel = effectiveModel, selectedProviderId = effectiveProviderId) }
                viewModelScope.launch {
                    var previousStatus = AgentStatus.IDLE
                    // 记录每轮开始时token 快照，用于计算单轮增
                    var turnStartTokenUsage = agent.tokenUsageState.value
                    var turnStartTime = 0L
                    agent.state.collect { agentState ->
                        // per-session 状态：直接sessionStates 读取当前会话的状
                        val currentSid = _uiState.value.sessionId
                        val sessionState = agentState.getSessionState(currentSid)
                        val newStatus = sessionState.status

                        // Agent IDLE 转为 THINKING（新一轮开始），记token 快照
                        if (newStatus == AgentStatus.THINKING && previousStatus == AgentStatus.IDLE) {
                            turnStartTokenUsage = agent.tokenUsageState.value
                            turnStartTime = System.currentTimeMillis()
                        }

                        // 工具执行完回THINKING：新一轮思考开始，重置 streaming 内容
                        // 避免上一轮的 thinking(A)/content 和本轮的合并显示
                        if (newStatus == AgentStatus.THINKING && previousStatus == AgentStatus.EXECUTING_TOOL) {
                            _streamingState.update { it.copy(streamingThinkingContent = "", streamingContent = "") }
                        }

                        _uiState.update { it.copy(agentStatus = newStatus) }

                        val nonIdleStatuses = agentState.sessionStates
                            .filter { it.value.status != AgentStatus.IDLE }
                            .mapValues { it.value.status }
                        _uiState.update { it.copy(sessionStatuses = it.sessionStatuses - agentState.sessionStates.keys + nonIdleStatuses) }

                        if (sessionState.pendingPermissionCommand != null || sessionState.missingAndroidPermissions.isNotEmpty()) {
                            permissionViewModel?.updatePermissionState(sessionState.pendingPermissionCommand, sessionState.missingAndroidPermissions)
                        }

                        if (newStatus == AgentStatus.IDLE && previousStatus == AgentStatus.IDLE) {
                            previousStatus = newStatus
                            return@collect
                        }

                        // EXECUTING_TOOL: 工具开始执sessionStore 读取 tool_calls 消息
                        // THINKING EXECUTING_TOOL 转来: 一轮工具完tool_result 已写
                        // IDLE 从任何非 IDLE 转来: 整个任务完成 必须重载以确保工具结果不残留 RUNNING 状
                        val sid = currentSid
                        if (sid.isNotEmpty() && (newStatus == AgentStatus.EXECUTING_TOOL ||
                                (newStatus == AgentStatus.THINKING && previousStatus == AgentStatus.EXECUTING_TOOL) ||
                                (newStatus == AgentStatus.IDLE && previousStatus != AgentStatus.IDLE) ||
                                (newStatus == AgentStatus.ERROR && previousStatus != AgentStatus.ERROR))) {
                            // 保留 streaming 状态，仅更turns 列表中的 toolCalls
                            val currentStreamingTurnId = _streamingState.value.streamingTurnId
                            // 保留已有originalImageUri（id 匹配 + content 内容兜底
                            val existingImageUris = _uiState.value.turns
                                .filterIsInstance<com.lin.hippyagent.core.chat.ChatTurn.UserTurn>()
                                .associate { it.id to it.originalImageUri }
                            val existingImageUrisByContent = _uiState.value.turns
                                .filterIsInstance<com.lin.hippyagent.core.chat.ChatTurn.UserTurn>()
                                .filter { it.originalImageUri != null }
                                .associate { it.message.content to it.originalImageUri }
                            sessionStore.getMessages(sid).onSuccess { storeMessages ->
                                val newTurns = turnConverter.convertIncremental(storeMessages).map { turn ->
                                    if (turn is com.lin.hippyagent.core.chat.ChatTurn.UserTurn && turn.originalImageUri == null) {
                                        val preservedUri = existingImageUris[turn.id]
                                            ?: existingImageUrisByContent[turn.message.content]
                                        if (preservedUri != null) turn.copy(originalImageUri = preservedUri) else turn
                                    } else turn
                                }
                                _uiState.update { state ->
                                    state.copy(turns = newTurns)
                                }
                                // 如果streamingTurnId，确保它仍然存在turns 列表
                                // （因streaming turn 可能reload 覆盖了）
                                val currentTurns = _uiState.value.turns
                                if (currentStreamingTurnId != null &&
                                    currentTurns.none { t -> t.id == currentStreamingTurnId }) {
                                    // 重新插入 streaming turn 到末尾（最新内容在最下方
                                    val streamingTurn = ChatTurn.AgentTurn(
                                        id = currentStreamingTurnId,
                                        response = SessionMessage(
                                            id = currentStreamingTurnId,
                                            sessionId = sid,
                                            role = MessageRole.ASSISTANT,
                                            content = _streamingState.value.streamingContent,
                                            timestamp = java.time.Instant.now()
                                        ),
                                        status = TurnStatus.STREAMING
                                    )
                                    _uiState.update { state ->
                                        state.copy(turns = state.turns + streamingTurn)
                                    }
                                }
                            }

                            // EXECUTING_TOOL 延迟重载：tool_call 可能尚未持久化到 store
                            // 延迟 500ms 后再次重载，确保工具调用在执行期间可
                            if (newStatus == AgentStatus.EXECUTING_TOOL) {
                                val delaySid = sid
                                val delayStreamingTurnId = _streamingState.value.streamingTurnId
                                viewModelScope.launch {
                                    kotlinx.coroutines.delay(500)
                                    // 仅在仍然EXECUTING_TOOL 状态时重载，避免重
                                    if (_uiState.value.agentStatus == AgentStatus.EXECUTING_TOOL && _uiState.value.sessionId == delaySid) {
                                        val delayExistingImageUris = _uiState.value.turns
                                            .filterIsInstance<com.lin.hippyagent.core.chat.ChatTurn.UserTurn>()
                                            .associate { it.id to it.originalImageUri }
                                        val delayExistingImageUrisByContent = _uiState.value.turns
                                            .filterIsInstance<com.lin.hippyagent.core.chat.ChatTurn.UserTurn>()
                                            .filter { it.originalImageUri != null }
                                            .associate { it.message.content to it.originalImageUri }
                                        sessionStore.getMessages(delaySid).onSuccess { storeMessages ->
                                            val newTurns = turnConverter.convertIncremental(storeMessages).map { turn ->
                                                if (turn is com.lin.hippyagent.core.chat.ChatTurn.UserTurn && turn.originalImageUri == null) {
                                                    val preservedUri = delayExistingImageUris[turn.id]
                                                        ?: delayExistingImageUrisByContent[turn.message.content]
                                                    if (preservedUri != null) turn.copy(originalImageUri = preservedUri) else turn
                                                } else turn
                                            }
                                            _uiState.update { state ->
                                                state.copy(turns = newTurns)
                                            }
                                            // 确保 streaming turn 仍存
                                            val currentTurns = _uiState.value.turns
                                            if (delayStreamingTurnId != null &&
                                                currentTurns.none { t -> t.id == delayStreamingTurnId }) {
                                                val streamingTurn = ChatTurn.AgentTurn(
                                                    id = delayStreamingTurnId,
                                                    response = SessionMessage(
                                                        id = delayStreamingTurnId,
                                                        sessionId = delaySid,
                                                        role = MessageRole.ASSISTANT,
                                                        content = _streamingState.value.streamingContent,
                                                        timestamp = java.time.Instant.now()
                                                    ),
                                                    status = TurnStatus.STREAMING
                                                )
                                                _uiState.update { state ->
                                                    state.copy(turns = state.turns + streamingTurn)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Agent 回到 IDLE 时，注入运行时元数据（token 单轮增量/model/延迟/API调用）到最后一AgentTurn
                        if (newStatus == AgentStatus.IDLE && previousStatus != AgentStatus.IDLE) {
                            // 轮询等待 tokenUsageState 更新完成（避免时序竞争导metadata 0
                            var currentUsage = agent.tokenUsageState.value
                            repeat(5) {
                                if (currentUsage.totalTokens > turnStartTokenUsage.totalTokens || currentUsage.apiCalls > turnStartTokenUsage.apiCalls) return@repeat
                                kotlinx.coroutines.delay(80)
                                currentUsage = agent.tokenUsageState.value
                            }
                            val deltaInput = currentUsage.inputTokens - turnStartTokenUsage.inputTokens
                            val deltaOutput = currentUsage.outputTokens - turnStartTokenUsage.outputTokens
                            val deltaTotal = currentUsage.totalTokens - turnStartTokenUsage.totalTokens
                            val deltaApiCalls = (currentUsage.apiCalls - turnStartTokenUsage.apiCalls).toInt().coerceAtLeast(0)
                            val deltaCacheRead = currentUsage.cacheReadTokens - turnStartTokenUsage.cacheReadTokens
                            val deltaCacheWrite = currentUsage.cacheWriteTokens - turnStartTokenUsage.cacheWriteTokens
                            val latencyMs = if (turnStartTime > 0) System.currentTimeMillis() - turnStartTime else 0L
                            val ctxInfo = agent.contextTokenInfo.value
                            // 检查是否使用了 fallback 模型
                            val fallbackModel = sessionState.usedFallbackModel
                            val isFallback = fallbackModel != null
                            val modelName = if (isFallback) {
                                fallbackModel
                            } else {
                                _uiState.value.selectedModel.takeIf { it.isNotEmpty() }
                                    ?: agent.profileConfig.modelName
                            }
                            if (deltaTotal > 0 || deltaApiCalls > 0 || modelName.isNotBlank() || deltaInput > 0 || deltaOutput > 0 || deltaCacheRead > 0 || deltaCacheWrite > 0) {
                                val turnMetadata = com.lin.hippyagent.core.chat.TurnMetadata(
                                    inputTokens = deltaInput.coerceAtLeast(0),
                                    outputTokens = deltaOutput.coerceAtLeast(0),
                                    totalTokens = deltaTotal.coerceAtLeast(0),
                                    model = modelName,
                                    latencyMs = latencyMs,
                                    apiCalls = deltaApiCalls,
                                    isFallback = isFallback,
                                    cacheReadTokens = deltaCacheRead.coerceAtLeast(0),
                                    cacheWriteTokens = deltaCacheWrite.coerceAtLeast(0),
                                    contextTokens = ctxInfo.currentTokens,
                                    maxContextTokens = ctxInfo.maxTokens
                                )
                                _uiState.update { state ->
                                    val turns = state.turns
                                    val lastAgentIdx = turns.indexOfLast { it is ChatTurn.AgentTurn }
                                    if (lastAgentIdx >= 0) {
                                        val lastTurn = turns[lastAgentIdx] as ChatTurn.AgentTurn
                                        val updatedTurn = lastTurn.copy(metadata = turnMetadata)
                                        state.copy(turns = turns.toMutableList().apply { this[lastAgentIdx] = updatedTurn })
                                    } else state
                                }
                                // 持久metadata SessionStore
                                val lastAgentTurn = _uiState.value.turns.filterIsInstance<ChatTurn.AgentTurn>().lastOrNull()
                                if (lastAgentTurn != null) {
                                    // 使用 SessionStore 中真实的最后一ASSISTANT 消息 ID（而非临时 streamingTurnId
                                    val realMsgId = sessionStore.getMessages(sid)
                                        .getOrDefault(emptyList())
                                        .lastOrNull { it.role == MessageRole.ASSISTANT }?.id
                                    val msgId = realMsgId ?: lastAgentTurn.response?.id ?: lastAgentTurn.id
                                    try {
                                        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
                                        val metadataJson = json.encodeToString(com.lin.hippyagent.core.chat.TurnMetadata.serializer(), turnMetadata)
                                        sessionStore.updateMessageMetadata(msgId, metadataJson)
                                    } catch (e: Exception) {
                                        Timber.w(e, "Failed to persist turn metadata")
                                    }
                                }
                                // 持久化后重新 reload，确ChatTurnConverter SessionStore 恢复 metadata
                                // 解决 reloadTurnsFromStore 先于 metadata 持久化执行的竞态问
                                reloadTurnsFromStore(sid)
                            }

                            if (currentUsage.totalTokens > 0) {
                                viewModelScope.launch {
                                    sessionStore.updateSessionTokenUsage(
                                        sessionId = sid,
                                        inputTokens = currentUsage.inputTokens.toInt(),
                                        outputTokens = currentUsage.outputTokens.toInt(),
                                        cacheReadTokens = currentUsage.cacheReadTokens.toInt(),
                                        cacheWriteTokens = currentUsage.cacheWriteTokens.toInt(),
                                        estimatedCostUsd = null
                                    )
                                    sessionStore.updateSessionModel(sid, _uiState.value.selectedModel.takeIf { it.isNotEmpty() }
                                        ?: agent.profileConfig.modelName)
                                }
                            }
                        }

                        if (newStatus == AgentStatus.IDLE && previousStatus != AgentStatus.IDLE) {
                            val clarificationMiddleware = agent.getMiddleware("clarification") as? com.lin.hippyagent.core.agent.middleware.ClarificationMiddleware
                            val pending = clarificationMiddleware?.pendingClarification
                            if (pending != null) {
                                handleClarification(pending.question, pending.type, pending.context, pending.options)
                                clarificationMiddleware.clearPending()
                            }
                        }

                        previousStatus = newStatus
                    }
                }
            }
            if (agentId == "group" && groupRegistry != null) {
                val group = groupRegistry.getGroup(sid)
                if (group != null) {
                    _uiState.update { it.copy(agentName = group.groupName) }
                } else {
                    _uiState.update { it.copy(agentName = context.getString(R.string.chat_group_name)) }
                }
            }
        }
    }
