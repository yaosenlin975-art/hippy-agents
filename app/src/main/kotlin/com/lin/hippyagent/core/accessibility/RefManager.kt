package com.lin.hippyagent.core.accessibility

import kotlinx.serialization.Serializable
import timber.log.Timber

@Serializable
data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

@Serializable
data class RefNode(
    val ref: String,
    val text: String?,
    val bounds: Rect,
    val className: String?,
    val clickable: Boolean,
    val focusable: Boolean,
    val scrollable: Boolean
)

@Serializable
data class TapTarget(
    val x: Float,
    val y: Float,
    val nodeRef: String
)

class RefManager {

    companion object {
        private const val REF_PREFIX = "e"
        private const val DEFAULT_MAX_AGE_MS = 20_000L
        private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    }

    private var nextRefId = 1
    private var seq = 0L
    private var lastUpdateTime = 0L
    private val refMap = mutableMapOf<String, RefNode>()

    fun isStale(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Boolean {
        return (System.currentTimeMillis() - lastUpdateTime) > maxAgeMs
    }

    fun invalidateSnapshotAfterMutation() {
        seq++
        Timber.d("RefManager invalidated, seq=%d", seq)
    }

    fun resolveRefForTap(refId: String, nodes: List<SerializedNode>): TapTarget? {
        val refNode = refMap[refId] ?: return resolveBySearch(refId, nodes)
        val flatNodes = flattenNodes(nodes)
        val matched = flatNodes.firstOrNull { node ->
            val nodeText = node.text ?: node.content_desc
            val nodeRect = parseBoundsRect(node.bounds)
            nodeText == refNode.text && nodeRect == refNode.bounds
        }

        if (matched != null) {
            val center = parseBoundsCenter(matched.bounds) ?: return null
            return TapTarget(x = center.first, y = center.second, nodeRef = refId)
        }

        return resolveBySearch(refId, nodes)
    }

    private fun resolveBySearch(refId: String, nodes: List<SerializedNode>): TapTarget? {
        val refNode = refMap[refId] ?: return null
        val flatNodes = flattenNodes(nodes)
        val candidates = flatNodes.filter { node ->
            val nodeText = node.text ?: node.content_desc ?: ""
            nodeText.isNotBlank() && nodeText == refNode.text && node.clickable
        }

        val best = candidates.minByOrNull { node ->
            val nodeRect = parseBoundsRect(node.bounds)
            if (nodeRect != null && refNode.bounds.right - refNode.bounds.left > 0) {
                val dx = ((nodeRect.left + nodeRect.right) / 2) - ((refNode.bounds.left + refNode.bounds.right) / 2)
                val dy = ((nodeRect.top + nodeRect.bottom) / 2) - ((refNode.bounds.top + refNode.bounds.bottom) / 2)
                (dx * dx + dy * dy).toFloat()
            } else Float.MAX_VALUE
        }

        if (best != null) {
            val center = parseBoundsCenter(best.bounds) ?: return null
            return TapTarget(x = center.first, y = center.second, nodeRef = refId)
        }

        Timber.w("RefManager: cannot resolve ref=%s", refId)
        return null
    }

    fun updateRefs(nodes: List<SerializedNode>): List<RefNode> {
        nextRefId = 1
        refMap.clear()
        lastUpdateTime = System.currentTimeMillis()

        val flatNodes = flattenNodes(nodes)
        val result = mutableListOf<RefNode>()

        for (node in flatNodes) {
            if (!node.clickable && !node.scrollable && !node.editable) continue
            val ref = allocateRef()
            val rect = parseBoundsRect(node.bounds) ?: continue
            val refNode = RefNode(
                ref = ref,
                text = node.text ?: node.content_desc,
                bounds = rect,
                className = node.type,
                clickable = node.clickable,
                focusable = false,
                scrollable = node.scrollable
            )
            refMap[ref] = refNode
            result.add(refNode)
        }

        Timber.d("RefManager: updated %d refs", result.size)
        return result
    }

    private fun allocateRef(): String {
        return "$REF_PREFIX${nextRefId++}"
    }

    private fun flattenNodes(nodes: List<SerializedNode>): List<SerializedNode> {
        val result = mutableListOf<SerializedNode>()
        for (node in nodes) {
            flattenNodeRecursive(node, result)
        }
        return result
    }

    private fun flattenNodeRecursive(node: SerializedNode, acc: MutableList<SerializedNode>) {
        acc.add(node)
        node.children?.forEach { flattenNodeRecursive(it, acc) }
    }

    private fun parseBoundsRect(bounds: String?): Rect? {
        if (bounds == null) return null
        val match = BOUNDS_REGEX.find(bounds) ?: return null
        val (l, t, r, b) = match.destructured
        return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    private fun parseBoundsCenter(bounds: String?): Pair<Float, Float>? {
        if (bounds == null) return null
        val match = BOUNDS_REGEX.find(bounds) ?: return null
        val (x1, y1, x2, y2) = match.destructured
        return ((x1.toInt() + x2.toInt()) / 2f) to ((y1.toInt() + y2.toInt()) / 2f)
    }
}
