package com.lin.hippyagent.core.agent.collaboration

import java.util.concurrent.ConcurrentHashMap

data class GroupContext(
    val groupId: String,
    val groupName: String,
    val allAgentIds: List<String>,
    val agentDescriptions: Map<String, String>,
    val recentMessages: List<GroupChatMessage>,
    val currentRound: Int,
    val maxRounds: Int
) {
    fun getOtherAgents(excludeAgentId: String): List<Pair<String, String>> {
        return agentDescriptions
            .filter { it.key != excludeAgentId }
            .map { it.key to it.value }
    }

    fun getMyMentionedMessages(agentId: String): List<GroupChatMessage> {
        return recentMessages.filter { message ->
            val mentions = MentionParser.parse(message.content)
            agentId in mentions
        }
    }

    fun buildAgentAwarenessPrompt(forAgentId: String): String {
        val sb = StringBuilder()
        sb.appendLine("## 群聊中的其他智能体")
        sb.appendLine()
        val others = getOtherAgents(forAgentId)
        for ((id, desc) in others) {
            sb.appendLine("- **@$id**: $desc")
        }
        sb.appendLine()
        sb.appendLine("如果需要某个智能体的帮助，可以在回复中使用 @智能体ID 来 @ 它。")
        return sb.toString()
    }
}

class AgentDescriptionProvider(
    private val customDescriptions: Map<String, String> = emptyMap(),
    private val agentInfoRepository: AgentInfoRepository? = null
) {
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun getDescription(agentId: String): String {
        return cache.getOrPut(agentId) {
            if (agentInfoRepository != null) {
                val card = agentInfoRepository.getAgentCard(agentId)
                val parts = mutableListOf<String>()
                card?.identity?.let { parts.add(it) }
                card?.responsibilities?.takeIf { it.isNotEmpty() }?.let { parts.add(it.joinToString(", ")) }
                parts.joinToString(" | ").ifBlank { customDescriptions[agentId] ?: "通用智能体" }
            } else {
                customDescriptions[agentId] ?: "通用智能体"
            }
        }
    }

    suspend fun getAllDescriptions(agentIds: List<String>): Map<String, String> {
        if (agentInfoRepository != null) {
            return agentIds.associateWith { getDescription(it) }
        }
        return agentIds.associateWith { cache.getOrPut(it) { customDescriptions[it] ?: "通用智能体" } }
    }

    suspend fun buildGroupContext(
        groupId: String,
        groupName: String,
        agentIds: List<String>,
        recentMessages: List<GroupChatMessage>,
        currentRound: Int,
        maxRounds: Int
    ): GroupContext {
        return GroupContext(
            groupId = groupId,
            groupName = groupName,
            allAgentIds = agentIds,
            agentDescriptions = getAllDescriptions(agentIds),
            recentMessages = recentMessages,
            currentRound = currentRound,
            maxRounds = maxRounds
        )
    }

    fun refreshCache() {
        cache.clear()
    }

    fun setDescription(agentId: String, description: String) {
        cache[agentId] = description
    }
}

