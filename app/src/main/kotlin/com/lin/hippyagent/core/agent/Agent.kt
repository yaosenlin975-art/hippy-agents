package com.lin.hippyagent.core.agent

import android.content.Context
import com.lin.hippyagent.core.agent.middleware.AgentMiddleware
import com.lin.hippyagent.core.agent.middleware.DanglingToolCallMiddleware
import com.lin.hippyagent.core.agent.middleware.MiddlewareChain
import com.lin.hippyagent.core.agent.middleware.MiddlewareContext
import com.lin.hippyagent.core.agent.middleware.MiddlewareResult
import com.lin.hippyagent.core.agent.middleware.ModelResponse
import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.session.SessionMessage
import com.lin.hippyagent.core.agent.session.SessionToolCall
import com.lin.hippyagent.core.agent.session.ToolCallStatus
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.bootstrap.BootstrapHook
import com.lin.hippyagent.core.channel.ChannelMessage
import com.lin.hippyagent.core.channel.ChannelManager
import com.lin.hippyagent.core.prompt.PromptBuilder
import com.lin.hippyagent.core.prompt.PromptContext
import com.lin.hippyagent.core.prompt.StandingOrdersManager
import com.lin.hippyagent.core.context.ContextManager
import com.lin.hippyagent.core.memory.MemoryManager
import com.lin.hippyagent.core.memory.QueryIntentClassifier
import com.lin.hippyagent.core.memory.commonmemory.toSearchIntent
import com.lin.hippyagent.core.memory.compaction.IterativeSummaryMerger
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelCallResponse
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.model.ToolCallInfo
import com.lin.hippyagent.core.model.FunctionInfo
import com.lin.hippyagent.core.model.TokenUsage
import com.lin.hippyagent.core.model.LlmRateLimiter
import com.lin.hippyagent.core.model.LlmRateLimitException
import com.lin.hippyagent.core.model.FailoverEngine
import com.lin.hippyagent.core.model.FailoverError
import com.lin.hippyagent.core.model.FailoverAction
import com.lin.hippyagent.core.model.AuthProfileManager
import com.lin.hippyagent.core.network.NetworkMonitor
import com.lin.hippyagent.core.storage.StorageManager
import com.lin.hippyagent.core.pool.StringBuilderPool
import com.lin.hippyagent.core.pool.ToolCallInfoListPool
import com.lin.hippyagent.core.tools.ToolCall
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.core.tools.ToolResult
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.coroutines.coroutineContext

/**
 * 流式输出块 — 区分普通内容和思考过程
 * 使用对象池复用，减少 GC 压力
 */
sealed class StreamChunk {
    data class Content(val text: String) : StreamChunk()
    data class Thinking(val text: String) : StreamChunk()
    data class Compaction(val compressedCount: Int, val summaryLength: Int) : StreamChunk()
    data class CompactionStarted(
        val totalTokens: Int,
        val maxTokens: Int,
        val messagesToCompress: Int,
        val messagesToKeep: Int
    ) : StreamChunk()
    data class CompactionCompleted(
        val compressedCount: Int,
        val newTokenEstimate: Int,
        val maxTokens: Int,
        val beforeTokens: Int = 0
    ) : StreamChunk()
    data class TaskCompleted(val inputTokens: Long, val outputTokens: Long, val apiCalls: Int) : StreamChunk()
    data object NewIteration : StreamChunk()
}

/**
 * 单个会话的运行时状态 — 支持同一 Agent 多会话并行
 */
data class SessionState(
    val status: AgentStatus = AgentStatus.IDLE,
    val isThinking: Boolean = false,
    val lastError: String? = null,
    val messageCount: Int = 0,
    val toolCallCount: Int = 0,
    /** 待授权的 Shell 命令（非 null 时 UI 层需显示授权对话框） */
    val pendingPermissionCommand: String? = null,
    /** 缺失的 Android 运行时权限列表 */
    val missingAndroidPermissions: List<String> = emptyList(),
    /** 最近一次 LLM 调用是否使用了 fallback 模型，非 null 时为实际使用的 fallback 模型名 */
    val usedFallbackModel: String? = null,
    /** mid-turn 智能体声明的模式覆盖；null 时由 ChatViewModel 调用 orchestrator 解析 (AUTO) */
    val modeOverride: String? = null
)

data class AgentState(
    val agentId: String,
    val tokenUsage: TokenUsage = TokenUsage(),
    /** per-session 状态映射，key = sessionId */
    val sessionStates: Map<String, SessionState> = emptyMap()
) {
    /** 获取指定会话的状态，不存在则返回 IDLE */
    fun getSessionState(sessionId: String): SessionState =
        sessionStates[sessionId] ?: SessionState()

    /** 获取当前所有活跃（非 IDLE）会话的 sessionId 列表 */
    internal fun activeSessionIds(): List<String> =
        sessionStates.filter { it.value.status != AgentStatus.IDLE }.keys.toList()
}

enum class AgentStatus {
    IDLE,
    THINKING,
    EXECUTING_TOOL,
    ERROR,
    STOPPED;

    /** 合法的状态转换映射 — 防止非法状态跳转 */
    fun canTransitionTo(target: AgentStatus): Boolean = when (this) {
        IDLE -> target in setOf(THINKING, ERROR, STOPPED)
        THINKING -> target in setOf(EXECUTING_TOOL, IDLE, ERROR, STOPPED)
        EXECUTING_TOOL -> target in setOf(THINKING, IDLE, ERROR, STOPPED)
        ERROR -> target in setOf(IDLE, THINKING, STOPPED)
        STOPPED -> target in setOf(IDLE, THINKING)
    }
}

/**
 * 网络不可用异常
 */
internal class NetworkUnavailableException(message: String) : Exception(message)

class Agent(
    private val context: Context,
    private val profile: AgentProfile,
    private val modelClient: ModelClient,
    private val toolRegistry: ToolRegistry,
    private val memoryManager: MemoryManager,
    private val channelManager: ChannelManager,
    private val sessionStore: SessionStore,
    private val failoverEngine: FailoverEngine? = null,
    private val authProfileManager: AuthProfileManager? = null,
    private val modelProviderStore: ModelProviderStore? = null,
    private val secureStorage: com.lin.hippyagent.core.storage.SecureStorage? = null,
    private val memoryExtractor: com.lin.hippyagent.core.memory.commonmemory.MemoryExtractor? = null,
    private val commonMemoryRepo: com.lin.hippyagent.core.memory.commonmemory.MemoryRepository? = null,
    private val tokenUsageManager: com.lin.hippyagent.core.model.TokenUsageManager? = null,
    private val modelRouter: com.lin.hippyagent.core.model.routing.ModelRouter? = null,
    private val configStorage: com.lin.hippyagent.core.storage.ConfigStorage? = null,
    private val toolGuardian: com.lin.hippyagent.core.tools.ToolGuardian? = null,
    private val sessionManager: com.lin.hippyagent.core.agent.AgentSessionManager? = null,
    private val approvalManager: com.lin.hippyagent.core.security.ToolApprovalManager? = null,
    private val onDeviceModelManager: com.lin.hippyagent.core.ondevice.OnDeviceModelManager? = null,
    /** 群组聊天时过滤上下文消息的回调 — 只给 agent 看它该看的 */
    var contextMessageFilter: ((com.lin.hippyagent.core.agent.session.SessionMessage) -> Boolean)? = null,

) : KoinComponent {
    /**
     * 去除模型名称中的供应商前缀，例如 "小米/mimo-v2.5" -> "mimo-v2.5"
     * API 只需要模型 ID，不需要供应商前缀。
     */
    private fun stripModelPrefix(modelName: String): String {
        val lastSlash = modelName.lastIndexOf('/')
        return if (lastSlash >= 0 && lastSlash < modelName.length - 1) {
            modelName.substring(lastSlash + 1)
        } else {
            modelName
        }
    }

    /**
     * 根据 providerId 动态创建 ModelClient，用于聊天界面切换到不同供应商的模型时使用。
     * 如果 providerId 为空或与当前 profile 相同，返回默认的 modelClient。
     */
    private suspend fun resolveModelClient(overrideProviderId: String?): ModelClient {
        if (overrideProviderId.isNullOrBlank() || overrideProviderId == profile.modelProvider) {
            return modelClient
        }
        val store = modelProviderStore ?: return modelClient

        val providers = store.providers.first()
        val provider = providers.firstOrNull { it.id == overrideProviderId && it.enabled }
            ?: providers.firstOrNull { it.name == overrideProviderId && it.enabled }
            ?: return modelClient

        return com.lin.hippyagent.core.model.ModelClientFactory.create(
            provider = provider,
            secureStorage = secureStorage,
            onDeviceModelManager = onDeviceModelManager
        )
    }
    private val networkMonitor = NetworkMonitor(context)
    private val offlineMessageQueue = com.lin.hippyagent.core.network.OfflineMessageQueue(context)
    private val rateLimiter = LlmRateLimiter(profile.running)
    private val _state = MutableStateFlow(AgentState(agentId = profile.agentId))
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _tokenUsage = MutableStateFlow(TokenUsage())
    val tokenUsageState: StateFlow<TokenUsage> = _tokenUsage.asStateFlow()

    data class ContextTokenInfo(val currentTokens: Long = 0, val maxTokens: Long = 0)
    private val _contextTokenInfo = MutableStateFlow(ContextTokenInfo())
    val contextTokenInfo: StateFlow<ContextTokenInfo> = _contextTokenInfo.asStateFlow()

    fun registerTool(tool: com.lin.hippyagent.core.tools.Tool) {
        toolRegistry.register(tool)
    }

    fun unregisterTool(name: String) {
        toolRegistry.unregister(name)
    }

    fun registerTools(tools: List<com.lin.hippyagent.core.tools.Tool>) {
        tools.forEach { toolRegistry.register(it) }
    }

    fun setToolDenyList(patterns: List<String>) {
        toolRegistry.setAgentDenyList(profile.agentId, patterns)
    }

    private data class SessionContext(
        val mutex: Mutex = Mutex(),
        var job: Job? = null,
        var interruptFlag: Boolean = false,
        var systemPrompt: String = "",
        val messageQueue: MutableList<String> = mutableListOf()
    )

    private val sessionContexts = ConcurrentHashMap<String, SessionContext>()

    private fun getOrCreateSessionContext(sessionId: String): SessionContext =
        sessionContexts.getOrPut(sessionId) { SessionContext() }

    private fun isSessionInterrupted(sessionId: String): Boolean =
        sessionContexts[sessionId]?.interruptFlag == true

    private fun markSessionInterrupted(sessionId: String) {
        getOrCreateSessionContext(sessionId).interruptFlag = true
    }

    private fun clearSessionInterrupt(sessionId: String) {
        sessionContexts[sessionId]?.interruptFlag = false
    }

    private val middlewareList = mutableListOf<AgentMiddleware>()
    private var middlewareChain = MiddlewareChain(emptyList())

    fun addMiddleware(middleware: AgentMiddleware) {
        middlewareList.add(middleware)
        middlewareChain = MiddlewareChain(middlewareList)
    }

    fun getMiddleware(name: String): AgentMiddleware? = middlewareChain.getMiddleware(name)

    private fun runBeforeModel(sessionId: String, messages: MutableList<ModelMessage>, iteration: Int): MutableList<ModelMessage> {
        val ctx = MiddlewareContext(
            sessionId = sessionId,
            agentId = profile.agentId,
            messages = messages,
            iteration = iteration
        )
        when (val result = middlewareChain.runBeforeModel(ctx)) {
            is MiddlewareResult.Modify -> {
                messages.clear()
                messages.addAll(result.messages)
            }
            is MiddlewareResult.AbortTurn -> {
                Timber.w("Middleware aborted turn: ${result.reason}")
            }
            is MiddlewareResult.HardAbort -> {
                Timber.w("Middleware hard-aborted")
            }
            else -> {}
        }
        return messages
    }

    private fun runAfterModel(sessionId: String, messages: MutableList<ModelMessage>, iteration: Int, response: ModelResponse): MiddlewareResult {
        val ctx = MiddlewareContext(
            sessionId = sessionId,
            agentId = profile.agentId,
            messages = messages,
            iteration = iteration
        )
        return middlewareChain.runAfterModel(ctx, response)
    }

    private fun runAfterAgent(sessionId: String, messages: MutableList<ModelMessage>) {
        val ctx = MiddlewareContext(
            sessionId = sessionId,
            agentId = profile.agentId,
            messages = messages
        )
        middlewareChain.runAfterAgent(ctx)
    }

    /** 获取指定会话的 Mutex，不存在则自动创建 */
    private fun getOrCreateSessionMutex(sessionId: String): Mutex =
        getOrCreateSessionContext(sessionId).mutex

    /** 获取指定会话是否正在处理中 */
    internal fun isSessionProcessing(sessionId: String): Boolean {
        return sessionContexts[sessionId]?.mutex?.isLocked == true
    }

    /** 当前正在处理的会话 ID（用于 fallback 等内部逻辑获取当前会话） */
    @Volatile
    private var _currentProcessingSessionId: String? = null

    private fun currentSessionId(): String? = _currentProcessingSessionId

    val profileConfig: AgentProfile get() = profile

    /** 获取指定会话的 SessionState, 不存在则返回默认 IDLE */
    fun getSessionState(sessionId: String): SessionState =
        _state.value.getSessionState(sessionId)

    /** 消费并清空 sessionState.modeOverride (单次使用, 下次 turn 恢复用户手动选择) */
    fun consumeModeOverride(sessionId: String): String? {
        val current = _state.value.getSessionState(sessionId).modeOverride ?: return null
        updateSessionState(sessionId) { it.copy(modeOverride = null) }
        return current
    }

    fun steer(direction: String, sessionId: String? = null) {
        val sid = sessionId ?: currentSessionId()
        if (sid != null) {
            val ctx = getOrCreateSessionContext(sid)
            ctx.systemPrompt = buildString { append(ctx.systemPrompt); append("\n[STEER] "); append(direction) }
            Timber.i("Steered agent ${profile.agentId} session $sid: $direction")
        }
    }

    fun queueMessage(sessionId: String, message: String): Int {
        val ctx = getOrCreateSessionContext(sessionId)
        ctx.messageQueue.add(message)
        Timber.i("Queued message for agent ${profile.agentId} session $sessionId, queue size: ${ctx.messageQueue.size}")
        return ctx.messageQueue.size
    }

    private val contextManager = ContextManager(
        profile.running,
        toolResultCacheDir = context.cacheDir.absolutePath
    )
    private val summaryMerger = IterativeSummaryMerger()

    private val SUMMARY_PREFIX = """
        |[以下是对话历史的压缩摘要，由系统自动生成]
        |摘要中描述的任务可能尚未完成，请根据摘要中的上下文继续工作。
        |---
        |""".trimMargin()

    private val sbPool = StringBuilderPool(maxSize = 8)
    private val toolCallListPool = ToolCallInfoListPool(maxSize = 4)
    private val reusableToolCallList = mutableListOf<ToolCallInfo>()
    private val repairPipeline = com.lin.hippyagent.core.agent.repair.ToolCallRepairPipeline()

    private fun buildMetaJson(existingMeta: Map<String, String>?, thinkingDurationMs: Long): String {
        if (thinkingDurationMs <= 0L) return ""
        if (existingMeta.isNullOrEmpty()) {
            return "{\"thinkingDurationMs\":$thinkingDurationMs}"
        }
        val sb = sbPool.acquire()
        try {
            sb.append('{')
            existingMeta.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append(',')
                sb.append('"').append(k).append("\":\"").append(v).append('"')
            }
            sb.append(",\"thinkingDurationMs\":").append(thinkingDurationMs)
            sb.append('}')
            return sb.toString()
        } finally {
            sbPool.release(sb)
        }
    }

    private class AccumulatedToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    private class AccumulatedToolCallPool(private val maxSize: Int = 8) {
        private val pool = java.util.ArrayDeque<AccumulatedToolCall>()

        fun acquire(): AccumulatedToolCall {
            val obj = pool.pollFirst()
            return if (obj != null) {
                obj.id = ""
                obj.name = ""
                obj.arguments.clear()
                obj
            } else {
                AccumulatedToolCall()
            }
        }

        fun release(obj: AccumulatedToolCall) {
            if (pool.size < maxSize) {
                obj.id = ""
                obj.name = ""
                obj.arguments.clear()
                pool.addLast(obj)
            }
        }
    }

    private val accumulatedToolCallPool = AccumulatedToolCallPool(maxSize = 8)

    private fun mergeToolCallDeltas(
        accumulated: android.util.SparseArray<AccumulatedToolCall>,
        deltas: List<ToolCallInfo>
    ) {
        for (delta in deltas) {
            val idx = if (delta.index >= 0) delta.index else accumulated.size()
            val acc = accumulated.get(idx) ?: run {
                val new = accumulatedToolCallPool.acquire()
                accumulated.put(idx, new)
                new
            }
            if (delta.id.isNotBlank() && delta.id != "unknown") acc.id = delta.id
            if (delta.function.name.isNotBlank()) acc.name = delta.function.name
            acc.arguments.append(delta.function.arguments)
        }
    }

    private fun buildFinalToolCalls(
        accumulated: android.util.SparseArray<AccumulatedToolCall>,
        out: MutableList<ToolCallInfo>
    ) {
        for (i in 0 until accumulated.size()) {
            val idx = accumulated.keyAt(i)
            val acc = accumulated.valueAt(i)
            out.add(ToolCallInfo(
                id = acc.id.ifBlank { "call_$idx" },
                function = FunctionInfo(
                    name = acc.name,
                    arguments = acc.arguments.toString()
                ),
                index = idx
            ))
        }
    }

    private data class PreparedContext(
        val effectiveClient: ModelClient,
        val messages: MutableList<ModelMessage>,
        val toolDefinitions: List<com.lin.hippyagent.core.model.ModelToolDefinition>,
        val escalatedThisTurn: Boolean,
        val compactionStartedInfo: StreamChunk.CompactionStarted? = null,
        val compactionInfo: StreamChunk.Compaction? = null,
        val compactionCompletedInfo: StreamChunk.CompactionCompleted? = null
    )

    private suspend fun prepareMessageContext(
        sessionId: String,
        channelId: String,
        userMessage: String,
        overrideProviderId: String?,
        skipUserMessage: Boolean = false,
        systemPromptSuffix: String? = null,
        overrideModel: String? = null
    ): PreparedContext? {
        if (!networkMonitor.isOnline()) {
            val queued = com.lin.hippyagent.core.network.QueuedMessage(
                sessionId = sessionId,
                content = userMessage,
                channelId = profile.agentId
            )
            offlineMessageQueue.enqueue(queued)
            Timber.w("Network offline: message queued (id=${queued.id})")
            return null
        }

        val effectiveClient = resolveModelClient(overrideProviderId)

        if (!skipUserMessage) {
            val addResult = sessionStore.addMessage(sessionId, MessageRole.USER, userMessage)
            Timber.d("prepareMessageContext[$sessionId]: user msg added, result=${addResult.isSuccess}, content=${userMessage.take(80)}, error=${addResult.exceptionOrNull()?.message}")
        }

        val messageCount = sessionStore.getMessages(sessionId, includeCompressed = false)
            .getOrDefault(emptyList()).size
        if (contextManager.quickEstimateNeedsCompression(messageCount, modelContextWindow = resolveModelContextWindow())) {
            Timber.d("Preemptive compression: estimated $messageCount messages may exceed context window")
        }

        val escalatedThisTurn = userMessage.trim().equals("/pro", ignoreCase = true)
        if (escalatedThisTurn) {
            Timber.i("/pro command: forcing HEAVY model for this turn")
        }

        val effectiveModel = overrideModel ?: profile.modelName
        val promptResult = buildPrompt(sessionId, systemPromptSuffix = systemPromptSuffix, isEscalated = escalatedThisTurn, effectiveModelName = effectiveModel)
        val messages = promptResult.messages

        val toolDefinitions = toolRegistry.getDefinitionsForAgent(
            agentId = profile.agentId
        ).map { def ->
            com.lin.hippyagent.core.model.ModelToolDefinition(
                name = def.name,
                description = def.description,
                parameters = buildToolParameterSchema(def.parameters)
            )
        }

        return PreparedContext(
            effectiveClient = effectiveClient,
            messages = messages,
            toolDefinitions = toolDefinitions,
            escalatedThisTurn = escalatedThisTurn,
            compactionStartedInfo = promptResult.compactionStartedInfo,
            compactionInfo = promptResult.compactionInfo,
            compactionCompletedInfo = promptResult.compactionCompletedInfo
        )
    }

    private data class ToolCallResult(
        val escalatedThisTurn: Boolean,
        val shouldReturn: Boolean
    )

    private suspend fun handleToolCalls(
        toolCalls: List<ToolCallInfo>,
        content: String,
        reasoningContent: String?,
        sessionId: String,
        channelId: String,
        messages: MutableList<ModelMessage>,
        isLastIteration: Boolean,
        escalatedThisTurn: Boolean,
        turnFailureTracker: com.lin.hippyagent.core.model.routing.TurnFailureTracker,
        thinkingDurationMs: Long = 0L
    ): ToolCallResult {
        val allowedToolNames = toolRegistry.getDefinitionsForAgent(
            agentId = profile.agentId
        ).map { it.name }.toSet()
        val repairResult = repairPipeline.repair(
            toolCalls = toolCalls,
            content = content,
            reasoningContent = reasoningContent,
            allowedToolNames = allowedToolNames
        )
        var currentEscalated = escalatedThisTurn
        if (repairResult.scavenged > 0 || repairResult.truncationsFixed > 0 || repairResult.stormsBroken > 0) {
            Timber.d("ToolCallRepair: scavenged=${repairResult.scavenged}, truncated=${repairResult.truncationsFixed}, storms=${repairResult.stormsBroken}")
            val escalated = turnFailureTracker.noteFailure(
                com.lin.hippyagent.core.model.routing.TurnFailureTracker.FailureSignal.SCAVENGED
            )
            if (escalated && profile.complexModelName.isNotEmpty() && !currentEscalated) {
                currentEscalated = true
                Timber.w("SCAVENGED → escalating to complex model")
            }
        }
        val effectiveToolCalls = repairResult.repairedToolCalls.ifEmpty { toolCalls }

        if (isLastIteration) {
            val partialReply = content.ifEmpty { "" }
            val exhaustionNotice = "\n\n⚠️ 迭代轮次已耗尽（${profile.running.maxIters}轮），任务未能完全完成。以下是当前进度：\n${partialReply.ifBlank { "（智能体在最后一轮仍在调用工具，未能生成文字总结）" }}"
            val fullReply = partialReply + exhaustionNotice
            if (fullReply.isNotEmpty()) {
                sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                val replyMessage = ChannelMessage(
                    content = fullReply,
                    senderId = profile.agentId,
                    sessionId = sessionId
                )
                channelManager.broadcast(replyMessage, excludeChannel = channelId)
            }
            return ToolCallResult(currentEscalated, shouldReturn = true)
        }

        val rawContent = content.ifEmpty { "" }
        val assistantContent = if (!reasoningContent.isNullOrBlank()) {
            "⋞${reasoningContent}⋟\n$rawContent"
        } else {
            rawContent
        }
        val assistantMsg = sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, assistantContent, senderId = profile.agentId).getOrNull()
        if (assistantMsg != null && thinkingDurationMs > 0L) {
            val existingMeta = assistantMsg.metadataJson?.let {
                try {
                    val obj = kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject
                    obj?.mapValues { (_, v) -> v.jsonPrimitive.content }
                } catch (_: Exception) { null }
            }
            val metaJson = buildMetaJson(existingMeta, thinkingDurationMs)
            if (metaJson.isNotEmpty()) {
                sessionStore.updateMessageMetadata(assistantMsg.id, metaJson)
            }
        }
        if (assistantMsg != null) {
            for (toolCall in effectiveToolCalls) {
                sessionStore.addToolCall(sessionId, assistantMsg, SessionToolCall(
                    id = toolCall.id,
                    name = toolCall.function.name,
                    arguments = toolCall.function.arguments,
                    status = ToolCallStatus.RUNNING
                ))
            }
        }

        messages.add(ModelMessage(
            role = "assistant",
            content = assistantContent,
            toolCalls = effectiveToolCalls
        ))

        val toolResults = coroutineScope {
            effectiveToolCalls.map { toolCall ->
                async(Dispatchers.IO) {
                    toolCall to executeToolCall(toolCall, sessionId, channelId)
                }
            }.awaitAll()
        }

        for ((toolCall, toolResult) in toolResults) {
            val resultContent = toolResult?.output ?: toolResult?.error ?: ""
            val resultStatus = if (toolResult?.success == true) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED
            if (toolResult?.success == false && resultContent.contains("search text not found", ignoreCase = true)) {
                val escalated = turnFailureTracker.noteFailure(
                    com.lin.hippyagent.core.model.routing.TurnFailureTracker.FailureSignal.SEARCH_MISMATCH
                )
                if (escalated && profile.complexModelName.isNotEmpty() && !currentEscalated) {
                    currentEscalated = true
                    Timber.w("SEARCH_MISMATCH → escalating to complex model")
                }
            }
            if (toolResult?.success == false && (resultContent.contains("truncated", ignoreCase = true) || resultContent.contains("JSON", ignoreCase = true))) {
                val escalated = turnFailureTracker.noteFailure(
                    com.lin.hippyagent.core.model.routing.TurnFailureTracker.FailureSignal.TRUNCATED
                )
                if (escalated && profile.complexModelName.isNotEmpty() && !currentEscalated) {
                    currentEscalated = true
                    Timber.w("TRUNCATED → escalating to complex model")
                }
            }
            if (assistantMsg != null) {
                sessionStore.updateToolCall(sessionId, assistantMsg.id, toolCall.id, resultStatus, resultContent)
            }
            sessionStore.addMessage(sessionId, MessageRole.TOOL, resultContent, toolName = toolCall.function.name, senderId = profile.agentId)
            Timber.d("Tool result: toolCallId=${toolCall.id}, name=${toolCall.function.name}, content=${resultContent.take(50)}")
            messages.add(ModelMessage(
                role = "tool",
                content = resultContent,
                toolCallId = toolCall.id
            ))
            if (toolResult?.needsPermissionApproval == true) {
                updateSessionState(sessionId) { it.copy(pendingPermissionCommand = toolResult.permissionCommand) }
            }
            if (toolResult?.missingAndroidPermissions?.isNotEmpty() == true) {
                updateSessionState(sessionId) { it.copy(missingAndroidPermissions = toolResult.missingAndroidPermissions) }
            }
        }

        return ToolCallResult(currentEscalated, shouldReturn = false)
    }

    private suspend fun handleTextReply(
        content: String?,
        reasoningContent: String?,
        sessionId: String,
        channelId: String,
        thinkingDurationMs: Long = 0L
    ) {
        val reply = content ?: ""
        val fullReply = if (!reasoningContent.isNullOrBlank() && reply.isNotEmpty()) {
            "⋞${reasoningContent}⋟\n$reply"
        } else if (!reasoningContent.isNullOrBlank()) {
            "⋞${reasoningContent}⋟"
        } else {
            reply
        }
        if (fullReply.isNotEmpty()) {
            val assistantMsg = sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId).getOrNull()
            if (assistantMsg != null && thinkingDurationMs > 0L) {
                val existingMeta = assistantMsg.metadataJson?.let {
                    try {
                        val obj = kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject
                        obj?.mapValues { (_, v) -> v.jsonPrimitive.content }
                    } catch (_: Exception) { null }
                }
                val metaJson = buildMetaJson(existingMeta, thinkingDurationMs)
                if (metaJson.isNotEmpty()) {
                    sessionStore.updateMessageMetadata(assistantMsg.id, metaJson)
                }
            }
            val replyMessage = ChannelMessage(
                content = reply,
                senderId = profile.agentId,
                sessionId = sessionId
            )
            channelManager.broadcast(replyMessage, excludeChannel = channelId)
        }
    }

    suspend fun processMessage(
        sessionId: String,
        channelId: String,
        content: String,
        overrideModel: String? = null,
        skipUserMessage: Boolean = false,
        overrideProviderId: String? = null,
        systemPromptSuffix: String? = null
    ): Result<Unit> = getOrCreateSessionMutex(sessionId).withLock {
    getOrCreateSessionContext(sessionId).job = coroutineContext[Job]!!
    _currentProcessingSessionId = sessionId
        sessionManager?.createSession(sessionId, profile.agentId, channelId)
        sessionManager?.updateActivity(sessionId)
        updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING, isThinking = true, usedFallbackModel = null) }

        var capturedMessages: MutableList<ModelMessage>? = null
        return runCatching {
            Timber.d("Agent ${profile.agentId} processing message: $content")

            val ctx = prepareMessageContext(sessionId, channelId, content, overrideProviderId, skipUserMessage, systemPromptSuffix, overrideModel)
                ?: return Result.failure(NetworkUnavailableException("网络连接不可用，消息已缓存"))

            val effectiveClient = ctx.effectiveClient
            val messages = ctx.messages
            capturedMessages = messages
            val toolDefinitions = ctx.toolDefinitions
            var escalatedThisTurn = ctx.escalatedThisTurn

            val loopDetector = LoopDetector()
            var autoContinueExtraCount = 0
            val turnFailureTracker = com.lin.hippyagent.core.model.routing.TurnFailureTracker()

            repeat(profile.running.maxIters) { iteration ->
                if (isSessionInterrupted(sessionId)) {
                    Timber.d("Agent ${profile.agentId} session $sessionId interrupted at iteration $iteration")
                    clearSessionInterrupt(sessionId)
                    return@runCatching
                }

                val isLastIteration = iteration >= profile.running.maxIters - 1
                if (isLastIteration) {
                    messages.add(ModelMessage(
                        role = "system",
                        content = "⚠️ 注意：这是你本次任务的最后一次迭代机会。请立即总结你目前的工作成果和进度，包括已完成的部分和尚未完成的部分。不要继续调用工具，直接给出总结。"
                    ))
                }
                var effectiveMessages = runBeforeModel(sessionId, messages, iteration)

                val routed = resolveRoutedModel(sessionId, content, overrideModel, escalatedThisTurn, effectiveClient)
                val routedModel = routed.modelName
                val routingClient = routed.client

                val request = ModelCallRequest(
                    model = routedModel,
                    messages = effectiveMessages,
                    temperature = 0.7f,
                    maxTokens = resolveModelMaxTokens() ?: profile.running.maxOutputTokens,
                    tools = toolDefinitions
                )

                val resp = callLlmWithRetryAndRateLimit(request, routingClient)

                resp.usage?.let { usage ->
                    _tokenUsage.update { tu ->
                        tu.copy(
                            inputTokens = tu.inputTokens + usage.promptTokens.toLong(),
                            outputTokens = tu.outputTokens + usage.completionTokens.toLong(),
                            totalTokens = tu.totalTokens + usage.totalTokens.toLong(),
                            apiCalls = tu.apiCalls + 1,
                            cacheReadTokens = tu.cacheReadTokens + usage.cacheReadTokens.toLong(),
                            cacheWriteTokens = tu.cacheWriteTokens + usage.cacheWriteTokens.toLong()
                        )
                    }
                    tokenUsageManager?.recordUsage(
                        providerId = profile.modelProvider,
                        modelName = request.model,
                        inputTokens = usage.promptTokens,
                        outputTokens = usage.completionTokens,
                        agentId = profile.agentId,
                        cacheReadTokens = usage.cacheReadTokens,
                        cacheWriteTokens = usage.cacheWriteTokens
                    )
                }

                val choice = resp.choices.firstOrNull()
                    ?: throw IllegalStateException("No choices in response")

                when (val loopResult = checkLoopAndInterrupt(iteration, loopDetector, turnFailureTracker, choice.message.toolCalls?.map { it.function.name }, choice.message.content)) {
                    is LoopCheckResult.Warn -> {
                        if (loopResult.shouldEscalate) {
                            escalatedThisTurn = true
                            Timber.w("Loop → escalating to complex model: ${profile.complexModelName}")
                            return@repeat
                        }
                        messages.add(ModelMessage(role = "system", content = "你似乎进入了死循环，请回忆本次任务的目标并注意你的行为是否符合目标"))
                    }
                    is LoopCheckResult.Hard -> {
                        val loopNotice = "\n\n⚠️ 检测到模型进入死循环（连续重复相似操作），已自动停止任务。请检查任务描述或调整智能体配置。"
                        val fullReply = loopResult.partialReply + loopNotice
                        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                        val replyMessage = ChannelMessage(content = fullReply, senderId = profile.agentId, sessionId = sessionId)
                        channelManager.broadcast(replyMessage, excludeChannel = channelId)
                        return@runCatching
                    }
                    LoopCheckResult.None -> {}
                }

                if (choice.finishReason == "stop" && choice.message.toolCalls.isNullOrEmpty()) {
                    if (shouldAutoContinue(choice.message.content, autoContinueExtraCount)) {
                        autoContinueExtraCount++
                        val hint = autoContinueSystemHint()
                        val tail = autoContinueTailContext(choice.message.content)
                        val hintMsg = buildString {
                            append(hint)
                            if (tail.isNotEmpty()) {
                                append("\n\n<previous-assistant-tail>\n")
                                append(tail)
                                append("\n</previous-assistant-tail>")
                            }
                        }
                        val fullReply = if (!choice.message.reasoningContent.isNullOrBlank() && choice.message.content.isNotEmpty()) {
                            "⋞${choice.message.reasoningContent}⋟\n${choice.message.content}"
                        } else {
                            choice.message.content
                        }
                        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                        messages.add(ModelMessage(role = "assistant", content = choice.message.content))
                        messages.add(ModelMessage(role = "system", content = hintMsg))
                        Timber.d("Auto-continue: text-only (${autoContinueExtraCount}/${AUTO_CONTINUE_MAX_EXTRA}); session=$sessionId")
                        return@repeat
                    }
                    val needsProResult = com.lin.hippyagent.core.model.routing.NeedsProDetector.detect(
                        choice.message.content ?: ""
                    )
                    val xmlModelSwitch = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.detectModelSwitch(
                        choice.message.content ?: ""
                    )
                    val xmlModeSwitch = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.detectModeSwitch(
                        choice.message.content ?: ""
                    )
                    val wantsComplex = needsProResult.hasMarker || xmlModelSwitch == "complex"
                    if (wantsComplex && profile.complexModelName.isNotEmpty() && !escalatedThisTurn) {
                        escalatedThisTurn = true
                        Timber.w("Model escalation requested: $needsProResult / xmlModel=$xmlModelSwitch → complex ${profile.complexModelName}")
                        val cleanedContent = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.stripAll(
                            com.lin.hippyagent.core.model.routing.NeedsProDetector.stripMarker(choice.message.content ?: "")
                        )
                        if (cleanedContent.isNotBlank()) {
                            messages.add(ModelMessage(role = "assistant", content = cleanedContent))
                        }
                        return@repeat
                    }
                    if (xmlModeSwitch != null) {
                        val targetMode = xmlModeSwitch.uppercase()
                        Timber.w("Agent ${profile.agentId} declared mode switch → $targetMode (next turn takes effect)")
                        updateSessionState(sessionId) { it.copy(modeOverride = targetMode) }
                    }
                    handleTextReply(
                        com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.stripAll(choice.message.content ?: ""),
                        choice.message.reasoningContent,
                        sessionId,
                        channelId
                    )
                    return@runCatching
                }

                if (!choice.message.toolCalls.isNullOrEmpty()) {
                    val tcResult = handleToolCalls(
                        toolCalls = choice.message.toolCalls,
                        content = choice.message.content ?: "",
                        reasoningContent = choice.message.reasoningContent,
                        sessionId = sessionId,
                        channelId = channelId,
                        messages = messages,
                        isLastIteration = isLastIteration,
                        escalatedThisTurn = escalatedThisTurn,
                        turnFailureTracker = turnFailureTracker
                    )
                    escalatedThisTurn = tcResult.escalatedThisTurn
                    // 后台补判：tool 调用 > 1 次 → 自动切复杂任务模型 (本轮剩余使用)
                    val sessionToolCount = _state.value.getSessionState(sessionId).toolCallCount
                    if (!escalatedThisTurn && sessionToolCount > 1 && profile.complexModelName.isNotEmpty()) {
                        escalatedThisTurn = true
                        Timber.w("Backend escalation: toolCallCount=$sessionToolCount > 1, switching to complex model ${profile.complexModelName}")
                    }
                    if (tcResult.shouldReturn) return@runCatching
                } else {
                    val reply = choice.message.content
                    if (reply.isNotEmpty()) {
                        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, reply, senderId = profile.agentId)
                        val replyMessage = ChannelMessage(
                            content = reply,
                            senderId = profile.agentId,
                            sessionId = sessionId
                        )
                        channelManager.broadcast(replyMessage, excludeChannel = channelId)
                    }
                    return@runCatching
                }
            }
        }.also { result ->
            sessionContexts[sessionId]?.job = null
            val currentState = _state.value.getSessionState(sessionId)
            if (currentState.status == AgentStatus.STOPPED) {
                updateSessionState(sessionId) { it.copy(isThinking = false) }
            } else {
                updateSessionState(sessionId) {
                    it.copy(
                        status = if (result.isSuccess) AgentStatus.IDLE else AgentStatus.ERROR,
                        isThinking = false,
                        lastError = if (result.isFailure) result.exceptionOrNull()?.message else it.lastError,
                        messageCount = it.messageCount + 1
                    )
                }
            }
            result.onFailure { e ->
                Timber.e(e, "Agent ${profile.agentId} failed to process message")
                val errorMsg = when {
                    e is NetworkUnavailableException -> "📡 网络不可用，消息已缓存"
                    e is kotlinx.coroutines.TimeoutCancellationException -> "⚠️ 请求超时，请稍后重试"
                    e.message?.contains("401") == true -> "⚠️ API 密钥无效，请检查模型提供商配置"
                    e.message?.contains("429") == true -> "⚠️ 请求过于频繁，请稍后重试"
                    else -> "⚠️ 网络错误：${e.message?.take(100) ?: "未知错误"}"
                }
                sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, errorMsg, senderId = profile.agentId)
                channelManager.broadcast(ChannelMessage(content = errorMsg, senderId = profile.agentId, sessionId = sessionId), excludeChannel = channelId)
            }
            if (result.isSuccess) {
                triggerMemoryExtraction(sessionId)
            }
            runAfterAgent(sessionId, capturedMessages ?: mutableListOf())
            sessionContexts.remove(sessionId)
        }
    }

    /**
     * 流式处理消息 - 返回 AI 回复的 Flow
     * 正常文本流式输出；遇到 tool call 时执行工具后继续下一轮
     *
     * 使用 per-session 互斥锁，允许不同会话并行处理。
     * 同一会话的消息排队等待（withLock），不会丢失。
     */
    fun processMessageStream(
        sessionId: String,
        channelId: String,
        content: String,
        overrideModel: String? = null,
        overrideProviderId: String? = null,
        planContext: String? = null,
        skipUserMessage: Boolean = false,
        systemPromptSuffix: String? = null
    ): Flow<StreamChunk> = flow {
        val sessionMutex = getOrCreateSessionMutex(sessionId)

        // 同一会话的消息排队等待，而非直接拒绝
        if (!sessionMutex.tryLock()) {
            // 会话内已有请求在处理，使用 withLock 排队等待
            // 但因为 flow builder 不能直接 suspend withLock，
            // 所以先尝试 tryLock，失败则抛出让 ChatViewModel 排队
            throw IllegalStateException("Session $sessionId is busy, please queue the message")
        }

        var streamFailed = false
        var capturedMessages: MutableList<ModelMessage>? = null
        var afterAgentCalled = false
        try {
            getOrCreateSessionContext(sessionId).job = coroutineContext[Job]!!
            val ctx = prepareMessageContext(sessionId, channelId, content, overrideProviderId, skipUserMessage, systemPromptSuffix, overrideModel)
            if (ctx == null) {
                emit(StreamChunk.Content("📡 网络不可用，消息已缓存，网络恢复后自动发送"))
                return@flow
            }

            val effectiveClient = ctx.effectiveClient
            val messages = ctx.messages
            capturedMessages = messages
            val toolDefinitions = ctx.toolDefinitions
            var escalatedThisTurn = ctx.escalatedThisTurn

            ctx.compactionStartedInfo?.let { emit(it) }
            ctx.compactionInfo?.let { emit(it) }
            ctx.compactionCompletedInfo?.let { emit(it) }

            updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING, isThinking = true) }
            sessionManager?.createSession(sessionId, profile.agentId, channelId)
            sessionManager?.updateActivity(sessionId)

            toolRegistry.deferredToolRegistry.clear()
            for (def in toolRegistry.getDeferredToolNames()) {
                val toolDef = toolRegistry.getToolDefinition(def) ?: continue
                toolRegistry.deferredToolRegistry.register(
                    com.lin.hippyagent.core.model.ModelToolDefinition(
                        name = toolDef.name,
                        description = toolDef.description,
                        parameters = buildToolParameterSchema(toolDef.parameters)
                    )
                )
            }
            var apiCallCount = 0
            var estimatedInputTokens = 0L
            var estimatedOutputTokens = 0L
            var iterationExhausted = false
            val loopDetector = LoopDetector()
            var autoContinueExtraCount = 0
            val turnFailureTracker = com.lin.hippyagent.core.model.routing.TurnFailureTracker()
            repeat(profile.running.maxIters) { iteration ->
                if (iteration > 0) {
                    emit(StreamChunk.NewIteration)
                }
                if (isSessionInterrupted(sessionId)) {
                    clearSessionInterrupt(sessionId)
                    return@flow
                }

                val isLastIteration = iteration >= profile.running.maxIters - 1
                if (isLastIteration) {
                    messages.add(ModelMessage(
                        role = "system",
                        content = "⚠️ 注意：这是你本次任务的最后一次迭代机会。请立即总结你目前的工作成果和进度，包括已完成的部分和尚未完成的部分。不要继续调用工具，直接给出总结。"
                    ))
                }
                var effectiveMessages = runBeforeModel(sessionId, messages, iteration)

                val routed = resolveRoutedModel(sessionId, content, overrideModel, escalatedThisTurn, effectiveClient, isStream = true)
                val routedModel = routed.modelName
                val routingClient = routed.client

                val request = ModelCallRequest(
                    model = routedModel,
                    messages = effectiveMessages,
                    temperature = 0.7f,
                    maxTokens = resolveModelMaxTokens() ?: profile.running.maxOutputTokens,
                    tools = toolDefinitions,
                    stream = true
                )

                val fullContent = sbPool.acquire()
                val reasoningContent = sbPool.acquire()
                val toolCallAccumulator = android.util.SparseArray<AccumulatedToolCall>()
                reusableToolCallList.clear()
                var lastEmitTime = 0L
                val emitIntervalMs = 150L
                val contentBatch = sbPool.acquire()
                val thinkingBatch = sbPool.acquire()
                var thinkingStartTime = 0L
                try {
                kotlinx.coroutines.withTimeout(300_000L) {
                routingClient.chatCompletionStream(request).collect { chunk ->
                    val delta = chunk.choices.firstOrNull()?.delta ?: return@collect
                    val now = System.currentTimeMillis()
                    delta.content?.let {
                        if (it.isNotEmpty()) {
                            fullContent.append(it)
                            contentBatch.append(it)
                        }
                    }
                    delta.reasoningContent?.let {
                        if (it.isNotEmpty()) {
                            if (thinkingStartTime == 0L) thinkingStartTime = System.currentTimeMillis()
                            reasoningContent.append(it)
                            thinkingBatch.append(it)
                        }
                    }
                    delta.toolCalls?.let { tc -> mergeToolCallDeltas(toolCallAccumulator, tc) }
                    if (now - lastEmitTime >= emitIntervalMs) {
                        if (contentBatch.isNotEmpty()) {
                            emit(StreamChunk.Content(contentBatch.toString()))
                            contentBatch.clear()
                        }
                        if (thinkingBatch.isNotEmpty()) {
                            emit(StreamChunk.Thinking(thinkingBatch.toString()))
                            thinkingBatch.clear()
                        }
                        lastEmitTime = now
                    }
                }
                }
                apiCallCount++
                if (fullContent.isEmpty() && reasoningContent.isEmpty() && toolCallAccumulator.size() == 0) {
                    Timber.w("Agent ${profile.agentId} iteration $iteration: empty response from model, skipping...")
                }
                val iterInputTokens = effectiveMessages.sumOf { (it.content.length / 3.5).toLong().coerceAtLeast(1) }
                val iterOutputTokens = (fullContent.length / 3.5).toLong().coerceAtLeast(1) + (reasoningContent.length / 3.5).toLong().coerceAtLeast(1)
                estimatedInputTokens += iterInputTokens
                estimatedOutputTokens += iterOutputTokens
                _tokenUsage.update { tu ->
                    tu.copy(
                        inputTokens = tu.inputTokens + iterInputTokens,
                        outputTokens = tu.outputTokens + iterOutputTokens,
                        totalTokens = tu.totalTokens + iterInputTokens + iterOutputTokens,
                        apiCalls = tu.apiCalls + 1
                    )
                }
                tokenUsageManager?.recordUsage(
                    providerId = profile.modelProvider,
                    modelName = request.model,
                    inputTokens = iterInputTokens.toInt(),
                    outputTokens = iterOutputTokens.toInt(),
                    agentId = profile.agentId
                )
                if (contentBatch.isNotEmpty()) {
                    emit(StreamChunk.Content(contentBatch.toString()))
                }
                if (thinkingBatch.isNotEmpty()) {
                    emit(StreamChunk.Thinking(thinkingBatch.toString()))
                }

                val thinkingDurationMs = if (thinkingStartTime > 0L && reasoningContent.isNotEmpty()) {
                    System.currentTimeMillis() - thinkingStartTime
                } else 0L

                reusableToolCallList.clear()
                buildFinalToolCalls(toolCallAccumulator, reusableToolCallList)

                // 释放 AccumulatedToolCall 对象回池，避免 GC 压力
                for (i in 0 until toolCallAccumulator.size()) {
                    accumulatedToolCallPool.release(toolCallAccumulator.valueAt(i))
                }
                toolCallAccumulator.clear()

                val reasoningPrefix = reasoningContent.toString().ifBlank { null }?.let { "⋞$it⋟\n" } ?: ""

                val modelResponse = ModelResponse(
                    content = fullContent.toString(),
                    toolCalls = reusableToolCallList.toList().ifEmpty { null },
                    finishReason = if (reusableToolCallList.isEmpty()) "stop" else "tool_calls"
                )
                when (val afterResult = runAfterModel(sessionId, messages, iteration, modelResponse)) {
                    is MiddlewareResult.Respond -> {
                        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, afterResult.message, senderId = profile.agentId)
                        emit(StreamChunk.Content(afterResult.message))
                        emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                        return@flow
                    }
                    is MiddlewareResult.AbortTurn -> {
                        emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                        return@flow
                    }
                    is MiddlewareResult.HardAbort -> {
                        emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                        return@flow
                    }
                    else -> {}
                }

                when (val loopResult = checkLoopAndInterrupt(iteration, loopDetector, turnFailureTracker, reusableToolCallList.map { it.function.name }, fullContent.toString(), isStream = true)) {
                    is LoopCheckResult.Warn -> {
                        if (loopResult.shouldEscalate) {
                            escalatedThisTurn = true
                            Timber.w("Loop(stream) → escalating to complex model: ${profile.complexModelName}")
                            return@repeat
                        }
                        messages.add(ModelMessage(role = "system", content = "你似乎进入了死循环，请回忆本次任务的目标并注意你的行为是否符合目标"))
                    }
                    is LoopCheckResult.Hard -> {
                        if (loopResult.hasToolCalls) {
                            reusableToolCallList.clear()
                            messages.add(ModelMessage(role = "system", content = "⚠️ 你已连续重复相似操作超过硬限制。工具调用已被强制取消。请立即用文字总结当前进度和结果，不要再调用任何工具。"))
                            updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING) }
                            return@repeat
                        }
                        val loopNotice = "\n\n⚠️ 检测到模型进入死循环（连续重复相似操作），已自动停止任务。请检查任务描述或调整智能体配置。"
                        val fullReply = reasoningPrefix + loopResult.partialReply + loopNotice
                        sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                        emit(StreamChunk.Content(loopNotice))
                        emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                        return@flow
                    }
                    LoopCheckResult.None -> {}
                }

                if (reusableToolCallList.isEmpty()) {
                    // Auto-continue: nudge model when text-only mid-task
                    if (shouldAutoContinue(fullContent.toString(), autoContinueExtraCount)) {
                        autoContinueExtraCount++
                        val hint = autoContinueSystemHint()
                        val tail = autoContinueTailContext(fullContent.toString())
                        val hintMsg = buildString {
                            append(hint)
                            if (tail.isNotEmpty()) {
                                append("\n\n<previous-assistant-tail>\n")
                                append(tail)
                                append("\n</previous-assistant-tail>")
                            }
                        }
                        val reply = fullContent.toString()
                        val fullReply = reasoningPrefix + reply
                        if (fullReply.isNotEmpty()) {
                            sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                        }
                        messages.add(ModelMessage(role = "assistant", content = reply))
                        messages.add(ModelMessage(role = "system", content = hintMsg))
                        updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING) }
                        Timber.d("Auto-continue(stream): text-only (${autoContinueExtraCount}/${AUTO_CONTINUE_MAX_EXTRA}); session=$sessionId")
                        return@repeat
                    }
                    val rawReply = fullContent.toString()
                    val xmlModelSwitch = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.detectModelSwitch(rawReply)
                    val xmlModeSwitch = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.detectModeSwitch(rawReply)
                    val needsProResult = com.lin.hippyagent.core.model.routing.NeedsProDetector.detect(rawReply)
                    val wantsComplex = needsProResult.hasMarker || xmlModelSwitch == "complex"
                    if (wantsComplex && profile.complexModelName.isNotEmpty() && !escalatedThisTurn) {
                        escalatedThisTurn = true
                        Timber.w("Model escalation(stream): $needsProResult / xmlModel=$xmlModelSwitch → complex ${profile.complexModelName}")
                    }
                    if (xmlModeSwitch != null) {
                        val targetMode = xmlModeSwitch.uppercase()
                        Timber.w("Agent ${profile.agentId} declared mode switch (stream) → $targetMode (next turn takes effect)")
                        updateSessionState(sessionId) { it.copy(modeOverride = targetMode) }
                    }
                    val cleanedReply = com.lin.hippyagent.core.model.routing.SwitchDeclarationDetector.stripAll(
                        com.lin.hippyagent.core.model.routing.NeedsProDetector.stripMarker(rawReply)
                    )
                    val fullReply = reasoningPrefix + cleanedReply
                    if (fullReply.isNotEmpty()) {
                        val assistantMsg = sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId).getOrNull()
                        if (assistantMsg != null && thinkingDurationMs > 0L) {
                            val metaJson = buildMetaJson(null, thinkingDurationMs)
                            if (metaJson.isNotEmpty()) {
                                sessionStore.updateMessageMetadata(assistantMsg.id, metaJson)
                            }
                        }
                        // 注意：前面的 Content chunks 已流式 emit 给 UI；此处不再重复 emit 以免用户看到重复。
                        // 通道广播使用清理后的内容, 避免将 XML 标签传播给其他频道接收者。
                        val replyMessage = ChannelMessage(
                            content = cleanedReply,
                            senderId = profile.agentId,
                            sessionId = sessionId
                        )
                        channelManager.broadcast(replyMessage, excludeChannel = channelId)
                    }
                    emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                    return@flow
                }

                if (isLastIteration && reusableToolCallList.isNotEmpty()) {
                    iterationExhausted = true
                    val partialReply = fullContent.toString()
                    val exhaustionNotice = "\n\n⚠️ 迭代轮次已耗尽（${profile.running.maxIters}轮），任务未能完全完成。以下是当前进度：\n${partialReply.ifBlank { "（智能体在最后一轮仍在调用工具，未能生成文字总结）" }}"
                    val fullReply = reasoningPrefix + partialReply + exhaustionNotice
                    sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, fullReply, senderId = profile.agentId)
                    emit(StreamChunk.Content(exhaustionNotice))
                    emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
                    return@flow
                }

                updateSessionState(sessionId) { it.copy(status = AgentStatus.EXECUTING_TOOL) }

                val tcResult = handleToolCalls(
                    toolCalls = reusableToolCallList.toList(),
                    content = fullContent.toString(),
                    reasoningContent = reasoningContent.toString().ifBlank { null },
                    sessionId = sessionId,
                    channelId = channelId,
                    messages = messages,
                    isLastIteration = false,
                    escalatedThisTurn = escalatedThisTurn,
                    turnFailureTracker = turnFailureTracker,
                    thinkingDurationMs = thinkingDurationMs
                )
                escalatedThisTurn = tcResult.escalatedThisTurn
                // 后台补判：tool 调用 > 1 次 → 自动切复杂任务模型 (本轮剩余使用)
                val sessionToolCount = _state.value.getSessionState(sessionId).toolCallCount
                if (!escalatedThisTurn && sessionToolCount > 1 && profile.complexModelName.isNotEmpty()) {
                    escalatedThisTurn = true
                    Timber.w("Backend escalation(stream): toolCallCount=$sessionToolCount > 1, switching to complex model ${profile.complexModelName}")
                }
                } finally {
                    fullContent.clear()
                    sbPool.release(fullContent)
                    reasoningContent.clear()
                    sbPool.release(reasoningContent)
                    contentBatch.clear()
                    sbPool.release(contentBatch)
                    thinkingBatch.clear()
                    sbPool.release(thinkingBatch)
                    reusableToolCallList.clear()
                }

                emit(StreamChunk.Content("\n"))
                updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING) }
            }
            emit(StreamChunk.TaskCompleted(estimatedInputTokens, estimatedOutputTokens, apiCallCount))
            runAfterAgent(sessionId, messages)
            afterAgentCalled = true
        } catch (e: OutOfMemoryError) {
            streamFailed = true
            Timber.e(e, "Agent ${profile.agentId} OOM — 建议缩短上下文或重启应用")
            val errorMsg = "⚠️ 内存不足 (OOM)：上下文过长，请缩短对话或重启应用"
            updateSessionState(sessionId) { it.copy(status = AgentStatus.ERROR, lastError = errorMsg) }
            sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, errorMsg, senderId = profile.agentId)
            channelManager.broadcast(ChannelMessage(content = errorMsg, senderId = profile.agentId, sessionId = sessionId), excludeChannel = channelId)
            runCatching { emit(StreamChunk.Content(errorMsg)) }
            return@flow
        } catch (e: Exception) {
            streamFailed = true
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                Timber.w(e, "Agent ${profile.agentId} stream timeout")
                val errorMsg = "⚠️ 请求超时，请稍后重试"
                updateSessionState(sessionId) { it.copy(status = AgentStatus.ERROR, lastError = errorMsg) }
                sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, errorMsg, senderId = profile.agentId)
                channelManager.broadcast(ChannelMessage(content = errorMsg, senderId = profile.agentId, sessionId = sessionId), excludeChannel = channelId)
                runCatching { emit(StreamChunk.Content(errorMsg)) }
                return@flow
            }
            if (e !is kotlinx.coroutines.CancellationException) {
                Timber.e(e, "Agent ${profile.agentId} failed to process stream message")
                val errorMsg = "⚠️ 网络错误：${e.message?.take(100) ?: "未知错误"}"
                updateSessionState(sessionId) { it.copy(status = AgentStatus.ERROR, lastError = errorMsg) }
                sessionStore.addMessage(sessionId, MessageRole.ASSISTANT, errorMsg, senderId = profile.agentId)
                channelManager.broadcast(ChannelMessage(content = errorMsg, senderId = profile.agentId, sessionId = sessionId), excludeChannel = channelId)
                runCatching { emit(StreamChunk.Content(errorMsg)) }
                return@flow
            }
        } finally {
            // 先释放 mutex，再更新状态为 IDLE，防止 sendMessage 读到 IDLE 但 mutex 仍被锁住的竞态
            sessionMutex.unlock()
            sessionContexts[sessionId]?.job = null
            _currentProcessingSessionId = null
            val currentSessionState = _state.value.getSessionState(sessionId)
            when (currentSessionState.status) {
                AgentStatus.STOPPED -> {
                    updateSessionState(sessionId) { it.copy(isThinking = false) }
                }
                AgentStatus.ERROR -> {
                    updateSessionState(sessionId) {
                        it.copy(isThinking = false, messageCount = it.messageCount + 1)
                    }
                }
                else -> {
                    updateSessionState(sessionId) {
                        it.copy(
                            status = AgentStatus.IDLE,
                            isThinking = false,
                            messageCount = it.messageCount + 1
                        )
                    }
                }
            }
            sessionContexts.remove(sessionId)
            if (!afterAgentCalled) {
                runAfterAgent(sessionId, capturedMessages ?: mutableListOf())
            }
            triggerMemoryExtraction(sessionId)
            val tu = _tokenUsage.value
            val effectiveModel = overrideModel ?: profile.modelName
            if (tu.apiCalls > 0) {
                val closeModel = stripModelPrefix(effectiveModel)
                val inputTok = tu.inputTokens.toInt()
                val outputTok = tu.outputTokens.toInt()
                val cacheReadTok = tu.cacheReadTokens.toInt()
                val cacheWriteTok = tu.cacheWriteTokens.toInt()
                if (streamFailed) {
                    sessionStore.failSession(sessionId, closeModel, inputTok, outputTok, cacheReadTok, cacheWriteTok, null, currentSessionState.lastError)
                } else {
                    sessionStore.closeSession(sessionId, closeModel, inputTok, outputTok, cacheReadTok, cacheWriteTok, null)
                }
            }
            if (tu.apiCalls > 0 && tokenUsageManager != null) {
                val providerId = overrideProviderId ?: profile.modelProvider
                tokenUsageManager.recordUsage(
                    providerId = providerId,
                    modelName = stripModelPrefix(effectiveModel),
                    inputTokens = tu.inputTokens.toInt(),
                    outputTokens = tu.outputTokens.toInt(),
                    agentId = profile.agentId
                )
            }
        }
    }

    private val storageManager = StorageManager(context)
    private val skillManager by lazy { com.lin.hippyagent.core.skill.SkillManager(context, java.io.File(storageManager.getWorkingDir(), "skills")) }
    private val skillTriggerResolver by lazy { com.lin.hippyagent.core.skill.SkillTriggerResolver(skillManager) }
    private val skillCatalog by lazy { com.lin.hippyagent.core.skill.SkillCatalog(skillManager) }

    /** 公开的工作区根目录，供外部模块（如 ChatViewModel）直接访问，避免反射。 */
    val workspaceDir: java.io.File get() = java.io.File(storageManager.getWorkingDir(), "workspaces/${profile.agentId}")

    private val promptBuilder = PromptBuilder()

    private val memoryExtractionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun triggerMemoryExtraction(sessionId: String) {
        val extractor = memoryExtractor ?: return
        memoryExtractionScope.launch {
            runCatching {
                val messages = sessionStore.getMessages(sessionId, includeCompressed = false).getOrNull() ?: return@runCatching
                val conversation = messages.takeLast(20).map { msg ->
                    msg.role.name.lowercase() to msg.content
                }
                if (conversation.size >= 2) {
                    extractor.extractAndStoreAsync(conversation)
                }
            }.onFailure { e ->
                Timber.w(e, "MemoryExtractor: trigger failed for session $sessionId")
            }
        }
    }
    private lateinit var bootstrapHook: BootstrapHook

    data class BuildPromptResult(
        val messages: MutableList<ModelMessage>,
        val systemPromptText: String = "",
        val compactionInfo: StreamChunk.Compaction? = null,
        val compactionStartedInfo: StreamChunk.CompactionStarted? = null,
        val compactionCompletedInfo: StreamChunk.CompactionCompleted? = null
    )

    private suspend fun buildPrompt(sessionId: String, planContext: String? = null, systemPromptSuffix: String? = null, isEscalated: Boolean = false, effectiveModelName: String? = null): BuildPromptResult {
        val messages = mutableListOf<ModelMessage>()
        var compactionInfo: StreamChunk.Compaction? = null
        var compactionStartedInfo: StreamChunk.CompactionStarted? = null
        var compactionCompletedInfo: StreamChunk.CompactionCompleted? = null

        val workingDir = java.io.File(storageManager.getWorkingDir(), "workspaces/${profile.agentId}")
        workingDir.mkdirs()

        // 仅在全新工作区（无任何核心文件）且未完成引导时创建 BOOTSTRAP.md
        // 避免已删除 BOOTSTRAP.md 的成熟工作区被重新触发引导
        val bootstrapFile = java.io.File(workingDir, "BOOTSTRAP.md")
        val bootstrapCompletedMarker = java.io.File(workingDir, ".bootstrap_completed")
        val hasExistingCoreFiles = java.io.File(workingDir, "PROFILE.md").exists() ||
                java.io.File(workingDir, "SOUL.md").exists() ||
                java.io.File(workingDir, "RULES.md").exists()
        if (!bootstrapFile.exists() && !bootstrapCompletedMarker.exists() && !hasExistingCoreFiles) {
            try {
                context.assets.open("templates/BOOTSTRAP.md").use { input ->
                    bootstrapFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Timber.i("BOOTSTRAP.md copied from assets to ${workingDir.absolutePath}")
            } catch (e: Exception) {
                Timber.w(e, "Failed to copy BOOTSTRAP.md from assets, creating default")
                bootstrapFile.writeText(
                    """# 启动引导

_你刚醒来。该搞清楚自己是谁了。_

还没有记忆。这是全新的工作区，记忆文件在你创建之前不存在很正常。

## 对话

像这样开始：

> "嘿，我刚上线。我是谁？你是谁？"

然后一起搞清楚：

1. **你的名字** — 他们该怎么叫你？
2. **你的定位** — 你是什么？
3. **你的风格** — 正式？随意？调皮？温暖？

## 完成后

确保以上的内容都保存到文件后。删除这个文件（`BOOTSTRAP.md`）。你不再需要引导脚本了 — 你已经是你了。
"""
                )
            }
        }

        if (!::bootstrapHook.isInitialized) {
            bootstrapHook = BootstrapHook(workingDir)
        }

        val allMessages = sessionStore.getMessages(sessionId, includeCompressed = false).getOrDefault(emptyList())
        val afterClearMarker = allMessages.indexOfLast { it.role == MessageRole.SYSTEM && it.content == com.lin.hippyagent.core.command.ClearCommandHandler.CONTEXT_CLEARED_MARKER }
        val messagesAfterClear = if (afterClearMarker >= 0) allMessages.drop(afterClearMarker + 1) else allMessages
        val sessionMessages = contextMessageFilter?.let { filter -> messagesAfterClear.filter { filter(it) } } ?: messagesAfterClear

        val autoMemorySearchConfig = profile.running.remeLightMemoryConfig.autoMemorySearchConfig
        val commonMemoryEntries = runCatching {
            if (commonMemoryRepo != null && sessionMessages.isNotEmpty() && autoMemorySearchConfig.enabled) {
                val lastUserMsg = sessionMessages.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
                if (lastUserMsg.isNotBlank()) {
                    if (autoMemorySearchConfig.enhancedSearchEnabled) {
                        val intent = QueryIntentClassifier.classify(lastUserMsg).toSearchIntent()
                        commonMemoryRepo.search(lastUserMsg, profile.agentId, intent = intent, limit = autoMemorySearchConfig.maxResults)
                    } else {
                        commonMemoryRepo.searchHybridByAgentId(lastUserMsg, profile.agentId, limit = autoMemorySearchConfig.maxResults)
                    }.filter { it.second >= autoMemorySearchConfig.minScore }
                        .sortedByDescending { it.second }
                        .take(autoMemorySearchConfig.maxResults)
                } else emptyList()
            } else emptyList()
        }.getOrDefault(emptyList())

        val promptContext = PromptContext(
            workingDir = workingDir,
            agentId = profile.agentId,
            sessionId = sessionId,
            coreFiles = profile.coreFiles,
            globalRules = configStorage?.getString("global_rules")?.takeIf { it.isNotBlank() },
            commonMemoryEntries = commonMemoryEntries,
            skills = buildSkillInfoList(workingDir),
            resolvedSkills = resolveTriggeredSkills(sessionMessages),
            skillCatalogText = if (profile.skills.isNotEmpty()) skillCatalog.buildProgressiveCatalogText(profile.skills) else null,
            progressiveSkillLoading = true,
            deferredToolNames = toolRegistry.getDeferredToolNames(),
            planContext = planContext,
            appAliases = com.lin.hippyagent.core.tools.android.AppPackageResolver.getAliasMap()
        )

        val systemPrompt = promptBuilder.buildSystemPrompt(promptContext)
        val escalationSuffix = if (profile.complexModelName.isNotEmpty()) {
            "\n\n" + com.lin.hippyagent.core.model.routing.EscalationContract.getContract(
                isHeavyModel = isEscalated
            )
        } else ""
        val effectiveSystemPrompt = buildString {
            append(systemPrompt)
            if (!systemPromptSuffix.isNullOrBlank()) {
                append("\n\n")
                append(systemPromptSuffix)
            }
            append(escalationSuffix)
        }

        Timber.d("buildPrompt[$sessionId]: bootstrap=${bootstrapHook.isBootstrapMode()}, sessionMsgCount=${sessionMessages.size}, " +
                "msgRoles=${sessionMessages.map { it.role }.joinToString()}")

        if (bootstrapHook.isBootstrapMode()) {
            if (sessionMessages.isEmpty()) {
                messages.add(ModelMessage(role = "system", content = effectiveSystemPrompt + bootstrapHook.getSystemPromptAddition()))
                Timber.d("buildPrompt[$sessionId]: bootstrap mode + empty msgs → injecting bootstrap addition")
            } else {
                messages.add(ModelMessage(role = "system", content = effectiveSystemPrompt))
                Timber.d("buildPrompt[$sessionId]: bootstrap mode but msgs exist → system prompt only")
            }
        } else {
            messages.add(ModelMessage(role = "system", content = effectiveSystemPrompt))
        }

        val checkResult = contextManager.checkContext(
            sessionMessages,
            systemPrompt,
            modelContextWindow = resolveModelContextWindow()
        )

        _contextTokenInfo.value = ContextTokenInfo(
            currentTokens = checkResult.totalTokens.toLong(),
            maxTokens = checkResult.maxTokens.toLong()
        )

        val existingSummary = sessionStore.getCompressedSummary(sessionId).getOrNull()

        if (existingSummary != null) {
            messages.add(ModelMessage(role = "system", content = SUMMARY_PREFIX + existingSummary))
        }

        if (checkResult.needsCompression && checkResult.messagesToCompress.isNotEmpty()
            && contextManager.useCompression) {
            Timber.d("Context compression triggered: ${checkResult.totalTokens} tokens, " +
                    "compressing ${checkResult.messagesToCompress.size} messages")

            // 记录压缩开始信息
            compactionStartedInfo = StreamChunk.CompactionStarted(
                totalTokens = checkResult.totalTokens,
                maxTokens = checkResult.maxTokens,
                messagesToCompress = checkResult.messagesToCompress.size,
                messagesToKeep = checkResult.messagesToKeep.size
            )
            // 兼容旧字段
            compactionInfo = StreamChunk.Compaction(checkResult.messagesToCompress.size, 0)

            val compactionPrompt = contextManager.buildCompactionPrompt(checkResult.messagesToCompress)
            val newSummary = performLlmCompaction(compactionPrompt, existingSummary, checkResult.messagesToCompress)

            sessionStore.updateCompressedSummary(sessionId, newSummary)
            val compressedIds = checkResult.messagesToCompress.map { it.id }
            sessionStore.markMessagesCompressed(compressedIds)

            val keptTokens = contextManager.getTokenStats(checkResult.messagesToKeep)["total"] ?: 0
            val summaryTokenEstimate = (SUMMARY_PREFIX.length + newSummary.length) / 4
            val systemTokenEstimate = systemPrompt.length / 4
            val postCompactionTokens = (keptTokens + summaryTokenEstimate + systemTokenEstimate).toLong()
            _contextTokenInfo.value = ContextTokenInfo(
                currentTokens = postCompactionTokens,
                maxTokens = checkResult.maxTokens.toLong()
            )

            Timber.d("Context compressed, summary length: ${newSummary.length} chars, marked ${compressedIds.size} messages as compressed")
            compactionInfo = StreamChunk.Compaction(compressedIds.size, newSummary.length)
            compactionCompletedInfo = StreamChunk.CompactionCompleted(
                compressedCount = compressedIds.size,
                newTokenEstimate = checkResult.messagesToKeep.sumOf { contextManager.getTokenStats(listOf(it))["total"] ?: 0 } + (contextManager.getTokenStats(checkResult.messagesToCompress)["total"] ?: 0) / 4,
                maxTokens = checkResult.maxTokens,
                beforeTokens = checkResult.totalTokens
            )
        }

        val messagesToAdd = if (checkResult.needsCompression) {
            checkResult.messagesToKeep
        } else {
            checkResult.prunedMessages
        }

        val lastUserMsgIndex = messagesToAdd.indexOfLast { it.role == MessageRole.USER }

        val imageExtensions = Companion.imageExtensions
        val attachmentRegex = Companion.attachmentRegex

        messagesToAdd.forEachIndexed { idx, sessionMessage ->
            val role = when (sessionMessage.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
                MessageRole.TOOL -> "tool"
                MessageRole.PRIVATE -> "user"
            }
            // 保留 toolCalls 和 toolCallId，避免 LLM API 因缺少 tool_call_id 拒绝请求
            val modelMessage = if (sessionMessage.role == MessageRole.ASSISTANT && sessionMessage.toolCalls.isNotEmpty()) {
                ModelMessage(
                    role = role,
                    content = sessionMessage.content,
                    toolCalls = sessionMessage.toolCalls.map { tc ->
                        ToolCallInfo(
                            id = tc.id,
                            function = FunctionInfo(
                                name = tc.name,
                                arguments = tc.arguments
                            )
                        )
                    }
                )
            } else if (sessionMessage.role == MessageRole.TOOL && sessionMessage.toolName != null) {
                // TOOL 角色消息需要 tool_call_id 以匹配 ASSISTANT 的 tool_calls
                // 尝试从 toolCalls 列表中获取 id，或用 toolName 作为回退标识
                ModelMessage(
                    role = role,
                    content = sessionMessage.content,
                    toolCallId = sessionMessage.toolCalls.firstOrNull()?.id ?: sessionMessage.id
                )
            } else {
                val attachments = attachmentRegex.findAll(sessionMessage.content).map { it.groupValues[1] }.toList()
                val imageFiles = attachments.filter { ext ->
                    ext.substringAfterLast(".", "").lowercase() in imageExtensions
                }
                val nonImageAttachments = attachments.filter { ext ->
                    ext.substringAfterLast(".", "").lowercase() !in imageExtensions
                }
                val isLatestUserMsg = role == "user" && idx == lastUserMsgIndex
                var textWithoutAttachments = if (attachments.isNotEmpty()) attachmentRegex.replace(sessionMessage.content, "").trim() else sessionMessage.content

                if (role == "user" && sessionMessage.metadataJson != null) {
                    val quotedPrefix = buildQuotedMessagePrefix(sessionMessage.metadataJson)
                    if (quotedPrefix != null) {
                        textWithoutAttachments = "$quotedPrefix\n$textWithoutAttachments"
                    }
                }

                if (imageFiles.isNotEmpty() && role == "user") {
                    val modelSupportsVision = effectiveModelName != null && MODEL_VISION_REGEX.containsMatchIn(effectiveModelName)
                    if (isLatestUserMsg && modelSupportsVision) {
                        val blocks = mutableListOf<com.lin.hippyagent.core.model.ContentBlock>()
                        if (textWithoutAttachments.isNotBlank()) {
                            blocks.add(com.lin.hippyagent.core.model.ContentBlock.Text(textWithoutAttachments))
                        }
                        imageFiles.forEach { path ->
                            try {
                                val file = java.io.File(path)
                                if (file.exists() && file.length() < 5 * 1024 * 1024) {
                                    val bytes = file.readBytes()
                                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                    val mimeType = when (file.extension.lowercase()) {
                                        "png" -> "image/png"
                                        "jpg", "jpeg" -> "image/jpeg"
                                        "gif" -> "image/gif"
                                        "webp" -> "image/webp"
                                        else -> "image/png"
                                    }
                                    blocks.add(com.lin.hippyagent.core.model.ContentBlock.ImageUrl(
                                        com.lin.hippyagent.core.model.ImageUrlDetail("data:$mimeType;base64,$base64")
                                    ))
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to load image: $path")
                            }
                        }
                        nonImageAttachments.forEach { path ->
                            blocks.add(com.lin.hippyagent.core.model.ContentBlock.Text("[用户发送了附件: $path，如需查看请使用 read_file 工具]"))
                        }
                        val hasImage = blocks.any { it is com.lin.hippyagent.core.model.ContentBlock.ImageUrl }
                        if (hasImage) {
                            ModelMessage(role = role, content = textWithoutAttachments, contentBlocks = blocks)
                        } else {
                            val fallback = blocks.filterIsInstance<com.lin.hippyagent.core.model.ContentBlock.Text>().joinToString("\n") { (it as com.lin.hippyagent.core.model.ContentBlock.Text).text }
                            ModelMessage(role = role, content = fallback.ifBlank { sessionMessage.content })
                        }
                    } else {
                        val guidance = buildString {
                            append(textWithoutAttachments)
                            if (isNotEmpty() && textWithoutAttachments.isNotBlank()) append("\n")
                            if (isLatestUserMsg && effectiveModelName != null && !MODEL_VISION_REGEX.containsMatchIn(effectiveModelName)) {
                                append("[当前模型($effectiveModelName)不支持图片理解，图片已忽略。如需分析图片，请切换到支持视觉的模型]\n")
                            }
                            imageFiles.forEach { path ->
                                append("[用户发送了图片: $path，如需查看请使用 view_image 工具]\n")
                            }
                            nonImageAttachments.forEach { path ->
                                append("[用户发送了附件: $path，如需查看请使用 read_file 工具]\n")
                            }
                        }.trimEnd()
                        ModelMessage(role = role, content = guidance)
                    }
                } else if (nonImageAttachments.isNotEmpty() && role == "user") {
                    val guidance = buildString {
                        append(textWithoutAttachments)
                        if (isNotEmpty() && textWithoutAttachments.isNotBlank()) append("\n")
                        nonImageAttachments.forEach { path ->
                            append("[用户发送了附件: $path，如需查看请使用 read_file 工具]\n")
                        }
                    }.trimEnd()
                    ModelMessage(role = role, content = guidance)
                } else {
                    ModelMessage(role = role, content = textWithoutAttachments)
                }
            }
            messages.add(modelMessage)
        }

        Timber.d("buildPrompt[$sessionId]: final prompt has ${messages.size} messages: ${messages.map { it.role }.joinToString()}")
        return BuildPromptResult(messages, effectiveSystemPrompt, compactionInfo, compactionStartedInfo, compactionCompletedInfo)
    }

    private fun buildQuotedMessagePrefix(metadataJson: String): String? {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(metadataJson) as? kotlinx.serialization.json.JsonObject ?: return null
            val quotedContent = obj["quotedContent"]?.jsonPrimitive?.content ?: return null
            val quotedSenderName = obj["quotedSenderName"]?.jsonPrimitive?.content
            val senderLabel = quotedSenderName?.ifBlank { null } ?: "某条消息"
            "[用户引用了${senderLabel}的消息: ${quotedContent.take(200)}]"
        } catch (_: Exception) { null }
    }

    private suspend fun resolveModelContextWindow(): Int? {
        val store = modelProviderStore ?: return null
        val modelName = stripModelPrefix(profile.modelName)
        try {
            val providers = store.providers.first()
            for (provider in providers) {
                val match = provider.models.find {
                    stripModelPrefix(it.name) == modelName || it.name == modelName
                }
                if (match?.contextWindow != null) {
                    return match.contextWindow
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * 从 ModelProviderStore 查找当前模型的自定义 maxTokens 设置
     * 如果模型配置中设置了 maxTokens，优先使用（覆盖 RunningConfig 的默认值）
     */
    private suspend fun resolveModelMaxTokens(): Int? {
        val store = modelProviderStore ?: return null
        val modelName = stripModelPrefix(profile.modelName)
        try {
            val providers = store.providers.first()
            for (provider in providers) {
                val match = provider.models.find {
                    stripModelPrefix(it.name) == modelName || it.name == modelName
                }
                if (match?.maxTokens != null) {
                    return match.maxTokens
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * 构建智能体已启用技能的信息列表，用于注入系统提示词
     */
    private fun buildSkillInfoList(workingDir: java.io.File): List<com.lin.hippyagent.core.prompt.SkillInfo> {
        val skillIds = profile.skills
        if (skillIds.isEmpty()) return emptyList()

        val globalSkillsDir = java.io.File(storageManager.getWorkingDir(), "skills")
        val agentSkillsDir = java.io.File(workingDir, "skills")
        val result = mutableListOf<com.lin.hippyagent.core.prompt.SkillInfo>()

        for (skillId in skillIds) {
            // 优先查找智能体专属技能目录，然后查找全局技能池
            val agentSkillDir = java.io.File(agentSkillsDir, skillId)
            val globalSkillDir = java.io.File(globalSkillsDir, skillId)
            val skillDir = when {
                agentSkillDir.exists() -> agentSkillDir
                globalSkillDir.exists() -> globalSkillDir
                else -> null
            }

            if (skillDir != null) {
                val skillMd = java.io.File(skillDir, "SKILL.md")
                val skillMdPath = if (skillMd.exists()) skillMd.absolutePath else ""
                // 从 SKILL.md 的前 5 行提取简要描述
                val description = if (skillMd.exists()) {
                    try {
                        skillMd.readLines().take(5)
                            .filter { it.isNotBlank() && !it.startsWith("#") }
                            .firstOrNull()?.take(120) ?: ""
                    } catch (_: Exception) { "" }
                } else ""
                result.add(com.lin.hippyagent.core.prompt.SkillInfo(
                    id = skillId,
                    name = skillId.replace("-", " ").replaceFirstChar { it.uppercase() },
                    description = description,
                    skillFilePath = skillMdPath
                ))
            } else {
                // 技能目录不存在，仅列出 ID
                result.add(com.lin.hippyagent.core.prompt.SkillInfo(
                    id = skillId,
                    name = skillId.replace("-", " ").replaceFirstChar { it.uppercase() }
                ))
            }
        }
        return result
    }

    private fun resolveTriggeredSkills(sessionMessages: List<SessionMessage>): List<com.lin.hippyagent.core.skill.ResolvedSkill> {
        val skillIds = profile.skills
        if (skillIds.isEmpty()) return emptyList()
        val lastUserMsg = sessionMessages.lastOrNull { it.role == MessageRole.USER }?.content ?: return emptyList()
        return runCatching {
            skillTriggerResolver.resolve(lastUserMsg, skillIds)
        }.getOrElse { emptyList() }
    }

    private fun resolveToolPath(path: String): String {
        val file = java.io.File(path)
        val workspaceDir = java.io.File(storageManager.getWorkingDir(), "workspaces/${profile.agentId}")
        val resolved = if (file.isAbsolute) file else java.io.File(workspaceDir, path)
        val canonicalWorkspace = workspaceDir.canonicalPath
        val canonicalPath = resolved.canonicalPath
        if (!canonicalPath.startsWith(canonicalWorkspace) && !canonicalPath.startsWith(java.io.File(storageManager.getWorkingDir(), "skills").canonicalPath)) {
            throw SecurityException("Access denied: path '$path' is outside agent workspace")
        }
        return canonicalPath
    }

    private suspend fun executeToolCall(
        toolCall: ToolCallInfo,
        sessionId: String,
        channelId: String
    ): ToolResult? {
        updateSessionState(sessionId) { it.copy(status = AgentStatus.EXECUTING_TOOL) }

        return try {
            val arguments = toolCall.function.arguments.let { argsJson ->
                try {
                    val jsonElement = Json.parseToJsonElement(argsJson)
                    if (jsonElement is JsonObject) {
                        jsonElement.mapValues { (_, value) ->
                            when (value) {
                                is JsonPrimitive -> when {
                                    value.isString -> value.content
                                    value.content == "true" -> true
                                    value.content == "false" -> false
                                    else -> value.content.toLongOrNull() ?: value.content.toDoubleOrNull() ?: value.content
                                }
                                else -> value.toString()
                            }
                        }
                    } else {
                        emptyMap()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse tool arguments JSON: $argsJson")
                    emptyMap()
                }
            }

            val pathKeys = setOf("path", "file_path", "filePath", "directory", "dir",
                "source", "destination", "dest", "output", "input", "filename", "uri")
            val resolvedArguments = arguments.mapValues { (key, value) ->
                if (key in pathKeys && value is String) {
                    resolveToolPath(value)
                } else {
                    value
                }
            }

            val toolCallObj = ToolCall(
                toolName = toolCall.function.name,
                arguments = resolvedArguments
            )

            toolGuardian?.let { guardian ->
                val workspaceDir = java.io.File(storageManager.getWorkingDir(), "workspaces/${profile.agentId}")
                val canonicalWorkspace = workspaceDir.canonicalPath
                val canonicalAppData = java.io.File("/data/user/0/${context?.packageName}").canonicalPath
                val workspacePaths = listOf(canonicalWorkspace + "/", canonicalAppData + "/")
                val securityCheck = guardian.checkToolCall(
                    toolCall.function.name, resolvedArguments,
                    workspacePaths = workspacePaths
                )
                guardian.logAudit(toolCall.function.name, resolvedArguments, securityCheck)

                if (!securityCheck.passed) {
                    Timber.w("ToolGuardian blocked tool call: ${toolCall.function.name}, reason: ${securityCheck.reason}")
                    approvalManager?.recordBlockedCall(
                        toolName = toolCall.function.name,
                        arguments = resolvedArguments,
                        reason = securityCheck.reason ?: "Security check failed",
                        agentId = profile.agentId,
                        sessionId = sessionId
                    )
                    return ToolResult(
                        callId = toolCall.id,
                        success = false,
                        output = "安全检查未通过: ${securityCheck.reason}",
                        error = "BLOCKED_BY_GUARDIAN: ${securityCheck.reason}"
                    )
                }

                if (securityCheck.riskLevel >= com.lin.hippyagent.core.tools.ToolGuardian.RiskLevel.HIGH) {
                    if (approvalManager != null) {
                        val existingRule = approvalManager.checkRule(toolCall.function.name, resolvedArguments)
                        val action = when {
                            existingRule == com.lin.hippyagent.core.security.ApprovalAction.ALLOW_ALWAYS -> com.lin.hippyagent.core.security.ApprovalAction.ALLOW_ALWAYS
                            existingRule == com.lin.hippyagent.core.security.ApprovalAction.DENY_ALWAYS -> com.lin.hippyagent.core.security.ApprovalAction.DENY_ALWAYS
                            else -> approvalManager.requestApproval(
                                toolName = toolCall.function.name,
                                arguments = resolvedArguments,
                                riskLevel = securityCheck.riskLevel,
                                findings = securityCheck.findings,
                                sessionId = sessionId,
                                agentId = profile.agentId
                            )
                        }

                        when (action) {
                            com.lin.hippyagent.core.security.ApprovalAction.DENY_ONCE,
                            com.lin.hippyagent.core.security.ApprovalAction.DENY_ALWAYS -> {
                                Timber.w("ToolGuardian approval denied: ${toolCall.function.name}, action: $action")
                                return ToolResult(
                                    callId = toolCall.id,
                                    success = false,
                                    output = "用户拒绝了此操作: ${securityCheck.reason}",
                                    error = "DENIED_BY_USER: ${securityCheck.reason}"
                                )
                            }
                            else -> {
                                Timber.i("ToolGuardian approval granted: ${toolCall.function.name}, action: $action")
                            }
                        }
                    } else {
                        Timber.w("ToolGuardian HIGH+ risk on ${toolCall.function.name}: ${securityCheck.reason} (no approval manager, proceeding)")
                    }
                }
            }

            val toolCtx = ToolContext(
                channel = channelId,
                sessionId = sessionId,
                agentId = profile.agentId,
                workspace = java.io.File(storageManager.getWorkingDir(), "workspaces/${profile.agentId}")
            )

            val result = toolRegistry.executeTool(toolCallObj, toolCtx)

            updateSessionState(sessionId) {
                it.copy(toolCallCount = it.toolCallCount + 1)
            }

            if (result.success) {
                Timber.d("Tool ${toolCall.function.name} executed successfully")
            } else {
                Timber.w("Tool ${toolCall.function.name} failed: ${result.error}")
            }

            result
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute tool call: ${toolCall.function.name}")
            ToolResult(callId = toolCall.id, success = false, error = "工具执行异常: ${e.message}")
        } finally {
            updateSessionState(sessionId) { it.copy(status = AgentStatus.THINKING) }
        }
    }

    suspend fun stop() {
        _state.value.sessionStates.keys.forEach { sessionId ->
            markSessionInterrupted(sessionId)
        }
        sessionContexts.values.forEach { it.job?.cancel() }
        sessionContexts.clear()
        // 停止所有活跃会话
        _state.update { state ->
            val newSessionStates = state.sessionStates.mapValues { (_, ss) ->
                if (ss.status != AgentStatus.IDLE) {
                    ss.copy(status = AgentStatus.STOPPED, isThinking = false)
                } else ss
            }
            state.copy(sessionStates = newSessionStates)
        }
    }

    /**
     * 停止指定会话的处理
     */
    fun stopSession(sessionId: String) {
        markSessionInterrupted(sessionId)
        sessionContexts[sessionId]?.job?.cancel()
        // 立即移除 session 上下文，释放 mutex，避免新消息被旧锁拒绝
        sessionContexts.remove(sessionId)
        updateSessionState(sessionId) { it.copy(status = AgentStatus.STOPPED, isThinking = false) }
    }

    /**
     * 调用 LLM 执行真正的上下文压缩。
     * 如果调用失败，回退到规则摘要（IterativeSummaryMerger）。
     */
    private suspend fun performLlmCompaction(
        compactionPrompt: String,
        existingSummary: String?,
        messagesToCompress: List<com.lin.hippyagent.core.agent.session.SessionMessage>
    ): String {
        return try {
            val request = ModelCallRequest(
                model = stripModelPrefix(profile.modelName),
                messages = listOf(
                    ModelMessage(role = "system", content = COMPACT_SYSTEM_PROMPT),
                    ModelMessage(role = "user", content = compactionPrompt)
                ),
                temperature = 0.3f,
                maxTokens = 2048
            )

            val resp = callLlmWithRetryAndRateLimit(request)
            val summary = resp.choices.firstOrNull()?.message?.content
                ?: throw IllegalStateException("Compression LLM returned no content")

            if (existingSummary != null) {
                summaryMerger.mergeWithNewSummary(existingSummary, summary)
            } else {
                summary
            }
        } catch (e: Exception) {
            Timber.w(e, "LLM compaction failed, falling back to rule-based summary")
            var summary = if (existingSummary != null) {
                summaryMerger.merge(existingSummary, messagesToCompress)
            } else {
                summaryMerger.merge("", messagesToCompress)
            }

            val fallbackConfig = profile.running.lightContextConfig.contextCompactConfig
            if (fallbackConfig.compactionFallbackEnabled) {
                val contextWindow = resolveModelContextWindow() ?: profile.running.maxInputLength
                val summaryTokens = summary.toByteArray(Charsets.UTF_8).size / 4
                val maxSummaryTokens = (contextWindow * (1f - fallbackConfig.compactionFallbackReserveRatio)).toInt()

                if (summaryTokens > maxSummaryTokens) {
                    Timber.w("Rule-based summary still over limit ($summaryTokens > $maxSummaryTokens), re-splitting with fallback ratio ${fallbackConfig.compactionFallbackReserveRatio}")
                    val fallbackReserve = (contextWindow * fallbackConfig.compactionFallbackReserveRatio).toInt()
                    var keepCount = 0
                    var keepTokens = 0
                    for (i in messagesToCompress.indices.reversed()) {
                        val msgTokens = messagesToCompress[i].content.toByteArray(Charsets.UTF_8).size / 4 + 4
                        if (keepTokens + msgTokens > fallbackReserve) break
                        keepCount++
                        keepTokens += msgTokens
                    }
                    if (keepCount < messagesToCompress.size) {
                        val toCompress = messagesToCompress.dropLast(keepCount)
                        summary = if (existingSummary != null) {
                            summaryMerger.merge(existingSummary, toCompress)
                        } else {
                            summaryMerger.merge("", toCompress)
                        }
                        Timber.w("Fallback re-split: compressed ${toCompress.size} messages, promoted $keepCount recent messages")
                    }
                }
            }

            summary
        }
    }

    private val AUTO_CONTINUE_MAX_EXTRA = 2
    private val AUTO_CONTINUE_TAIL_CHARS = 600

    private val AUTO_CONTINUE_HINT_ZH = """
<system-hint>你上一轮的回复只包含纯文本，没有使用任何工具。
请根据<previous-assistant-tail>中的结尾内容（若有）在本轮推理中判断：仍需执行则立刻 tool；已完结则简短收尾。
需要操作时勿只输出计划或代码块。</system-hint>
""".trimIndent()

    private val AUTO_CONTINUE_HINT_EN = """
<system-hint>Your previous assistant turn had text only (no tool calls).
Use the trailing excerpt in <previous-assistant-tail> (if present) plus the conversation to decide in this **reasoning** step: if the user's task still needs tools, emit tool_use now; if it is fully done, reply with a short text only (no tools).
Do not stop with plans or code fences alone when tools are still needed.</system-hint>
""".trimIndent()

    private fun autoContinueSystemHint(): String {
        val lang = profile.running.agentLanguage.trim().lowercase()
        return if (lang == "zh") AUTO_CONTINUE_HINT_ZH else AUTO_CONTINUE_HINT_EN
    }

    private fun autoContinueTailContext(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= AUTO_CONTINUE_TAIL_CHARS) return trimmed
        return trimmed.takeLast(AUTO_CONTINUE_TAIL_CHARS).trimStart()
    }

    private data class RoutedModelResult(val modelName: String, val client: ModelClient)

    private suspend fun resolveRoutedModel(
        sessionId: String,
        content: String,
        overrideModel: String?,
        escalatedThisTurn: Boolean,
        effectiveClient: ModelClient,
        isStream: Boolean = false
    ): RoutedModelResult {
        val tag = if (isStream) "(stream)" else ""
        val routedModel = if (overrideModel == null && modelRouter != null) {
            if (escalatedThisTurn && profile.complexModelName.isNotEmpty()) {
                Timber.d("ModelRouter$tag: ESCALATED → using HEAVY model ${profile.complexModelName}")
                stripModelPrefix(profile.complexModelName)
            } else {
                val historyTokens = tokenUsageState.value.totalTokens.toInt()
                val sessionSt = _state.value.getSessionState(sessionId)
                val routingConfig = com.lin.hippyagent.core.model.routing.RoutingConfig(
                    lightModel = profile.modelName,
                    heavyModel = if (profile.complexModelName.isNotEmpty()) profile.complexModelName else profile.modelName
                )
                val routing = modelRouter.selectModel(
                    message = content,
                    config = routingConfig,
                    toolCallCount = sessionSt.toolCallCount,
                    historyTokenEstimate = historyTokens
                )
                if (routing.usedLightModel) {
                    Timber.d("ModelRouter$tag: using LIGHT model ${routing.selectedModel} (score=${routing.score})")
                } else {
                    Timber.d("ModelRouter$tag: using HEAVY model ${routing.selectedModel} (score=${routing.score})")
                }
                stripModelPrefix(routing.selectedModel)
            }
        } else {
            stripModelPrefix(overrideModel ?: profile.modelName)
        }

        var routingClient = effectiveClient
        if (overrideModel == null && modelRouter != null && profile.complexModelName.isNotEmpty()
            && routedModel == stripModelPrefix(profile.complexModelName)
            && profile.complexModelProvider.isNotEmpty()
        ) {
            val complexClient = resolveModelClient(profile.complexModelProvider)
            if (complexClient !== effectiveClient) {
                Timber.d("ModelRouter$tag: switching client to complexModelProvider=${profile.complexModelProvider}")
                routingClient = complexClient
            }
        }

        return RoutedModelResult(routedModel, routingClient)
    }

    private sealed class LoopCheckResult {
        data object None : LoopCheckResult()
        data class Warn(val shouldEscalate: Boolean) : LoopCheckResult()
        data class Hard(val partialReply: String, val hasToolCalls: Boolean) : LoopCheckResult()
    }

    private fun checkLoopAndInterrupt(
        iteration: Int,
        loopDetector: LoopDetector,
        turnFailureTracker: com.lin.hippyagent.core.model.routing.TurnFailureTracker,
        toolCallNames: List<String>?,
        textContent: String,
        isStream: Boolean = false
    ): LoopCheckResult {
        val signature = buildIterationSignature(toolCallNames)
        val tag = if (isStream) "(stream)" else ""
        when (loopDetector.checkAndRecord(signature)) {
            LoopDetector.LoopLevel.WARN -> {
                Timber.w("Loop warning${tag} for agent ${profile.agentId} at iteration $iteration")
                val escalated = turnFailureTracker.noteFailure(
                    com.lin.hippyagent.core.model.routing.TurnFailureTracker.FailureSignal.REPEAT_LOOP
                )
                return LoopCheckResult.Warn(shouldEscalate = escalated && profile.complexModelName.isNotEmpty())
            }
            LoopDetector.LoopLevel.HARD -> {
                Timber.w("Loop hard limit${tag} for agent ${profile.agentId} at iteration $iteration")
                return LoopCheckResult.Hard(
                    partialReply = textContent.ifEmpty { "" },
                    hasToolCalls = !toolCallNames.isNullOrEmpty()
                )
            }
            LoopDetector.LoopLevel.NONE -> {}
        }
        return LoopCheckResult.None
    }

    private fun shouldAutoContinue(
        content: String?,
        autoContinueExtraCount: Int
    ): Boolean {
        return profile.running.autoContinueOnTextOnly && !content.isNullOrBlank() && autoContinueExtraCount < AUTO_CONTINUE_MAX_EXTRA
    }

    companion object {
        private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        private val attachmentRegex = Regex("""\[附件:\s*(\S+)\]""")
        private val MODEL_VISION_REGEX = Regex("(?i)(vision|vl|gemini|gpt-4o|gpt-5|claude-3|claude-4|qwen-vl|llava|deepseek-vl|flash-image)")

        private val toolSchemaCache = java.util.concurrent.ConcurrentHashMap<Map<String, ToolParameter>, Map<String, Any>>()

        private fun buildToolParameterSchema(params: Map<String, ToolParameter>): Map<String, Any> {
            if (params.isEmpty()) {
                return mapOf("type" to "object")
            }
            return toolSchemaCache.getOrPut(params) {
                val properties = params.mapValues { (_, param) ->
                    val prop = mutableMapOf<String, Any>("type" to param.type, "description" to param.description)
                    param.defaultValue?.let { prop["default"] = it }
                    param.items?.let { prop["items"] = it }
                    prop.toMap()
                }
                val required = params.filter { it.value.required }.keys.toList()
                mapOf(
                    "type" to "object",
                    "properties" to properties,
                    "required" to required
                )
            }
        }
        /**
         * 压缩专用系统提示词 — 指导模型生成高质量摘要
         */
        private const val COMPACT_SYSTEM_PROMPT = """你是一个对话摘要专家。请将提供的对话历史压缩为结构化摘要，严格遵循以下规则：

## 规则
1. 保留所有关键决策、用户偏好和技术细节
2. 保留文件路径、代码片段引用等精确信息
3. 保留任务进度（已完成/待完成）
4. 使用简洁的中文，每段不超过3句话
5. 不要编造原文中没有的信息

## 输出格式
## 目标
用户的主要目标和意图

## 关键决策
对话中做出的重要决定

## 进度
- 已完成：...
- 待完成：...

## 关键上下文
需要记住的重要信息（文件路径、技术细节等）"""
    }

    private suspend fun callLlmWithRetryAndRateLimit(request: ModelCallRequest, client: ModelClient = modelClient): ModelCallResponse {
        val cfg = profile.running
        if (!cfg.llmRetryEnabled) {
            return rateLimiter.acquireAndExecute { client.chatCompletion(request) }
        }

        // P0-3: 如果 FailoverEngine 可用，走故障转移流程
        if (failoverEngine != null) {
            return executeWithFailover(request, client)
        }

        // 原始简单重试逻辑（作为后备）
        var lastException: Throwable? = null
        for (attempt in 0..cfg.llmRetryMaxRetries) {
            try {
                return rateLimiter.acquireAndExecute { client.chatCompletion(request) }
            } catch (e: LlmRateLimitException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                    throw e
                }
                if (e.message?.contains("429") == true) {
                    rateLimiter.notify429()
                    continue
                }
                if (attempt < cfg.llmRetryMaxRetries) {
                    val baseMs = (cfg.llmRetryBackoffBase * 1000).toLong()
                    val capMs = (cfg.llmRetryBackoffCap * 1000).toLong()
                    val delayMs = min(
                        (baseMs * 2.0.pow(attempt.toDouble())).toLong(),
                        capMs
                    )
                    val jitter = if (delayMs > 100) Random.nextLong(0, delayMs / 4) else 0
                    Timber.w("LLM call failed (attempt ${attempt + 1}/${cfg.llmRetryMaxRetries}), retrying in ${delayMs + jitter}ms: ${e.message}")
                    delay(delayMs + jitter)
                }
            }
        }
        // 主模型重试耗尽，尝试 profile 配置的 fallback 模型
        if (profile.fallbackModelName.isNotEmpty()) {
            Timber.w("Primary model exhausted (simple retry), trying fallback: ${profile.fallbackModelName}")
            val fallbackClient = resolveModelClient(
                profile.fallbackModelProvider.takeIf { it.isNotEmpty() && it != profile.modelProvider }
            )
            try {
                val result = rateLimiter.acquireAndExecute { fallbackClient.chatCompletion(request.copy(model = stripModelPrefix(profile.fallbackModelName))) }
                // 标记当前会话使用了 fallback 模型
                currentSessionId()?.let { sid ->
                    updateSessionState(sid) { it.copy(usedFallbackModel = profile.fallbackModelName) }
                }
                return result
            } catch (e: Exception) {
                Timber.e(e, "Fallback model also failed (simple retry): ${profile.fallbackModelName}")
            }
        }
        throw lastException ?: RuntimeException("All model retries exhausted")
    }

    /**
     * P0-3: 使用 FailoverEngine 执行 LLM 调用，支持 Profile 轮换和智能重试。
     */
    private suspend fun executeWithFailover(request: ModelCallRequest, client: ModelClient = modelClient): ModelCallResponse {
        var currentRequest = request
        var retryCount = 0
        val maxRetries = profile.running.llmRetryMaxRetries

        while (retryCount <= maxRetries) {
            try {
                val result = rateLimiter.acquireAndExecute { client.chatCompletion(currentRequest) }
                failoverEngine?.resetRetryCount()
                return result
            } catch (e: LlmRateLimitException) {
                throw e // 由 rateLimiter 自行处理
            } catch (e: Exception) {
                val failoverError = failoverEngine?.classifyError(e) ?: throw e
                val decision = failoverEngine.decide(failoverError, currentRetry = 0)

                Timber.w("Failover: ${decision.action} - ${decision.reason}")

                when (decision.action) {
                    FailoverAction.GIVE_UP -> throw failoverError
                    FailoverAction.SURFACE_TO_USER -> throw failoverError
                    FailoverAction.COMPRESS_CONTEXT -> throw failoverError
                    FailoverAction.ROTATE_PROFILE -> {
                        if (decision.retryDelayMs > 0) delay(decision.retryDelayMs)
                        // Profile 轮换由 ModelClient 层面处理（通过 AuthProfileManager 的 cooldown）
                        retryCount++
                    }
                    FailoverAction.SWITCH_MODEL -> {
                        if (decision.nextModel != null) {
                            currentRequest = currentRequest.copy(model = stripModelPrefix(decision.nextModel))
                        }
                        if (decision.retryDelayMs > 0) delay(decision.retryDelayMs)
                        retryCount++
                    }
                    FailoverAction.SWITCH_PROVIDER -> {
                        // Provider 切换需要重建 ModelClient，当前暂由外层处理
                        if (decision.retryDelayMs > 0) delay(decision.retryDelayMs)
                        retryCount++
                    }
                    FailoverAction.RETRY_SAME -> {
                        if (decision.retryDelayMs > 0) delay(decision.retryDelayMs)
                        retryCount++
                    }
                }
            }
        }

        // 主模型重试耗尽，尝试 profile 配置的 fallback 模型
        if (profile.fallbackModelName.isNotEmpty()) {
            Timber.w("Primary model exhausted, trying fallback: ${profile.fallbackModelName}")
            val fallbackClient = resolveModelClient(
                profile.fallbackModelProvider.takeIf { it.isNotEmpty() && it != profile.modelProvider }
            )
            val fallbackRequest = currentRequest.copy(model = stripModelPrefix(profile.fallbackModelName))
            try {
                val result = rateLimiter.acquireAndExecute { fallbackClient.chatCompletion(fallbackRequest) }
                failoverEngine?.resetRetryCount()
                // 标记当前会话使用了 fallback 模型
                currentSessionId()?.let { sid ->
                    updateSessionState(sid) { it.copy(usedFallbackModel = profile.fallbackModelName) }
                }
                return result
            } catch (e: Exception) {
                Timber.e(e, "Fallback model also failed: ${profile.fallbackModelName}")
            }
        }

        throw FailoverError("达到最大重试次数", com.lin.hippyagent.core.model.FailoverReason.UNKNOWN)
    }

    /**
     * 更新指定会话的状态 — per-session 级别的原子更新
     * P3: 状态机规范化 — 验证状态转换合法性，非法转换降级为 IDLE
     */
    private fun updateSessionState(sessionId: String, transform: (SessionState) -> SessionState) {
        _state.update { state ->
            val current = state.sessionStates[sessionId] ?: SessionState()
            val updated = transform(current)
            if (updated === current) return@update state

            // 验证状态转换合法性
            val finalState = if (updated.status != current.status && !current.status.canTransitionTo(updated.status)) {
                Timber.w("Illegal state transition: ${current.status} -> ${updated.status} for session $sessionId, falling back to IDLE")
                updated.copy(status = AgentStatus.IDLE, isThinking = false)
            } else {
                updated
            }

            state.copy(sessionStates = state.sessionStates + (sessionId to finalState))
        }
    }

    /**
     * 清除已完成/已停止会话的状态条目，防止 sessionStates 无限增长
     */
    fun cleanupSessionState(sessionId: String) {
        _state.update { state ->
            val ss = state.sessionStates[sessionId]
            if (ss != null && (ss.status == AgentStatus.IDLE || ss.status == AgentStatus.STOPPED)) {
                state.copy(sessionStates = state.sessionStates - sessionId)
            } else state
        }
        // 清理互斥锁缓存
        sessionContexts.remove(sessionId)
    }

    fun clearPendingPermission(sessionId: String) {
        updateSessionState(sessionId) { it.copy(pendingPermissionCommand = null) }
    }

    internal suspend fun reset() {
        stop()
        _state.update {
            AgentState(agentId = profile.agentId)
        }
        sessionContexts.clear()
    }

    /**
     * 销毁 Agent 实例，释放所有资源。
     * 由 AgentFactory 在 LRU 驱逐或显式移除时调用。
     * 调用后 Agent 不应再被使用。
     */
    fun destroy() {
        sessionContexts.values.forEach { it.job?.cancel() }
        sessionContexts.clear()
        _state.update { AgentState(agentId = profile.agentId) }
        Timber.i("Agent ${profile.agentId} destroyed and resources released")
    }

    private fun buildIterationSignature(
        toolNames: List<String>?
    ): String {
        val toolPart = toolNames?.sorted()?.joinToString(",") ?: ""
        return toolPart
    }
}

private class LoopDetector(
    private val windowSize: Int = 10,
    private val warnThreshold: Int = 3,
    private val hardLimit: Int = 5,
    private val maxConsecutiveWarns: Int = 3
) {
    private val recentSignatures = ArrayDeque<String>(windowSize)
    private var consecutiveWarnCount = 0

    enum class LoopLevel { NONE, WARN, HARD }

    fun checkAndRecord(signature: String): LoopLevel {
        recentSignatures.addLast(signature)
        if (recentSignatures.size > windowSize) {
            recentSignatures.removeFirst()
        }
        if (recentSignatures.size < warnThreshold) {
            consecutiveWarnCount = 0
            return LoopLevel.NONE
        }
        val first = recentSignatures.first()
        val repeatCount = recentSignatures.count { it == first }
        return when {
            repeatCount >= hardLimit && consecutiveWarnCount >= maxConsecutiveWarns -> LoopLevel.HARD
            repeatCount >= warnThreshold -> {
                consecutiveWarnCount++
                if (consecutiveWarnCount >= maxConsecutiveWarns) LoopLevel.HARD
                else LoopLevel.WARN
            }
            else -> {
                consecutiveWarnCount = 0
                LoopLevel.NONE
            }
        }
    }
}
