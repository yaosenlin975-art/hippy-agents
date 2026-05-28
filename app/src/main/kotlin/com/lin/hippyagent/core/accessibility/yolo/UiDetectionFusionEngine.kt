package com.lin.hippyagent.core.accessibility.yolo

import android.graphics.Rect
import com.lin.hippyagent.core.accessibility.SerializedNode
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import timber.log.Timber
import kotlin.math.min
import kotlin.math.sqrt

@Serializable
enum class MatchMethod {
    IOU,
    CENTER_CONTAINED,
    NEAREST_NEIGHBOR,
    UNMATCHED
}

@Serializable
data class FusedElement(
    val detection: UiDetection,
    val matchedNode: SerializedNode? = null,
    val matchMethod: MatchMethod,
    @Contextual
    val fusedBounds: Rect,
    val fusedText: String? = null
)

class UiDetectionFusionEngine {

    fun fuse(detections: List<UiDetection>, nodes: List<SerializedNode>): List<FusedElement> {
        val flatNodes = flattenNodes(nodes)
        val results = mutableListOf<FusedElement>()

        for (detection in detections) {
            val detRect = Rect(
                detection.x1.toInt(),
                detection.y1.toInt(),
                detection.x2.toInt(),
                detection.y2.toInt()
            )

            val matched = matchByIou(detection, detRect, flatNodes)
                ?: matchByCenterContained(detection, detRect, flatNodes)
                ?: matchByNearestNeighbor(detection, detRect, flatNodes)

            if (matched != null) {
                val (node, method) = matched
                results.add(
                    FusedElement(
                        detection = detection,
                        matchedNode = node,
                        matchMethod = method,
                        fusedBounds = mergeBounds(detRect, parseBounds(node.bounds)),
                        fusedText = resolveText(node)
                    )
                )
            } else {
                results.add(
                    FusedElement(
                        detection = detection,
                        matchMethod = MatchMethod.UNMATCHED,
                        fusedBounds = detRect,
                        fusedText = null
                    )
                )
            }
        }

        Timber.d("Fused ${detections.size} detections with ${flatNodes.size} nodes, ${results.count { it.matchMethod != MatchMethod.UNMATCHED }} matched")
        return results
    }

    private fun matchByIou(detection: UiDetection, detRect: Rect, nodes: List<SerializedNode>): Pair<SerializedNode, MatchMethod>? {
        var bestNode: SerializedNode? = null
        var bestIou = IOU_THRESHOLD

        for (node in nodes) {
            val nodeRect = parseBounds(node.bounds) ?: continue
            val iou = computeIou(detRect, nodeRect)
            if (iou >= bestIou) {
                bestIou = iou
                bestNode = node
            }
        }

        return bestNode?.let { it to MatchMethod.IOU }
    }

    private fun matchByCenterContained(detection: UiDetection, detRect: Rect, nodes: List<SerializedNode>): Pair<SerializedNode, MatchMethod>? {
        val cx = (detRect.left + detRect.right) / 2
        val cy = (detRect.top + detRect.bottom) / 2

        for (node in nodes) {
            val nodeRect = parseBounds(node.bounds) ?: continue
            if (nodeRect.contains(cx, cy)) {
                return node to MatchMethod.CENTER_CONTAINED
            }
        }

        return null
    }

    private fun matchByNearestNeighbor(detection: UiDetection, detRect: Rect, nodes: List<SerializedNode>): Pair<SerializedNode, MatchMethod>? {
        val cx = (detRect.left + detRect.right) / 2f
        val cy = (detRect.top + detRect.bottom) / 2f

        var bestNode: SerializedNode? = null
        var bestDist = NEAREST_THRESHOLD

        for (node in nodes) {
            val nodeRect = parseBounds(node.bounds) ?: continue
            val nodeCx = (nodeRect.left + nodeRect.right) / 2f
            val nodeCy = (nodeRect.top + nodeRect.bottom) / 2f
            val dist = sqrt((cx - nodeCx) * (cx - nodeCx) + (cy - nodeCy) * (cy - nodeCy))
            if (dist < bestDist) {
                bestDist = dist
                bestNode = node
            }
        }

        return bestNode?.let { it to MatchMethod.NEAREST_NEIGHBOR }
    }

    private fun computeIou(a: Rect, b: Rect): Float {
        val ix1 = maxOf(a.left, b.left)
        val iy1 = maxOf(a.top, b.top)
        val ix2 = minOf(a.right, b.right)
        val iy2 = minOf(a.bottom, b.bottom)

        val intersection = maxOf(0, ix2 - ix1) * maxOf(0, iy2 - iy1)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - intersection

        return if (union > 0) intersection.toFloat() / union.toFloat() else 0f
    }

    private fun mergeBounds(a: Rect, b: Rect?): Rect {
        if (b == null) return a
        return Rect(
            min(a.left, b.left),
            min(a.top, b.top),
            min(a.right, b.right),
            min(a.bottom, b.bottom)
        )
    }

    private fun resolveText(node: SerializedNode): String? {
        return node.text
            ?: node.content_desc
            ?: node.view_id?.substringAfterLast("/")
    }

    private fun parseBounds(bounds: String?): Rect? {
        if (bounds == null) return null
        val regex = REGEX_BOUNDS
        val match = regex.matchEntire(bounds) ?: return null
        return try {
            Rect(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
                match.groupValues[4].toInt()
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun flattenNodes(nodes: List<SerializedNode>): List<SerializedNode> {
        val result = mutableListOf<SerializedNode>()
        for (node in nodes) {
            flattenRecursive(node, result)
        }
        return result
    }

    private fun flattenRecursive(node: SerializedNode, acc: MutableList<SerializedNode>) {
        acc.add(node)
        node.children?.forEach { flattenRecursive(it, acc) }
    }

    companion object {
        private const val IOU_THRESHOLD = 0.15f
        private const val NEAREST_THRESHOLD = 160f
        private val REGEX_BOUNDS = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
    }
}
