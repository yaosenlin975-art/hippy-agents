package com.lin.hippyagent.core.accessibility

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.Json

class AccessibilityController(
    private val context: Context,
    private val actionApprover: ActionApprover
) {

    val screenEventBus = ScreenEventBus()

    init {
        PhoneControlAccessibilityService.screenEventBus = screenEventBus
    }

    fun getContext(): Context = context

    companion object {
        private const val TAG = "A11yController"
        private val swipeRegex = Regex("""(\d+)\s*,\s*(\d+)\s*→\s*(\d+)\s*,\s*(\d+)""")
        private val boundsRectRegex = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    }

    private val nodeOperator = NodeOperator()
    private val gesturePlayer = GesturePlayer()
    private val actionExecutor = ActionExecutor(context)
    private val auditLogger = AuditLogger(context)
    private val screenObserver = ScreenObserver(this)
    private val json = Json { encodeDefaults = true; prettyPrint = false }

    fun isServiceRunning(): Boolean = PhoneControlAccessibilityService.isRunning()

    suspend fun observe(request: ObserveRequest): ObserveResult {
        return when (request.mode) {
            "screenshot" -> screenObserver.observeScreenshot(request)
            "hybrid" -> screenObserver.observeHybrid(request)
            else -> observeNodes(request)
        }
    }

    fun observeNodes(request: ObserveRequest): ObserveResult {
        val service = PhoneControlAccessibilityService.instance
            ?: return ObserveResult(error = "AccessibilityService not running")

        val root = service.getRootNode()
            ?: return ObserveResult(error = "Cannot get root node")

        try {
            val window = root.packageName?.toString()
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            context.getSystemService(android.view.WindowManager::class.java)
                .defaultDisplay.getMetrics(metrics)
            val screenSize = ScreenSize(metrics.widthPixels, metrics.heightPixels)

            val (tree, count) = nodeOperator.getNodeTree(
                root, request.depth, request.filter, request.includeBounds
            )

            val interactiveCount = countInteractiveNodes(root, request.depth)

            return ObserveResult(
                window = window,
                screenSize = screenSize,
                nodeTree = tree,
                nodeCount = count,
                interactiveCount = interactiveCount
            )
        } finally {
            root.recycle()
        }
    }

    suspend fun interact(request: InteractRequest): InteractResult {
        val service = PhoneControlAccessibilityService.instance
            ?: return InteractResult(false, error = "AccessibilityService not running")

        val riskLevel = actionApprover.assessRisk(
            request.action, request.target, request.value,
            service.getRootNode()?.packageName?.toString()
        )

        if (riskLevel == RiskLevel.BLOCKED) {
            auditLogger.log("screen_interact", request.action, request.target, request.value, riskLevel, false, false)
            return InteractResult(false, error = "Action blocked by security policy")
        }

        var approved: Boolean? = true
        if (riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.MEDIUM) {
            // 需要审批时，确保 ApprovalOverlayService 作为前台服务运行，
            // 这样悬浮窗可以在其他应用上方显示（无障碍服务控制其他 app 时）
            if (!ApprovalOverlayService.isRunning) {
                ApprovalOverlayService.start(context)
            }
            approved = actionApprover.approve(
                ApprovalRequest(
                    action = request.action,
                    target = request.target,
                    value = request.value,
                    riskLevel = riskLevel,
                    packageName = service.getRootNode()?.packageName?.toString()
                )
            )
            if (!approved) {
                auditLogger.log("screen_interact", request.action, request.target, request.value, riskLevel, false, false)
                return InteractResult(false, error = "Action denied by user (risk: $riskLevel)")
            }
        }

        val result = when (request.action) {
            "click" -> handleClick(service, request)
            "long_click" -> handleLongClick(service, request)
            "input_text" -> handleInputText(service, request)
            "scroll" -> handleScroll(service, request)
            "swipe" -> handleSwipe(service, request)
            "press_back" -> handleGlobalAction { actionExecutor.pressBack(service) }
            "press_home" -> handleGlobalAction { actionExecutor.pressHome(service) }
            "press_recents" -> handleGlobalAction { actionExecutor.pressRecents(service) }
            "open_notifications" -> handleGlobalAction { actionExecutor.openNotifications(service) }
            "open_quick_settings" -> handleGlobalAction { actionExecutor.openQuickSettings(service) }
            "launch_app" -> handleLaunchApp(request)
            else -> InteractResult(false, error = "Unknown action: ${request.action}")
        }

        auditLogger.log("screen_interact", request.action, request.target, request.value, riskLevel, approved, result.success)
        return result
    }

    private suspend fun handleClick(service: PhoneControlAccessibilityService, request: InteractRequest): InteractResult {
        val target = request.target ?: return InteractResult(false, error = "target required for click")
        val node = resolveTarget(service, target)

        if (node != null) {
            val success = nodeOperator.performClick(node)
            node.recycle()
            return InteractResult(success, output = if (success) "Clicked: $target" else "Click failed: $target")
        }

        return tryCoordinateClick(service, target)
    }

    private suspend fun handleLongClick(service: PhoneControlAccessibilityService, request: InteractRequest): InteractResult {
        val target = request.target ?: return InteractResult(false, error = "target required for long_click")
        val node = resolveTarget(service, target)

        if (node != null) {
            val (cx, cy) = nodeOperator.getCenterBounds(node)
            node.recycle()
            val success = gesturePlayer.longClick(service, cx, cy)
            return InteractResult(success, output = if (success) "Long clicked: $target" else "Long click failed: $target")
        }

        return tryCoordinateClick(service, target, longClick = true)
    }

    private suspend fun handleInputText(service: PhoneControlAccessibilityService, request: InteractRequest): InteractResult {
        val target = request.target ?: return InteractResult(false, error = "target required for input_text")
        val value = request.value ?: return InteractResult(false, error = "value required for input_text")
        val node = resolveTarget(service, target)
            ?: return InteractResult(false, error = "Node not found: $target")

        return try {
            val success = nodeOperator.performSetText(node, value)
            InteractResult(success, output = if (success) "Input text to: $target" else "Input failed: $target")
        } finally {
            node.recycle()
        }
    }

    private suspend fun handleScroll(service: PhoneControlAccessibilityService, request: InteractRequest): InteractResult {
        val direction = request.value ?: request.target ?: "down"
        val metrics = getScreenMetrics()
        val success = when (direction) {
            "up" -> gesturePlayer.scrollUp(service, metrics.widthPixels, metrics.heightPixels)
            "down" -> gesturePlayer.scrollDown(service, metrics.widthPixels, metrics.heightPixels)
            "left" -> gesturePlayer.scrollLeft(service, metrics.widthPixels, metrics.heightPixels)
            "right" -> gesturePlayer.scrollRight(service, metrics.widthPixels, metrics.heightPixels)
            else -> return InteractResult(false, error = "Invalid scroll direction: $direction")
        }
        return InteractResult(success, output = if (success) "Scrolled $direction" else "Scroll failed")
    }

    private suspend fun handleSwipe(service: PhoneControlAccessibilityService, request: InteractRequest): InteractResult {
        val coords = request.target ?: return InteractResult(false, error = "target required for swipe (format: x1,y1→x2,y2)")
        val match = swipeRegex.find(coords) ?: return InteractResult(false, error = "Invalid swipe format. Use: x1,y1→x2,y2")
        val (x1, y1, x2, y2) = match.destructured
        val success = gesturePlayer.swipe(service, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
        return InteractResult(success, output = if (success) "Swiped" else "Swipe failed")
    }

    private fun handleGlobalAction(action: () -> Boolean): InteractResult {
        val success = action()
        return InteractResult(success, output = if (success) "Action executed" else "Action failed")
    }

    private fun handleLaunchApp(request: InteractRequest): InteractResult {
        val packageName = request.value ?: request.target
            ?: return InteractResult(false, error = "package name required for launch_app")
        val success = actionExecutor.launchApp(packageName)
        return InteractResult(success, output = if (success) "App launched: $packageName" else "App not found: $packageName")
    }

    private fun resolveTarget(service: PhoneControlAccessibilityService, target: String): AccessibilityNodeInfo? {
        val root = service.getRootNode() ?: return null

        val node = when {
            target.startsWith("id:") -> {
                val viewId = target.removePrefix("id:")
                nodeOperator.findNodeById(root, viewId)
            }
            target.startsWith("text:") -> {
                val text = target.removePrefix("text:")
                nodeOperator.findNodeByText(root, text)
            }
            target.startsWith("bounds:") -> {
                val bounds = target.removePrefix("bounds:")
                nodeOperator.findNodeByBounds(root, bounds)
            }
            else -> {
                nodeOperator.findNodeByText(root, target)
                    ?: nodeOperator.findNodeById(root, target)
            }
        }

        if (node == null) root.recycle()
        return node
    }

    private suspend fun tryCoordinateClick(
        service: PhoneControlAccessibilityService,
        target: String,
        longClick: Boolean = false
    ): InteractResult {
        val match = boundsRectRegex.find(target)
        if (match != null) {
            val (x1, y1, x2, y2) = match.destructured
            val cx = (x1.toInt() + x2.toInt()) / 2
            val cy = (y1.toInt() + y2.toInt()) / 2
            val success = if (longClick) {
                gesturePlayer.longClick(service, cx, cy)
            } else {
                gesturePlayer.click(service, cx, cy)
            }
            return InteractResult(success, output = if (success) "Coordinate click at ($cx, $cy)" else "Coordinate click failed")
        }
        return InteractResult(false, error = "Node not found and target is not a coordinate: $target")
    }

    private fun countInteractiveNodes(node: AccessibilityNodeInfo, maxDepth: Int, depth: Int = 0): Int {
        if (depth > maxDepth) return 0
        var count = 0
        if (node.isClickable || node.isLongClickable || node.isScrollable || node.isEditable) {
            count++
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            count += countInteractiveNodes(child, maxDepth, depth + 1)
        }
        return count
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(android.view.WindowManager::class.java)
            .defaultDisplay.getMetrics(metrics)
        return metrics
    }
}

