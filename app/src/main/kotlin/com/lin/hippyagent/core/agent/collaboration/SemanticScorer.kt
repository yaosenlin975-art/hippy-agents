package com.lin.hippyagent.core.agent.collaboration

import kotlin.math.sqrt

interface SemanticScorer : Scorer {
    val isReady: Boolean
    suspend fun refreshEmbeddings(agentDescriptions: Map<String, String>)
}

class HybridSemanticScorer(
    private val agentDescriptions: Map<String, String>
) : SemanticScorer {

    override val name: String = "semantic(混合)"
    override val isReady: Boolean = true

    override fun score(message: String, agentId: String, description: String): Int {
        val desc = agentDescriptions[agentId] ?: description
        val keywordScore = keywordSimilarity(message, desc)
        val vectorScore = cosineSimilarity(message, desc)
        val combined = 0.6 * keywordScore + 0.4 * vectorScore
        return (combined * 10).coerceIn(0.0, 10.0).toInt()
    }

    override suspend fun refreshEmbeddings(agentDescriptions: Map<String, String>) {}

    private fun keywordSimilarity(message: String, description: String): Double {
        val msgWords = message.lowercase().split(Regex("\\s+")).toSet()
        val descWords = description.lowercase().split(Regex("\\s+")).toSet()
        val intersection = msgWords.intersect(descWords)
        return if (descWords.isEmpty()) 0.0 else intersection.size.toDouble() / descWords.size
    }

    private fun cosineSimilarity(text1: String, text2: String): Double {
        val vec1 = textToVector(text1)
        val vec2 = textToVector(text2)
        val dotProduct = vec1.keys.intersect(vec2.keys).sumOf { vec1[it]!! * vec2[it]!! }
        val mag1 = sqrt(vec1.values.sumOf { it * it })
        val mag2 = sqrt(vec2.values.sumOf { it * it })
        return if (mag1 == 0.0 || mag2 == 0.0) 0.0 else dotProduct / (mag1 * mag2)
    }

    private fun textToVector(text: String): Map<String, Double> {
        val words = text.lowercase().split(Regex("\\s+"))
        val freq = mutableMapOf<String, Int>()
        for (word in words) {
            freq[word] = (freq[word] ?: 0) + 1
        }
        val total = words.size.toDouble()
        return if (total == 0.0) emptyMap() else freq.mapValues { it.value.toDouble() / total }
    }
}
