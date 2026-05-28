package com.lin.hippyagent.core.agent.middleware

import com.lin.hippyagent.core.model.ModelMessage
import timber.log.Timber

class LoopDetectionMiddleware(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val warnThreshold: Int = DEFAULT_WARN_THRESHOLD,
    private val hardLimit: Int = DEFAULT_HARD_LIMIT
) : AgentMiddleware {

    override val priority: Int = PRIORITY
    override val name: String = NAME

    private val recentSignatures = ArrayDeque<String>(windowSize)

    fun checkAndRecord(signature: String): LoopLevel {
        recentSignatures.addLast(signature)
        if (recentSignatures.size > windowSize) {
            recentSignatures.removeFirst()
        }
        if (recentSignatures.size < warnThreshold) return LoopLevel.NONE
        val first = recentSignatures.first()
        val repeatCount = recentSignatures.count { it == first }
        return when {
            repeatCount >= hardLimit -> LoopLevel.HARD
            repeatCount >= warnThreshold -> LoopLevel.WARN
            else -> LoopLevel.NONE
        }
    }

    fun reset() {
        recentSignatures.clear()
    }

    enum class LoopLevel { NONE, WARN, HARD }

    companion object {
        const val PRIORITY = 80
        const val NAME = "loop_detection"
        const val DEFAULT_WINDOW_SIZE = 10
        const val DEFAULT_WARN_THRESHOLD = 5
        const val DEFAULT_HARD_LIMIT = 8
    }
}
