package com.lin.hippyagent.core.agent

import android.content.Context
import com.lin.hippyagent.core.agent.middleware.ClarificationMiddleware
import com.lin.hippyagent.core.agent.middleware.DanglingToolCallMiddleware
import com.lin.hippyagent.core.agent.middleware.MemoryMiddleware
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.channel.ChannelManager
import com.lin.hippyagent.core.memory.EmbeddingModel
import com.lin.hippyagent.core.memory.HybridRetriever
import com.lin.hippyagent.core.memory.KeywordRetriever
import com.lin.hippyagent.core.memory.MemoryManager
import com.lin.hippyagent.core.memory.MemoryStore
import com.lin.hippyagent.core.memory.commonmemory.MemoryExtractor
import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import com.lin.hippyagent.core.memory.SemanticRetriever
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.model.FailoverEngine
import com.lin.hippyagent.core.model.AuthProfileManager
import com.lin.hippyagent.core.prompt.StandingOrdersManager
import com.lin.hippyagent.core.storage.SecureStorage
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.data.repository.AgentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class AgentFactory(
    private val context: Context,
    private val repository: AgentRepository,
    private val modelClientProvider: suspend (profile: AgentProfile) -> ModelClient,
    private val toolRegistry: ToolRegistry,
    private val memoryStore: MemoryStore,
    private val embeddingModel: EmbeddingModel,
    private val channelManager: ChannelManager,
    private val sessionStore: SessionStore,
    private val authProfileManager: AuthProfileManager? = null,
    private val modelProviderStore: ModelProviderStore? = null,
    private val secureStorage: SecureStorage? = null,
    private val commonMemoryRepo: MemoryRepository? = null,
    private val tokenUsageManager: com.lin.hippyagent.core.model.TokenUsageManager? = null,
    private val skillLifecycleManager: com.lin.hippyagent.core.skill.SkillLifecycleManager? = null,
    private val configStorage: com.lin.hippyagent.core.storage.ConfigStorage? = null,
    private val toolGuardian: com.lin.hippyagent.core.tools.ToolGuardian? = null,
    private val approvalManager: com.lin.hippyagent.core.security.ToolApprovalManager? = null,
    private val agentRegistry: com.lin.hippyagent.core.agent.AgentRegistry? = null,
    private val onDeviceModelManager: com.lin.hippyagent.core.ondevice.OnDeviceModelManager? = null,
    private val sessionManager: com.lin.hippyagent.core.agent.AgentSessionManager? = null,
) {
    /** 最大同时存活的 Agent 实例数 */
    companion object {
        const val MAX_AGENT_INSTANCES = 10
    }

    /**
     * LRU 缓存 — accessOrder=true，最近访问的排在尾部，头部是最久未使用的。
     * 超过 MAX_AGENT_INSTANCES 时自动驱逐最久未用的 Agent。
     */
    private val agentInstances = object : LinkedHashMap<String, Agent>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Agent>): Boolean {
            if (size > MAX_AGENT_INSTANCES) {
                // 驱逐最久未用的 Agent，先调用 destroy 清理资源
                try {
                    eldest.value.destroy()
                    Timber.i("LRU evicted agent: ${eldest.key}")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to destroy evicted agent: ${eldest.key}")
                }
                return true
            }
            return false
        }
    }

    private val failoverEngine = FailoverEngine(
        authProfileManager = authProfileManager
    )

    suspend fun createAgent(profile: AgentProfile): Agent {
        val modelClient = modelClientProvider(profile)
        val semanticRetriever = SemanticRetriever(memoryStore, embeddingModel)
        val keywordRetriever = KeywordRetriever(memoryStore)
        val memoryManager = MemoryManager(
            store = memoryStore,
            retriever = HybridRetriever(
                semanticRetriever = semanticRetriever,
                keywordRetriever = keywordRetriever
            )
        )

        val extractor = commonMemoryRepo?.let { repo ->
            MemoryExtractor(
                llmClient = modelClient,
                modelName = profile.modelName,
                memoryRepo = repo
            )
        }

        val agent = Agent(
            context = context,
            profile = profile,
            modelClient = modelClient,
            toolRegistry = toolRegistry,
            memoryManager = memoryManager,
            channelManager = channelManager,
            sessionStore = sessionStore,
            failoverEngine = failoverEngine,
            authProfileManager = authProfileManager,
            modelProviderStore = modelProviderStore,
            secureStorage = secureStorage,
            memoryExtractor = extractor,
            commonMemoryRepo = commonMemoryRepo,
            tokenUsageManager = tokenUsageManager,
            configStorage = configStorage,
            modelRouter = com.lin.hippyagent.core.model.routing.ModelRouter(), 
            toolGuardian = toolGuardian,
            approvalManager = approvalManager,
            onDeviceModelManager = onDeviceModelManager,
            sessionManager = sessionManager
        )

        agentInstances[profile.agentId] = agent
        agentRegistry?.register(profile)

        if (profile.running.remeLightMemoryConfig.rebuildMemoryIndexOnStart) {
            (commonMemoryRepo as? com.lin.hippyagent.core.memory.commonmemory.RoomMemoryRepositoryImpl)?.let { repo ->
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching { repo.rebuildFtsIndex() }
                        .onFailure { Timber.w(it, "rebuildFtsIndex failed") }
                }
            }
        }

        agent.addMiddleware(DanglingToolCallMiddleware())
        agent.addMiddleware(ClarificationMiddleware())
        agent.addMiddleware(MemoryMiddleware())
        agent.addMiddleware(com.lin.hippyagent.core.agent.middleware.LoopDetectionMiddleware())

        skillLifecycleManager?.activateSkills(profile.skills)

        if (profile.disabledTools.isNotEmpty()) {
            toolRegistry.setAgentDenyList(profile.agentId, profile.disabledTools)
        }

        // 将可用技能完整复制到智能体工作区（首次，不主动覆盖）
        if (profile.skills.isNotEmpty()) {
            val agentWorkspaceDir = java.io.File(context.filesDir, "workspaces/${profile.agentId}")
            skillLifecycleManager?.syncSkillsToAgentWorkspace(
                agentId = profile.agentId,
                skillIds = profile.skills,
                agentWorkspaceDir = agentWorkspaceDir
            )
        }

        return agent
    }

    /**
     * 获取 Agent 实例，如果不存在则从 Repository 自动创建
     *
     * 当 cached agent 的关键配置字段（enabled / skills）发生变化时，强制 reload
     * 以保证新配置（特别是用户刚在 AgentSkillScreen 勾选启用/禁用的技能）立即生效
     */
    suspend fun getAgent(agentId: String): Agent? {
        agentInstances[agentId]?.let { cached ->
            try {
                val freshProfiles = repository.loadAgentProfiles().first()
                val freshProfile = freshProfiles[agentId]
                if (freshProfile != null && needsReload(cached.profileConfig, freshProfile)) {
                    if (!freshProfile.enabled) {
                        removeAgent(agentId)
                        return null
                    } else {
                        return reloadAgent(agentId)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "needsReload check failed for $agentId, fallback to cached")
            }
            return cached
        }

        // 自动从 profile 创建
        val profiles = repository.loadAgentProfiles().first()
        val profile = profiles[agentId] ?: return null

        return try {
            createAgent(profile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to auto-create agent: $agentId")
            null
        }
    }

    /**
     * 判断 cached agent 是否需要 reload：
     * - enabled 变化（被禁用 → 整体重建）
     * - skills 变化（用户从 AgentSkillScreen 勾选启用/禁用技能）
     * 其它字段（modelName / identity / responsibilities 等）的变化暂不触发 reload
     * 以避免每次 send message 时不必要的实例重建，必要时可扩展
     */
    private fun needsReload(cached: AgentProfile, fresh: AgentProfile): Boolean {
        if (cached.enabled != fresh.enabled) return true
        if (cached.skills != fresh.skills) return true
        return false
    }

    fun removeAgent(agentId: String) {
        val agent = agentInstances.remove(agentId)
        agentRegistry?.unregister(agentId)
        agent?.destroy()
    }

    fun getAllAgents(): List<Agent> = agentInstances.values.toList()

    /**
     * 从磁盘重新加载指定 Agent 的 profile 并重建实例。
     * 用于用户在配置界面修改模型/参数后，立即使新配置生效，
     * 而不需要重启整个应用。
     *
     * @return 重新创建的 Agent，或 null（如果 profile 不存在）
     */
    suspend fun reloadAgent(agentId: String): Agent? {
        val profiles = repository.loadAgentProfiles().first()
        val profile = profiles[agentId] ?: return null
        // 移除旧实例并销毁
        val oldAgent = agentInstances.remove(agentId)
        oldAgent?.destroy()
        // 用新 profile 创建
        return try {
            createAgent(profile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to reload agent: $agentId")
            null
        }
    }

    /**
     * 从磁盘加载所有 Agent 并缓存，返回全部列表
     */
    suspend fun loadAllAgents(): List<Agent> {
        val profiles = repository.loadAgentProfiles().first()
        for ((agentId, profile) in profiles) {
            if (!agentInstances.containsKey(agentId)) {
                try {
                    createAgent(profile)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load agent: $agentId")
                }
            }
        }
        return agentInstances.values.toList()
    }
}

