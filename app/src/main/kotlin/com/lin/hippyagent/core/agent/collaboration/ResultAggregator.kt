package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Aggregates results from multiple agent executions into a unified summary.
 */
class ResultAggregator(
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore
) {
    suspend fun aggregateResults(
        agentIds: List<String>,
        query: String,
        strategy: AggregationStrategy = AggregationStrategy.MERGE
    ): AggregatedResult = withContext(Dispatchers.Default) {
        val results = mutableListOf<AgentResult>()

        for (agentId in agentIds) {
            val agent = agentFactory.getAgent(agentId)
            if (agent == null) {
                results.add(AgentResult(agentId = agentId, success = false, error = "Agent not found: $agentId"))
                continue
            }

            try {
                val sessionId = "agg:${agentId}:${System.currentTimeMillis()}"
                val result = agent.processMessage(sessionId, "aggregate", query)
                results.add(AgentResult(
                    agentId = agentId,
                    success = result.isSuccess,
                    content = if (result.isSuccess) "OK" else "",
                    error = result.exceptionOrNull()?.message
                ))
            } catch (e: Exception) {
                results.add(AgentResult(agentId = agentId, success = false, error = e.message))
            }
        }

        val summary = when (strategy) {
            AggregationStrategy.MERGE -> mergeResults(results)
            AggregationStrategy.BEST -> selectBestResult(results)
            AggregationStrategy.CONSENSUS -> buildConsensus(results)
        }

        AggregatedResult(
            query = query,
            strategy = strategy,
            results = results,
            summary = summary,
            totalAgents = agentIds.size,
            successfulAgents = results.count { it.success }
        )
    }
}

enum class AggregationStrategy { MERGE, BEST, CONSENSUS }

data class AgentResult(
    val agentId: String,
    val success: Boolean,
    val content: String = "",
    val error: String? = null
)

data class AggregatedResult(
    val query: String,
    val strategy: AggregationStrategy,
    val results: List<AgentResult>,
    val summary: String,
    val totalAgents: Int,
    val successfulAgents: Int
) {
    val hasErrors: Boolean get() = results.any { !it.success }
    val successRate: Float get() = if (totalAgents > 0) successfulAgents.toFloat() / totalAgents else 0f
}

private fun mergeResults(results: List<AgentResult>): String {
    val successful = results.filter { it.success }
    if (successful.isEmpty()) return "No successful results from any agent."
    return "Aggregated Results (${successful.size}/${results.size} agents): ${successful.joinToString { it.agentId }}"
}

private fun selectBestResult(results: List<AgentResult>): String {
    val successful = results.filter { it.success }
    if (successful.isEmpty()) return "No successful results from any agent."
    val best = successful.first()
    return "Best Result (from ${best.agentId}): ${best.content}"
}

private fun buildConsensus(results: List<AgentResult>): String {
    val successful = results.filter { it.success }
    if (successful.isEmpty()) return "No successful results from any agent."
    if (successful.size == 1) return successful.first().content
    return "Consensus (${successful.size} agents): ${successful.joinToString { it.agentId }}"
}

