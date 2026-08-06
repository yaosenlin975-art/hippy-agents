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
import com.lin.hippyagent.core.agent.tools.LlmToolRouter
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
import com.lin.hippyagent.core.model.ContextWindowGuard
import com.lin.hippyagent.core.network.NetworkMonitor
import com.lin.hippyagent.core.storage.StorageManager
import com.lin.hippyagent.core.pool.StringBuilderPool
import com.lin.hippyagent.core.pool.ToolCallInfoListPool
import com.lin.hippyagent.core.security.InputGuard
import com.lin.hippyagent.core.security.RiskLevel
import com.lin.hippyagent.core.security.SecuritySpanReporter
import com.lin.hippyagent.core.security.injection.InjectionDetector
import com.lin.hippyagent.core.security.output.OutputValidator
import com.lin.hippyagent.core.security.pii.PiiMasker
import com.lin.hippyagent.core.tools.ToolCall
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.core.tools.ToolResult
import com.lin.hippyagent.core.trace.SpanCollector
import com.lin.hippyagent.core.trace.SpanContext
import com.lin.hippyagent.core.trace.SpanType
import com.lin.hippyagent.core.trace.TraceContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.coroutines.coroutineContext
class Agent(
    internal val context: Context,
    internal val profile: AgentProfile,
    internal val modelClient: ModelClient,
    internal val toolRegistry: ToolRegistry,
    internal val memoryManager: MemoryManager,
    internal val channelManager: ChannelManager,
    internal val sessionStore: SessionStore,
    internal val failoverEngine: FailoverEngine? = null,
    internal val authProfileManager: AuthProfileManager? = null,
    internal val modelProviderStore: ModelProviderStore? = null,
    internal val secureStorage: com.lin.hippyagent.core.storage.SecureStorage? = null,
    internal val memoryExtractor: com.lin.hippyagent.core.memory.commonmemory.MemoryExtractor? = null,
    internal val commonMemoryRepo: com.lin.hippyagent.core.memory.commonmemory.MemoryRepository? = null,
    internal val volunteerContextInjector: com.lin.hippyagent.core.memory.volunteer.VolunteerContextInjector? = null,
    internal val tokenUsageManager: com.lin.hippyagent.core.model.TokenUsageManager? = null,
    internal val modelRouter: com.lin.hippyagent.core.model.routing.ModelRouter? = null,
    internal val configStorage: com.lin.hippyagent.core.storage.ConfigStorage? = null,
    internal val toolGuardian: com.lin.hippyagent.core.tools.ToolGuardian? = null,
    internal val sessionManager: com.lin.hippyagent.core.agent.AgentSessionManager? = null,
    internal val approvalManager: com.lin.hippyagent.core.security.ToolApprovalManager? = null,
    internal val onDeviceModelManager: com.lin.hippyagent.core.ondevice.OnDeviceModelManager? = null,
    /** 群组聊天时过滤上下文消息的回调 — 只给 agent 看它该看的 */
    var contextMessageFilter: ((com.lin.hippyagent.core.agent.session.SessionMessage) -> Boolean)? = null,

) : KoinComponent {
    /**
     * 去除模型名称中的供应商前缀，例如 "小米/mimo-v2.5" -> "mimo-v2.5"
     * API 只需要模型 ID，不需要供应商前缀。
     */
    internal fun stripModelPrefix(modelName: String): String {
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
    internal suspend fun resolveModelClient(overrideProviderId: String?): ModelClient {
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
    internal val networkMonitor = NetworkMonitor(context)
    internal val offlineMessageQueue = com.lin.hippyagent.core.network.OfflineMessageQueue(context)
    internal val rateLimiter = LlmRateLimiter(profile.running)
    internal val llmToolRouter by lazy { LlmToolRouter() }
    internal val _state = MutableStateFlow(AgentState(agentId = profile.agentId))
    val state: StateFlow<AgentState> = _state.asStateFlow()

    internal val _tokenUsage = MutableStateFlow(TokenUsage())
    val tokenUsageState: StateFlow<TokenUsage> = _tokenUsage.asStateFlow()
    internal val _contextTokenInfo = MutableStateFlow(ContextTokenInfo())
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

    internal data class SessionContext(
        val mutex: Mutex = Mutex(),
        var job: Job? = null,
        var interruptFlag: Boolean = false,
        var systemPrompt: String = "",
        val messageQueue: MutableList<String> = mutableListOf()
    )

    internal val sessionContexts = ConcurrentHashMap<String, SessionContext>()

    internal fun getOrCreateSessionContext(sessionId: String): SessionContext =
        sessionContexts.getOrPut(sessionId) { SessionContext() }

    internal fun isSessionInterrupted(sessionId: String): Boolean =
        sessionContexts[sessionId]?.interruptFlag == true

    internal fun markSessionInterrupted(sessionId: String) {
        getOrCreateSessionContext(sessionId).interruptFlag = true
    }

    internal fun clearSessionInterrupt(sessionId: String) {
        sessionContexts[sessionId]?.interruptFlag = false
    }

    internal val middlewareList = mutableListOf<AgentMiddleware>()
    internal var middlewareChain = MiddlewareChain(emptyList())

    fun addMiddleware(middleware: AgentMiddleware) {
        middlewareList.add(middleware)
        middlewareChain = MiddlewareChain(middlewareList)
    }

    fun getMiddleware(name: String): AgentMiddleware? = middlewareChain.getMiddleware(name)

    internal fun runBeforeModel(sessionId: String, messages: MutableList<ModelMessage>, iteration: Int): MutableList<ModelMessage> {
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

    internal fun runAfterModel(sessionId: String, messages: MutableList<ModelMessage>, iteration: Int, response: ModelResponse): MiddlewareResult {
        val ctx = MiddlewareContext(
            sessionId = sessionId,
            agentId = profile.agentId,
            messages = messages,
            iteration = iteration
        )
        return middlewareChain.runAfterModel(ctx, response)
    }

    internal fun runAfterAgent(sessionId: String, messages: MutableList<ModelMessage>) {
        val ctx = MiddlewareContext(
            sessionId = sessionId,
            agentId = profile.agentId,
            messages = messages
        )
        middlewareChain.runAfterAgent(ctx)
    }

    /** 获取指定会话的 Mutex，不存在则自动创建 */
    internal fun getOrCreateSessionMutex(sessionId: String): Mutex =
        getOrCreateSessionContext(sessionId).mutex

    /** 获取指定会话是否正在处理中 */
    internal fun isSessionProcessing(sessionId: String): Boolean {
        return sessionContexts[sessionId]?.mutex?.isLocked == true
    }

    /** 当前正在处理的会话 ID（用于 fallback 等内部逻辑获取当前会话） */
    @Volatile
    internal var _currentProcessingSessionId: String? = null

    internal fun currentSessionId(): String? = _currentProcessingSessionId

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

    internal val contextManager = ContextManager(
        profile.running,
        toolResultCacheDir = context.cacheDir.absolutePath
    )
    internal val summaryMerger = IterativeSummaryMerger()

    internal val SUMMARY_PREFIX = """
        |[以下是对话历史的压缩摘要，由系统自动生成]
        |摘要中描述的任务可能尚未完成，请根据摘要中的上下文继续工作。
        |---
        |""".trimMargin()

    internal val sbPool = StringBuilderPool(maxSize = 8)
    internal val toolCallListPool = ToolCallInfoListPool(maxSize = 4)
    internal val reusableToolCallList = mutableListOf<ToolCallInfo>()
    internal val repairPipeline = com.lin.hippyagent.core.agent.repair.ToolCallRepairPipeline()
    internal val accumulatedToolCallPool = AccumulatedToolCallPool(maxSize = 8)
    internal val storageManager = StorageManager(context)
    internal val skillManager by lazy { com.lin.hippyagent.core.skill.SkillManager(context, java.io.File(storageManager.getWorkingDir(), "skills")) }
    internal val skillTriggerResolver by lazy { com.lin.hippyagent.core.skill.SkillTriggerResolver(skillManager) }
    internal val skillCatalog by lazy { com.lin.hippyagent.core.skill.SkillCatalog(skillManager) }

    /** 公开的工作区根目录，供外部模块（如 ChatViewModel）直接访问，避免反射。 */
    val workspaceDir: java.io.File get() = java.io.File(storageManager.getWorkingDir(), "workspaces/${profile.agentId}")

    internal val promptBuilder = PromptBuilder()

    internal val memoryExtractionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal lateinit var bootstrapHook: BootstrapHook

    internal fun ensureBootstrapHook(workingDir: java.io.File): BootstrapHook {
        if (!::bootstrapHook.isInitialized) {
            bootstrapHook = BootstrapHook(workingDir)
        }
        return bootstrapHook
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
    companion object {
        internal val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        internal val attachmentRegex = Regex("""\[附件:\s*(\S+)\]""")
        internal val MODEL_VISION_REGEX = Regex("(?i)(vision|vl|gemini|gpt-4o|gpt-5|claude-3|claude-4|qwen-vl|llava|deepseek-vl|flash-image)")

        internal val toolSchemaCache = java.util.concurrent.ConcurrentHashMap<Map<String, ToolParameter>, Map<String, Any>>()
        internal const val TOOL_SCHEMA_CACHE_MAX = 1000

        internal fun buildToolParameterSchema(params: Map<String, ToolParameter>): Map<String, Any> {
            if (params.isEmpty()) {
                return mapOf("type" to "object")
            }
            if (toolSchemaCache.size >= TOOL_SCHEMA_CACHE_MAX) {
                toolSchemaCache.clear()
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
        internal const val COMPACT_SYSTEM_PROMPT = """你是一个对话摘要专家。请将提供的对话历史压缩为结构化摘要，严格遵循以下规则：

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
    /**
     * 更新指定会话的状态 — per-session 级别的原子更新
     * P3: 状态机规范化 — 验证状态转换合法性，非法转换降级为 IDLE
     */
    internal fun updateSessionState(sessionId: String, transform: (SessionState) -> SessionState) {
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
        memoryExtractionScope.cancel()
        sessionContexts.values.forEach { it.job?.cancel() }
        sessionContexts.clear()
        _state.update { AgentState(agentId = profile.agentId) }
        Timber.i("Agent ${profile.agentId} destroyed and resources released")
    }

}
