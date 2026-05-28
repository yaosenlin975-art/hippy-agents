package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.agent.AgentProfile
import com.lin.hippyagent.core.agent.AgentRegistry
import com.lin.hippyagent.data.repository.AgentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

data class AgentCard(
    val agentId: String,
    val displayName: String,
    val avatarUrl: String?,
    val identity: String?,
    val responsibilities: List<String>,
    val boundaries: List<String>,
    val skills: List<SkillRef>,
    val collaboration: CollaborationContract,
    val modelInfo: ModelInfo?
)

data class SkillRef(
    val id: String,
    val name: String,
    val description: String,
    val triggers: List<String> = emptyList()
)

data class CollaborationContract(
    val mentionable: Boolean = true,
    val delegatable: Boolean = false,
    val preferredTopics: List<String> = emptyList(),
    val notes: String? = null
)

data class ModelInfo(
    val provider: String,
    val modelName: String
)

data class AgentCardSummary(
    val agentId: String,
    val displayName: String,
    val identity: String?,
    val responsibilityTags: List<String>,
    val skillCount: Int
)

class AgentInfoRepository(
    private val agentRegistry: AgentRegistry,
    private val agentRepository: AgentRepository,
    private val groupRegistry: GroupRegistry,
    private val parser: AgentCardParser,
    private val cache: AgentInfoCache
) {

    suspend fun getAgentCard(agentId: String, refresh: Boolean = false): AgentCard? {
        val cached = cache.get(agentId)
        if (cached != null && !refresh) return cached
        val profile = agentRegistry.getAgent(agentId)
            ?: agentRepository.loadAgentProfiles().first()[agentId]
            ?: return null
        val card = buildCard(profile)
        cache.put(agentId, card)
        return card
    }

    suspend fun listAgentSummaries(): List<AgentCardSummary> {
        val profiles = agentRepository.loadAgentProfiles().first()
        return profiles.values
            .filter { it.enabled }
            .map { buildCard(it).toSummary() }
    }

    suspend fun searchAgents(keyword: String): List<AgentCardSummary> {
        val kw = keyword.lowercase()
        return listAgentSummaries().filter { s ->
            s.agentId.lowercase().contains(kw) ||
                s.displayName.lowercase().contains(kw) ||
                (s.identity?.lowercase()?.contains(kw) == true) ||
                s.responsibilityTags.any { it.lowercase().contains(kw) }
        }
    }

    suspend fun getGroupAgentSummaries(groupId: String): List<AgentCardSummary>? {
        val group = groupRegistry.getGroup(groupId) ?: return null
        return group.agentIds.mapNotNull { getAgentCard(it)?.toSummary() }
    }

    fun getGroupName(groupId: String): String? = groupRegistry.getGroup(groupId)?.groupName

    private suspend fun buildCard(profile: AgentProfile): AgentCard {
        val identity = profile.identity.ifBlank {
            runCatching { agentRepository.readCoreFile(profile.agentId, "SOUL.md").getOrNull() }
                .getOrNull()
                ?.let { parser.parseIdentity(it) }
        }

        val agentsContent = runCatching { agentRepository.readCoreFile(profile.agentId, "AGENTS.md").getOrNull() }.getOrNull()

        val responsibilities = profile.responsibilities.ifEmpty {
            parser.parseResponsibilities(agentsContent)
        }

        val boundaries = profile.boundaries.ifEmpty {
            parser.parseBoundaries(agentsContent)
        }

        val skills = profile.skills.map { skillId ->
            SkillRef(id = skillId, name = skillId, description = "", triggers = emptyList())
        }

        val collaboration = CollaborationContract(
            mentionable = profile.collaboration.mentionable,
            delegatable = profile.collaboration.delegatable,
            preferredTopics = profile.collaboration.preferredTopics,
            notes = profile.collaboration.notes.ifBlank { null }
        )

        val modelInfo = if (profile.modelProvider.isNotBlank() && profile.modelName.isNotBlank()) {
            ModelInfo(provider = profile.modelProvider, modelName = profile.modelName)
        } else null

        return AgentCard(
            agentId = profile.agentId,
            displayName = profile.name.ifBlank { profile.agentId },
            avatarUrl = profile.avatarUrl,
            identity = identity,
            responsibilities = responsibilities,
            boundaries = boundaries,
            skills = skills,
            collaboration = collaboration,
            modelInfo = modelInfo
        )
    }

    private fun AgentCard.toSummary() = AgentCardSummary(
        agentId = agentId,
        displayName = displayName,
        identity = identity,
        responsibilityTags = responsibilities,
        skillCount = skills.size
    )

    fun startWatching(coroutineScope: CoroutineScope) {
        coroutineScope.launch {
            agentRegistry.agents.collect {
                cache.invalidateAll()
                Timber.d("AgentInfoCache invalidated due to registry change")
            }
        }
    }
}
