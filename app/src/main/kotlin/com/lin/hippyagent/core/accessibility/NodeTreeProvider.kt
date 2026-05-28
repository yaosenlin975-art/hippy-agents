package com.lin.hippyagent.core.accessibility

import kotlinx.serialization.Serializable

@Serializable
data class NodeTreeQuality(
    val score: Float,
    val totalNodes: Int,
    val interactiveNodes: Int,
    val hasText: Boolean,
    val hasBounds: Boolean,
    val needsVlm: Boolean
)

class NodeTreeProvider(
    private val controller: AccessibilityController
) {
    fun observe(request: ObserveRequest): Pair<ObserveResult, NodeTreeQuality> {
        val result = controller.observeNodes(request)
        val quality = assessQuality(result)
        return Pair(result, quality)
    }

    private fun assessQuality(result: ObserveResult): NodeTreeQuality {
        val interactiveCount = result.interactiveCount ?: 0
        val totalCount = result.nodeCount.let { if (it > 0) it else 1 }
        val interactiveRatio = interactiveCount.toFloat() / totalCount.toFloat()

        val score = when {
            result.error != null -> 0.0f
            interactiveCount < 3 -> 0.2f
            interactiveRatio < 0.05f -> 0.4f
            result.nodeTree?.text == null && result.nodeTree?.children.isNullOrEmpty() -> 0.3f
            else -> (0.8f + interactiveRatio.coerceAtMost(0.2f))
        }

        return NodeTreeQuality(
            score = score,
            totalNodes = totalCount,
            interactiveNodes = interactiveCount,
            hasText = result.nodeTree?.text != null,
            hasBounds = result.nodeTree?.bounds != null,
            needsVlm = score < 0.5f
        )
    }
}
