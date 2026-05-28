package com.lin.hippyagent.core.accessibility

import com.lin.hippyagent.core.model.ModelClient
import timber.log.Timber

class SmartPerceptionLayer(
    private val nodeTreeProvider: NodeTreeProvider,
    private val screenEventBus: ScreenEventBus,
    private val screenshotCapturer: ScreenshotCapturer,
    private val vlmProvider: VlmProvider? = null
) {
    private var lastObserveTimestamp: Long = 0

    suspend fun observe(request: ObserveRequest): UnifiedScreenState {
        val now = System.currentTimeMillis()

        val (nodeResult, quality) = nodeTreeProvider.observe(request)

        var vlmResult: VlmAnalysisResult? = null
        if (quality.needsVlm && vlmProvider != null) {
            val service = PhoneControlAccessibilityService.instance
            if (service != null) {
                val screenshot = screenshotCapturer.captureForVlm(service)
                if (screenshot != null) {
                    val nodeTreeHint = buildNodeTreeHint(nodeResult)
                    vlmResult = vlmProvider.analyze(
                        screenshotBase64 = screenshot.first,
                        screenSize = screenshot.second,
                        nodeTreeHint = nodeTreeHint
                    )
                }
            } else {
                Timber.w("AccessibilityService not running, skipping VLM analysis")
            }
        }

        val unifiedElements = fuseElements(nodeResult, vlmResult)

        val recentEvents = screenEventBus.getRecentEvents(lastObserveTimestamp)
        lastObserveTimestamp = now

        return UnifiedScreenState(
            window = nodeResult.window,
            screenSize = nodeResult.screenSize ?: ScreenSize(1080, 2400),
            timestamp = now,
            nodeTree = nodeResult.nodeTree,
            nodeCount = nodeResult.nodeCount,
            interactiveCount = nodeResult.interactiveCount,
            nodeTreeQuality = quality,
            vlmAnalysis = vlmResult,
            recentEvents = recentEvents,
            unifiedElements = unifiedElements
        )
    }

    private fun buildNodeTreeHint(result: ObserveResult): String {
        val sb = StringBuilder()
        sb.append("Window: ${result.window}\n")
        sb.append("Interactive nodes: ${result.interactiveCount}\n")
        result.nodeTree?.let { tree ->
            serializeInteractiveElements(tree, sb, depth = 0, maxDepth = 3)
        }
        return sb.toString()
    }

    private fun serializeInteractiveElements(node: SerializedNode, sb: StringBuilder, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val indent = "  ".repeat(depth)
        if (node.clickable || node.scrollable || node.editable) {
            sb.append("$indent- [${node.type}]")
            node.text?.let { sb.append(" text=\"$it\"") }
            node.content_desc?.let { sb.append(" desc=\"$it\"") }
            node.bounds?.let { sb.append(" bounds=$it") }
            node.view_id?.let { sb.append(" id=$it") }
            sb.append("\n")
        }
        node.children?.forEach { child ->
            serializeInteractiveElements(child, sb, depth + 1, maxDepth)
        }
    }

    private fun fuseElements(
        nodeResult: ObserveResult,
        vlmResult: VlmAnalysisResult?
    ): List<UnifiedElement> {
        val elements = mutableListOf<UnifiedElement>()

        extractInteractiveElements(nodeResult.nodeTree).forEach { node ->
            elements.add(UnifiedElement(
                source = ElementSource.NODE_TREE,
                role = inferRole(node),
                text = node.text,
                contentDesc = node.content_desc,
                bounds = node.bounds,
                viewId = node.view_id,
                clickable = node.clickable,
                confidence = 1.0f
            ))
        }

        vlmResult?.elements?.forEach { vlmEl ->
            val vlmBounds = vlmEl.bounds
            if (vlmBounds != null) {
                val matchedNode = elements.find { existing ->
                    existing.bounds != null && computeIoU(vlmBounds, existing.bounds) > 0.7f
                }
                if (matchedNode != null) {
                    val idx = elements.indexOf(matchedNode)
                    elements[idx] = matchedNode.copy(
                        source = ElementSource.FUSED,
                        contentDesc = matchedNode.contentDesc ?: vlmEl.description
                    )
                } else {
                    elements.add(UnifiedElement(
                        source = ElementSource.VLM,
                        role = vlmEl.role,
                        text = vlmEl.text,
                        contentDesc = vlmEl.description,
                        bounds = vlmEl.bounds,
                        clickable = vlmEl.role in setOf("button", "input", "tab"),
                        confidence = vlmResult.confidence
                    ))
                }
            } else {
                elements.add(UnifiedElement(
                    source = ElementSource.VLM,
                    role = vlmEl.role,
                    text = vlmEl.text,
                    contentDesc = vlmEl.description,
                    bounds = null,
                    clickable = false,
                    confidence = vlmResult.confidence * 0.5f
                ))
            }
        }

        return elements.sortedByDescending { it.confidence }
    }

    private fun extractInteractiveElements(node: SerializedNode?, depth: Int = 0, maxDepth: Int = 10): List<SerializedNode> {
        if (node == null || depth > maxDepth) return emptyList()
        val result = mutableListOf<SerializedNode>()
        if (node.clickable || node.scrollable || node.editable) {
            result.add(node)
        }
        node.children?.forEach { child ->
            result.addAll(extractInteractiveElements(child, depth + 1, maxDepth))
        }
        return result
    }

    private fun inferRole(node: SerializedNode): String = when {
        node.editable -> "input"
        node.scrollable -> "scrollable"
        node.clickable -> "button"
        else -> node.type
    }

    private fun computeIoU(bounds1: String, bounds2: String): Float {
        val rect1 = parseBoundsRect(bounds1) ?: return 0f
        val rect2 = parseBoundsRect(bounds2) ?: return 0f

        val xOverlap = maxOf(0, minOf(rect1.right, rect2.right) - maxOf(rect1.left, rect2.left))
        val yOverlap = maxOf(0, minOf(rect1.bottom, rect2.bottom) - maxOf(rect1.top, rect2.top))
        val intersection = xOverlap * yOverlap

        val area1 = (rect1.right - rect1.left) * (rect1.bottom - rect1.top)
        val area2 = (rect2.right - rect2.left) * (rect2.bottom - rect2.top)
        val union = area1 + area2 - intersection

        return if (union > 0) intersection.toFloat() / union.toFloat() else 0f
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    companion object {
        private val boundsRectRegex = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    }

    private fun parseBoundsRect(bounds: String): Rect? {
        val match = boundsRectRegex.find(bounds) ?: return null
        val (l, t, r, b) = match.destructured
        return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }
}

object SmartPerceptionLayerFactory {
    fun create(
        controller: AccessibilityController,
        modelClient: ModelClient?,
        vlmModelName: String?
    ): SmartPerceptionLayer {
        val nodeTreeProvider = NodeTreeProvider(controller)
        val screenEventBus = controller.screenEventBus
        val screenshotCapturer = ScreenshotCapturer()

        val vlmProvider = if (modelClient != null && !vlmModelName.isNullOrBlank()) {
            VlmProvider(modelClient, vlmModelName)
        } else null

        return SmartPerceptionLayer(
            nodeTreeProvider = nodeTreeProvider,
            screenEventBus = screenEventBus,
            screenshotCapturer = screenshotCapturer,
            vlmProvider = vlmProvider
        )
    }
}
