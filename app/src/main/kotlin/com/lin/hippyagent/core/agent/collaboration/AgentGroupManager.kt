package com.lin.hippyagent.core.agent.collaboration

import android.content.Context
import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.mode.ModeOrchestrator
import com.lin.hippyagent.core.agent.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class GroupLifecycleState {
    FORMING,
    ACTIVE,
    SUSPENDED,
    DISSOLVED
}

internal enum class GroupMemberStatus {
    ONLINE,
    INACTIVE,
    OFFLINE
}

data class GroupHealthReport(
    val groupId: String,
    val recommendedStatus: GroupLifecycleState,
    val offlineMembers: List<String>
)

class AgentGroupManager(
    private val groupRegistry: GroupRegistry,
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore,
    private val messageBus: AgentMessageBus,
    private val context: Context,
    private val speakerSelector: LLMSpeakerSelector? = null,
    private val collaborationProtocol: com.lin.hippyagent.core.agent.group.GroupCollaborationProtocol? = null,
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val inactiveTimeoutMs: Long = 300_000L,
    private val descriptionProvider: AgentDescriptionProvider? = null,
    private val modelClients: Map<String, com.lin.hippyagent.core.model.ModelClient> = emptyMap(),
    private val modeOrchestrator: ModeOrchestrator? = null
) {
    private val _activeGroups = MutableStateFlow<List<GroupInfo>>(emptyList())
    val activeGroups: StateFlow<List<GroupInfo>> = _activeGroups.asStateFlow()

    private val agentGroupCache = ConcurrentHashMap<String, AgentGroup>()

    private val _groupStates = ConcurrentHashMap<String, GroupChatState>()
    private val _groupStateFlows = ConcurrentHashMap<String, MutableStateFlow<GroupChatState>>()

    private val groupLifecycleStates = ConcurrentHashMap<String, GroupLifecycleState>()
    private val groupMemberStatuses = ConcurrentHashMap<String, MutableMap<String, GroupMemberStatus>>()
    private val groupLastActivity = ConcurrentHashMap<String, Instant>()

    fun createGroup(name: String, agentIds: List<String>, config: GroupChatConfig? = null): Result<GroupInfo> {
        if (agentIds.isEmpty()) {
            return Result.failure(IllegalArgumentException("At least one agent required"))
        }

        val groupId = "group_${System.currentTimeMillis()}"
        val group = groupRegistry.createGroup(groupId, name, agentIds)

        val chatConfig = config ?: GroupChatConfig(
            groupId = groupId,
            groupName = name,
            agentIds = agentIds
        )
        val state = GroupChatState(groupId = groupId)
        _groupStates[groupId] = state
        _groupStateFlows[groupId] = MutableStateFlow(state)

        groupLifecycleStates[groupId] = GroupLifecycleState.FORMING
        groupMemberStatuses[groupId] = agentIds.associateWith { GroupMemberStatus.ONLINE }.toMutableMap()
        groupLastActivity[groupId] = Instant.now()

        if (agentIds.isNotEmpty()) {
            activateGroup(groupId)
        }

        refreshActiveGroups()
        Timber.i("Group created: $name ($groupId) with ${agentIds.size} agents")
        return Result.success(group)
    }

    fun deleteGroup(groupId: String): Result<Unit> {
        val group = groupRegistry.getGroup(groupId)
            ?: return Result.failure(IllegalStateException("Group not found: $groupId"))

        dissolveGroup(groupId)

        groupRegistry.deleteGroup(groupId)
        agentGroupCache.remove(groupId)?.cleanup()
        groupConfigCache.remove(groupId)
        groupDescriptionsCache.remove(groupId)
        _groupStates.remove(groupId)
        _groupStateFlows.remove(groupId)
        refreshActiveGroups()
        Timber.i("Group deleted: ${group.groupName} ($groupId)")
        return Result.success(Unit)
    }

    private fun groupConfigFor(groupId: String): GroupPreDecisionConfig? {
        val cached = groupConfigCache[groupId] ?: return null
        return cached
    }

    private fun agentDescriptionsFor(groupId: String): List<Pair<String, String>> {
        return groupDescriptionsCache[groupId] ?: emptyList()
    }

    private fun cacheGroupConfig(groupId: String, group: GroupInfo) {
        val modelName = group.llmSelectorModelName?.takeIf { it.isNotBlank() } ?: return
        groupConfigCache[groupId] = GroupPreDecisionConfig(
            decisionProviderId = group.llmSelectorProviderId,
            decisionModelName = modelName
        )
    }

    private suspend fun cacheAgentDescriptions(groupId: String, group: GroupInfo) {
        val list = group.agentIds.map { id ->
            val agent: com.lin.hippyagent.core.agent.Agent? = runCatching { agentFactory.getAgent(id) }.getOrNull()
            val name: String = agent?.profileConfig?.name?.ifBlank { id } ?: id
            val desc: String = agent?.profileConfig?.identity?.ifBlank { name } ?: name
            id to desc
        }
        groupDescriptionsCache[groupId] = list
    }

    private val groupConfigCache = ConcurrentHashMap<String, GroupPreDecisionConfig>()
    private val groupDescriptionsCache = ConcurrentHashMap<String, List<Pair<String, String>>>()

    fun addAgent(groupId: String, agentId: String): Result<Unit> {
        val lifecycleState = groupLifecycleStates[groupId]
        if (lifecycleState == GroupLifecycleState.DISSOLVED) {
            return Result.failure(IllegalStateException("Cannot add agent to dissolved group: $groupId"))
        }

        val added = groupRegistry.addAgentToGroup(groupId, agentId)
        if (!added) {
            return Result.failure(IllegalStateException("Failed to add agent $agentId to group $groupId"))
        }

        if (messageBus is InMemoryAgentMessageBus) {
            messageBus.registerAgentToGroup(agentId, groupId)
        }

        groupMemberStatuses[groupId]?.put(agentId, GroupMemberStatus.ONLINE)
        groupDescriptionsCache.remove(groupId)
        groupLastActivity[groupId] = Instant.now()

        if (lifecycleState == GroupLifecycleState.FORMING) {
            activateGroup(groupId)
        }

        agentGroupCache.remove(groupId)?.cleanup()
        refreshActiveGroups()
        return Result.success(Unit)
    }

    fun removeAgent(groupId: String, agentId: String): Result<Unit> {
        val removed = groupRegistry.removeAgentFromGroup(groupId, agentId)
        if (!removed) {
            return Result.failure(IllegalStateException("Failed to remove agent $agentId from group $groupId"))
        }

        if (messageBus is InMemoryAgentMessageBus) {
            messageBus.unregisterAgentFromGroup(agentId, groupId)
        }

        groupMemberStatuses[groupId]?.remove(agentId)
        groupLastActivity[groupId] = Instant.now()

        val remainingMembers = groupMemberStatuses[groupId]?.size ?: 0
        if (remainingMembers == 0) {
            dissolveGroup(groupId)
        } else {
            agentGroupCache[groupId]?.removeAgentId(agentId)
            groupDescriptionsCache.remove(groupId)
        }

        refreshActiveGroups()
        return Result.success(Unit)
    }

    fun activateGroup(groupId: String) {
        val current = groupLifecycleStates[groupId] ?: return
        if (current == GroupLifecycleState.DISSOLVED) return
        groupLifecycleStates[groupId] = GroupLifecycleState.ACTIVE
        groupLastActivity[groupId] = Instant.now()
        Timber.i("Group $groupId activated (was $current)")
    }

    fun suspendGroup(groupId: String) {
        val current = groupLifecycleStates[groupId] ?: return
        if (current != GroupLifecycleState.ACTIVE) return
        groupLifecycleStates[groupId] = GroupLifecycleState.SUSPENDED
        Timber.i("Group $groupId suspended (was $current)")
    }

    fun dissolveGroup(groupId: String) {
        val current = groupLifecycleStates[groupId] ?: return
        if (current == GroupLifecycleState.DISSOLVED) return

        if (messageBus is InMemoryAgentMessageBus) {
            groupMemberStatuses[groupId]?.keys?.forEach { agentId ->
                messageBus.unregisterAgentFromGroup(agentId, groupId)
            }
        }

        groupLifecycleStates[groupId] = GroupLifecycleState.DISSOLVED
        groupMemberStatuses[groupId]?.clear()
        groupLastActivity[groupId] = Instant.now()
        Timber.i("Group $groupId dissolved (was $current)")
    }

    fun getGroupLifecycleState(groupId: String): GroupLifecycleState =
        groupLifecycleStates[groupId] ?: GroupLifecycleState.DISSOLVED

    internal fun updateMemberStatus(groupId: String, agentId: String, status: GroupMemberStatus) {
        groupMemberStatuses[groupId]?.put(agentId, status)
        groupLastActivity[groupId] = Instant.now()
    }

    fun checkGroupHealth(): List<GroupHealthReport> {
        val now = Instant.now()
        val reports = mutableListOf<GroupHealthReport>()

        for ((groupId, _) in groupLifecycleStates) {
            val lifecycleState = groupLifecycleStates[groupId] ?: continue
            if (lifecycleState == GroupLifecycleState.DISSOLVED) continue

            val members = groupMemberStatuses[groupId] ?: continue
            val offlineMembers = mutableListOf<String>()

            for ((agentId, status) in members) {
                when (status) {
                    GroupMemberStatus.OFFLINE -> offlineMembers.add(agentId)
                    GroupMemberStatus.INACTIVE -> {
                        val elapsed = now.toEpochMilli() - (groupLastActivity[groupId]?.toEpochMilli() ?: 0L)
                        if (elapsed > inactiveTimeoutMs) {
                            members[agentId] = GroupMemberStatus.OFFLINE
                            offlineMembers.add(agentId)
                        }
                    }
                    GroupMemberStatus.ONLINE -> { }
                }
            }

            val recommendedStatus = when {
                members.isEmpty() -> GroupLifecycleState.DISSOLVED
                offlineMembers.size == members.size -> GroupLifecycleState.SUSPENDED
                else -> GroupLifecycleState.ACTIVE
            }

            reports.add(
                GroupHealthReport(
                    groupId = groupId,
                    recommendedStatus = recommendedStatus,
                    offlineMembers = offlineMembers
                )
            )
        }

        return reports
    }

    fun getGroup(groupId: String): GroupInfo? = groupRegistry.getGroup(groupId)

    fun listGroups(): List<GroupInfo> = groupRegistry.listGroups()

    fun getGroupState(groupId: String): GroupChatState? = _groupStates[groupId]

    fun observeGroupState(groupId: String): StateFlow<GroupChatState>? = _groupStateFlows[groupId]

    suspend fun getOrCreateAgentGroup(groupId: String): AgentGroup? {
        val group = groupRegistry.getGroup(groupId) ?: return null

        return agentGroupCache.getOrPut(groupId) {
            cacheGroupConfig(groupId, group)
            cacheAgentDescriptions(groupId, group)
            // modelClients 是异步填充的 ConcurrentHashMap 引用，构造时即使为空后续条目也会被 GroupPreDecisionMaker 看到
            val groupPreDecisionMaker = GroupPreDecisionMaker(
                modelClients = modelClients,
                getGroupConfig = { gid -> groupConfigFor(gid) },
                getAgentDescriptions = { gid -> agentDescriptionsFor(gid) }
            )
            AgentGroup(
                groupId = groupId,
                groupName = group.groupName,
                agentIds = group.agentIds,
                agentFactory = agentFactory,
                sessionStore = sessionStore,
                speakerSelector = speakerSelector,
                collaborationProtocol = collaborationProtocol,
                config = AgentGroupConfig(
                    enableLLMSpeakerSelection = true,
                    enablePingPongDetection = true,
                    enableLLMTermination = true,
                    llmSelectorProviderId = group.llmSelectorProviderId,
                    llmSelectorModelName = group.llmSelectorModelName
                ),
                descriptionProvider = descriptionProvider,
                groupPreDecisionMaker = groupPreDecisionMaker,
                modeOrchestrator = modeOrchestrator
            ).also { agentGroup ->
                agentGroup.mentionOnlyAgentIds = group.mentionOnlyAgentIds
                val cachedDescriptions = group.agentIds.associateWith { id ->
                    runCatching { kotlinx.coroutines.runBlocking { agentFactory.getAgent(id) } }
                        .getOrNull()?.profileConfig?.let { cfg ->
                            if (cfg.identity.isNotBlank()) cfg.identity else cfg.name.ifBlank { id }
                        } ?: id
                }
                val cachedTriggerWords = group.agentIds.associateWith { id ->
                    runCatching { kotlinx.coroutines.runBlocking { agentFactory.getAgent(id) } }
                        .getOrNull()?.profileConfig?.collaboration?.preferredTopics ?: emptyList()
                }
                val agentDescriptions: () -> Map<String, String> = { cachedDescriptions }
                val triggerWords: () -> Map<String, List<String>> = { cachedTriggerWords }
                val scorer = createBroadcastPreScorer(context, appScope, agentDescriptions, triggerWords)
                agentGroup.setBroadcastPreScorer(scorer)
            }
        }
    }

    fun getAgentGroup(groupId: String): AgentGroup? = agentGroupCache[groupId]

    fun renameGroup(groupId: String, newName: String): Result<Unit> {
        val renamed = groupRegistry.renameGroup(groupId, newName)
        if (!renamed) {
            return Result.failure(IllegalStateException("Group not found: $groupId"))
        }
        agentGroupCache.remove(groupId)
        refreshActiveGroups()
        Timber.i("Group renamed: $groupId -> $newName")
        return Result.success(Unit)
    }

    fun updateGroupMentionOnly(groupId: String, mentionOnlyAgentIds: List<String>) {
        val group = groupRegistry.getGroup(groupId) ?: return
        groupRegistry.updateGroup(group.copy(mentionOnlyAgentIds = mentionOnlyAgentIds))
        agentGroupCache[groupId]?.mentionOnlyAgentIds = mentionOnlyAgentIds
    }

    fun updateGroupLlmSelector(groupId: String, providerId: String?, modelName: String?) {
        val group = groupRegistry.getGroup(groupId) ?: return
        groupRegistry.updateGroup(group.copy(llmSelectorProviderId = providerId, llmSelectorModelName = modelName))
        agentGroupCache.remove(groupId)?.cleanup()
    }

    private fun refreshActiveGroups() {
        _activeGroups.value = groupRegistry.listGroups()
    }
}
