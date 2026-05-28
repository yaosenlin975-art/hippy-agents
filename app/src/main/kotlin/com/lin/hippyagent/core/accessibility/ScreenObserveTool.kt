package com.lin.hippyagent.core.accessibility

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ScreenObserveTool(
    private val controller: AccessibilityController,
    private val smartPerceptionLayer: SmartPerceptionLayer? = null
) : Tool() {

    private val json = Json { encodeDefaults = true; prettyPrint = true }

    override val definition = ToolDefinition(
        name = "screen_observe",
        description = "感知当前屏幕状态，获取UI节点树信息。只读不写，Agent的「眼睛」。需要先在系统设置中开启Paw无障碍服务。",
        parameters = mapOf(
            "mode" to ToolParameter(
                name = "mode",
                type = "string",
                description = "感知模式：nodes（节点树，默认）/ screenshot（截图+VLM分析）/ hybrid（节点树优先，不足时补充截图）/ smart（智能感知：自动评估节点树质量，低质量时VLM补充，融合输出统一元素列表）",
                required = false,
                defaultValue = "nodes"
            ),
            "target" to ToolParameter(
                name = "target",
                type = "string",
                description = "焦点范围：current_window（默认）/ full_tree",
                required = false,
                defaultValue = "current_window"
            ),
            "depth" to ToolParameter(
                name = "depth",
                type = "integer",
                description = "节点树最大深度，默认5",
                required = false,
                defaultValue = "5"
            ),
            "filter" to ToolParameter(
                name = "filter",
                type = "string",
                description = "节点过滤：all（默认）/ interactive（仅可交互控件）",
                required = false,
                defaultValue = "all"
            ),
            "include_bounds" to ToolParameter(
                name = "include_bounds",
                type = "boolean",
                description = "是否包含控件坐标，默认true",
                required = false,
                defaultValue = "true"
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""

        if (!controller.isServiceRunning()) {
            return ToolResult(
                callId, false,
                error = "AccessibilityService not running. Please enable Hippy accessibility service in system Settings > Accessibility."
            )
        }

        val request = ObserveRequest(
            mode = getOptionalArgument(arguments, "mode", "nodes")!!,
            target = getOptionalArgument(arguments, "target", "current_window")!!,
            depth = (arguments["depth"] as? Number)?.toInt() ?: 5,
            filter = getOptionalArgument(arguments, "filter", "all")!!,
            includeBounds = arguments["include_bounds"]?.toString()?.toBoolean() ?: true
        )

        if (request.mode == "smart" && smartPerceptionLayer != null) {
            val unifiedState = smartPerceptionLayer.observe(request)
            val outputJson = json.encodeToString(unifiedState)
            return ToolResult(callId, true, output = outputJson, forLLM = outputJson)
        }

        val result = controller.observe(request)

        return if (result.error != null) {
            ToolResult(callId, false, error = result.error)
        } else {
            val outputJson = json.encodeToString(result)
            ToolResult(callId, true, output = outputJson, forLLM = outputJson)
        }
    }
}

