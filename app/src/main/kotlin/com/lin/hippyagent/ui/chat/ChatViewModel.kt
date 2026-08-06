package com.lin.hippyagent.ui.chat

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.conflate
import org.json.JSONObject
import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.AgentStatus
import com.lin.hippyagent.core.agent.collaboration.MentionParser
import com.lin.hippyagent.data.repository.AgentRepository
import com.lin.hippyagent.core.agent.MessageQueueManager
import com.lin.hippyagent.core.agent.QueuedMessage
import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.session.SessionMessage
import com.lin.hippyagent.core.agent.session.BadgeLevel
import com.lin.hippyagent.core.agent.session.Session
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.agent.session.UnreadSummary
import com.lin.hippyagent.core.chat.ChatTurn
import com.lin.hippyagent.core.chat.ChatTurnConverter
import com.lin.hippyagent.core.chat.SystemTurnType
import com.lin.hippyagent.core.chat.TurnStatus
import com.lin.hippyagent.core.command.CommandContext
import com.lin.hippyagent.core.command.CommandRegistry
import com.lin.hippyagent.core.command.CompactCommandHandler
import com.lin.hippyagent.core.command.NewSessionCommandHandler
import com.lin.hippyagent.core.command.ClearCommandHandler
import com.lin.hippyagent.core.command.HistoryCommandHandler
import com.lin.hippyagent.core.command.MissionCommandHandler
import com.lin.hippyagent.core.command.ProactiveCommandHandler
import com.lin.hippyagent.core.command.PlanCommandHandler
import com.lin.hippyagent.core.command.SummarizeStatusCommandHandler
import com.lin.hippyagent.core.command.BackupCommandHandler
import com.lin.hippyagent.core.command.StatsCommandHandler
import com.lin.hippyagent.core.mission.MissionManager
import com.lin.hippyagent.core.mission.MissionState
import com.lin.hippyagent.core.mission.MissionStatus
import com.lin.hippyagent.core.memory.ProactiveMemoryManager
import com.lin.hippyagent.core.model.ModelProvider
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.notification.HippyAgentNotificationService
import com.lin.hippyagent.core.agent.task.TaskDao
import com.lin.hippyagent.core.agent.task.TaskEntity
import com.lin.hippyagent.core.agent.task.TaskStatus
import com.lin.hippyagent.core.backup.BackupManager
import com.lin.hippyagent.core.stats.StatsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import com.lin.hippyagent.R

@Immutable
class ChatViewModel(
    internal val context: Application,
    internal val sessionStore: SessionStore,
    internal val agentFactory: AgentFactory,
    internal val agentRepository: AgentRepository,
    internal val modelProviderStore: ModelProviderStore? = null,
    internal val notificationService: HippyAgentNotificationService? = null,
    internal val missionManager: MissionManager? = null,
    internal val proactiveMemory: ProactiveMemoryManager? = null,
    internal val backupManager: BackupManager? = null,
    internal val statsManager: StatsManager? = null,
    internal val groupRegistry: com.lin.hippyagent.core.agent.collaboration.GroupRegistry? = null,
    internal val agentGroupManager: com.lin.hippyagent.core.agent.collaboration.AgentGroupManager? = null,
    internal val onDeviceModelManager: com.lin.hippyagent.core.ondevice.OnDeviceModelManager? = null,
    internal val modeOrchestrator: com.lin.hippyagent.core.agent.mode.ModeOrchestrator? = null,
    internal val toolApprovalManager: com.lin.hippyagent.core.security.ToolApprovalManager? = null,
    internal val taskDao: TaskDao? = null
) : ViewModel() {

    internal val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 独立streaming 状态流 高频更新只影响此流，不触ChatUiState 重组 */
    internal val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    internal val commandRegistry = CommandRegistry().apply {
        register(CompactCommandHandler(sessionStore))
        register(NewSessionCommandHandler(context, sessionStore))
        register(ClearCommandHandler(sessionStore))
        register(HistoryCommandHandler(sessionStore))
        register(PlanCommandHandler(sessionStore))
        register(SummarizeStatusCommandHandler(sessionStore))
        if (missionManager != null) {
            register(MissionCommandHandler(missionManager))
        }
        if (proactiveMemory != null) {
            register(ProactiveCommandHandler(proactiveMemory))
        }
        if (backupManager != null) {
            register(BackupCommandHandler(backupManager))
        }
    }

    internal fun registerAgentSpecificCommands(agentId: String) {
        if (statsManager != null) {
            commandRegistry.register(StatsCommandHandler(statsManager, agentId))
        }
    }

    internal val messageQueue = MessageQueueManager()
    val messageQueueItems: StateFlow<List<QueuedMessage>> = messageQueue.queueItems
    internal val turnConverter = ChatTurnConverter()
    internal var hasDerivedTitle = false
    internal var deliveryJob: Job? = null
    internal var currentSessionId: String = ""
    internal val _selectedMode = MutableStateFlow(com.lin.hippyagent.core.skill.AgentMode.AUTO)
    val selectedMode: StateFlow<com.lin.hippyagent.core.skill.AgentMode> = _selectedMode.asStateFlow()

    fun selectMode(mode: com.lin.hippyagent.core.skill.AgentMode) {
        _selectedMode.value = mode
    }

    // 当前 session 等待审批的 task (source IN ['task','tool_approval'], status=AWAITING_APPROVAL)
    private val _currentSessionApproval = MutableStateFlow<TaskEntity?>(null)
    val currentSessionApproval: StateFlow<TaskEntity?> = _currentSessionApproval.asStateFlow()

    // 其他 session 等待审批的 task (后台 session / 无 session)
    private val _otherSessionApproval = MutableStateFlow<TaskEntity?>(null)
    val otherSessionApproval: StateFlow<TaskEntity?> = _otherSessionApproval.asStateFlow()

    private val approvalSources = listOf("task", "tool_approval")
    private var approvalObserverJob: Job? = null
    private var sessionObserverJob: Job? = null

    private val voiceRecorder = com.lin.hippyagent.core.voice.VoiceRecorder(
        outputDir = java.io.File(context.cacheDir, "voice_messages").also { it.mkdirs() },
        scope = viewModelScope
    )
    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()
    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()
    private var durationUpdateJob: Job? = null

    /**
     * 独立viewModelScope delivery scope 保证用户退出对话后 Agent 仍继续执行
     * viewModelScope 被取消时不会影响scope 中的协程
     * 使用 Dispatchers.IO 处理后台任务，避免主线程阻塞和发
     */
    internal var deliveryScope: CoroutineScope = DeliveryScopeManager.getOrCreateScope("pending")
    @Volatile
    internal var cachedProviders: List<ModelProvider>? = null

    var permissionViewModel: PermissionViewModel? = null
    var planViewModel: PlanViewModel? = null

    fun attachSubViewModels(permVm: PermissionViewModel, planVm: PlanViewModel) {
        permissionViewModel = permVm
        planViewModel = planVm
        permVm.onAddPermissionTurn = { permTurn ->
            _uiState.update { it.copy(turns = it.turns + permTurn) }
        }
        permVm.onResolvePermissionTurns = {
            _uiState.update { state ->
                val updatedTurns = state.turns.map { turn ->
                    if (turn is ChatTurn.PermissionTurn && !turn.isResolved) {
                        turn.copy(isResolved = true)
                    } else turn
                }
                state.copy(turns = updatedTurns)
            }
        }
        permVm.deliveryScopeProvider = { deliveryScope }
    }

    init {
        loadAvailableModels()
        startApprovalObserver()
        observeOnDeviceModelReady()
    }

    private fun observeOnDeviceModelReady() {
        val manager = onDeviceModelManager ?: return
        viewModelScope.launch {
            manager.currentEngineModelId.collect { modelId ->
                _uiState.update { it.copy(onDeviceModelReady = modelId != null) }
            }
        }
    }

    private fun startApprovalObserver() {
        val dao = taskDao ?: return
        approvalObserverJob?.cancel()
        approvalObserverJob = viewModelScope.launch {
            // 跟随 _uiState.sessionId: session 切换时取消旧 session 的两个 observe 子协程, 启新的
            _uiState.map { it.sessionId }.distinctUntilChanged().collect { sid ->
                sessionObserverJob?.cancel()
                if (sid.isEmpty()) {
                    _currentSessionApproval.value = null
                    _otherSessionApproval.value = null
                    return@collect
                }
                sessionObserverJob = launch {
                    launch {
                        dao.observeCurrentSessionApprovalList(
                            sessionId = sid,
                            sources = approvalSources,
                            status = TaskStatus.AWAITING_APPROVAL
                        ).collect { list ->
                            _currentSessionApproval.value = list.firstOrNull()
                        }
                    }
                    launch {
                        dao.observeOtherSessionApprovalList(
                            sessionId = sid,
                            sources = approvalSources,
                            status = TaskStatus.AWAITING_APPROVAL
                        ).collect { list ->
                            _otherSessionApproval.value = list.firstOrNull()
                        }
                    }
                }
            }
        }
    }

    /**
     * 用户在 ChatScreen 内点批准/拒绝
     * - always=false: ALLOW_ONCE / DENY_ONCE
     * - always=true: ALLOW_ALWAYS / DENY_ALWAYS (写 tool_approval_rules)
     */
    fun onApprove(approvalId: String, always: Boolean = false) {
        val mgr = toolApprovalManager ?: return
        viewModelScope.launch {
            mgr.resolveApproval(
                requestId = approvalId,
                action = if (always) com.lin.hippyagent.core.security.ApprovalAction.ALLOW_ALWAYS
                else com.lin.hippyagent.core.security.ApprovalAction.ALLOW_ONCE
            )
        }
    }

    fun onDeny(approvalId: String, always: Boolean = false) {
        val mgr = toolApprovalManager ?: return
        viewModelScope.launch {
            mgr.resolveApproval(
                requestId = approvalId,
                action = if (always) com.lin.hippyagent.core.security.ApprovalAction.DENY_ALWAYS
                else com.lin.hippyagent.core.security.ApprovalAction.DENY_ONCE
            )
        }
    }

    fun loadSession(sessionId: String, agentId: String) {
        currentSessionId = sessionId
        deliveryScope = DeliveryScopeManager.getOrCreateScope(sessionId)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    sessionId = sessionId,
                    agentId = agentId,
                    sessionPhase = SessionPhase.LOADING
                )
            }

            var loadedSession: Session? = null
            sessionStore.getSession(sessionId)
                .onSuccess { session ->
                    loadedSession = session
                    if (session != null) {
                        _uiState.update { it.copy(sessionTitle = session.title, privacyMode = session.privacyMode) }
                        hasDerivedTitle = session.title != context.getString(R.string.chat_new_session)
                    }
                }

            sessionStore.getMessages(sessionId)
                .onSuccess { messages ->
                    val turns = turnConverter.convertIncremental(messages)
                    val summaryTurn = loadedSession?.compressedSummary?.let { summary ->
                        if (summary.isNotBlank()) ChatTurn.SystemTurn(
                            id = "compressed_summary_$sessionId",
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
                    sessionStore.resetUnread(sessionId)
                }
                .onFailure { e ->
                    Timber.e(e, "Failed to load session messages")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            sessionPhase = SessionPhase.ERROR
                        )
                    }
                }

            registerAgentSpecificCommands(agentId)
        }
    }

    /**
     * 将附件复制到工作目录，返(目标文件绝对路径, 图片URI) 元组
     * content 参数已不再用于文本替换，保留签名兼容
     */

    /**
     * 群组消息投通过 AgentGroupManager 协调多智能体响应
     */

    fun getGroupMemberIds(groupId: String): List<String> {
        val group = agentGroupManager?.getGroup(groupId) ?: return emptyList()
        val profiles = agentRepository?.getProfiles()?.let { flow ->
            runCatching { kotlinx.coroutines.runBlocking { flow.first() } }.getOrNull()
        } ?: return group.agentIds
        return group.agentIds.filter { id ->
            val profile = profiles[id]
            profile != null && profile.enabled
        }
    }

    fun observeGroupMembers(groupId: String): Flow<Map<String, String>> {
        val registry = groupRegistry ?: return flowOf(emptyMap())
        val repo = agentRepository ?: return flowOf(emptyMap())
        return combine(
            registry.groupsFlow,
            repo.getProfiles()
        ) { groups, profiles ->
            val group = groups.find { it.groupId == groupId }
            val ids = group?.agentIds ?: emptyList()
            ids.filter { id ->
                val profile = profiles[id]
                profile != null && profile.enabled
            }.associateWith { id ->
                profiles[id]?.name?.ifBlank { id } ?: id
            }
        }
    }

    fun selectModel(modelName: String, providerId: String = "") {
        _uiState.update { it.copy(selectedModel = modelName, selectedProviderId = providerId) }
        // 检查是否为免费模型，是则弹出提
        checkFreeModelWarning(modelName, providerId)
        val sessionId = _uiState.value.sessionId
        if (sessionId.isNotEmpty()) {
            viewModelScope.launch {
                sessionStore.updateSessionModel(sessionId, modelName)
            }
        }
    }

    fun togglePrivacyMode(enabled: Boolean) {
        val sid = _uiState.value.sessionId
        if (sid.isEmpty()) return
        viewModelScope.launch {
            sessionStore.updatePrivacyMode(sid, enabled)
            _uiState.update { it.copy(privacyMode = enabled) }
        }
    }

    /**
     * 模式解析结果: 后缀 + 是否需升级到复杂模型。
     * 升级标志会通过 [com.lin.hippyagent.core.agent.Agent.processMessageStream] 的
     * forceEscalate 形参传到 Agent,使复杂任务模型在本 turn 实际生效。
     */

    /**
     * 解析模式并应用模式过滤;返回 (system prompt 后缀, useComplexModel)。
     * 失败 / orchestrator 不可用时返回 (null, false),不打断主流程。
     */

    fun removeQueuedMessage(index: Int) {
        viewModelScope.launch {
            messageQueue.removeAt(index)
            _uiState.update { it.copy(messageQueueSize = messageQueue.size()) }
        }
    }

    fun moveQueuedMessage(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            messageQueue.move(fromIndex, toIndex)
        }
    }

    fun startVoiceRecording() {
        val file = voiceRecorder.startRecording() ?: return
        _isRecordingVoice.value = true
        _recordingDurationMs.value = 0L
        durationUpdateJob = viewModelScope.launch {
            while (coroutineContext.isActive) {
                delay(100)
                _recordingDurationMs.value = voiceRecorder.currentDurationMs
            }
        }
    }

    fun stopVoiceRecording() {
        durationUpdateJob?.cancel()
        durationUpdateJob = null
        _isRecordingVoice.value = false
        viewModelScope.launch {
            val result = voiceRecorder.stopRecording() ?: return@launch
            val transcription = try {
                onDeviceModelManager?.transcribeAudio(result.pcmBytes)
                    ?: throw IllegalStateException("OnDeviceModelManager 不可用")
            } catch (e: Exception) {
                Timber.w(e, "Voice STT failed, sending without transcription")
                context.getString(R.string.chat_voice_message)
            }
            val voiceMeta = JSONObject().apply {
                put("voiceFile", result.file.absolutePath)
                put("voiceDuration", result.durationMs)
            }.toString()
            val sid = _uiState.value.sessionId
            sessionStore.addMessage(sid, MessageRole.USER, transcription).onSuccess { userMsg ->
                sessionStore.updateMessageMetadata(userMsg.id, voiceMeta)
            }
            sessionStore.getMessages(sid).onSuccess { messages ->
                _uiState.update { it.copy(turns = turnConverter.convertIncremental(messages)) }
            }
            sendMessage(transcription)
        }
    }

    fun stopAgent() {
        deliveryJob?.cancel()
        deliveryJob = null
        viewModelScope.launch {
            val agent = agentFactory.getAgent(_uiState.value.agentId)
            val sessionId = _uiState.value.sessionId
            if (agent != null && sessionId.isNotEmpty()) {
                agent.stopSession(sessionId)
                agent.cleanupSessionState(sessionId)
            } else {
                agent?.stop()
            }
        }
        // 添加打断提示
        addInterruptNotice()
    }

    private fun addInterruptNotice() {
        val interruptTurn = ChatTurn.SystemTurn(
            id = "interrupt_${System.currentTimeMillis()}",
            content = context.getString(R.string.chat_interrupt_notice),
            type = com.lin.hippyagent.core.chat.SystemTurnType.WARNING
        )
        _uiState.update { state ->
            state.copy(turns = state.turns + interruptTurn)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun cancelGeneration() {
        // 1. 取消正在运行的流collect 协程
        deliveryJob?.cancel()
        deliveryJob = null
        // 2. 中断 Agent 内部当前会话LLM 请求
        viewModelScope.launch {
            val agentId = _uiState.value.agentId
            val sessionId = _uiState.value.sessionId
            val agent = agentFactory.getAgent(agentId)
            if (agent != null && sessionId.isNotEmpty()) {
                agent.stopSession(sessionId)
                agent.cleanupSessionState(sessionId)
            } else {
                agent?.stop()
            }
        }
        // 3. 立即清除 streaming 状
        _streamingState.update { StreamingState() }
        // 4. 添加打断提示
        addInterruptNotice()
    }

    fun regenerateLastResponse() {
        viewModelScope.launch {
            val sid = _uiState.value.sessionId
            val messages = sessionStore.getMessages(sid).getOrNull() ?: return@launch
            val lastUserMsg = messages.lastOrNull { it.role == MessageRole.USER } ?: return@launch
            val toDelete = messages.filter { it.timestamp.isAfter(lastUserMsg.timestamp) || it.id == lastUserMsg.id }
            toDelete.forEach { sessionStore.deleteMessage(it.id) }
            sendMessage(lastUserMsg.content, null)
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            sessionStore.deleteMessage(messageId)
            val sid = _uiState.value.sessionId
            sessionStore.getMessages(sid).onSuccess { messages ->
                _uiState.update { it.copy(turns = turnConverter.convertIncremental(messages)) }
            }
        }
    }

    fun enterMultiSelectMode(initialMessageId: String? = null) {
        _uiState.update { it.copy(
            isMultiSelectMode = true,
            selectedMessageIds = if (initialMessageId != null) setOf(initialMessageId) else emptySet()
        )}
    }

    fun toggleMessageSelection(messageId: String) {
        _uiState.update { state ->
            val current = state.selectedMessageIds
            val newSet = if (messageId in current) current - messageId else current + messageId
            state.copy(selectedMessageIds = newSet)
        }
    }

    fun exitMultiSelectMode() {
        _uiState.update { it.copy(isMultiSelectMode = false, selectedMessageIds = emptySet()) }
    }

    fun deleteSelectedMessages() {
        viewModelScope.launch {
            val ids = _uiState.value.selectedMessageIds.toList()
            for (id in ids) {
                sessionStore.deleteMessage(id)
            }
            val sid = _uiState.value.sessionId
            sessionStore.getMessages(sid).onSuccess { messages ->
                _uiState.update { it.copy(
                    turns = turnConverter.convertIncremental(messages),
                    isMultiSelectMode = false,
                    selectedMessageIds = emptySet()
                )}
            }
        }
    }

    fun exportSelectedMessagesAsMarkdown(): String {
        val selectedIds = _uiState.value.selectedMessageIds
        val turns = _uiState.value.turns
        val sb = StringBuilder()
        for (turn in turns) {
            val messageId = when (turn) {
                is ChatTurn.UserTurn -> turn.message.id
                is ChatTurn.AgentTurn -> turn.response?.id
                else -> null
            }
            if (messageId != null && messageId in selectedIds) {
                val content = when (turn) {
                    is ChatTurn.UserTurn -> turn.message.content
                    is ChatTurn.AgentTurn -> turn.response?.content ?: continue
                    else -> continue
                }
                val role = when (turn) {
                    is ChatTurn.UserTurn -> context.getString(R.string.chat_user_role)
                    is ChatTurn.AgentTurn -> turn.senderAgentId ?: context.getString(R.string.chat_agent_fallback_name)
                    else -> continue
                }
                sb.append("**$role**:\n$content\n\n")
            }
        }
        return sb.toString()
    }

    fun forwardSelectedMessages(targetAgentId: String) {
        viewModelScope.launch {
            val selectedIds = _uiState.value.selectedMessageIds
            val turns = _uiState.value.turns
            val sb = StringBuilder(context.getString(R.string.chat_forwarded_message) + "\n\n")
            for (turn in turns) {
                val messageId = when (turn) {
                    is ChatTurn.UserTurn -> turn.message.id
                    is ChatTurn.AgentTurn -> turn.response?.id
                    else -> null
                }
                if (messageId != null && messageId in selectedIds) {
                    val content = when (turn) {
                        is ChatTurn.UserTurn -> turn.message.content
                        is ChatTurn.AgentTurn -> turn.response?.content ?: continue
                        else -> continue
                    }
                    val role = when (turn) {
                        is ChatTurn.UserTurn -> context.getString(R.string.chat_user_role)
                        is ChatTurn.AgentTurn -> turn.senderAgentId ?: context.getString(R.string.chat_agent_fallback_name)
                        else -> continue
                    }
                    sb.append("**$role**:\n$content\n\n---\n\n")
                }
            }
            val newSessionId = sessionStore.createSession(targetAgentId, context.getString(R.string.chat_new_session)).getOrNull()?.id ?: return@launch
            val forwardContent = sb.toString()
            sessionStore.addMessage(newSessionId, MessageRole.USER, forwardContent)
            exitMultiSelectMode()
            _uiState.update {
                it.copy(
                    sessionId = newSessionId,
                    agentId = targetAgentId,
                    sessionPhase = SessionPhase.READY,
                    isLoading = false,
                    turns = turnConverter.convertIncremental(sessionStore.getMessages(newSessionId).getOrDefault(emptyList()))
                )
            }
            val agent = agentFactory.getAgent(targetAgentId) ?: return@launch
            deliveryJob?.cancel()
            deliveryJob = deliveryScope.launch {
                deliverMessage(agent, newSessionId, forwardContent, skipUserMessage = true)
                deliveryJob = null
            }
        }
    }

    fun editMessage(messageId: String, newContent: String) {
        viewModelScope.launch {
            val sid = _uiState.value.sessionId
            val messages = sessionStore.getMessages(sid).getOrNull() ?: return@launch
            val targetMsg = messages.find { it.id == messageId } ?: return@launch
            val toDelete = messages.filter { it.timestamp.isAfter(targetMsg.timestamp) || it.id == messageId }
            toDelete.forEach { sessionStore.deleteMessage(it.id) }
            sendMessage(newContent, null)
        }
    }

    fun cancelMission() {
        viewModelScope.launch {
            val mission = _uiState.value.activeMission ?: return@launch
            missionManager?.cancelMission(mission.config.taskId)?.onSuccess {
                _uiState.update { it.copy(activeMission = null) }
            }
        }
    }

    fun refreshMissionStatus() {
        viewModelScope.launch {
            missionManager?.getActiveMission()?.let { mission ->
                if (mission.status == MissionStatus.RUNNING) {
                    _uiState.update { it.copy(activeMission = mission) }
                } else {
                    _uiState.update { it.copy(activeMission = null) }
                }
            }
        }
    }

    /**
     * 当前 ViewModel 管理agentId，供外部读取（如导航层用来记住最后活跃的 Agent）
     */
    val currentAgentId: String get() = _uiState.value.agentId

    fun handleClarification(question: String, clarificationType: String, context: String, options: List<String>) {
        val clarificationTurn = ChatTurn.ClarificationTurn(
            id = "clarify_${System.currentTimeMillis()}",
            question = question,
            clarificationType = clarificationType,
            context = context,
            options = options,
            isResolved = false
        )
        _uiState.update { state ->
            state.copy(turns = state.turns + clarificationTurn)
        }
    }

    fun resolveClarification(turnId: String, selectedOption: String?) {
        _uiState.update { state ->
            val updatedTurns = state.turns.map { turn ->
                if (turn is ChatTurn.ClarificationTurn && turn.id == turnId) {
                    turn.copy(isResolved = true, selectedOption = selectedOption)
                } else turn
            }
            state.copy(turns = updatedTurns)
        }
        if (selectedOption != null) {
            sendMessage(selectedOption)
        }
    }

    override fun onCleared() {
        Timber.d("ChatViewModel onCleared: deliveryScope 保持活跃, 不取消后台任务")

        sessionObserverJob?.cancel()
        approvalObserverJob?.cancel()

        val sessionId = _uiState.value.sessionId
        if (sessionId.isNotEmpty()) {
            val hasUserTurn = _uiState.value.turns.any { it is ChatTurn.UserTurn }
            if (!hasUserTurn) {
                deliveryScope.launch {
                    sessionStore.deleteSession(sessionId)
                    Timber.d("onCleared: deleted empty session $sessionId (no user turns)")
                }
            } else {
                deliveryScope.launch {
                    sessionStore.resetUnread(sessionId)
                    Timber.d("onCleared: resetUnread for session $sessionId")
                }
            }
        }
    }

    /**
     * 清理空会话：如果当前会话没有任何用户消息，则删除
     * 在退出聊天界面时调用
     */
    fun cleanupEmptySession() {
        val sid = _uiState.value.sessionId
        val hasUserTurn = _uiState.value.turns.any { it is ChatTurn.UserTurn }
        if (!hasUserTurn) {
            viewModelScope.launch {
                sessionStore.deleteSession(sid)
                Timber.d("Cleaned up empty session: $sid")
            }
        }
    }
}
