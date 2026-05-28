package com.lin.hippyagent.core.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable

@Serializable
data class SerializedNode(
    val type: String,
    val text: String? = null,
    val content_desc: String? = null,
    val view_id: String? = null,
    val bounds: String? = null,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checked: Boolean? = null,
    val children: List<SerializedNode>? = null
)

data class ObserveRequest(
    val mode: String = "nodes",
    val target: String = "current_window",
    val depth: Int = 5,
    val filter: String = "all",
    val includeBounds: Boolean = true
)

@Serializable
data class ObserveResult(
    val window: String? = null,
    val screenSize: ScreenSize? = null,
    val nodeTree: SerializedNode? = null,
    val nodeCount: Int = 0,
    val interactiveCount: Int = 0,
    val screenshotAnalysis: ScreenshotAnalysis? = null,
    val screenshotBase64: String? = null,
    val error: String? = null
)

@Serializable
data class ScreenSize(
    val width: Int,
    val height: Int
)

@Serializable
data class InteractRequest(
    val action: String,
    val target: String? = null,
    val value: String? = null,
    val waitAfter: Int = 500
)

@Serializable
data class InteractResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null
)

class NodeOperator {

    fun findNodeByText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()
    }

    fun findNodeById(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return nodes.firstOrNull()
    }

    fun findNodeByBounds(root: AccessibilityNodeInfo?, boundsStr: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val regex = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
        val match = regex.find(boundsStr) ?: return null
        val (x1, y1, x2, y2) = match.destructured
        val targetRect = android.graphics.Rect(
            x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt()
        )
        return findNodeByRect(root, targetRect)
    }

    private fun findNodeByRect(node: AccessibilityNodeInfo, targetRect: android.graphics.Rect): AccessibilityNodeInfo? {
        val nodeRect = android.graphics.Rect()
        node.getBoundsInScreen(nodeRect)
        if (nodeRect == targetRect) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByRect(child, targetRect)
            if (found != null) return found
        }
        return null
    }

    fun getNodeTree(
        root: AccessibilityNodeInfo?,
        maxDepth: Int = 5,
        filter: String = "all",
        includeBounds: Boolean = true,
        maxNodes: Int = 200
    ): Pair<SerializedNode?, Int> {
        if (root == null) return Pair(null, 0)
        val counter = intArrayOf(0)
        val tree = serializeNode(root, 0, maxDepth, filter, includeBounds, counter, maxNodes)
        return Pair(tree, counter[0])
    }

    private fun serializeNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        filter: String,
        includeBounds: Boolean,
        counter: IntArray,
        maxNodes: Int
    ): SerializedNode? {
        if (depth > maxDepth || counter[0] >= maxNodes) return null
        counter[0]++

        val isInteractive = node.isClickable || node.isLongClickable ||
                node.isScrollable || node.isEditable || node.isCheckable

        if (filter == "interactive" && !isInteractive && depth > 0) {
            val childResults = mutableListOf<SerializedNode>()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val serialized = serializeNode(child, depth + 1, maxDepth, filter, includeBounds, counter, maxNodes)
                if (serialized != null) childResults.add(serialized)
            }
            if (childResults.isEmpty()) return null
            return SerializedNode(
                type = node.className?.toString()?.substringAfterLast(".") ?: "View",
                text = node.text?.toString(),
                content_desc = node.contentDescription?.toString(),
                view_id = node.viewIdResourceName,
                bounds = if (includeBounds) getBoundsStr(node) else null,
                clickable = node.isClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                checked = if (node.isCheckable) node.isChecked else null,
                children = childResults.ifEmpty { null }
            )
        }

        val childResults = mutableListOf<SerializedNode>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val serialized = serializeNode(child, depth + 1, maxDepth, filter, includeBounds, counter, maxNodes)
            if (serialized != null) childResults.add(serialized)
        }

        return SerializedNode(
            type = node.className?.toString()?.substringAfterLast(".") ?: "View",
            text = node.text?.toString(),
            content_desc = node.contentDescription?.toString(),
            view_id = node.viewIdResourceName,
            bounds = if (includeBounds) getBoundsStr(node) else null,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            checked = if (node.isCheckable) node.isChecked else null,
            children = childResults.ifEmpty { null }
        )
    }

    private fun getBoundsStr(node: AccessibilityNodeInfo): String {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"
    }

    fun performClick(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun performLongClick(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    fun performSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun performScrollForward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun performScrollBackward(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun findScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        while (current != null) {
            if (current.isScrollable) return current
            current = current.parent
        }
        return null
    }

    fun getCenterBounds(node: AccessibilityNodeInfo): Pair<Int, Int> {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return Pair(rect.centerX(), rect.centerY())
    }
}

