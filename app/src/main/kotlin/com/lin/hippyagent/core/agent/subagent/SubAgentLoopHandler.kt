package com.lin.hippyagent.core.agent.subagent

import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.task.HippyJobContext
import com.lin.hippyagent.core.task.HippyJobHandler
import timber.log.Timber

class SubAgentLoopHandler(
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore
) : HippyJobHandler {

    override suspend fun execute(context: HippyJobContext): Map<String, Any> {
        val agentId = context.data["agent_id"] as? String
            ?: throw IllegalArgumentException("agent_id required")
        val prompt = context.data["prompt"] as? String
            ?: throw IllegalArgumentException("prompt required")
        val maxTurns = (context.data["max_turns"] as? Number)?.toInt() ?: 20
        val parentSessionId = context.data["parent_session_id"] as? String ?: ""

        val agent = agentFactory.getAgent(agentId)
        if (agent == null) {
            throw IllegalArgumentException("Agent not found: $agentId")
        }

        val subSessionId = if (parentSessionId.isNotEmpty()) {
            "subagent_${parentSessionId}_${context.id}"
        } else {
            "subagent_${context.id}"
        }

        agent.setToolDenyList(SubAgentOrchestrator.SUBAGENT_DISABLED_TOOLS)

        Timber.d("SubAgentLoopHandler: starting agent=$agentId job=${context.id} session=$subSessionId maxTurns=$maxTurns")

        try {
            val result = agent.processMessage(subSessionId, "subagent", prompt)

            runCatching { sessionStore.hideSession(subSessionId) }
                .onFailure { Timber.w(it, "SubAgentLoopHandler: failed to hide session $subSessionId") }

            return if (result.isSuccess) {
                mapOf("status" to "completed", "session_id" to subSessionId, "agent_id" to agentId)
            } else {
                val error = result.exceptionOrNull()?.message ?: "unknown error"
                Timber.w("SubAgentLoopHandler: agent=$agentId job=${context.id} failed: $error")
                mapOf("status" to "failed", "error" to error, "agent_id" to agentId)
            }
        } finally {
            agent.setToolDenyList(emptyList())
        }
    }
}
