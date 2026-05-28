package com.lin.hippyagent.core.accessibility

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class ScreenInteractTool(
    private val controller: AccessibilityController
) : Tool() {

    override val definition = ToolDefinition(
        name = "screen_interact",
        description = "操控手机屏幕，执行点击/输入/滑动等操作。Agent的「手」，只写不读。需要先在系统设置中开启Paw无障碍服务。target定位优先级：id: > text: > bounds:",
        parameters = mapOf(
            "action" to ToolParameter(
                name = "action",
                type = "string",
                description = "操作类型：click / long_click / input_text / scroll / swipe / press_back / press_home / press_recents / open_notifications / open_quick_settings / launch_app",
                required = true
            ),
            "target" to ToolParameter(
                name = "target",
                type = "string",
                description = "操作目标，格式：text:\"按钮文本\" / id:\"com.xxx:id/btn\" / bounds:\"[x1,y1][x2,y2]\" / 方向:\"up\"/\"down\"/\"left\"/\"right\" / 坐标:\"x1,y1→x2,y2\" / 包名:\"com.tencent.mm\"",
                required = false
            ),
            "value" to ToolParameter(
                name = "value",
                type = "string",
                description = "操作值：输入文本内容 / 滚动方向 / App包名",
                required = false
            ),
            "wait_after" to ToolParameter(
                name = "wait_after",
                type = "integer",
                description = "操作后等待时间(ms)，默认500",
                required = false,
                defaultValue = "500"
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

        val action = getRequiredArgument(arguments, "action")
        val waitAfter = (arguments["wait_after"] as? Number)?.toInt() ?: 500

        val request = InteractRequest(
            action = action,
            target = getOptionalArgument(arguments, "target"),
            value = getOptionalArgument(arguments, "value"),
            waitAfter = waitAfter
        )

        val result = controller.interact(request)

        if (waitAfter > 0 && result.success) {
            kotlinx.coroutines.delay(waitAfter.toLong())
        }

        return if (result.success) {
            ToolResult(callId, true, output = result.output ?: "Action '$action' executed", forLLM = result.output ?: "Action '$action' executed")
        } else {
            ToolResult(callId, false, error = result.error ?: "Action '$action' failed")
        }
    }
}

