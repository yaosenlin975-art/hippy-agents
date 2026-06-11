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

/**
 * Delivery Scope 管理- 管理所ChatViewModel deliveryScope 生命周期
 * 确保旧的 scope 在新ViewModel 创建时被取消，避免内存泄
 */
object DeliveryScopeManager {
    private val activeScopes = java.util.concurrent.ConcurrentHashMap<String, CoroutineScope>()

    fun getOrCreateScope(sessionId: String = "default"): CoroutineScope = synchronized(this) {
        activeScopes.getOrPut(sessionId) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }

    fun cancelScope(sessionId: String) = synchronized(this) {
        activeScopes.remove(sessionId)?.cancel()
    }

    fun cancelAll() = synchronized(this) {
        activeScopes.values.forEach { it.cancel() }
        activeScopes.clear()
    }
}

enum class SessionPhase {
    LOADING,
    READY,
    ERROR
}

@Immutable
data class ChatUiState(
    val sessionId: String = "",
    val sessionTitle: String = "",
    val agentId: String = "",
    val turns: List<ChatTurn> = emptyList(),
    val agentStatus: AgentStatus = AgentStatus.IDLE,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedModel: String = "",
    val selectedProviderId: String = "",
    val availableModels: List<Triple<String, String, String>> = emptyList(),
    val activeMission: MissionState? = null,
    val allSessions: List<Session> = emptyList(),
    val sessionBadges: Map<String, BadgeLevel> = emptyMap(),
    val sessionUnreadCounts: Map<String, Int> = emptyMap(),
    val sessionStatuses: Map<String, AgentStatus> = emptyMap(),
    val sessionPhase: SessionPhase = SessionPhase.LOADING,
    val messageQueueSize: Int = 0,
    val agentName: String = "",
    val showFreeModelWarning: Boolean = false,
    val freeModelKeys: Set<String> = emptySet(),
    val pendingSendText: String? = null,
    val pendingSendChips: List<InputChip> = emptyList(),
    val isMultiSelectMode: Boolean = false,
    val selectedMessageIds: Set<String> = emptySet(),
    val iterationExhausted: Boolean = false,
    val autoDecidedMode: String? = null,
    val autoDecidedModeSource: String? = null,
    val autoDecidedModeReasoning: String? = null,
    /** 触发本次 auto 决策的用户 turn.id；用于在用户消息下显示 AutoDecisionHint */
    val autoDecidedModeTurnId: String? = null,
    val selectedModeLocked: Boolean = false,
    /** Auto/Work 模式正在 LLM 决策中 (决策完成前显示「决策中」状态) */
    val isModeDeciding: Boolean = false
)

@Immutable
data class StreamingState(
    val streamingContent: String = "",
    val streamingThinkingContent: String = "",
    val streamingTurnId: String? = null
)

private val TITLE_PREFIX_REGEX = Regex("^(你好|hello|hi|嗨|hey|请问|请|帮我|帮我想|我想|我要|告诉|说说|聊聊)")

class ChatViewModel(
    private val context: Application,
    private val sessionStore: SessionStore,
    private val agentFactory: AgentFactory,
    private val agentRepository: AgentRepository,
    private val modelProviderStore: ModelProviderStore? = null,
    private val notificationService: HippyAgentNotificationService? = null,
    private val missionManager: MissionManager? = null,
    private val proactiveMemory: ProactiveMemoryManager? = null,
    private val backupManager: BackupManager? = null,
    private val statsManager: StatsManager? = null,
    private val groupRegistry: com.lin.hippyagent.core.agent.collaboration.GroupRegistry? = null,
    private val agentGroupManager: com.lin.hippyagent.core.agent.collaboration.AgentGroupManager? = null,
    private val onDeviceModelManager: com.lin.hippyagent.core.ondevice.OnDeviceModelManager? = null,
    private val modeOrchestrator: com.lin.hippyagent.core.agent.mode.ModeOrchestrator? = null,
    private val toolApprovalManager: com.lin.hippyagent.core.security.ToolApprovalManager? = null,
    private val taskDao: TaskDao? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 独立streaming 状态流 高频更新只影响此流，不触ChatUiState 重组 */
    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    private val commandRegistry = CommandRegistry().apply {
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

    private fun registerAgentSpecificCommands(agentId: String) {
        if (statsManager != null) {
            commandRegistry.register(StatsCommandHandler(statsManager, agentId))
        }
    }

    private val messageQueue = MessageQueueManager()
    val messageQueueItems: StateFlow<List<QueuedMessage>> = messageQueue.queueItems
    private val turnConverter = ChatTurnConverter()
    private var hasDerivedTitle = false
    private var deliveryJob: Job? = null
    private var currentSessionId: String = ""
    private val _selectedMode = MutableStateFlow(com.lin.hippyagent.core.skill.AgentMode.AUTO)
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
    private var deliveryScope: CoroutineScope = DeliveryScopeManager.getOrCreateScope("pending")
    @Volatile
    private var cachedProviders: List<ModelProvider>? = null

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

    private fun loadAvailableModels() {
        viewModelScope.launch {
            try {
                val providers = modelProviderStore?.providers?.first()
                cachedProviders = providers
                if (providers != null) {
                    val models = providers.filter { it.enabled }.flatMap { provider ->
                        provider.models.filter { it.enabled }.map { model ->
                            Triple(model.name, provider.id, provider.name)
                        }
                    }
                    val currentSelected = _uiState.value.selectedModel
                    val matchedModel = if (currentSelected.isNotEmpty()) {
                        models.find { it.first == currentSelected || it.first.endsWith("/$currentSelected") }
                    } else null
                    _uiState.update {
                        it.copy(
                            availableModels = models,
                            selectedModel = matchedModel?.first ?: currentSelected
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load available models")
            }
        }
    }

    private fun deriveTitleFromFirstMessage(text: String): String {
        val cleaned = text.trim()
            .replace(TITLE_PREFIX_REGEX, "")
            .trim()
        val result = cleaned.ifEmpty { text }
        return if (result.length <= 20) result else result.take(20) + "…"
    }

    fun initSession(sessionId: String, agentId: String) {
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
                        _uiState.update { it.copy(sessionTitle = session.title) }
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

    fun sendMessage(content: String, attachedFileUri: String? = null, chips: List<InputChip> = emptyList(), quotedMessage: QuotedMessage? = null) {
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
                                    targetedAgentIds!!.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
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

    /**
     * 将附件复制到工作目录，返(目标文件绝对路径, 图片URI) 元组
     * content 参数已不再用于文本替换，保留签名兼容
     */
    private suspend fun copyAttachmentToWorkspace(
        content: String,
        attachedFileUri: String?,
        agentId: String
    ): Pair<String, String?> {
        Timber.d("copyAttachment: attachedFileUri=$attachedFileUri, agentId=$agentId")
        if (attachedFileUri.isNullOrBlank()) {
            Timber.w("copyAttachment: attachedFileUri is null/blank, returning empty")
            return Pair("", null)
        }

        return try {
            val uri = android.net.Uri.parse(attachedFileUri)
            Timber.d("copyAttachment: parsed uri=$uri, scheme=${uri.scheme}")
            val agent = agentFactory.getAgent(agentId)
            if (agent == null) {
                Timber.w("copyAttachment: agent is null for agentId=$agentId")
                return Pair("", null)
            }

            val workspaceDir = agent.workspaceDir
            workspaceDir.mkdirs()

            val fileName = getFileNameFromUri(uri) ?: "attachment_${System.currentTimeMillis()}"
            Timber.d("copyAttachment: fileName=$fileName, workspaceDir=${workspaceDir.absolutePath}")
            val destFile = java.io.File(workspaceDir, fileName)

            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Timber.e("copyAttachment: openInputStream returned null for uri=$uri")
                return Pair("", null)
            }
            inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Timber.d("copyAttachment: file copied to ${destFile.absolutePath}, size=${destFile.length()}")

            // 判断是否为图
            val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
            val ext = fileName.substringAfterLast(".", "").lowercase()
            val isImage = ext in imageExtensions

            // 返回目标文件绝对路径，和图片路径（如适用
            Pair(destFile.absolutePath, if (isImage) destFile.absolutePath else null)
        } catch (e: Exception) {
            Timber.e(e, "Failed to copy attachment to workspace")
            Pair("", null)
        }
    }

    private fun flushStreamingState(
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

    private suspend fun deliverMessage(agent: com.lin.hippyagent.core.agent.Agent, sessionId: String, content: String, planContext: String? = null, skipUserMessage: Boolean = false) {
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
    private suspend fun deliverGroupMessage(sessionId: String, content: String, mentionChipIds: List<String> = emptyList()) {
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

    private suspend fun reloadTurnsFromStore(sessionId: String) {
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
                    .associate { (it.response?.content ?: "").take(200) to it.metadata!! }
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
    private fun mergeSystemTurns(
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

    fun dismissFreeModelWarning() {
        val pending = _uiState.value.pendingSendText
        val pendingChips = _uiState.value.pendingSendChips
        _uiState.update { it.copy(showFreeModelWarning = false, pendingSendText = null, pendingSendChips = emptyList()) }
        if (pending != null) {
            sendMessage(pending, chips = pendingChips)
        }
    }

    fun suppressFreeModelWarning() {
        val prefs = context.getSharedPreferences("free_model_warning", Application.MODE_PRIVATE)
        prefs.edit().putBoolean("suppressed", true).apply()
        _uiState.update { it.copy(showFreeModelWarning = false) }
    }

    private fun checkFreeModelWarning(modelName: String, providerId: String) {
        val prefs = context.getSharedPreferences("free_model_warning", Application.MODE_PRIVATE)
        if (prefs.getBoolean("suppressed", false)) return
        viewModelScope.launch {
            val allProviders = modelProviderStore?.providers?.first() ?: return@launch
            var foundFree = false
            for (provider in allProviders) {
                if (providerId.isNotBlank() && provider.id != providerId) continue
                for (m in provider.models) {
                    if (m.name == modelName && m.free) {
                        foundFree = true
                        break
                    }
                }
                if (foundFree) break
            }
            if (foundFree) {
                _uiState.update { it.copy(showFreeModelWarning = true, freeModelKeys = setOf("${providerId}:${modelName}")) }
            }
        }
    }

    private companion object {
        private val VISION_MODEL_REGEX = Regex("(?i)(vision|vl|gemini|gpt-4o|gpt-5|claude-3|claude-4|qwen-vl|llava|deepseek-vl|flash-image)")
    }

    /**
     * 模式解析结果: 后缀 + 是否需升级到复杂模型。
     * 升级标志会通过 [com.lin.hippyagent.core.agent.Agent.processMessageStream] 的
     * forceEscalate 形参传到 Agent,使复杂任务模型在本 turn 实际生效。
     */
    private data class ModeSuffixResult(
        val suffix: String?,
        val useComplexModel: Boolean,
    )

    /**
     * 解析模式并应用模式过滤;返回 (system prompt 后缀, useComplexModel)。
     * 失败 / orchestrator 不可用时返回 (null, false),不打断主流程。
     */
    private suspend fun resolveModeSuffix(
        content: String,
        agentId: String,
        agent: com.lin.hippyagent.core.agent.Agent,
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

    private fun isCurrentModelVisionCapable(): Boolean {
        val selectedModel = _uiState.value.selectedModel
        if (selectedModel.isBlank()) return false
        if (VISION_MODEL_REGEX.containsMatchIn(selectedModel)) return true
        val allProviders = cachedProviders ?: return false
        for (provider in allProviders) {
            for (m in provider.models) {
                if (m.name == selectedModel && com.lin.hippyagent.core.model.ModelCapability.VISION in m.capabilities) {
                    return true
                }
            }
        }
        return false
    }

    fun checkFreeModelBeforeSend(text: String, chips: List<InputChip>): Boolean {
        val prefs = context.getSharedPreferences("free_model_warning", Application.MODE_PRIVATE)
        if (prefs.getBoolean("suppressed", false)) return true
        val selectedModel = _uiState.value.selectedModel
        if (selectedModel.isBlank()) return true
        val allProviders = cachedProviders ?: return true
        var foundFree = false
        for (provider in allProviders) {
            for (m in provider.models) {
                if (m.name == selectedModel && m.free) {
                    foundFree = true
                    break
                }
            }
            if (foundFree) break
        }
        if (foundFree) {
            _uiState.update { it.copy(showFreeModelWarning = true, freeModelKeys = setOf("send:${selectedModel}"), pendingSendText = text, pendingSendChips = chips) }
            return false
        }
        return true
    }

    fun setAvailableModels(models: List<Triple<String, String, String>>) {
        _uiState.update { it.copy(availableModels = models) }
    }

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

    fun flushQueuedMessages() {
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

    private fun getFileNameFromUri(uri: android.net.Uri): String? {
        var fileName: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (fileName == null) {
            fileName = uri.path?.substringAfterLast('/')
        }
        return fileName
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

