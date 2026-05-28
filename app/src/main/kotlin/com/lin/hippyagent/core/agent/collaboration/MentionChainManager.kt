package com.lin.hippyagent.core.agent.collaboration

import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class MentionPath(
    val path: List<String>,
    val depth: Int = path.size - 1,
    val maxDepth: Int = 6,
    val pathId: String = UUID.randomUUID().toString()
) {
    val isMaxDepth: Boolean get() = depth >= maxDepth

    fun append(agentId: String): MentionPath = copy(path = path + agentId, depth = depth + 1)

    fun visitCount(agentId: String): Int = path.count { it == agentId }

    fun hasCycle(agentId: String): Boolean = agentId in path

    fun toDisplayString(): String = path.joinToString(" → ")

    fun toSafeLogString(): String = path.joinToString(" → ") {
        if (it.length > 2) "${it.first()}***" else it
    }
}

data class RejectedTarget(
    val agentId: String,
    val reason: String,
    val message: String
)

data class PropagationResult(
    val allowed: List<String>,
    val rejected: List<RejectedTarget>,
    val paths: Map<String, MentionPath>,
    val cycleTargets: List<String> = emptyList()
)

class MentionChainManager {

    data class CircuitBreakerState(
        val consecutiveRejections: Int = 0,
        val isOpen: Boolean = false,
        val lastTrippedAt: Long = 0L,
        val threshold: Int = 5,
        val coolDownMs: Long = 60_000L
    )

    private val processedMap = ConcurrentHashMap<String, String>()
    private val activePaths = ConcurrentHashMap<String, MentionPath>()
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreakerState>()

    companion object {
        const val MAX_VISITS_PER_AGENT = 3
    }

    fun checkPropagation(
        fromAgentId: String,
        targetAgentIds: List<String>,
        parentPath: MentionPath? = null
    ): PropagationResult {
        val allowed = mutableListOf<String>()
        val rejected = mutableListOf<RejectedTarget>()
        val paths = mutableMapOf<String, MentionPath>()
        val cycleTargets = mutableListOf<String>()

        val currentPath = parentPath ?: MentionPath(path = listOf(fromAgentId))

        for (targetId in targetAgentIds) {
            if (currentPath.isMaxDepth) {
                rejected.add(RejectedTarget(targetId, "MAX_DEPTH", "Propagation depth exceeded max ${currentPath.maxDepth}"))
                continue
            }
            val newPath = currentPath.append(targetId)
            if (currentPath.hasCycle(targetId)) {
                cycleTargets.add(targetId)
                Timber.d("Cycle detected: ${currentPath.toSafeLogString()} → $targetId, allowing with warning")
            }
            allowed.add(targetId)
            paths[targetId] = newPath
            Timber.d("Propagation allowed: ${currentPath.toSafeLogString()} → $targetId")
        }

        return PropagationResult(allowed, rejected, paths, cycleTargets)
    }

    fun registerPath(path: MentionPath) {
        activePaths[path.pathId] = path
        Timber.d("Path registered: ${path.pathId} -> ${path.toSafeLogString()}")
    }

    fun getPath(pathId: String): MentionPath? = activePaths[pathId]

    fun isCircuitOpen(groupId: String): Boolean {
        val state = circuitBreakers[groupId] ?: return false
        if (!state.isOpen) return false
        val elapsed = System.currentTimeMillis() - state.lastTrippedAt
        if (elapsed >= state.coolDownMs) {
            circuitBreakers[groupId] = state.copy(isOpen = false, consecutiveRejections = 0)
            Timber.d("Circuit breaker recovered for group: $groupId")
            return false
        }
        return true
    }

    fun recordRejection(groupId: String) {
        val current = circuitBreakers[groupId] ?: CircuitBreakerState()
        val newCount = current.consecutiveRejections + 1
        val tripped = newCount >= current.threshold
        circuitBreakers[groupId] = current.copy(
            consecutiveRejections = newCount,
            isOpen = tripped,
            lastTrippedAt = if (tripped) System.currentTimeMillis() else current.lastTrippedAt
        )
        if (tripped) {
            Timber.w("Circuit breaker tripped for group: $groupId after $newCount consecutive rejections")
        }
    }

    fun cleanup(groupId: String) {
        val keysToRemove = activePaths.keys.filter { pathId ->
            processedMap.entries.any { it.value == pathId }
        }
        keysToRemove.forEach { activePaths.remove(it) }
        processedMap.entries.removeIf { true }
        circuitBreakers.remove(groupId)
        Timber.d("Cleaned up mention chain state for group: $groupId")
    }
}
