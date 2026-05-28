package com.lin.hippyagent.core.agent.collaboration

class TriggerWordMatcher(
    private val triggerWords: () -> Map<String, List<String>>
) : Scorer {

    override val name: String = "trigger_word"

    override fun score(message: String, agentId: String, description: String): Int {
        val words = triggerWords()[agentId] ?: return 0
        val lowerMessage = message.lowercase()
        val hits = words.filter { lowerMessage.contains(it) }
        return when {
            hits.size >= 2 -> 9
            hits.size == 1 -> 7
            else -> 0
        }
    }
}
