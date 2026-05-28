package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.group.GroupCollaborationProtocol
import com.lin.hippyagent.core.agent.group.MentionExchange
import com.lin.hippyagent.core.agent.group.detectNewTask
import com.lin.hippyagent.core.agent.group.detectQuestion
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

data class AgentGroupConfig(
    val enableLLMSpeakerSelection: Boolean = false,
    val enablePingPongDetection: Boolean = false,
    val enableLLMTermination: Boolean = false,
    val llmSelectorModel: String? = null,
    val llmSelectorProviderId: String? = null,
    val llmSelectorModelName: String? = null,
    val maxRounds: Int = 100,
    val selectorTimeoutMs: Long = 5000
)

/** 群组内可用的工具 — 智能体可调用以获取群组信息 */
class GroupMemberListTool(
    private val groupId: String,
    private val agentNamesProvider: () -> Map<String, String>
) : com.lin.hippyagent.core.tools.Tool() {
    override val definition = com.lin.hippyagent.core.tools.ToolDefinition(
        name = "get_group_members",
        description = "获取当前群组的成员列表（名字、ID），仅在群组聊天中可用",
        parameters = mapOf(
            "includeId" to com.lin.hippyagent.core.tools.ToolParameter(name = "includeId", type = "boolean", description = "是否包含完整ID", required = false)
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): com.lin.hippyagent.core.tools.ToolResult {
        val includeId = (arguments["includeId"] as? Boolean) ?: false
        val currentNames = agentNamesProvider()
        val sb = StringBuilder()
        sb.appendLine("=== 群组成员列表 ===")
        sb.appendLine("群组ID: $groupId")
        sb.appendLine("成员数量: ${currentNames.size}")
        sb.appendLine()
        currentNames.forEach { (id, name) ->
            sb.appendLine(if (includeId) "- **$name** (`$id`)" else "- **$name**")
        }
        return com.lin.hippyagent.core.tools.ToolResult(
            callId = (arguments["callId"] as? String) ?: "",
            success = true,
            output = sb.toString(),
            forUser = "已检索群组成员"
        )
    }
}

/**
 * 群组智能体消息处理引擎。
 *
 * 架构：队列 + 池化并发
 * - 每个智能体拥有独立的 [AgentMessageQueue]，消息先入队再串行处理
 * - [GroupMessageRouter] 根据 @提及 和 sender 类型路由消息
 * - [concurrencyPool] (Semaphore) 限制全局同时处理的智能体数量（默认最多 3 个）
 * - 每处理完一条消息自动检查队列中是否有下一条待处理
 */
class AgentGroup(
    val groupId: String,
    val groupName: String,
    agentIds: List<String>,
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore,
    private val maxConcurrency: Int = 3,
    private val speakerSelector: LLMSpeakerSelector? = null,
    private val collaborationProtocol: GroupCollaborationProtocol? = null,
    private val config: AgentGroupConfig = AgentGroupConfig(),
    private val descriptionProvider: AgentDescriptionProvider? = null,
    var onAgentReplied: ((agentId: String, agentName: String, content: String, sessionId: String) -> Unit)? = null
) {
    private val _agentIds = MutableStateFlow(agentIds)
    val agentIds: List<String> get() = _agentIds.value

    private val _messages = MutableStateFlow<List<GroupChatMessage>>(emptyList())
    val messages: StateFlow<List<GroupChatMessage>> = _messages

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    /** 每个智能体的待处理消息队列 */
    private val agentQueues: ConcurrentHashMap<String, AgentMessageQueue> =
        ConcurrentHashMap(agentIds.associateWith { AgentMessageQueue(it) })

    private val mentionChainManager = MentionChainManager()
    private val parallelArbitrator = ParallelArbitrator()
    private val displayNameFuzzyMapper = DisplayNameFuzzyMapper { agentIds.associateWith { getAgentName(it) } }
    private var broadcastPreScorer: BroadcastPreScorer? = null
    private val groupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val router: GroupMessageRouter = GroupMessageRouter(agentQueues, MentionParser, mentionChainManager, displayNameFuzzyMapper) { broadcastPreScorer }

    private suspend fun getAgentName(agentId: String): String {
        return agentFactory.getAgent(agentId)?.profileConfig?.name?.ifBlank { agentId } ?: agentId
    }

    fun setBroadcastPreScorer(scorer: BroadcastPreScorer?) {
        broadcastPreScorer = scorer
    }

    suspend fun selectNextSpeakerWithLLM(): SelectorResult {
        val selector = speakerSelector
            ?: return SelectorResult.Error("LLMSpeakerSelector not configured")
        val enabledAgentIds = agentIds.filter { id ->
            val agent = agentFactory.getAgent(id)
            agent != null && agent.profileConfig.enabled
        }
        val chatConfig = GroupChatConfig(
            groupId = groupId,
            groupName = groupName,
            agentIds = enabledAgentIds,
            maxRounds = config.maxRounds,
            turnStrategy = TurnStrategy.LLM_SELECTOR,
            llmSelectorModel = config.llmSelectorModel,
            llmSelectorProviderId = config.llmSelectorProviderId,
            llmSelectorModelName = config.llmSelectorModelName,
            selectorTimeoutMs = config.selectorTimeoutMs
        )
        val chatState = GroupChatState(
            groupId = groupId,
            currentRound = _messages.value.size,
            messages = _messages.value
        )
        return selector.selectNextSpeaker(chatConfig, chatState)
    }

    fun detectPingPong(targetAgentId: String? = null): Boolean {
        val protocol = collaborationProtocol ?: return false
        val msgs = _messages.value
        if (msgs.size < 2) return false
        val toAgent = targetAgentId ?: msgs.last().agentId
        val mentionHistory = msgs.takeLast(10).map { msg ->
            MentionExchange(
                fromAgent = msg.agentId,
                toAgent = toAgent,
                timestamp = msg.timestamp,
                hasNewTask = detectNewTask(msg.content),
                hasQuestion = detectQuestion(msg.content),
                hasDecision = msg.content.contains("决定") || msg.content.contains("确认")
            )
        }
        return protocol.shouldStopPingPong(mentionHistory)
    }

    suspend fun shouldTerminateWithLLM(): Boolean {
        val selector = speakerSelector ?: return false
        val chatConfig = GroupChatConfig(
            groupId = groupId,
            groupName = groupName,
            agentIds = agentIds,
            maxRounds = config.maxRounds,
            useLLMToTerminate = true,
            llmSelectorModel = config.llmSelectorModel,
            llmSelectorProviderId = config.llmSelectorProviderId,
            llmSelectorModelName = config.llmSelectorModelName,
            selectorTimeoutMs = config.selectorTimeoutMs
        )
        val chatState = GroupChatState(
            groupId = groupId,
            currentRound = _messages.value.size,
            messages = _messages.value
        )
        return selector.shouldTerminate(chatConfig, chatState)
    }

    /** 每个智能体的处理互斥锁（确保同一智能体串行从队列取消息处理） */
    private val agentMutexes = agentIds.associateWith { Mutex() }

    /** 并发池 — 最多允许 [maxConcurrency] 个智能体同时进行 LLM 调用 */
    private val concurrencyPool = Semaphore(maxConcurrency)

    /** 当前正在处理的智能体数量 */
    private val activeCount = AtomicInteger(0)

    /** 群组配置（来自 GroupInfo） */
    var mentionOnlyAgentIds: List<String> = emptyList()

    suspend fun processMessage(
        senderId: String,
        content: String,
        mentionedAgentIds: List<String> = emptyList(),
        parentPath: MentionPath? = null,
        sourceMessageId: String? = null
    ): Result<String> {
        Timber.d("AgentGroup.processMessage: group=$groupId sender=$senderId content=${content.take(50)} mentions=$mentionedAgentIds")
        activeCount.incrementAndGet()
        _isActive.value = true
        return try {
            val hasExplicitMentions = mentionedAgentIds.isNotEmpty()

            if (!hasExplicitMentions && config.enablePingPongDetection && detectPingPong()) {
                Timber.i("AgentGroup: ping-pong detected, stopping group chat: $groupId")
                return Result.success("")
            }

            val userMessage = GroupChatMessage(
                agentId = senderId,
                content = content,
                round = _messages.value.size,
                timestamp = System.currentTimeMillis(),
                senderIsUser = true
            )
            _messages.update { it + userMessage }

            if (sourceMessageId != null) {
                val path = parentPath ?: MentionPath(path = listOf(senderId))
                mentionChainManager.registerPath(path)
            }

            val routeResult = router.route(userMessage)
            var targetAgents = routeResult.deliverToAgents
            val mentionPaths = routeResult.mentionPaths

            if (userMessage.senderIsUser && mentionedAgentIds.isEmpty() && mentionOnlyAgentIds.isNotEmpty()) {
                targetAgents = targetAgents.filter { it !in mentionOnlyAgentIds }
            }

            if (!hasExplicitMentions && config.enableLLMSpeakerSelection) {
                when (val result = selectNextSpeakerWithLLM()) {
                    is SelectorResult.SpeakerSelected -> {
                        if (result.agentId in agentIds) {
                            targetAgents = listOf(result.agentId)
                            Timber.i("AgentGroup: LLM selected speaker: ${result.agentId}, reason: ${result.reason}")
                        }
                    }
                    is SelectorResult.Finish -> {
                        Timber.i("AgentGroup: LLM decided to finish group chat")
                        return Result.success("")
                    }
                    is SelectorResult.Error -> {
                        Timber.w("AgentGroup: LLM speaker selection error: ${result.message}, using router result")
                    }
                    is SelectorResult.Continue -> {
                        Timber.d("AgentGroup: LLM says continue, using router result")
                    }
                }
            }

            if (routeResult.rejectedTargets.isNotEmpty()) {
                Timber.w("AgentGroup: rejected targets: ${routeResult.rejectedTargets.map { "${it.agentId}(${it.reason})" }}")
            }

            Timber.d("AgentGroup.processMessage: targetAgents=$targetAgents (total agents=${agentIds.size})")

            val enabledTargetAgents = targetAgents.filter { agentId ->
                val agent = agentFactory.getAgent(agentId)
                agent != null && agent.profileConfig.enabled
            }
            if (enabledTargetAgents.size < targetAgents.size) {
                val disabled = targetAgents - enabledTargetAgents.toSet()
                Timber.w("AgentGroup.processMessage: skipping disabled agents: $disabled")
            }
            targetAgents = enabledTargetAgents

            if (targetAgents.isEmpty()) {
                if (hasExplicitMentions) {
                    Timber.w("AgentGroup.processMessage: no target agents!")
                    return Result.success("")
                }
                Timber.w("AgentGroup.processMessage: no target agents from router/LLM, falling back to all agents")
                targetAgents = agentIds.filter { id ->
                    val agent = agentFactory.getAgent(id)
                    agent != null && agent.profileConfig.enabled
                }
            }

            val agentNames = agentIds.associateWith { getAgentName(it) }
            for (agentId in targetAgents) {
                registerGroupToolsToAgent(agentId, agentNames)
            }

            for (agentId in targetAgents) {
                val queue = agentQueues[agentId]
                if (queue != null) {
                    queue.enqueue(QueuedMessage(
                        senderId = senderId,
                        content = content,
                        mentions = mentionedAgentIds,
                        timestamp = System.currentTimeMillis(),
                        senderIsUser = true
                    ))
                    Timber.d("AgentGroup: enqueued message for $agentId (queue size=${queue.pendingCount})")
                } else {
                    Timber.w("AgentGroup: no queue for $agentId")
                }
            }

            Timber.d("AgentGroup: starting serial processing (concurrent disabled to avoid session race), pool permits=${concurrencyPool.availablePermits()}")
            val responses = if (false) { // 并发路径暂禁：多 agent 共享 session 导致 senderId 竞争覆盖
                supervisorScope {
                    targetAgents.map { agentId ->
                        async {
                            val acquireResult = parallelArbitrator.acquire(
                                agentId = agentId,
                                groupId = groupId,
                                sessionLockId = "${groupId}_$agentId",
                                priority = if (userMessage.senderIsUser) TriggerPriority.USER_DIRECT else TriggerPriority.AI_MENTION
                            )
                            when (acquireResult) {
                                is AcquireResult.Granted -> {
                                    try {
                                        val result = processAgentQueue(agentId, mentionPaths[agentId])
                                        Timber.d("AgentGroup: $agentId finished with result=${result?.take(50)}")
                                        result
                                    } finally {
                                        parallelArbitrator.release(acquireResult.ticket.ticketId)
                                    }
                                }
                                is AcquireResult.Denied -> {
                                    Timber.w("AgentGroup: $agentId denied by arbitrator: ${acquireResult.reason}")
                                    null
                                }
                            }
                        }
                    }.mapNotNull { it.await() }
                }
            } else {
                val results = mutableListOf<String>()
                for (agentId in targetAgents) {
                    val acquireResult = parallelArbitrator.acquire(
                        agentId = agentId,
                        groupId = groupId,
                        sessionLockId = "${groupId}_$agentId",
                        priority = TriggerPriority.USER_DIRECT
                    )
                    when (acquireResult) {
                        is AcquireResult.Granted -> {
                            try {
                                val result = processAgentQueue(agentId, mentionPaths[agentId])
                                Timber.d("AgentGroup: $agentId finished (serial) with result=${result?.take(50)}")
                                if (result != null) results.add(result)
                            } finally {
                                parallelArbitrator.release(acquireResult.ticket.ticketId)
                            }
                        }
                        is AcquireResult.Denied -> {
                            Timber.w("AgentGroup: $agentId denied by arbitrator (serial): ${acquireResult.reason}")
                        }
                    }
                }
                // 串行循环完毕后 drain 所有队列，处理串行期间 @传播遗留的消息
                for ((agentId, queue) in agentQueues) {
                    if (queue.pendingCount > 0) {
                        val acquireResult = parallelArbitrator.acquire(
                            agentId = agentId,
                            groupId = groupId,
                            sessionLockId = "${groupId}_$agentId",
                            priority = TriggerPriority.AI_MENTION
                        )
                        when (acquireResult) {
                            is AcquireResult.Granted -> {
                                try {
                                    val result = processAgentQueue(agentId)
                                    if (result != null) results.add(result)
                                } finally {
                                    parallelArbitrator.release(acquireResult.ticket.ticketId)
                                }
                            }
                            is AcquireResult.Denied -> {
                                Timber.w("AgentGroup: drain denied for $agentId: ${acquireResult.reason}")
                            }
                        }
                    }
                }
                results
            }

            Timber.d("AgentGroup: processed ${responses.size}/${targetAgents.size} agents successfully")

            if (config.enableLLMTermination && (_messages.value.size > config.maxRounds / 2 || detectPingPong())) {
                if (shouldTerminateWithLLM()) {
                    Timber.i("AgentGroup: LLM decided to terminate after round ${_messages.value.size}")
                }
            }

            Result.success(responses.joinToString("\n\n"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "AgentGroup.processMessage failed: ${e.message}")
            Result.failure(e)
        } finally {
            if (activeCount.decrementAndGet() == 0) _isActive.value = false
        }
    }

    /**
     * 从智能体的队列中依次取出消息处理。
     *
     * - per-agent [Mutex] 保证同一智能体串行处理队列
     * - [concurrencyPool] 限制全局并发数不超过 [maxConcurrency]
     * - 处理完一条自动检查队列中是否有下一条，直到队列为空
     */
    private suspend fun processAgentQueue(agentId: String, mentionPath: MentionPath? = null, isCycleTarget: Boolean = false): String? {
        return agentMutexes[agentId]?.withLock {
            var lastResponse: String? = null
            var processedCount = 0

            while (true) {
                val queue = agentQueues[agentId] ?: break
                val message = queue.dequeue() ?: break
                processedCount++

                Timber.d("AgentGroup.processAgentQueue: $agentId processing message #$processedCount (queue remaining=${queue.pendingCount})")

                // 从并发池获取许可（阻塞直到有可用配额）
                concurrencyPool.acquire()
                try {
                    val response = processAgentResponse(
                        agentId,
                        message.content,
                        message.senderId,
                        mentionPath,
                        isCycleTarget
                    )
                    if (response != null) {
                        Timber.d("AgentGroup.processAgentQueue: $agentId got response (${response.take(50)})")
                        lastResponse = response
                    } else {
                        Timber.w("AgentGroup.processAgentQueue: $agentId processAgentResponse returned null")
                    }
                } finally {
                    concurrencyPool.release()
                }
            }

            Timber.d("AgentGroup.processAgentQueue: $agentId done, processed=$processedCount, lastResponse=${lastResponse != null}")
            lastResponse
        }
    }

    private fun filterGroupMessagesForAgent(agentId: String, agentName: String, messages: List<GroupChatMessage>): List<GroupChatMessage> {
        return messages.filter { msg ->
            msg.senderIsUser ||
                msg.agentId == agentId ||
                MentionParser.parse(msg.content).any { it == agentId || it.equals(agentName, ignoreCase = true) }
        }
    }

    private suspend fun processAgentResponse(
        agentId: String,
        userContent: String,
        senderId: String,
        mentionPath: MentionPath? = null,
        isCycleTarget: Boolean = false
    ): String? {
        val agent = agentFactory.getAgent(agentId) ?: return null

        if (!agent.profileConfig.enabled) {
            Timber.w("AgentGroup.processAgentResponse: agent $agentId is disabled, skipping")
            return null
        }

        if (senderId != "user") {
            val senderName = getAgentName(senderId)
            sessionStore.addMessage(groupId, MessageRole.PRIVATE, "[$senderName]: $userContent", senderId = senderId)
        }

        val msgCountBefore = sessionStore.getMessages(groupId).getOrDefault(emptyList()).size

        val agentName = getAgentName(agentId)
        val filteredMessages = filterGroupMessagesForAgent(agentId, agentName, _messages.value)

        // 构建智能体描述：优先使用 descriptionProvider 获取完整描述，回退到名字
        val agentDescriptions = if (descriptionProvider != null) {
            agentIds.associateWith { id ->
                runCatching { descriptionProvider?.getDescription(id) }.getOrDefault(getAgentName(id)) ?: getAgentName(id)
            }
        } else {
            agentIds.associateWith { runCatching { getAgentName(it) }.getOrDefault(it) }
        }
        val groupContext = GroupContext(
            groupId = groupId,
            groupName = groupName,
            allAgentIds = agentIds,
            agentDescriptions = agentDescriptions,
            recentMessages = filteredMessages,
            currentRound = _messages.value.size,
            maxRounds = 100
        )
        val systemPromptSuffix = GroupChatPrompts.buildAgentSystemPrompt(agentId, groupContext, mentionPath, isCycleTarget)

        val existingSessionMessages = sessionStore.getMessages(groupId).getOrDefault(emptyList())
        val previousToolMsgIds = existingSessionMessages.filter { it.role == MessageRole.TOOL }.map { it.id }.toSet()

        try {
            agent.contextMessageFilter = { msg ->
                val isOtherAgentMessage = (msg.role == MessageRole.TOOL || msg.role == MessageRole.ASSISTANT)
                    && msg.senderId != null
                    && msg.senderId != agentId
                    && msg.senderId != "user"
                !isOtherAgentMessage && (msg.role != MessageRole.TOOL || msg.id !in previousToolMsgIds)
            }
            val result = agent.processMessage(
                sessionId = groupId,
                channelId = "group",
                content = userContent,
                skipUserMessage = true,
                systemPromptSuffix = systemPromptSuffix,
                overrideProviderId = agent.profileConfig.modelProvider.ifBlank { null }
            )

            if (!result.isSuccess) return null

            val allAgentMessages = sessionStore.getMessages(groupId).getOrDefault(emptyList())
            val newMessages = allAgentMessages.drop(msgCountBefore)

            for (msg in newMessages) {
                if (msg.senderId == null) {
                    sessionStore.updateMessageSenderId(msg.id, agentId)
                } else if (msg.senderId != agentId) {
                    // 这条消息已被其他 agent 标记，跳过
                    Timber.d("AgentGroup.processAgentResponse: msg ${msg.id} already attributed to ${msg.senderId}, skipping")
                }
            }

            val lastAssistantMsg = allAgentMessages.lastOrNull {
                it.role == MessageRole.ASSISTANT && it.senderId == agentId
            }

            if (lastAssistantMsg != null) {
                val agentMessage = GroupChatMessage(
                    agentId = agentId,
                    content = lastAssistantMsg.content,
                    round = _messages.value.size,
                    timestamp = System.currentTimeMillis()
                )
                _messages.update { it + agentMessage }

                onAgentReplied?.invoke(agentId, agentName, lastAssistantMsg.content, groupId)

                val agentMentions = MentionParser.parse(lastAssistantMsg.content)
                    .filter { it in agentIds && it != agentId && agentFactory.getAgent(it) != null }

                if (agentMentions.isNotEmpty()) {
                    val propagation = mentionChainManager.checkPropagation(agentId, agentMentions, mentionPath)
                    if (propagation.allowed.isNotEmpty()) {
                        val agentNames = agentIds.associateWith { getAgentName(it) }
                        for (targetId in propagation.allowed) {
                            registerGroupToolsToAgent(targetId, agentNames)
                            val queue = agentQueues[targetId]
                            if (queue != null) {
                                queue.enqueue(QueuedMessage(
                                    senderId = agentId,
                                    content = lastAssistantMsg.content,
                                    mentions = agentMentions,
                                    timestamp = System.currentTimeMillis(),
                                    senderIsUser = false
                                ))
                                Timber.d("AgentGroup: mention chain propagation $agentId → $targetId (queue size=${queue.pendingCount})")
                            }
                        }
                        for (targetId in propagation.allowed) {
                            val isCycle = targetId in propagation.cycleTargets
                            val targetPath = propagation.paths[targetId]
                            // 异步传播只在 serial 循环外启动；serial 循环内由 processAgentQueue 的 while 循环自然处理
                            activeCount.incrementAndGet()
                            _isActive.value = true
                            groupScope.launch {
                                try {
                                    processAgentQueue(targetId, targetPath, isCycle)
                                } finally {
                                    if (activeCount.decrementAndGet() == 0) _isActive.value = false
                                }
                            }
                            Timber.d("AgentGroup: async mention chain propagation $agentId → $targetId (isCycle=$isCycle)")
                        }
                    }
                }

                return "[${agentId}]: ${lastAssistantMsg.content}"
            }

            return null
        } finally {
            agent.contextMessageFilter = null
            cleanupAgentDenyList(agentId)
        }
    }

    private val GROUP_DENIED_TOOLS = listOf(
        "spawn_subagent",
        "spawn_sub_agent",
        "check_subagent_tasks",
        "aggregate_subagent_results",
        "submit_to_agent",
        "check_agent_task",
        "chat_with_agent",
        "list_agents",
        "ask"
    )

    /**
     * 注册群组专用工具到指定智能体的工具列表中。
     * 每次群组处理时调用，确保工具定义在智能体的 system prompt 中可见。
     */
    suspend fun registerGroupToolsToAgent(agentId: String, agentNames: Map<String, String>) {
        val agent = agentFactory.getAgent(agentId) ?: return
        val groupRef = this
        val cachedNames = java.util.concurrent.ConcurrentHashMap<String, String>(agentNames)
        agent.registerTool(GroupMemberListTool(groupId) {
            val currentIds = groupRef._agentIds.value
            val result = mutableMapOf<String, String>()
            for (id in currentIds) {
                result[id] = cachedNames.getOrPut(id) { id }
            }
            result
        })
        val existingDenyList = agent.profileConfig.disabledTools.toMutableList()
        for (tool in GROUP_DENIED_TOOLS) {
            if (tool !in existingDenyList) {
                existingDenyList.add(tool)
            }
        }
        agent.setToolDenyList(existingDenyList)
        Timber.d("Registered group member tool for agent $agentId")
    }

    private suspend fun cleanupAgentDenyList(agentId: String) {
        val agent = agentFactory.getAgent(agentId) ?: return
        val cleaned = agent.profileConfig.disabledTools.filter { it !in GROUP_DENIED_TOOLS }
        agent.setToolDenyList(cleaned)
    }

    fun getHistory(limit: Int = 50): List<GroupChatMessage> {
        return _messages.value.takeLast(limit)
    }

    fun clearHistory() {
        _messages.update { emptyList() }
    }

    suspend fun storeMessageOnly(senderId: String, content: String, mentionedAgentIds: List<String> = emptyList()) {
        val message = GroupChatMessage(
            agentId = senderId,
            content = content,
            round = _messages.value.size,
            timestamp = System.currentTimeMillis(),
            senderIsUser = false
        )
        _messages.update { it + message }
    }

    fun removeAgentId(agentId: String) {
        val currentList = _agentIds.value
        if (agentId in currentList) {
            _agentIds.update { it - agentId }
            agentQueues.remove(agentId)
        }
    }

    fun containsAgent(agentId: String): Boolean = agentId in _agentIds.value

    fun cleanup() {
        groupScope.cancel()
        mentionChainManager.cleanup(groupId)
        parallelArbitrator.cleanup(groupId)
    }
}
