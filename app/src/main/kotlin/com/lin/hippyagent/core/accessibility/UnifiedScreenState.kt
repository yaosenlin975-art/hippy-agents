package com.lin.hippyagent.core.accessibility

import kotlinx.serialization.Serializable

@Serializable
data class UnifiedScreenState(
    val window: String? = null,
    val screenSize: ScreenSize = ScreenSize(1080, 2400),
    val timestamp: Long = 0,

    val nodeTree: SerializedNode? = null,
    val nodeCount: Int = 0,
    val interactiveCount: Int = 0,
    val nodeTreeQuality: NodeTreeQuality? = null,

    val vlmAnalysis: VlmAnalysisResult? = null,

    val recentEvents: List<ScreenEvent> = emptyList(),

    val unifiedElements: List<UnifiedElement> = emptyList()
)

@Serializable
data class UnifiedElement(
    val source: ElementSource,
    val role: String,
    val text: String? = null,
    val contentDesc: String? = null,
    val bounds: String? = null,
    val viewId: String? = null,
    val clickable: Boolean = false,
    val confidence: Float = 1.0f
)

@Serializable
enum class ElementSource { NODE_TREE, VLM, FUSED }
