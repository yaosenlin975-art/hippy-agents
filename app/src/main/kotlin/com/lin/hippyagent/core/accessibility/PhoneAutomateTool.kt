package com.lin.hippyagent.core.accessibility

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PhoneAutomateTool(
    private val automator: PhoneAutomator
) : Tool() {

    private val json = Json { encodeDefaults = true; prettyPrint = true }

    override val definition = ToolDefinition(
        name = "phone_automate",
        description = "下发自然语言任务，系统自动编排步骤执行。Agent的「大脑」，高层语义操控。需要先在系统设置中开启Paw无障碍服务。",
        parameters = mapOf(
            "task" to ToolParameter(
                name = "task",
                type = "string",
                description = "自然语言任务描述，如「在微信给张三发消息：明天开会」",
                required = true
            ),
            "app" to ToolParameter(
                name = "app",
                type = "string",
                description = "目标App包名，不指定则自动判断",
                required = false
            ),
            "max_steps" to ToolParameter(
                name = "max_steps",
                type = "integer",
                description = "最大执行步数，默认15",
                required = false,
                defaultValue = "15"
            ),
            "confirm_dangerous" to ToolParameter(
                name = "confirm_dangerous",
                type = "boolean",
                description = "是否对高危操作弹窗确认，默认true",
                required = false,
                defaultValue = "true"
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""

        val task = arguments["task"]?.toString()
            ?: return ToolResult(callId, false, error = "task parameter required")

        val request = AutomateRequest(
            task = task,
            app = getOptionalArgument(arguments, "app"),
            maxSteps = (arguments["max_steps"] as? Number)?.toInt() ?: 15,
            confirmDangerous = arguments["confirm_dangerous"]?.toString()?.toBoolean() ?: true
        )

        val result = automator.execute(request)

        return if (result.success) {
            val outputJson = json.encodeToString(result)
            ToolResult(callId, true, output = "Task completed: $task in ${result.steps} steps", forLLM = outputJson)
        } else {
            ToolResult(callId, false, error = result.error ?: "Task failed after ${result.steps} steps")
        }
    }
}

