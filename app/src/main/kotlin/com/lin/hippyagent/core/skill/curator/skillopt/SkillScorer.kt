package com.lin.hippyagent.core.skill.curator.skillopt

import kotlin.math.ln
import kotlin.math.max

class SkillScorer(
    private val alpha: Double = 1.0,
    private val beta: Double = 5.0,
    private val gamma: Double = 2.0,
    private val delta: Double = 3.0,
    private val archiveThreshold: Double = 1.0,
    private val archiveMinCalls: Int = 5
) {
    fun score(events: List<SkillCallEvent>): Double {
        if (events.isEmpty()) return 0.0
        val callCount = events.size
        val successCount = events.count { it.success }
        val successRate = if (callCount == 0) 0.0 else successCount.toDouble() / callCount
        val recentWindowMs = 7L * 24 * 60 * 60 * 1000
        val now = events.maxOf { it.timestamp }
        val recentCount = events.count { now - it.timestamp <= recentWindowMs }
        val recentActivity = if (callCount == 0) 0.0 else recentCount.toDouble() / callCount
        val feedbackCount = events.count { it.userFeedback != null }
        val negativeCount = events.count { it.userFeedback == -1 }
        val negativeRatio = if (feedbackCount == 0) 0.0 else negativeCount.toDouble() / feedbackCount
        return alpha * ln(1.0 + callCount) +
            beta * successRate +
            gamma * recentActivity -
            delta * negativeRatio
    }

    fun recommendArchive(events: List<SkillCallEvent>): Boolean {
        if (events.size <= archiveMinCalls) return false
        return max(0.0, score(events)) < archiveThreshold
    }
}
