package com.lin.hippyagent.core.memory.volunteer

/**
 * 多轮窗口候选评分器。
 * recency（越近权重越高）+ frequency（出现次数）+ user-role（用户消息权重高于 assistant）。
 */
class CandidateScorer(
    private val recencyWeight: Double = 0.4,
    private val frequencyWeight: Double = 0.4,
    private val userRoleWeight: Double = 0.2
) {
    fun score(candidates: List<EntityCandidate>, window: List<WindowTurn>): List<ScoredCandidate> {
        val now = System.currentTimeMillis()
        val scored = candidates.map { candidate ->
            val occurrences = window.mapIndexedNotNull { _, turn ->
                if (turn.text.contains(candidate.name, ignoreCase = true)) {
                    val recency = 1.0 / (1 + (now - turn.timestamp) / (1000.0 * 60 * 60)) // 小时级衰减
                    val roleWeight = if (turn.role == "user") 1.0 else 0.5
                    recency * roleWeight
                } else null
            }
            val recencyScore = occurrences.maxOrNull() ?: 0.0
            val frequencyScore = occurrences.size.toDouble().coerceAtMost(5.0) / 5.0
            val userRoleScore = window.count {
                it.role == "user" && it.text.contains(candidate.name, ignoreCase = true)
            }.toDouble().coerceAtMost(3.0) / 3.0

            val total = recencyScore * recencyWeight +
                        frequencyScore * frequencyWeight +
                        userRoleScore * userRoleWeight
            ScoredCandidate(candidate, total)
        }
        return scored.sortedByDescending { it.score }
    }
}

data class WindowTurn(
    val role: String,    // "user" | "assistant"
    val text: String,
    val timestamp: Long
)

data class ScoredCandidate(
    val candidate: EntityCandidate,
    val score: Double
)
