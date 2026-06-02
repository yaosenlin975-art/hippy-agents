package com.lin.hippyagent.core.agent.collaboration

import timber.log.Timber

data class RouteResult(
    val deliverToAgents: List<String>,
    val visibleToUser: Boolean = true,
    val rejectedTargets: List<RejectedTarget> = emptyList(),
    val mentionPaths: Map<String, MentionPath> = emptyMap(),
    val rawMentions: List<String> = emptyList()
)

class GroupMessageRouter(
    private val agentQueues: Map<String, AgentMessageQueue>,
    private val mentionParser: MentionParser = MentionParser,
    private val mentionChainManager: MentionChainManager? = null,
    private val displayNameFuzzyMapper: DisplayNameFuzzyMapper? = null,
    private val broadcastPreScorerProvider: () -> BroadcastPreScorer? = { null }
) {
    suspend fun route(message: GroupChatMessage): RouteResult {
        var mentions = mentionParser.parse(message.content)

        if (displayNameFuzzyMapper != null) {
            mentions = mentions.mapNotNull { displayNameFuzzyMapper.resolve(it) }
        }

        val validMentions = mentions.filter { it in agentQueues.keys }

        if (mentionChainManager != null && validMentions.isNotEmpty()) {
            val result = mentionChainManager.checkPropagation(message.agentId, validMentions, null)
            val rejectedTargets = result.rejected.toMutableList()
            val mentionPaths = result.paths
            val deliverToAgents = result.allowed.filter { it in agentQueues.keys && !mentionChainManager.isCircuitOpen(it) }.toMutableList()
            for (rejected in result.rejected) {
                mentionChainManager.recordRejection(rejected.agentId)
            }
            val circuitOpenAgents = result.allowed.filter { mentionChainManager.isCircuitOpen(it) }
            for (agentId in circuitOpenAgents) {
                rejectedTargets.add(RejectedTarget(agentId, "CIRCUIT_OPEN", "Circuit breaker is open for agent $agentId"))
                mentionChainManager.recordRejection(agentId)
            }
            deliverToAgents.removeAll(circuitOpenAgents)
            return RouteResult(
                deliverToAgents = deliverToAgents,
                visibleToUser = true,
                rejectedTargets = rejectedTargets,
                mentionPaths = mentionPaths,
                rawMentions = mentions
            )
        }

        val targetAgents = when {
            message.senderIsUser && validMentions.isEmpty() -> {
                val scorer = broadcastPreScorerProvider()
                if (scorer != null) {
                    scorer.score(message.content, agentQueues.keys.toList())
                        .filter { it.isRelevant }
                        .map { it.agentId }
                } else {
                    agentQueues.keys.toList()
                }
            }
            message.senderIsUser -> validMentions
            else -> if (validMentions.isEmpty()) emptyList() else validMentions
        }

        return RouteResult(
            deliverToAgents = targetAgents,
            visibleToUser = true,
            rawMentions = mentions
        )
    }

    suspend fun shouldDeliverToAgent(message: GroupChatMessage, targetAgentId: String): Boolean {
        return targetAgentId in route(message).deliverToAgents
    }

    suspend fun deliverMessage(message: GroupChatMessage): Map<String, Boolean> {
        val routeResult = route(message)
        val results = mutableMapOf<String, Boolean>()

        for (agentId in routeResult.deliverToAgents) {
            val queue = agentQueues[agentId]
            if (queue != null) {
                val queuedMessage = QueuedMessage(
                    senderId = message.agentId,
                    content = message.content,
                    mentions = routeResult.rawMentions,
                    timestamp = message.timestamp,
                    senderIsUser = message.senderIsUser
                )
                val success = queue.enqueue(queuedMessage)
                results[agentId] = success
                if (!success) {
                    Timber.w("Failed to enqueue message for agent: $agentId, queue full")
                }
            } else {
                results[agentId] = false
                Timber.w("Agent queue not found: $agentId")
            }
        }

        return results
    }

    fun isMessageVisibleToUser(message: GroupChatMessage): Boolean = true

    fun parseMentions(message: String): List<String> = mentionParser.parse(message)
}