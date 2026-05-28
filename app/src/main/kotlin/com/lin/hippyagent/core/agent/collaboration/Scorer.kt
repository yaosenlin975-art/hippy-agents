package com.lin.hippyagent.core.agent.collaboration

interface Scorer {
    fun score(message: String, agentId: String, description: String): Int
    val name: String
}

data class RelevanceScore(
    val agentId: String,
    val score: Int,
    val reason: String,
    val scorerName: String,
    val threshold: Int = 7
) {
    val isRelevant: Boolean get() = score >= threshold
}
